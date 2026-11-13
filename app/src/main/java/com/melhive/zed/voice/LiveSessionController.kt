package com.melhive.zed.voice

import android.content.Context
import com.melhive.zed.agents.AssistantOrchestrator
import com.melhive.zed.agents.AssistantPhase
import com.melhive.zed.agents.AssistantReply
import com.melhive.zed.android.PermissionCatalog
import com.melhive.zed.memory.ConversationRepository
import com.melhive.zed.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Visual/logical state of the ZED LIVE session; each maps to an orb animation. */
enum class OrbState(val label: String) {
    IDLE("Ready"),
    LISTENING("Listening..."),
    THINKING("Thinking..."),
    SPEAKING("Speaking..."),
    EXECUTING("Executing task..."),
    RESEARCHING("Researching..."),
    SEARCHING("Searching documents..."),
    COMPLETED("Completed"),
    ERROR("Error"),
}

data class LiveTurn(val fromUser: Boolean, val text: String)

data class LiveUiState(
    val active: Boolean = false,
    val orb: OrbState = OrbState.IDLE,
    val statusDetail: String? = null,
    /** Real microphone level 0..1 (recognizer RMS or barge-in monitor). */
    val micLevel: Float = 0f,
    /** Incremented on every TTS word boundary; the orb pulses on change. */
    val speechPulse: Int = 0,
    val partial: String = "",
    val transcript: List<LiveTurn> = emptyList(),
    val micMuted: Boolean = false,
    val speakerOn: Boolean = true,
    val echoCancellation: Boolean = false,
    val sttEngine: String = "",
    val error: String? = null,
)

/**
 * Continuous voice conversation:
 *
 *  LISTENING ──final transcript──▶ THINKING/EXECUTING/… (orchestrator)
 *      ▲                                   │ reply text streams in
 *      │                                   ▼
 *      └──── utterances done ◀──────── SPEAKING (sentence by sentence)
 *                                          │ user speaks (barge-in) or taps
 *                                          ▼
 *                              stop playback → LISTENING
 *
 * Responses are spoken while they are still being generated (sentence
 * streaming). Speech recognition itself is turn-based per utterance (Android
 * SpeechRecognizer), not a continuous audio stream.
 *
 * Ending the session never cancels background tasks.
 */
