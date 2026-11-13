package com.melhive.zed.intelligence

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.melhive.zed.core.intelligence.ChatTurn
import com.melhive.zed.core.intelligence.Role
import com.melhive.zed.settings.LocalBackend
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
    /** Whether speculative decoding is active for the loaded model (null until a model loads). */
    @Volatile var speculativeEnabled: Boolean? = null
        private set
    @Volatile var cpuThreads: Int? = null
        private set
    /** Why the GPU couldn't be used for the loaded model (it then runs on the CPU), or null. */
    @Volatile var gpuFallbackReason: String? = null
        private set
    /** Whether the loaded model can look at photos (its vision encoder started). */
    @Volatile var visionReady: Boolean = false
        private set

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
                val threads = com.melhive.zed.android.DeviceInfo.performanceCores()
                // Only files that contain a vision encoder can look at photos (checked in the file itself).
                var vision = hasVisionEncoder(file)
                fun config(speculative: Boolean, gpu: Boolean) = EngineConfig(
                    modelPath = file.absolutePath,
                    // CPU: only the fast cores — slow efficiency cores drag every token down on big.LITTLE phones.
                    backend = if (gpu) Backend.GPU() else Backend.CPU(threads, threads),
                    // Photos ("what is this?") run through the model's own vision encoder, on the phone.
                    visionBackend = if (!vision) null else if (gpu) Backend.GPU() else Backend.CPU(threads, threads),
                    maxNumImages = if (vision) 1 else null,
                    maxNumTokens = options.contextTokens,
                    cacheDir = cacheDir.absolutePath,
                    speculativeDecodingConfig = if (speculative) com.google.ai.edge.litertlm.SpeculativeDecodingConfig(com.google.ai.edge.litertlm.SpeculativeDecodingMode.ADAPTIVE) else null,
                )
                // Speculative decoding drafts and verifies several tokens per step (same output, faster).
                // Models without support fail to initialize with it, so retry without.
                fun startText(gpu: Boolean): Engine = runCatching { Engine(config(speculative = true, gpu = gpu)).also { it.initialize() } }.getOrElse {
                    speculativeEnabled = false
                    Engine(config(speculative = false, gpu = gpu)).also { it.initialize() }
                }.also { if (speculativeEnabled == null) speculativeEnabled = true }
                // A model without a usable vision encoder still starts, just without photos.
                fun start(gpu: Boolean): Engine = if (!vision) startText(gpu) else try { startText(gpu) } catch (t: Throwable) {
                    vision = false; speculativeEnabled = null; startText(gpu)
                }
                var usedBackend = options.backend
                val e = if (options.backend == LocalBackend.GPU) {
                    try { start(gpu = true) } catch (gpuError: Throwable) {
                        // The GPU couldn't start (driver missing or unsupported): run on the CPU instead of failing.
                        gpuFallbackReason = gpuError.message ?: gpuError.javaClass.simpleName
                        usedBackend = LocalBackend.CPU
                        try { start(gpu = false) } catch (cpuError: Throwable) {
                            throw IllegalStateException("The GPU couldn't start ($gpuFallbackReason) and this build doesn't run on the CPU. Download the CPU build of this model.", cpuError)
                        }
                    }
                } else { gpuFallbackReason = null; start(gpu = false) }
                engine = e; loadedKey = key; contextTokens = options.contextTokens
                visionReady = vision
                cpuThreads = if (usedBackend == LocalBackend.GPU) null else threads
                _state.value = State.Ready(modelId, usedBackend, System.currentTimeMillis() - started, options.contextTokens)
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
            raw.startsWith("The GPU couldn't start") -> raw
            raw.contains("memory", ignoreCase = true) -> "Not enough memory to load the model ($raw). Close other apps or choose a smaller context size."
            else -> "Could not load the model: $raw"
        }
    }

    private fun closeSession() {
        session?.let { runCatching { it.conversation.close() } }
        session = null
    }

    /**
     * Frees the model. A generation in progress is cancelled first and allowed to finish before the
     * engine is closed — closing it underneath a running generation crashed the app (native).
     */
    suspend fun unload() {
        activeConversation?.let { runCatching { it.cancelProcess() } }
        generationLock.withLock {
            lock.withLock {
                withContext(Dispatchers.IO) {
                    closeSession()
                    engine?.close(); engine = null; loadedKey = null
                    _state.value = State.Unloaded
                }
            }
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
        // A photo goes to the model's vision encoder; it starts a fresh conversation (history as text).
        if (request.images.isNotEmpty() && !visionReady) {
            emit(GenerationEvent.Error("Your copy of this model has no photo support. Open Model Manager and tap “Update — adds photo support” (or download Gemma 4 E2B), then ask again."))
            return
        }
        val image = request.images.firstOrNull()
        val messageTokens = estimateTokens(message) + if (image != null) IMAGE_TOKENS else 0
        val reuse = image == null && !minimalHistory && current != null && request.sessionKey != null && current.key == request.sessionKey &&
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
                    // No hidden "thinking" pass before answering: much faster replies for everyday requests.
                    thinkingConfig = com.google.ai.edge.litertlm.ThinkingConfig(false),
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
                val reply = if (image == null) s.conversation.sendMessageAsync(message) else s.conversation.sendMessageAsync(
                    Contents.of(Content.ImageBytes(android.util.Base64.decode(image.base64, android.util.Base64.NO_WRAP)), Content.Text(message)),
                )
                reply.collect { msg ->
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
            if (image != null) closeSession()
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
        /** Room a photo takes in the context window. */
        private const val IMAGE_TOKENS = 300

        /**
         * True when a .litertlm file contains a vision encoder. Its section table near the start of the
         * file names each part ("tf_lite_vision_encoder", "tf_lite_audio_encoder_hw", …).
         */
        fun hasVisionEncoder(file: File): Boolean = runCatching {
            val head = ByteArray(minOf(file.length(), 4L shl 20).toInt())
            java.io.RandomAccessFile(file, "r").use { it.readFully(head) }
            String(head, Charsets.ISO_8859_1).contains("tf_lite_vision_encoder")
        }.getOrDefault(false)

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

    /** Loads the model now (does nothing when it is already loaded). */
    suspend fun warmUp() { if (file.exists()) runtime.ensureLoaded(modelId, file, options) }
    override val label = "On-device · $modelName"

    override suspend fun status(): ProviderStatus =
        if (file.exists()) ProviderStatus(true, "$modelName (${options.backend.name}, ${options.contextTokens / 1024}K context)") else ProviderStatus(false, "Model file missing")

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = runtime.generate(modelId, file, options, request)
}
