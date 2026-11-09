package com.melhive.zenith.agents

import com.melhive.zenith.core.command.CommandParser
import com.melhive.zenith.core.command.ParsedCommand
import com.melhive.zenith.core.conversation.SmallTalk
import com.melhive.zenith.core.intelligence.ContextWindow
import com.melhive.zenith.core.intelligence.PromptBuilder
import com.melhive.zenith.core.intelligence.Role
import com.melhive.zenith.core.security.ToolCallExtractor
import com.melhive.zenith.core.skills.SkillRegistry
import com.melhive.zenith.core.tools.ToolRequest
import com.melhive.zenith.intelligence.GenerationEvent
import com.melhive.zenith.intelligence.GenerationRequest
import com.melhive.zenith.intelligence.IntelligenceProvider
import com.melhive.zenith.intelligence.LocalModelRuntime
import com.melhive.zenith.intelligence.ProviderSource
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

enum class AssistantPhase { IDLE, THINKING, EXECUTING, RESEARCHING, SEARCHING, COMPLETED, ERROR }

data class AssistantActivity(
    val phase: AssistantPhase = AssistantPhase.IDLE,
    val detail: String? = null,
    val conversationId: Long? = null,
    val at: Long = System.currentTimeMillis(),
) {
    val busy: Boolean get() = phase in setOf(AssistantPhase.THINKING, AssistantPhase.EXECUTING, AssistantPhase.RESEARCHING, AssistantPhase.SEARCHING)
}

data class AssistantReply(val text: String, val ok: Boolean, val source: String)