class LiveSessionController(
    private val context: Context,
    private val orchestrator: AssistantOrchestrator,
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val sttFactory: () -> SpeechToText,
    private val ttsFactory: () -> TextToSpeechEngine,
) {
    private val _state = MutableStateFlow(LiveUiState())
    val state: StateFlow<LiveUiState> = _state.asStateFlow()

    private var scope: CoroutineScope? = null
    private var stt: SpeechToText? = null
    private var tts: TextToSpeechEngine? = null
    private var monitor: BargeInMonitor? = null
    private var focus: AudioFocus? = null
    private var conversationId: Long? = null
    private var requestJob: Job? = null

    // Sentence streaming bookkeeping.
    private val pendingUtterances = mutableSetOf<String>()
    private var streamText = ""
    private var spokenChars = 0
    private var replyFinished = true
    private var emptyResults = 0
    private var consecutiveErrors = 0
    private var bargeInEnabled = true
    private var listenJob: Job? = null

    private var soundStyle = com.melhive.zed.core.voice.SoundSynth.Style.CORE
    private var hideBeep = true

    fun start() {
        if (_state.value.active) return
        if (!PermissionCatalog.isGranted(context, PermissionCatalog.MICROPHONE)) {
            _state.value = LiveUiState(orb = OrbState.ERROR, error = "Microphone permission is required for ZED LIVE.")
            return
        }
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        WakeWordService.paused.value = true // LIVE takes the microphone
        stt = sttFactory()
        tts = ttsFactory()
        monitor = BargeInMonitor(s)
        focus = AudioFocus(context) { reason -> s.launch { onInterrupted(reason) } }
        _state.value = LiveUiState(active = true, orb = OrbState.IDLE, sttEngine = stt?.engineName.orEmpty())

        s.launch {
            val cfg = settings.current()
            soundStyle = com.melhive.zed.core.voice.SoundSynth.Style.parse(cfg.liveSound)
            hideBeep = cfg.hideRecognizerBeep
            val soundMs = ZedSounds.play(context, soundStyle, start = true)
            bargeInEnabled = cfg.bargeIn
            _state.update { it.copy(speakerOn = cfg.voiceResponses) }
            tts?.setRate(cfg.speechRate)
            conversationId = conversations.create("ZED LIVE session")
            if (tts?.awaitReady() != true) _state.update { it.copy(speakerOn = false, statusDetail = "Text-to-speech is unavailable; replies will be shown only") }
            delay(soundMs) // let the start sound finish before the microphone opens
            if (!recognitionAvailable()) {
                setOrb(OrbState.ERROR, "No speech recognition service on this phone. Use the keyboard or system voice input below, or install Google's speech services.")
                com.melhive.zed.android.CrashReporter.recordNonFatal("live", IllegalStateException("No speech recognition service available"))
            } else listen()
        }
        s.launch { stt!!.events.collect { onStt(it) } }
        s.launch { tts!!.events.collect { onTts(it) } }
        s.launch { orchestrator.activity.collect { a -> onActivity(a.phase, a.detail) } }
        s.launch { orchestrator.streamingText.collect { onStreamText(it) } }
    }

    /** Ends the voice session. Background tasks keep running. */
    fun end() {
        if (_state.value.active) ZedSounds.play(context, soundStyle, start = false)
        BeepSuppressor.restore(context)
        WakeWordService.paused.value = false
        listenJob?.cancel()
        requestJob?.cancel()
        monitor?.stop()
        stt?.cancel(); stt?.release()
        tts?.stop(); tts?.release()
        focus?.release()
        scope?.cancel()
        stt = null; tts = null; monitor = null; focus = null; scope = null
        pendingUtterances.clear()
        _state.value = LiveUiState()
    }

    fun toggleMute() {
        val muted = !_state.value.micMuted
        _state.update { it.copy(micMuted = muted) }
        if (muted) {
            stt?.cancel(); monitor?.stop()
            if (_state.value.orb == OrbState.LISTENING) setOrb(OrbState.IDLE, "Microphone muted")
        } else if (_state.value.orb == OrbState.IDLE) listen()
    }

    fun toggleSpeaker() {
        val on = !_state.value.speakerOn
        _state.update { it.copy(speakerOn = on) }
        if (!on) { tts?.stop(); pendingUtterances.clear(); monitor?.stop(); focus?.release(); if (replyFinished) listen() }
    }

    /** Tap on the orb: interrupt speech, cancel the pending request, or start listening. */
    fun tapOrb() {
        when (_state.value.orb) {
            OrbState.SPEAKING -> interrupt()
            OrbState.THINKING, OrbState.SEARCHING, OrbState.RESEARCHING -> { cancelRequest(); listen() }
            OrbState.LISTENING -> stt?.stop()
            else -> { consecutiveErrors = 0; if (_state.value.micMuted) toggleMute() else listen() }
        }
    }

    /** Sends typed text or text from the system voice-input popup (fallbacks when live recognition fails). */
    fun submitText(text: String) {
        if (!_state.value.active || text.isBlank()) return
        listenJob?.cancel(); stt?.cancel(); monitor?.stop()
        tts?.stop(); pendingUtterances.clear()
        if (!replyFinished) { requestJob?.cancel(); orchestrator.cancelCurrent() }
        consecutiveErrors = 0
        submit(text.trim())
    }

    /** True when Android reports a usable speech recognition service. */
    fun recognitionAvailable(): Boolean = android.speech.SpeechRecognizer.isRecognitionAvailable(context) ||
        (android.os.Build.VERSION.SDK_INT >= 31 && android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(context))

    /** Cancels only the current assistant request (not background tasks). */
    fun cancelRequest() {
        requestJob?.cancel()
        orchestrator.cancelCurrent()
        tts?.stop(); pendingUtterances.clear(); replyFinished = true
        monitor?.stop()
        setOrb(OrbState.IDLE, "Request cancelled")
    }

    private fun interrupt() {
        monitor?.stop()
        tts?.stop()
        pendingUtterances.clear()
        focus?.release()
        if (!replyFinished) { requestJob?.cancel(); orchestrator.cancelCurrent(); replyFinished = true }
        // Give the barge-in recorder a moment to release the microphone before the recognizer opens it.
        scope?.launch { delay(200); listen() }
    }

    private fun setOrb(o: OrbState, detail: String? = null) = _state.update { it.copy(orb = o, statusDetail = detail, error = if (o == OrbState.ERROR) detail else null) }

    private fun listen() {
        if (!_state.value.active || _state.value.micMuted) return
        _state.update { it.copy(partial = "", micLevel = 0f) }
        setOrb(OrbState.LISTENING)
        listenJob?.cancel()
        listenJob = scope?.launch {
            // Make sure the barge-in recorder has released the microphone first.
            monitor?.stopAndJoin()
            delay(120)
            if (_state.value.orb == OrbState.LISTENING && !_state.value.micMuted) {
                if (hideBeep) BeepSuppressor.suppress(context)
                stt?.start()
            }
        }
    }

    private fun onStt(e: SttEvent) {
        when (e) {
            is SttEvent.Level -> if (_state.value.orb == OrbState.LISTENING) _state.update { it.copy(micLevel = e.level) }
            is SttEvent.Partial -> _state.update { it.copy(partial = e.text) }
            is SttEvent.Ready, is SttEvent.SpeechStarted -> consecutiveErrors = 0
            is SttEvent.Final -> {
                consecutiveErrors = 0
                val text = e.text.trim()
                _state.update { it.copy(partial = "", micLevel = 0f) }
                if (text.isEmpty()) onNothingHeard() else { emptyResults = 0; submit(text) }
            }
            is SttEvent.Error -> {
                _state.update { it.copy(micLevel = 0f) }
                if (_state.value.orb != OrbState.LISTENING) return // stale error from a cancelled recognizer
                if (e.code == android.speech.SpeechRecognizer.ERROR_NO_MATCH || e.code == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT) { onNothingHeard(); return }
                consecutiveErrors++
                com.melhive.zed.android.CrashReporter.recordNonFatal("live speech", IllegalStateException(e.message))
                if (e.recoverable && consecutiveErrors < 3) {
                    setOrb(OrbState.ERROR, e.message)
                    scope?.launch { delay(1200L * consecutiveErrors); if (_state.value.orb == OrbState.ERROR) listen() }
                } else {
                    consecutiveErrors = 0
                    setOrb(OrbState.ERROR, "${e.message}. Tap the orb to try again." +
                        if (e.code == android.speech.SpeechRecognizer.ERROR_NETWORK || e.code == android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT)
                            " For offline use, install your language's offline speech pack in Android settings." else "")
                }
            }
            else -> Unit
        }
    }

    private fun onNothingHeard() {
        emptyResults++
        if (emptyResults >= 2) { emptyResults = 0; setOrb(OrbState.IDLE, "Tap the orb to talk") }
        else listen()
    }

    /** "What am I looking at?": sends a photo with a question. */
    fun submitImage(image: com.melhive.zed.intelligence.ImageInput, prompt: String) {
        if (!_state.value.active) return
        listenJob?.cancel(); stt?.cancel(); monitor?.stop()
        tts?.stop(); pendingUtterances.clear()
        if (!replyFinished) { requestJob?.cancel(); orchestrator.cancelCurrent() }
        submit("📷 $prompt", image)
    }

    private fun submit(text: String, image: com.melhive.zed.intelligence.ImageInput? = null) {
        val conv = conversationId ?: return
        _state.update { it.copy(transcript = it.transcript + LiveTurn(true, text)) }
        setOrb(OrbState.THINKING)
        // Instant acknowledgement: a low thunk the moment ZED has the request, before any answer exists.
        if (soundStyle != com.melhive.zed.core.voice.SoundSynth.Style.OFF) ZedSounds.ack(context)
        streamText = ""; spokenChars = 0; replyFinished = false
        requestJob = scope?.launch {
            val reply = try { orchestrator.respond(conv, text.removePrefix("📷 "), voice = true, image = image) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            onReply(reply)
        }
    }

    private fun onActivity(phase: AssistantPhase, detail: String?) {
        if (replyFinished || _state.value.orb == OrbState.SPEAKING) return
        when (phase) {
            AssistantPhase.THINKING -> setOrb(OrbState.THINKING)
            AssistantPhase.EXECUTING -> setOrb(OrbState.EXECUTING, detail)
            AssistantPhase.RESEARCHING -> setOrb(OrbState.RESEARCHING, detail)
            AssistantPhase.SEARCHING -> setOrb(OrbState.SEARCHING, detail)
            else -> Unit
        }
    }

    /** Speaks completed sentences while the model is still generating. */
    private fun onStreamText(text: String) {
        if (replyFinished || !_state.value.speakerOn) return
        if (!text.startsWith(streamText.take(minOf(streamText.length, text.length)))) { spokenChars = 0 } // new round
        streamText = text
        val speakable = text.substringBefore("```")
        if (speakable.length <= spokenChars) return
        val pending = speakable.substring(spokenChars)
        val boundary = Regex("[.!?\\n](\\s|$)").findAll(pending).lastOrNull() ?: return
        val end = boundary.range.first + 1
        val sentence = pending.substring(0, end)
        if (sentence.isNotBlank()) speakChunk(sentence)
        spokenChars += end
    }

    private fun speakChunk(text: String) {
        val id = tts?.speak(text, flush = false) ?: return
        pendingUtterances += id
        if (_state.value.orb != OrbState.SPEAKING) {
            focus?.acquire()
            setOrb(OrbState.SPEAKING)
            startBargeIn()
        }
    }

    private fun onReply(reply: AssistantReply) {
        replyFinished = true
        if (!reply.ok) com.melhive.zed.android.CrashReporter.recordNonFatal("live reply", IllegalStateException(reply.text))
        _state.update { it.copy(transcript = it.transcript + LiveTurn(false, reply.text)) }
        if (_state.value.speakerOn) {
            // Speak whatever was not already streamed.
            val visibleStream = streamText.substringBefore("```")
            val remainder = when {
                spokenChars > 0 && reply.text.startsWith(visibleStream.take(spokenChars)) -> reply.text.drop(spokenChars)
                spokenChars > 0 -> ""
                else -> reply.text
            }
            if (remainder.isNotBlank()) speakChunk(remainder)
        }
        if (pendingUtterances.isEmpty()) {
            setOrb(if (reply.ok) OrbState.COMPLETED else OrbState.ERROR, if (reply.ok) null else reply.text)
            scope?.launch { delay(if (reply.ok) 700 else 2000); if (pendingUtterances.isEmpty() && replyFinished) listen() }
        }
    }

    private fun onTts(e: TtsEvent) {
        when (e) {
            is TtsEvent.Word -> _state.update { it.copy(speechPulse = it.speechPulse + 1) }
            is TtsEvent.Done, is TtsEvent.Stopped, is TtsEvent.Error -> {
                val id = when (e) { is TtsEvent.Done -> e.utteranceId; is TtsEvent.Stopped -> e.utteranceId; is TtsEvent.Error -> e.utteranceId; else -> null }
                pendingUtterances.remove(id)
                if (pendingUtterances.isEmpty()) focus?.release()
                if (pendingUtterances.isEmpty() && replyFinished && _state.value.orb == OrbState.SPEAKING) {
                    monitor?.stop()
                    setOrb(OrbState.COMPLETED)
                    scope?.launch { delay(400); if (pendingUtterances.isEmpty() && replyFinished) listen() }
                }
            }
            is TtsEvent.Started -> Unit
        }
    }

    private fun startBargeIn() {
        // On the loudspeaker ZED would hear itself; talk-over interruption needs headphones. Tap the orb otherwise.
        if (!bargeInEnabled || _state.value.micMuted || focus?.headphonesConnected() != true) return
        val m = monitor ?: return
        m.start(
            onLevel = { lvl -> _state.update { it.copy(micLevel = lvl) } },
            onSpeech = { scope?.launch { if (_state.value.orb == OrbState.SPEAKING) interrupt() } },
        )
        scope?.launch { delay(500); _state.update { it.copy(echoCancellation = m.echoCancellerActive) } }
    }

    private fun onInterrupted(reason: String) {
        if (!_state.value.active) return
        listenJob?.cancel()
        tts?.stop(); pendingUtterances.clear(); monitor?.stop(); stt?.cancel(); focus?.release()
        setOrb(OrbState.IDLE, "$reason. Tap the orb to continue.")
    }

}
