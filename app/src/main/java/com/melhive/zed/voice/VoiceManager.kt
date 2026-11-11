package com.melhive.zed.voice

import android.content.Context
import com.melhive.zed.core.voice.VoiceSelection
import com.melhive.zed.settings.SettingsRepository
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
 * The one text-to-speech engine ZED speaks with (Chat read-aloud and LIVE).
 * It follows Settings: the selected voice decides the engine (Android system
 * voice or an offline neural voice pack), speed and pitch apply immediately.
 * If a neural voice cannot load, it falls back to the system voice so ZED
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
                lateinit var isolated: IsolatedNeuralTts
                isolated = IsolatedNeuralTts(
                    context, selection.pack, selection.voice, packs.dir(selection.pack), Locale.getDefault().toLanguageTag(),
                    onCrash = { reason -> scope.launch { fallBack(isolated, reason) } },
                    onSpeed = { rtf -> onSpeed(isolated, rtf) },
                )
                isolated
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

    /** Never throws: any failure is recorded and reported as "not ready". */
    override suspend fun awaitReady(): Boolean = try {
        awaitReadyOrThrow()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        com.melhive.zed.android.CrashReporter.recordNonFatal("voice", e)
        _status.value = Status(Phase.FALLBACK, "System voice", "Voice error: ${e.message ?: e.javaClass.simpleName}")
        false
    }

    private suspend fun awaitReadyOrThrow(): Boolean {
        val e = ensure()
        if (e.awaitReady()) {
            if (_status.value.phase != Phase.FALLBACK) _status.value = when (e) {
                is IsolatedNeuralTts -> Status(Phase.READY, e.info()?.voiceName ?: "Neural voice", "Offline neural voice · ready in ${e.loadMillis} ms")
                else -> Status(Phase.READY, e.info()?.voiceName ?: "System voice", e.info()?.engine)
            }
            return true
        }
        if (e is IsolatedNeuralTts) {
            val fallback = fallBack(e, e.loadError ?: "unknown error") ?: return false
            return fallback.awaitReady()
        }
        return false
    }

    /** Tells the user when this phone renders the voice slower than it speaks (pauses between sentences). */
    private fun onSpeed(e: IsolatedNeuralTts, rtf: Float) {
        if (engine !== e || _status.value.phase != Phase.READY) return
        val detail = when {
            rtf > 1.05f -> "This phone renders this voice slower than it speaks (${"%.1f".format(rtf)}×), so expect pauses between sentences. Supertonic voices are faster."
            else -> "Offline neural voice · ready in ${e.loadMillis} ms · renders ${"%.1f".format(1f / rtf)}× faster than real time"
        }
        _status.value = _status.value.copy(detail = detail)
    }

    /** Replaces a failed or crashed neural voice with the phone voice so ZED keeps talking. */
    private suspend fun fallBack(failed: IsolatedNeuralTts, reason: String): TextToSpeechEngine? {
        val replacement = mutex.withLock {
            if (engine !== failed) return@withLock engine
            forwardJob?.cancel()
            failed.release()
            AndroidTextToSpeech(context).also { attach(it, engineKey ?: "fallback") }
        }
        _status.value = Status(Phase.FALLBACK, "System voice", "Neural voice unavailable ($reason). Using the phone voice; details are in Diagnostics.")
        return replacement
    }

    override fun speak(text: String, flush: Boolean): String? {
        val e = engine
        if (e == null) { scope.launch { awaitReady() }; return null } // reloads after a memory trim
        return runCatching { e.speak(text, flush) }.onFailure { com.melhive.zed.android.CrashReporter.recordNonFatal("voice speak", it) }.getOrNull()
    }
    override fun stop() { runCatching { engine?.stop() } }
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
    suspend fun preview(text: String): Boolean = try {
        if (awaitReady()) speak(text, flush = true) != null else false
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        com.melhive.zed.android.CrashReporter.recordNonFatal("voice preview", e)
        false
    }

    /** Installed Android voices (from the system text-to-speech engine). */
    suspend fun systemVoices(): List<SystemVoice> = runCatching {
        (engine as? AndroidTextToSpeech)?.let { if (it.awaitReady()) return@runCatching it.voices() }
        val temp = AndroidTextToSpeech(context)
        try { if (temp.awaitReady()) temp.voices() else emptyList() } finally { temp.release() }
    }.getOrDefault(emptyList())
}
