package com.melhive.zenith.agents

import com.melhive.zenith.core.command.CommandParser
import com.melhive.zenith.core.command.ParsedCommand
import com.melhive.zenith.core.intelligence.ContextWindow
import com.melhive.zenith.core.intelligence.PromptBuilder
import com.melhive.zenith.core.intelligence.Role
import com.melhive.zenith.core.security.ToolCallExtractor
import com.melhive.zenith.core.skills.SkillRegistry
import com.melhive.zenith.core.tools.ToolRequest
import com.melhive.zenith.intelligence.GenerationEvent
import com.melhive.zenith.intelligence.GenerationRequest
import com.melhive.zenith.intelligence.IntelligenceProvider
import com.melhive.zenith.intelligence.ProviderRegistry
import com.melhive.zenith.knowledge.KnowledgeRepository
import com.melhive.zenith.memory.ConversationRepository
import com.melhive.zenith.memory.MemoryRepository
import com.melhive.zenith.settings.SettingsRepository
import com.melhive.zenith.tools.ToolManager
import com.melhive.zenith.tools.ToolOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

enum class AssistantPhase { IDLE, THINKING, EXECUTING, RESEARCHING, SEARCHING, COMPLETED, ERROR }

data class AssistantActivity(val phase: AssistantPhase = AssistantPhase.IDLE, val detail: String? = null, val conversationId: Long? = null)

data class AssistantReply(val text: String, val ok: Boolean, val source: String)

/**
 * Handles one user turn end to end:
 *  1. Deterministic native commands are tried first (no model needed).
 *  2. Otherwise the active intelligence provider generates a reply, with
 *     enabled tools described in the system prompt.
 *  3. Tool calls in model output are extracted as untrusted requests and run
 *     through the ToolManager (validation → permission → confirmation).
 *  4. Results are fed back for up to [MAX_ROUNDS] rounds, then the final
 *     answer is persisted.
 */
