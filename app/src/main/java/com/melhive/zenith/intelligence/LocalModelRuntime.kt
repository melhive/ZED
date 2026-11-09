package com.melhive.zenith.intelligence

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.melhive.zenith.core.intelligence.Role
import com.melhive.zenith.settings.LocalBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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

/**
 * Owns the LiteRT-LM [Engine]. Loading a multi-GB model takes seconds and a
 * lot of RAM, so the engine is loaded lazily, kept while in use, and released
 * on [unload] (e.g. when the user switches model or memory is low).
 */
class LocalModelRuntime(private val cacheDir: File) {

    sealed interface State {
        data object Unloaded : State
        data class Loading(val modelId: String) : State
        data class Ready(val modelId: String, val backend: LocalBackend, val loadMillis: Long) : State
        data class Failed(val modelId: String, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Unloaded)
    val state: StateFlow<State> = _state.asStateFlow()

    private val lock = Mutex()
    private var engine: Engine? = null
    private var loadedKey: String? = null
    @Volatile private var activeConversation: com.google.ai.edge.litertlm.Conversation? = null

    suspend fun ensureLoaded(modelId: String, file: File, backend: LocalBackend): Engine = lock.withLock {
        val key = "${file.absolutePath}|$backend"
        engine?.let { if (loadedKey == key) return it }
        withContext(Dispatchers.IO) {
            engine?.close(); engine = null; loadedKey = null
            require(file.exists()) { "Model file is missing: ${file.name}" }
            _state.value = State.Loading(modelId)
            val started = System.currentTimeMillis()
            try {
                val config = EngineConfig(
                    modelPath = file.absolutePath,
                    backend = if (backend == LocalBackend.GPU) Backend.GPU() else Backend.CPU(),
                    cacheDir = cacheDir.absolutePath,
                )
                val e = Engine(config)
                e.initialize()
                engine = e; loadedKey = key
                _state.value = State.Ready(modelId, backend, System.currentTimeMillis() - started)
                e
            } catch (t: Throwable) {
                _state.value = State.Failed(modelId, t.message ?: t.javaClass.simpleName)
                throw t
            }
        }
    }

    suspend fun unload() = lock.withLock {
        withContext(Dispatchers.IO) {
            activeConversation?.let { runCatching { it.cancelProcess() } }
            engine?.close(); engine = null; loadedKey = null
            _state.value = State.Unloaded
        }
    }

    fun cancelActive() { activeConversation?.let { runCatching { it.cancelProcess() } } }

    /**
     * Streams a reply. A fresh LiteRT-LM conversation is created per request,
     * seeded with the system instruction and prior turns, so history stays
     * under ZENITH's control (persisted in Room) rather than inside the runtime.
     */
    fun generate(modelId: String, file: File, backend: LocalBackend, request: GenerationRequest): Flow<GenerationEvent> = flow {
        val e = ensureLoaded(modelId, file, backend)
        val turns = normalizeTurns(request.turns)
        val last = turns.lastOrNull()
        if (last == null || last.role != Role.USER) { emit(GenerationEvent.Error("Nothing to answer")); return@flow }
        val initial = turns.dropLast(1).map { t ->
            if (t.role == Role.ASSISTANT) Message.model(t.content) else Message.user(t.content)
        }
        val config = ConversationConfig(
            systemInstruction = Contents.of(request.system),
            initialMessages = initial,
            samplerConfig = SamplerConfig(40, 0.95, 0.7, 0),
        )
        val conversation = e.createConversation(config)
        activeConversation = conversation
        try {
            conversation.sendMessageAsync(last.content).collect { msg ->
                val text = msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                if (text.isNotEmpty()) emit(GenerationEvent.Delta(text))
            }
            emit(GenerationEvent.Done("stop"))
        } finally {
            activeConversation = null
            runCatching { conversation.close() }
        }
    }.catch { t ->
        if (t is kotlinx.coroutines.CancellationException) throw t
        emit(GenerationEvent.Error("Local model error: ${t.message ?: t.javaClass.simpleName}"))
    }.flowOn(Dispatchers.Default)
}

class LocalLiteRtProvider(
    private val runtime: LocalModelRuntime,
    private val modelId: String,
    private val modelName: String,
    private val file: File,
    private val backend: LocalBackend,
) : IntelligenceProvider {
    override val id = "local"
    override val label = "On-device · $modelName"

    override suspend fun status(): ProviderStatus =
        if (file.exists()) ProviderStatus(true, "$modelName (${backend.name})") else ProviderStatus(false, "Model file missing")

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = runtime.generate(modelId, file, backend, request)
}
