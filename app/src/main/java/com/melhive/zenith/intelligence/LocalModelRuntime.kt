package com.melhive.zenith.intelligence

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.melhive.zenith.core.intelligence.ChatTurn
import com.melhive.zenith.core.intelligence.Role
import com.melhive.zenith.settings.LocalBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Runtime options for an on-device model. */
data class LocalModelOptions(val backend: LocalBackend, val contextTokens: Int)

/**
 * Owns the LiteRT-LM [Engine]. Loading a multi-GB model takes seconds and a
 * lot of RAM, so the engine is loaded lazily, kept while in use, and released
 * on [unload] (model switch, memory pressure).
 *
 * Context management (the window is fixed when the engine loads):
 * - the engine is created with an explicit `maxNumTokens` (the runtime's own
 *   default is only 1280, too small for a system prompt plus a conversation);
 * - history sent to a new session is trimmed to fit, leaving room for the reply;
 * - a session is reused across turns (only the new message is processed) until
 *   it nears the limit, then rebuilt from the trimmed history;
 * - if the runtime still reports a full window, the turn is retried once on a
 *   fresh session with minimal history, so users never see that error.
 */
class LocalModelRuntime(private val cacheDir: File) {

    sealed interface State {
        data object Unloaded : State
        data class Loading(val modelId: String) : State
        data class Ready(val modelId: String, val backend: LocalBackend, val loadMillis: Long, val contextTokens: Int) : State
        data class Failed(val modelId: String, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Unloaded)
    val state: StateFlow<State> = _state.asStateFlow()

    private val lock = Mutex()
    /** One inference at a time: chat and LIVE share a single engine. */
    private val generationLock = Mutex()
    private var engine: Engine? = null
    private var loadedKey: String? = null
    private var contextTokens: Int = DEFAULT_CONTEXT
    @Volatile private var activeConversation: Conversation? = null

    /** The live LiteRT-LM conversation and the user turns it has already seen. */
    private class Session(val key: String, val system: String, val conversation: Conversation, val userTurns: MutableList<String>)
    private var session: Session? = null

    suspend fun ensureLoaded(modelId: String, file: File, options: LocalModelOptions): Engine = lock.withLock {
        val key = "${file.absolutePath}|${options.backend}|${options.contextTokens}"
        engine?.let { if (loadedKey == key) return it }
        withContext(Dispatchers.IO) {
            closeSession()
            engine?.close(); engine = null; loadedKey = null
            require(file.exists()) { "Model file is missing: ${file.name}" }
            _state.value = State.Loading(modelId)
            val started = System.currentTimeMillis()
            try {
                val config = EngineConfig(
                    modelPath = file.absolutePath,
                    backend = if (options.backend == LocalBackend.GPU) Backend.GPU() else Backend.CPU(),
                    maxNumTokens = options.contextTokens,
                    cacheDir = cacheDir.absolutePath,
                )
                val e = Engine(config)
                e.initialize()
                engine = e; loadedKey = key; contextTokens = options.contextTokens
                _state.value = State.Ready(modelId, options.backend, System.currentTimeMillis() - started, options.contextTokens)
                e
            } catch (t: Throwable) {
                _state.value = State.Failed(modelId, friendlyLoadError(t, options))
                throw IllegalStateException(friendlyLoadError(t, options), t)
            }
        }
    }

    private fun friendlyLoadError(t: Throwable, o: LocalModelOptions): String {
        val raw = t.message ?: t.javaClass.simpleName
        return when {
            o.backend == LocalBackend.GPU -> "Could not start the model on the GPU ($raw). Switch to CPU in Model Manager."
            raw.contains("memory", ignoreCase = true) -> "Not enough memory to load the model ($raw). Close other apps or choose a smaller context size."
            else -> "Could not load the model: $raw"
        }
    }

    private fun closeSession() {
        session?.let { runCatching { it.conversation.close() } }
        session = null
    }

    suspend fun unload() = lock.withLock {
        withContext(Dispatchers.IO) {
            activeConversation?.let { runCatching { it.cancelProcess() } }
            closeSession()
            engine?.close(); engine = null; loadedKey = null
            _state.value = State.Unloaded
        }
    }

    fun cancelActive() { activeConversation?.let { runCatching { it.cancelProcess() } } }

    fun generate(modelId: String, file: File, options: LocalModelOptions, request: GenerationRequest): Flow<GenerationEvent> = flow {
        generationLock.withLock { generateLocked(modelId, file, options, request) }
    }.catch { t ->
        if (t is kotlinx.coroutines.CancellationException) throw t
        emit(GenerationEvent.Error(friendlyError(t)))
    }.flowOn(Dispatchers.Default)

    private suspend fun FlowCollector<GenerationEvent>.generateLocked(modelId: String, file: File, options: LocalModelOptions, request: GenerationRequest) {
        val e = ensureLoaded(modelId, file, options)
        val turns = normalizeTurns(request.turns)
        val last = turns.lastOrNull()
        if (last == null || last.role != Role.USER) { emit(GenerationEvent.Error("Nothing to answer")); return }
        val message = request.volatileContext?.let { "$it\n\n${last.content}" } ?: last.content
        val replyBudget = replyBudget(request)

        try {
            runTurn(e, request, turns, message, replyBudget, minimalHistory = false)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException || !isContextFull(t)) throw t
            // Window overflow despite budgeting (token estimates are approximate): retry once with minimal history.
            closeSession()
            runTurn(e, request, turns, message, replyBudget, minimalHistory = true)
        }
    }