class AssistantOrchestrator(
    private val appScope: CoroutineScope,
    private val conversations: ConversationRepository,
    private val memory: MemoryRepository,
    private val knowledge: KnowledgeRepository,
    private val providers: ProviderRegistry,
    private val tools: ToolManager,
    private val skills: SkillRegistry,
    private val settings: SettingsRepository,
) {
    private val parser = CommandParser()
    private val _activity = MutableStateFlow(AssistantActivity())
    val activity: StateFlow<AssistantActivity> = _activity.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    /** Live text of the reply being generated (for streaming TTS / UI). */
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private var currentJob: Job? = null

    fun submit(conversationId: Long, text: String, attachmentName: String? = null, attachmentText: String? = null, voice: Boolean = false, onReply: (AssistantReply) -> Unit = {}): Job {
        currentJob?.cancel()
        val job = appScope.launch {
            val reply = respond(conversationId, text, attachmentName, attachmentText, voice)
            onReply(reply)
        }
        currentJob = job
        return job
    }

    fun cancelCurrent() {
        currentJob?.cancel()
        providers.localRuntime.cancelActive()
    }

    private fun setPhase(p: AssistantPhase, detail: String? = null, conv: Long? = null) {
        _activity.value = AssistantActivity(p, detail, conv)
    }

    suspend fun respond(conversationId: Long, text: String, attachmentName: String? = null, attachmentText: String? = null, voice: Boolean = false): AssistantReply {
        val trimmed = text.trim()
        val userContent = if (attachmentText != null) "$trimmed\n\n[Attached file: $attachmentName]\n$attachmentText" else trimmed
        conversations.addMessage(conversationId, "user", userContent, attachmentName = attachmentName)
        setPhase(AssistantPhase.THINKING, conv = conversationId)
        _streamingText.value = ""
        return try {
            val reply = if (attachmentText == null) handleNative(conversationId, trimmed) else null
            val final = reply ?: handleWithModel(conversationId, voice)
            setPhase(if (final.ok) AssistantPhase.COMPLETED else AssistantPhase.ERROR, conv = conversationId)
            final
        } catch (e: CancellationException) {
            setPhase(AssistantPhase.IDLE)
            throw e
        } catch (e: Exception) {
            val msg = "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
            conversations.addMessage(conversationId, "assistant", msg, status = "error", source = "system")
            setPhase(AssistantPhase.ERROR, msg, conversationId)
            AssistantReply(msg, false, "system")
        }
    }

    private suspend fun handleNative(conversationId: Long, text: String): AssistantReply? {
        return when (val cmd = parser.parse(text)) {
            ParsedCommand.NotACommand -> null
            ParsedCommand.Help -> {
                conversations.addMessage(conversationId, "assistant", CommandParser.HELP_TEXT, source = "native")
                AssistantReply("Here is what I can do without an AI model. The list is on screen.", true, "native")
            }
            is ParsedCommand.Clarify -> {
                conversations.addMessage(conversationId, "assistant", cmd.message, source = "native")
                AssistantReply(cmd.message, true, "native")
            }
            is ParsedCommand.Tool -> {
                val outcome = runTool(conversationId, cmd.request)
                conversations.addMessage(conversationId, "assistant", outcome.message, source = "native", toolName = cmd.request.tool,
                    status = if (outcome.isSuccess()) "complete" else "error")
                AssistantReply(outcome.message, outcome.isSuccess(), "native")
            }
        }
    }

    private fun ToolOutcome.isSuccess() = this is ToolOutcome.Executed && result.ok

    private suspend fun runTool(conversationId: Long, request: ToolRequest): ToolOutcome {
        val phase = when (request.tool) {
            "web.research" -> AssistantPhase.RESEARCHING
            "docs.search", "history.search", "file.read", "file.list" -> AssistantPhase.SEARCHING
            else -> AssistantPhase.EXECUTING
        }
        setPhase(phase, request.tool, conversationId)
        return tools.execute(request)
    }

    private suspend fun handleWithModel(conversationId: Long, voice: Boolean): AssistantReply {
        val provider = providers.active()
        if (provider == null) {
            val why = providers.describeActive()
            val msg = "I can only run native commands right now ($why). Say “help” to see them, or set up an on-device model or online provider in Model Manager for open-ended conversation."
            conversations.addMessage(conversationId, "assistant", msg, source = "native")
            return AssistantReply(msg, true, "native")
        }
        val s = settings.current()
        val researchAvailable = providers.research() != null
        val toolSpecs = skills.enabledTools(s.enabledSkills).filter { it.name != "web.research" || researchAvailable }
        val memories = memory.list(30).map { it.text }
        val origin = if (provider.id == "local") ToolRequest.Origin.LOCAL_MODEL else ToolRequest.Origin.ONLINE_MODEL
        val sourceTag = if (provider.id == "local") "local" else "online:${provider.id}"
        val contextChars = if (provider.id == "local") 6_000 else 60_000

        var lastText = ""
        for (round in 1..MAX_ROUNDS) {
            val history = conversations.history(conversationId, 40)
            val lastUser = history.lastOrNull { it.role == Role.USER }?.content.orEmpty()
            val knowledgeSnippets = if (s.useKnowledge && round == 1) {
                setPhase(AssistantPhase.SEARCHING, "documents", conversationId)
                runCatching { knowledge.search(lastUser, 3) }.getOrDefault(emptyList()).map { "${it.docPath}: ${it.text.take(700)}" }
            } else emptyList()
            val system = PromptBuilder.systemPrompt(
                tools = toolSpecs,
                nowDescription = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd'T'HH:mm (VV)")),
                memories = memories,
                knowledge = knowledgeSnippets,
                voiceMode = voice,
            )
            setPhase(AssistantPhase.THINKING, provider.label, conversationId)
            val result = stream(conversationId, provider, GenerationRequest(system, ContextWindow.fit(history, contextChars)), sourceTag)
            if (!result.ok) return AssistantReply(result.text, false, sourceTag)

            val extraction = ToolCallExtractor.extract(result.text, origin)
            lastText = extraction.visibleText
            conversations.updateMessage(result.messageId, extraction.visibleText.ifBlank { if (extraction.requests.isNotEmpty()) "Working on it…" else "(no response)" }, "complete")
            if (extraction.requests.isEmpty()) {
                if (extraction.errors.isNotEmpty()) {
                    conversations.addMessage(conversationId, "tool", "Ignored malformed tool call(s): ${extraction.errors.joinToString("; ")}", source = "system")
                }
                return AssistantReply(lastText, true, sourceTag)
            }
            for (req in extraction.requests) {
                val outcome = runTool(conversationId, req)
                val turn = PromptBuilder.toolResultTurn(req.tool, outcome.isSuccess(), outcome.message)
                conversations.addMessage(conversationId, "tool", turn.content, source = "tool", toolName = req.tool, status = "complete")
            }
            if (round == MAX_ROUNDS) break
        }
        val note = "I stopped after $MAX_ROUNDS tool rounds to stay safe. Ask me to continue if needed."
        conversations.addMessage(conversationId, "assistant", note, source = sourceTag)
        return AssistantReply(lastText.ifBlank { note }, true, sourceTag)
    }

    private data class StreamResult(val messageId: Long, val text: String, val ok: Boolean)

    private suspend fun stream(conversationId: Long, provider: IntelligenceProvider, request: GenerationRequest, source: String): StreamResult {
        val id = conversations.addMessage(conversationId, "assistant", "", status = "streaming", source = source)
        val sb = StringBuilder()
        var lastFlush = 0L
        var error: String? = null
        try {
            provider.generate(request).collect { e ->
                when (e) {
                    is GenerationEvent.Delta -> {
                        sb.append(e.text)
                        _streamingText.value = sb.toString()
                        val now = System.currentTimeMillis()
                        if (now - lastFlush > 120) { conversations.updateMessage(id, sb.toString(), "streaming"); lastFlush = now }
                    }
                    is GenerationEvent.Error -> error = e.message
                    is GenerationEvent.Usage, is GenerationEvent.Done -> Unit
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { conversations.updateMessage(id, sb.toString().ifBlank { "(stopped)" } , "cancelled") }
            throw e
        }
        val err = error
        return if (err != null && sb.isBlank()) {
            conversations.updateMessage(id, err, "error")
            setPhase(AssistantPhase.ERROR, err, conversationId)
            StreamResult(id, err, false)
        } else {
            if (err != null) sb.append("\n\n[Interrupted: $err]")
            conversations.updateMessage(id, sb.toString(), "complete")
            StreamResult(id, sb.toString(), true)
        }
    }

    companion object { const val MAX_ROUNDS = 3 }
}

