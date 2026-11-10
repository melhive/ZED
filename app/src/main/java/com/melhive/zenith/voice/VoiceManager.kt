package com.melhive.zenith.voice

import android.content.Context
import com.melhive.zenith.core.voice.VoiceSelection
import com.melhive.zenith.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/**
 * The one text-to-speech engine ZENITH speaks with (Chat read-aloud and LIVE).
 * It follows Settings: the selected voice decides the engine (Android system
 * voice or an offline neural voice pack), speed and pitch apply immediately.
 * If a neural voice cannot load, it falls back to the system voice so ZENITH
 * never goes silent, and [status] explains why.
 */
class VoiceManager(
    private val context: Context,
    private val settings: SettingsRepository,
    val packs: VoicePackRepository,
    private val scope: CoroutineScope,
) : TextToSpeechEngine {
    enum class Phase { IDLE, LOADING, READY, FALLBACK }
    data class Status(val phase: Phase, val voiceLabel: String, val detail: String? = null)

    private val _status = MutableStateFlow(Status(Phase.IDLE, "System voice"))
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _events = MutableSharedFlow<TtsEvent>(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<TtsEvent> = _events.asSharedFlow()

    private val mutex = Mutex()
    @Volatile private var engine: TextToSpeechEngine? = null
    private var engineKey: String? = null
    private var forwardJob: Job? = null
    @Volatile private var rate = 1f
    @Volatile private var pitch = 1f

    init {
        scope.launch {
            settings.settings.map { Triple(it.voiceId, it.speechRate, it.speechPitch) }.distinctUntilChanged().collect { (id, r, p) ->
                rate = r; pitch = p
                engine?.setRate(r); engine?.setPitch(p)
                // Swap a loaded engine right away so the next sentence uses the new voice.
                if (engine != null && engineKey != keyFor(id)) runCatching { awaitReady() }
            }
        }
        // A pack finishing its download makes its voice usable without restarting.
        scope.launch { packs.installed.collect { if (engine != null && engineKey != keyFor(settings.current().voiceId)) runCatching { awaitReady() } } }
    }

    private fun keyFor(id: String): String = when (val s = VoiceSelection.parse(id)) {
        is VoiceSelection.Neural -> if (packs.isInstalled(s.pack)) s.voice.id else "missing:${s.voice.id}"
        is VoiceSelection.System -> VoiceSelection.system(s.voiceName)
        VoiceSelection.SystemDefault -> VoiceSelection.SYSTEM_DEFAULT
    }

    private suspend fun ensure(): TextToSpeechEngine = mutex.withLock {
        val selection = VoiceSelection.parse(settings.current().voiceId)
        val key = keyFor(settings.current().voiceId)
        engine?.let { if (engineKey == key) return it }
        engine?.let { it.stop(); it.release() }
        forwardJob?.cancel()
        val created: TextToSpeechEngine = when (selection) {
            is VoiceSelection.Neural -> if (packs.isInstalled(selection.pack)) {
                _status.value = Status(Phase.LOADING, selection.voice.name, "Loading ${selection.pack.name}…")
                NeuralTextToSpeech(selection.pack, selection.voice, packs.dir(selection.pack), Locale.getDefault().toLanguageTag())
            } else {
                _status.value = Status(Phase.FALLBACK, "System voice", "${selection.pack.name} is not downloaded; using the system voice")
                AndroidTextToSpeech(context)
            }
            is VoiceSelection.System -> { _status.value = Status(Phase.LOADING, "System voice"); AndroidTextToSpeech(context, selection.voiceName) }
            VoiceSelection.SystemDefault -> { _status.value = Status(Phase.LOADING, "System voice"); AndroidTextToSpeech(context) }
        }
        attach(created, key)
        created
    }

    private fun attach(e: TextToSpeechEngine, key: String) {
        engine = e
        engineKey = key
        forwardJob = scope.launch { e.events.collect { _events.emit(it) } }
        e.setRate(rate); e.setPitch(pitch)
    }

    override suspend fun awaitReady(): Boolean {
        val e = ensure()
        if (e.awaitReady()) {
            if (_status.value.phase != Phase.FALLBACK) _status.value = when (e) {
                is NeuralTextToSpeech -> Status(Phase.READY, e.info()?.voiceName ?: "Neural voice", "Offline neural voice · ready in ${e.loadMillis} ms")
                else -> Status(Phase.READY, e.info()?.voiceName ?: "System voice", e.info()?.engine)
            }
            return true
        }
        if (e is NeuralTextToSpeech) {
            val reason = e.loadError ?: "unknown error"
            val fallback = mutex.withLock {
                if (engine !== e) return@withLock engine
                forwardJob?.cancel()
                e.release()
                AndroidTextToSpeech(context).also { attach(it, engineKey ?: "fallback") }
            } ?: return false
            _status.value = Status(Phase.FALLBACK, "System voice", "Neural voice could not load ($reason); using the system voice")
            return fallback.awaitReady()
        }
        return false
    }

    override fun speak(text: String, flush: Boolean): String? {
        val e = engine
        if (e == null) { scope.launch { runCatching { awaitReady() } }; return null } // reloads after a memory trim
        return e.speak(text, flush)
    }
    override fun stop() { engine?.stop() }
    override fun setRate(rate: Float) { this.rate = rate; engine?.setRate(rate) }
    override fun setPitch(pitch: Float) { this.pitch = pitch; engine?.setPitch(pitch) }
    override fun isSpeaking(): Boolean = engine?.isSpeaking() == true
    override fun info(): VoiceInfo? = engine?.info()

    /** Frees the loaded voice (neural voices hold 100–300 MB); it reloads on next use. */
    override fun release() { unload() }

    fun unload() {
        val e = engine ?: return
        engine = null; engineKey = null
        forwardJob?.cancel()
        e.stop(); e.release()
        _status.value = Status(Phase.IDLE, _status.value.voiceLabel)
    }

    /** A handle for one LIVE session: ending the session stops speech but keeps the voice loaded. */
    fun sessionHandle(): TextToSpeechEngine = object : TextToSpeechEngine by this@VoiceManager {
        override fun release() { this@VoiceManager.stop() }
    }

    /** Speaks a sample with the current settings (used by the voice picker). */
    suspend fun preview(text: String): Boolean {
        if (!awaitReady()) return false
        speak(text, flush = true)
        return true
    }

    /** Installed Android voices (from the system text-to-speech engine). */
    suspend fun systemVoices(): List<SystemVoice> {
        (engine as? AndroidTextToSpeech)?.let { if (it.awaitReady()) return it.voices() }
        val temp = AndroidTextToSpeech(context)
        return try { if (temp.awaitReady()) temp.voices() else emptyList() } finally { temp.release() }
    }
}