/**
 * Handles one user turn end to end:
 *  1. Deterministic native commands and computations are tried first (no model needed).
 *  2. Otherwise the active intelligence provider holds the conversation, with
 *     enabled tools described in the system prompt. Without a provider, the
 *     native small-talk layer answers social messages and explains its limits.
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
    private val providers: ProviderSource,
    private val tools: ToolManager,
    private val skills: SkillRegistry,
    private val settings: SettingsRepository,
) {
    private val parser = CommandParser()
    private val smallTalk = SmallTalk()
    private val _activity = MutableStateFlow(AssistantActivity())
    val activity: StateFlow<AssistantActivity> = _activity.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    /** Live text of the reply being generated (for streaming TTS / UI). */
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _lastUsage = MutableStateFlow<String?>(null)
    /** Token usage of the last online reply, when the provider reports it. */
    val lastUsage: StateFlow<String?> = _lastUsage.asStateFlow()

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

    /** Discards the last answer and asks again with the same user message. */
    fun regenerate(conversationId: Long): Job? {
        currentJob?.cancel()
        val job = appScope.launch {
            val text = conversations.popLastExchange(conversationId) ?: return@launch
            val attachmentSplit = text.split("\n\n[Attached file: ", limit = 2)
            if (attachmentSplit.size == 2) {
                val name = attachmentSplit[1].substringBefore("]")
                respond(conversationId, attachmentSplit[0], name, attachmentSplit[1].substringAfter("]\n", ""))
            } else respond(conversationId, text)
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
            val provider = providers.active()
            val reply = if (attachmentText == null) handleNative(conversationId, trimmed, modelAvailable = provider != null) else null
            val final = reply ?: if (provider != null) handleWithModel(conversationId, provider, voice) else nativeConversation(conversationId, trimmed)
            setPhase(if (final.ok) AssistantPhase.COMPLETED else AssistantPhase.ERROR, if (final.ok) null else final.text, conversationId)
            final
        } catch (e: CancellationException) {
            setPhase(AssistantPhase.IDLE)
            throw e
        } catch (e: Exception) {
            com.melhive.zenith.android.CrashReporter.recordNonFatal("assistant", e)
            val msg = "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
            conversations.addMessage(conversationId, "assistant", msg, status = "error", source = "system")
            setPhase(AssistantPhase.ERROR, msg, conversationId)
            AssistantReply(msg, false, "system")
        }
    }

    /**
     * Clear device commands and exact computations are handled natively (fast, reliable, offline).
     * When an AI model is available, conversational requests ("what can you do", requests missing
     * details) go to the model instead of fixed replies.
     */
    private suspend fun handleNative(conversationId: Long, text: String, modelAvailable: Boolean): AssistantReply? {
        val cmd = parser.parse(text)
        if (modelAvailable && (cmd is ParsedCommand.Help || cmd is ParsedCommand.Clarify)) return null
        return when (cmd) {
            ParsedCommand.NotACommand -> null
            ParsedCommand.Help -> {
                conversations.addMessage(conversationId, "assistant", CommandParser.HELP_TEXT, source = "native")
                AssistantReply("Here is what I can do without an AI model. The list is on screen.", true, "native")
            }
            is ParsedCommand.Clarify -> {
                conversations.addMessage(conversationId, "assistant", cmd.message, source = "native")
                AssistantReply(cmd.message, true, "native")
            }
            is ParsedCommand.Answer -> {
                conversations.addMessage(conversationId, "assistant", cmd.text, source = "native")
                AssistantReply(cmd.text, true, "native")
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

    /** No model available: social replies or an honest, helpful explanation. */
    private suspend fun nativeConversation(conversationId: Long, text: String): AssistantReply {
        val ctx = SmallTalk.Context(
            userName = memory.userName(),
            modelConfiguredButUnavailable = providers.anyConfigured(),
            hourOfDay = LocalTime.now().hour,
        )
        val reply = smallTalk.respond(text, ctx) ?: smallTalk.fallback(text, ctx)
        conversations.addMessage(conversationId, "assistant", reply, source = "native")
        return AssistantReply(reply, true, "native")
    }

    private suspend fun handleWithModel(conversationId: Long, provider: IntelligenceProvider, voice: Boolean): AssistantReply {
        val s = settings.current()
        val isLocal = provider.id == "local"
        val researchAvailable = providers.research() != null
        // Very small on-device windows (e.g. 1280 tokens) cannot hold the tool list; such models just converse.
        val localContext = (provider as? com.melhive.zenith.intelligence.LocalLiteRtProvider)?.contextTokens
        val tinyContext = localContext != null && localContext < 2048
        val toolSpecs = if (tinyContext) emptyList() else skills.enabledTools(s.enabledSkills).filter { it.name != "web.research" || researchAvailable }
        val memories = memory.list(if (tinyContext) 5 else 30).map { it.text }
        val origin = if (isLocal) ToolRequest.Origin.LOCAL_MODEL else ToolRequest.Origin.ONLINE_MODEL
        val sourceTag = if (isLocal) "local" else "online:${provider.id}"
        val contextChars = if (isLocal) ((localContext ?: 4096) * 3) else 60_000

        var lastText = ""
        for (round in 1..MAX_ROUNDS) {
            val history = conversations.history(conversationId, 40)
            val lastUser = history.lastOrNull { it.role == Role.USER }?.content.orEmpty()
            val knowledgeSnippets = if (s.useKnowledge && round == 1) {
                setPhase(AssistantPhase.SEARCHING, "documents", conversationId)
                runCatching { knowledge.search(lastUser, 3) }.getOrDefault(emptyList()).map { "${it.docPath}: ${it.text.take(if (isLocal) 400 else 700)}" }
            } else emptyList()
            val now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd'T'HH:mm (VV)"))
            // On-device: keep the system prompt stable so the runtime can reuse its session; time and
            // document excerpts travel with the newest user message instead.
            val system = PromptBuilder.systemPrompt(
                tools = toolSpecs,
                nowDescription = if (isLocal) null else now,
                memories = memories,
                knowledge = if (isLocal) emptyList() else knowledgeSnippets,
                voiceMode = voice,
                compact = isLocal,
            )
            val request = GenerationRequest(
                system = system,
                turns = ContextWindow.fit(history, contextChars),
                maxOutputTokens = when { isLocal && voice -> 384; isLocal -> 1024; voice -> 1024; else -> 4096 },
                sessionKey = "conversation-$conversationId",
                volatileContext = if (isLocal) PromptBuilder.volatileContext(now, knowledgeSnippets) else null,
            )
            val loading = isLocal && providers.localRuntime.state.value !is LocalModelRuntime.State.Ready
            setPhase(AssistantPhase.THINKING, if (loading) "Loading on-device model (first reply takes longer)…" else provider.label, conversationId)

            var result = stream(conversationId, provider, request, sourceTag)
            if (!result.ok && result.retryable) {
                // One automatic retry for transient failures (network blips, rate limits, 5xx).
                setPhase(AssistantPhase.THINKING, "Retrying…", conversationId)
                conversations.deleteMessage(result.messageId)
                delay(1500)
                result = stream(conversationId, provider, request, sourceTag)
            }
            if (!result.ok) return AssistantReply(result.text, false, sourceTag)

            val extraction = ToolCallExtractor.extract(result.text, origin)
            lastText = extraction.visibleText
            conversations.updateMessage(result.messageId, extraction.visibleText.ifBlank { if (extraction.requests.isNotEmpty()) "Working on it…" else "(no response)" }, "complete")
            if (extraction.requests.isEmpty()) {
                if (extraction.errors.isNotEmpty()) {
                    conversations.addMessage(conversationId, "tool", "Ignored malformed tool call(s): ${extraction.errors.joinToString("; ")}", source = "system")
                }
                return AssistantReply(lastText.ifBlank { "(no response)" }, true, sourceTag)
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

    private data class StreamResult(val messageId: Long, val text: String, val ok: Boolean, val retryable: Boolean = false)

    private suspend fun stream(conversationId: Long, provider: IntelligenceProvider, request: GenerationRequest, source: String): StreamResult {
        val id = conversations.addMessage(conversationId, "assistant", "", status = "streaming", source = source)
        val sb = StringBuilder()
        var lastFlush = 0L
        var error: GenerationEvent.Error? = null
        var first = true
        try {
            provider.generate(request).collect { e ->
                when (e) {
                    is GenerationEvent.Delta -> {
                        if (first) { first = false; setPhase(AssistantPhase.THINKING, provider.label, conversationId) }
                        sb.append(e.text)
                        _streamingText.value = sb.toString()
                        val now = System.currentTimeMillis()
                        if (now - lastFlush > 120) { conversations.updateMessage(id, sb.toString(), "streaming"); lastFlush = now }
                    }
                    is GenerationEvent.Error -> error = e
                    is GenerationEvent.Usage -> _lastUsage.value = "${provider.label}: ${e.inputTokens ?: "?"} input / ${e.outputTokens ?: "?"} output tokens"
                    is GenerationEvent.Done -> Unit
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { conversations.updateMessage(id, sb.toString().ifBlank { "(stopped)" }, "cancelled") }
            throw e
        }
        val err = error
        return if (err != null && sb.isBlank()) {
            conversations.updateMessage(id, err.message, "error")
            StreamResult(id, err.message, false, err.retryable)
        } else {
            if (err != null) sb.append("\n\n[Interrupted: ${err.message}]")
            conversations.updateMessage(id, sb.toString(), "complete")
            StreamResult(id, sb.toString(), true)
        }
    }

    companion object { const val MAX_ROUNDS = 3 }
}