    private fun replyBudget(request: GenerationRequest) = minOf(request.maxOutputTokens, contextTokens / 4).coerceAtLeast(128)

    private suspend fun FlowCollector<GenerationEvent>.runTurn(
        e: Engine, request: GenerationRequest, turns: List<ChatTurn>, message: String, replyBudget: Int, minimalHistory: Boolean,
    ) {
        val last = turns.last()
        val previousUserTurns = turns.dropLast(1).filter { it.role == Role.USER }.map { it.content }
        val current = session
        val messageTokens = estimateTokens(message)
        val reuse = !minimalHistory && current != null && request.sessionKey != null && current.key == request.sessionKey &&
            current.system == request.system && current.userTurns == previousUserTurns &&
            runCatching { current.conversation.getTokenCount() }.getOrDefault(Int.MAX_VALUE) + messageTokens + replyBudget < contextTokens

        val s = if (reuse) current!! else {
            closeSession()
            val systemTokens = estimateTokens(request.system)
            val historyBudget = (contextTokens - systemTokens - messageTokens - replyBudget - SAFETY_TOKENS).coerceAtLeast(0)
            val history = if (minimalHistory) emptyList() else fitHistory(turns.dropLast(1), historyBudget)
            val conversation = e.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(request.system),
                    initialMessages = history.map { t -> if (t.role == Role.ASSISTANT) Message.model(t.content) else Message.user(t.content) },
                    samplerConfig = SamplerConfig(40, 0.95, 0.7, 0),
                    maxOutputToken = replyBudget,
                )
            )
            Session(request.sessionKey ?: "", request.system, conversation, history.filter { it.role == Role.USER }.map { it.content }.toMutableList())
                .also { session = it }
        }

        activeConversation = s.conversation
        var completed = false
        var produced = false
        try {
            try {
                s.conversation.sendMessageAsync(message).collect { msg ->
                    val text = msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    if (text.isNotEmpty()) { produced = true; emit(GenerationEvent.Delta(text)) }
                }
            } catch (t: Throwable) {
                // Reply hit the window/output limit after producing text: keep what we have.
                if (produced && isContextFull(t)) { closeSession(); emit(GenerationEvent.Done("length")); return }
                throw t
            }
            completed = true
            s.userTurns += last.content
            emit(GenerationEvent.Done("stop"))
        } finally {
            activeConversation = null
            // A cancelled or failed generation leaves the runtime state uncertain: start fresh next time.
            if (!completed) closeSession()
        }
    }

    /** Keeps the most recent turns that fit [budget] tokens, starting with a user turn. */
    private fun fitHistory(history: List<ChatTurn>, budget: Int): List<ChatTurn> {
        val out = ArrayDeque<ChatTurn>()
        var used = 0
        for (t in history.asReversed()) {
            val cost = estimateTokens(t.content) + 8
            if (used + cost > budget) break
            out.addFirst(t); used += cost
        }
        while (out.isNotEmpty() && out.first().role != Role.USER) out.removeFirst()
        return out.toList()
    }

    companion object {
        const val DEFAULT_CONTEXT = 4096
        private const val SAFETY_TOKENS = 96

        /** Conservative estimate (~3 characters per token for mixed English text). */
        fun estimateTokens(text: String): Int = text.length / 3 + 4

        fun isContextFull(t: Throwable): Boolean {
            val m = (t.message ?: "") + (t.cause?.message ?: "")
            return m.contains("context window", true) || m.contains("Max number of tokens", true) || m.contains("Status Code: 8", true)
        }

        fun friendlyError(t: Throwable): String {
            val m = t.message ?: t.javaClass.simpleName
            return when {
                isContextFull(t) -> "This conversation is too long for the on-device model's memory. Start a new chat, or raise the context size in Model Manager."
                m.startsWith("Could not") || m.startsWith("Not enough") -> m
                else -> "On-device model error: $m"
            }
        }

        /** Context size from device RAM: 8K tokens on phones with ≥ 11 GB, otherwise 4K. */
        fun autoContextTokens(totalRamBytes: Long): Int = if (totalRamBytes >= 11L * 1024 * 1024 * 1024) 8192 else 4096
    }
}

class LocalLiteRtProvider(
    private val runtime: LocalModelRuntime,
    private val modelId: String,
    private val modelName: String,
    private val file: File,
    private val options: LocalModelOptions,
) : IntelligenceProvider {
    val contextTokens: Int get() = options.contextTokens
    override val id = "local"
    override val label = "On-device · $modelName"

    override suspend fun status(): ProviderStatus =
        if (file.exists()) ProviderStatus(true, "$modelName (${options.backend.name}, ${options.contextTokens / 1024}K context)") else ProviderStatus(false, "Model file missing")

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = runtime.generate(modelId, file, options, request)
}
