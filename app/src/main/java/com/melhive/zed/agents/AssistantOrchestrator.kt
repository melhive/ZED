package com.melhive.zed.agents

import com.melhive.zed.core.command.CommandParser
import com.melhive.zed.core.command.ParsedCommand
import com.melhive.zed.core.conversation.SmallTalk
import com.melhive.zed.core.intelligence.ContextWindow
import com.melhive.zed.core.intelligence.PromptBuilder
import com.melhive.zed.core.intelligence.Role
import com.melhive.zed.core.security.ToolCallExtractor
import com.melhive.zed.core.skills.SkillRegistry
import com.melhive.zed.core.tools.ToolRequest
import com.melhive.zed.intelligence.GenerationEvent
import com.melhive.zed.intelligence.GenerationRequest
import com.melhive.zed.intelligence.IntelligenceProvider
import com.melhive.zed.intelligence.LocalModelRuntime
import com.melhive.zed.intelligence.ProviderSource
import com.melhive.zed.knowledge.KnowledgeRepository
import com.melhive.zed.memory.ConversationRepository
import com.melhive.zed.memory.MemoryRepository
import com.melhive.zed.settings.SettingsRepository
import com.melhive.zed.tools.ToolManager
import com.melhive.zed.tools.ToolOutcome
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
    /** Photo attached to the request being answered (sent with every step of this turn). */
    @Volatile private var currentImage: com.melhive.zed.intelligence.ImageInput? = null

    /** Returns a routine name when the message is one of the user's routine phrases ("good night"). */
    var routineMatcher: (suspend (String) -> String?)? = null

    fun submit(conversationId: Long, text: String, attachmentName: String? = null, attachmentText: String? = null, voice: Boolean = false, image: com.melhive.zed.intelligence.ImageInput? = null, onReply: (AssistantReply) -> Unit = {}): Job {
        currentJob?.cancel()
        val job = appScope.launch {
            val reply = respond(conversationId, text, attachmentName, attachmentText, voice, image)
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

    suspend fun respond(conversationId: Long, text: String, attachmentName: String? = null, attachmentText: String? = null, voice: Boolean = false, image: com.melhive.zed.intelligence.ImageInput? = null): AssistantReply {
        val trimmed = text.trim()
        val userContent = if (attachmentText != null) "$trimmed\n\n[Attached file: $attachmentName]\n$attachmentText" else trimmed
        conversations.addMessage(conversationId, "user", userContent, attachmentName = attachmentName ?: if (image != null) "📷 Photo" else null)
        currentImage = image
        setPhase(AssistantPhase.THINKING, conv = conversationId)
        _streamingText.value = ""
        return try {
            val provider = providers.active()
            // Local models: let the runtime decide from the file itself (it explains when a copy has no vision).
            val localSees = provider?.id == "local"
            if (image != null && (provider == null || (provider.id == "local" && !localSees))) {
                val msg = "This model can't look at photos. Gemma 4 E2B or E4B can, right on your phone: download one in Model Manager, or add a Gemini, OpenAI or Claude key."
                conversations.addMessage(conversationId, "assistant", msg, source = "system")
                setPhase(AssistantPhase.COMPLETED, null, conversationId)
                return AssistantReply(msg, false, "system")
            }
            val reply = if (attachmentText == null && image == null) handleNative(conversationId, trimmed, modelAvailable = provider != null) else null
            val final = reply ?: if (provider != null) handleWithModel(conversationId, provider, voice, trimmed) else nativeConversation(conversationId, trimmed)
            setPhase(if (final.ok) AssistantPhase.COMPLETED else AssistantPhase.ERROR, if (final.ok) null else final.text, conversationId)
            final
        } catch (e: CancellationException) {
            setPhase(AssistantPhase.IDLE)
            throw e
        } catch (e: Exception) {
            com.melhive.zed.android.CrashReporter.recordNonFatal("assistant", e)
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
        runCatching { routineMatcher?.invoke(text.trim().trimEnd('.', '!', '?')) }.getOrNull()?.let { name ->
            val outcome = runTool(conversationId, ToolRequest("routine.run", mapOf("name" to name), ToolRequest.Origin.NATIVE))
            conversations.addMessage(conversationId, "assistant", outcome.message, source = "native", toolName = "routine.run", status = if (outcome.isSuccess()) "complete" else "error")
            return AssistantReply(outcome.message, outcome.isSuccess(), "native")
        }
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
            addressAs = settings.current().userTitle.ifBlank { null },
            modelConfiguredButUnavailable = providers.anyConfigured(),
            hourOfDay = LocalTime.now().hour,
        )
        val reply = smallTalk.respond(text, ctx) ?: smallTalk.fallback(text, ctx)
        conversations.addMessage(conversationId, "assistant", reply, source = "native")
        return AssistantReply(reply, true, "native")
    }

    private suspend fun handleWithModel(conversationId: Long, provider: IntelligenceProvider, voice: Boolean, userText: String): AssistantReply {
        val s = settings.current()
        val isLocal = provider.id == "local"
        val researchAvailable = providers.research() != null
        // Very small on-device windows (e.g. 1280 tokens) cannot hold the tool list; such models just converse.
        val localContext = (provider as? com.melhive.zed.intelligence.LocalLiteRtProvider)?.contextTokens
        val tinyContext = localContext != null && localContext < 2048
        // Casual conversation gets a lean, persona-focused prompt; the tool list is included only when the
        // message looks like a device request (more room for the conversation, more natural replies).
        val nativeTools = !isLocal && provider.supportsNativeTools
        // On-device models get the same (compact) instructions every turn, so the runtime keeps its cached
        // conversation instead of re-reading the whole history whenever a message switches between chat and actions.
        val useTools = nativeTools || (if (isLocal) !tinyContext else PromptBuilder.looksLikeAction(userText))
        val toolSpecs = if (!useTools) emptyList() else skills.enabledTools(s.enabledSkills).filter { it.name != "web.research" || researchAvailable }
        val userName = memory.userName()
        val memories = memory.list(if (tinyContext) 5 else 30).map { it.text }
        val origin = if (isLocal) ToolRequest.Origin.LOCAL_MODEL else ToolRequest.Origin.ONLINE_MODEL
        val sourceTag = if (isLocal) "local" else "online:${provider.id}"
        val contextChars = if (isLocal) ((localContext ?: 4096) * 3) else 60_000

        if (nativeTools) return runNativeToolLoop(conversationId, provider, voice, toolSpecs, memories, userName, sourceTag, contextChars)

        var lastText = ""
        for (round in 1..MAX_ROUNDS) {
            val history = conversations.history(conversationId, 40)
            val lastUser = history.lastOrNull { it.role == Role.USER }?.content.orEmpty()
            val knowledgeSnippets = if (s.useKnowledge && round == 1) {
                setPhase(AssistantPhase.SEARCHING, "documents", conversationId)
                runCatching { knowledge.search(lastUser, 3) }.getOrDefault(emptyList()).map { "${it.docPath}: ${it.text.take(if (isLocal) 400 else 700)}" }
            } else emptyList()
            val now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd'T'HH:mm:ss (h:mm:ss a, VV)", java.util.Locale.ENGLISH))
            // On-device: keep the system prompt stable so the runtime can reuse its session; time and
            // document excerpts travel with the newest user message instead.
            val system = PromptBuilder.systemPrompt(
                tools = toolSpecs,
                nowDescription = if (isLocal) null else now,
                memories = memories,
                knowledge = if (isLocal) emptyList() else knowledgeSnippets,
                voiceMode = voice,
                compact = isLocal,
                userName = userName,
                persona = com.melhive.zed.core.intelligence.Persona.parse(s.persona),
                userTitle = s.userTitle.ifBlank { null },
            )
            val request = GenerationRequest(
                system = system,
                turns = ContextWindow.fit(history, contextChars),
                maxOutputTokens = when { isLocal && voice -> 384; isLocal -> 1024; voice -> 1024; else -> 4096 },
                sessionKey = "conversation-$conversationId",
                volatileContext = if (isLocal) PromptBuilder.volatileContext(now, knowledgeSnippets) else null,
                images = listOfNotNull(currentImage),
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

    /**
     * Native function calling (online providers): tools are declared to the model, which may call
     * several in one step and chain steps (read → decide → act). Every call still goes through the
     * ToolManager (validation, permissions, confirmation, audit). The history is read once; this
     * turn's tool calls and results are replayed in the provider's own format.
     */
    private suspend fun runNativeToolLoop(
        conversationId: Long, provider: IntelligenceProvider, voice: Boolean, toolSpecs: List<com.melhive.zed.core.tools.ToolSpec>,
        memories: List<String>, userName: String?, sourceTag: String, contextChars: Int,
    ): AssistantReply {
        val s = settings.current()
        val history = ContextWindow.fit(conversations.history(conversationId, 40), contextChars)
        val lastUser = history.lastOrNull { it.role == Role.USER }?.content.orEmpty()
        val knowledgeSnippets = if (s.useKnowledge) {
            setPhase(AssistantPhase.SEARCHING, "documents", conversationId)
            runCatching { knowledge.search(lastUser, 3) }.getOrDefault(emptyList()).map { "${it.docPath}: ${it.text.take(700)}" }
        } else emptyList()
        val now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd'T'HH:mm:ss (h:mm:ss a, VV)", java.util.Locale.ENGLISH))
        val system = PromptBuilder.systemPrompt(
            tools = emptyList(), nowDescription = now, memories = memories, knowledge = knowledgeSnippets, voiceMode = voice,
            userName = userName, persona = com.melhive.zed.core.intelligence.Persona.parse(s.persona),
            userTitle = s.userTitle.ifBlank { null }, nativeTools = toolSpecs.isNotEmpty(),
        )
        val exchanges = mutableListOf<com.melhive.zed.intelligence.ToolExchange>()
        val summary = mutableListOf<String>()
        for (step in 1..MAX_NATIVE_STEPS) {
            val request = GenerationRequest(
                system = system, turns = history, maxOutputTokens = if (voice) 1024 else 4096,
                sessionKey = "conversation-$conversationId", tools = toolSpecs, exchanges = exchanges.toList(),
                images = listOfNotNull(currentImage),
            )
            setPhase(AssistantPhase.THINKING, if (step == 1) provider.label else "Step $step…", conversationId)
            var result = stream(conversationId, provider, request, sourceTag)
            if (!result.ok && result.retryable) {
                setPhase(AssistantPhase.THINKING, "Retrying…", conversationId)
                conversations.deleteMessage(result.messageId)
                delay(1500)
                result = stream(conversationId, provider, request, sourceTag)
            }
            if (!result.ok) return AssistantReply(result.text, false, sourceTag)
            if (result.calls.isEmpty()) {
                val text = result.text.ifBlank { summary.lastOrNull() ?: "(no response)" }
                if (result.text.isBlank()) conversations.updateMessage(result.messageId, text, "complete")
                return AssistantReply(text, true, sourceTag)
            }
            if (result.text.isBlank()) conversations.updateMessage(result.messageId, "Working on it…", "complete")
            val results = mutableListOf<com.melhive.zed.intelligence.ToolCallResult>()
            for (call in result.calls) {
                val outcome = runTool(conversationId, ToolRequest(call.name, call.args, ToolRequest.Origin.ONLINE_MODEL))
                val turn = PromptBuilder.toolResultTurn(call.name, outcome.isSuccess(), outcome.message)
                conversations.addMessage(conversationId, "tool", turn.content, source = "tool", toolName = call.name, status = "complete")
                results += com.melhive.zed.intelligence.ToolCallResult(call.id, call.name, outcome.message.take(8000), outcome.isSuccess())
                summary += outcome.message
            }
            exchanges += com.melhive.zed.intelligence.ToolExchange(result.text, result.calls, results)
        }
        val note = "I stopped after $MAX_NATIVE_STEPS steps to stay safe. Ask me to continue if there's more to do."
        conversations.addMessage(conversationId, "assistant", note, source = sourceTag)
        return AssistantReply(note, true, sourceTag)
    }

    private data class StreamResult(
        val messageId: Long, val text: String, val ok: Boolean, val retryable: Boolean = false,
        val calls: List<com.melhive.zed.intelligence.ToolCall> = emptyList(),
    )

    private suspend fun stream(conversationId: Long, provider: IntelligenceProvider, request: GenerationRequest, source: String): StreamResult {
        val id = conversations.addMessage(conversationId, "assistant", "", status = "streaming", source = source)
        val sb = StringBuilder()
        var lastFlush = 0L
        var error: GenerationEvent.Error? = null
        var first = true
        val calls = mutableListOf<com.melhive.zed.intelligence.ToolCall>()
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
                    is GenerationEvent.ToolCalls -> calls += e.calls
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { conversations.updateMessage(id, sb.toString().ifBlank { "(stopped)" }, "cancelled") }
            throw e
        }
        val err = error
        return if (err != null && sb.isBlank() && calls.isEmpty()) {
            conversations.updateMessage(id, err.message, "error")
            StreamResult(id, err.message, false, err.retryable)
        } else {
            if (err != null) sb.append("\n\n[Interrupted: ${err.message}]")
            conversations.updateMessage(id, sb.toString(), "complete")
            StreamResult(id, sb.toString(), true, calls = calls.toList())
        }
    }

    companion object {
        const val MAX_ROUNDS = 3
        /** Native tool calling: enough steps for multi-step requests, bounded for safety and cost. */
        const val MAX_NATIVE_STEPS = 8
    }
}
