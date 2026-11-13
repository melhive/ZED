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
    /** Listening for a follow-up after ZED answered (no need to say "Hey Zed" again). */
    val followUp: Boolean = false,
    /** The conversation went quiet: the floating orb closes itself. */
    val finished: Boolean = false,
    /** The sentence ZED is saying right now (Eye mode reads its feeling and shows it as a caption). */
    val spoken: String = "",
    /** ZED is looking through the camera to answer ("what is this?" in Eye mode). */
    val looking: Boolean = false,
    /** The conversation this call is saved in (null until something is said). */
    val conversationId: Long? = null,
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
    /** The user's name, for greetings (set by the container). */
    var userName: (suspend () -> String?)? = null
    private var lastGreeting: String? = null
    /** Answers given in this session; after the first, listening is a follow-up. */
    private var exchanges = 0
    private var speechEndedAt = 0L
    /** When each sentence was handed to the voice, to time how quickly it starts speaking. */
    private val queuedAt = HashMap<String, Long>()

    private val _state = MutableStateFlow(LiveUiState())
    val state: StateFlow<LiveUiState> = _state.asStateFlow()

    private var scope: CoroutineScope? = null
    private var stt: SpeechToText? = null
    private var tts: TextToSpeechEngine? = null
    private var monitor: BargeInMonitor? = null
    private var focus: AudioFocus? = null
    private var conversationId: Long? = null
    /** The conversation this session writes to, so "chat mode" can open it. */
    val conversation: Long? get() = conversationId
    /** Eye mode's camera: when set, questions like "what is this?" are answered from what it sees. */
    @Volatile var frameSource: (suspend () -> com.melhive.zed.intelligence.ImageInput?)? = null
    private val utteranceText = HashMap<String, String>()
    /** A chat to carry on with when the call starts (LIVE or Eye mode opened from that chat). Used once. */
    @Volatile var continueWith: Long? = null
    /** Listening for "stop" while ZED talks on the loudspeaker. */
    private var eavesdropping = false
    private var stopWordEnabled = true
    private var quietRounds = 2
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

    /**
     * Starts LIVE. [greet]: ZED was called ("Hey Zed" or the shortcut) and answers with a greeting first.
     * [announcement]: ZED came onto the screen by itself (a reminder, an event, after an alarm) and says it.
     */
    fun start(greet: Boolean = false, announcement: String? = null) {
        if (_state.value.active) return
        if (!PermissionCatalog.isGranted(context, PermissionCatalog.MICROPHONE)) {
            _state.value = LiveUiState(orb = OrbState.ERROR, error = "Microphone permission is required for ZED LIVE.")
            return
        }
        // A failure inside the session must never close the app: record it and show it on the orb.
        val guard = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            com.melhive.zed.android.CrashReporter.recordNonFatal("live session", e)
            runCatching { setOrb(OrbState.ERROR, "Something went wrong — tap the orb to try again") }
        }
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + guard)
        scope = s
        WakeWordService.paused.value = true // LIVE takes the microphone
        exchanges = 0
        AudioRoute.beginSession(context)
        stt = sttFactory()
        (stt as? OnDeviceSpeechToText)?.warm()
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
            stopWordEnabled = cfg.voiceStop
            quietRounds = when (cfg.listenPatience) { "short" -> 1; "long" -> 4; else -> 2 }
            _state.update { it.copy(speakerOn = cfg.voiceResponses) }
            tts?.setRate(cfg.speechRate)
            // Carry on a chat when the call was opened from one; otherwise the conversation is created with the first thing said.
            conversationId = continueWith?.takeIf { runCatching { conversations.get(it) }.getOrNull() != null }
            continueWith = null
            _state.update { it.copy(conversationId = conversationId) }
            if (tts?.awaitReady() != true) _state.update { it.copy(speakerOn = false, statusDetail = "Text-to-speech is unavailable; replies will be shown only") }
            delay(soundMs) // let the start sound finish before the microphone opens
            if (!recognitionAvailable()) {
                setOrb(OrbState.ERROR, "No speech recognition service on this phone. Use the keyboard or system voice input below, or install Google's speech services.")
                com.melhive.zed.android.CrashReporter.recordNonFatal("live", IllegalStateException("No speech recognition service available"))
            } else if (announcement != null) {
                say(announcement)
            } else if (greet && cfg.greetOnCall && _state.value.speakerOn) {
                // Answer like an assistant at the ready; listening starts when the greeting ends (or on a tap).
                val name = runCatching { userName?.invoke() }.getOrNull()
                val hello = com.melhive.zed.core.voice.Greetings.pick(java.time.LocalTime.now().hour, cfg.userTitle, name, lastGreeting)
                lastGreeting = hello
                _state.update { it.copy(transcript = it.transcript + LiveTurn(false, hello)) }
                replyFinished = true
                speakChunk(hello)
                if (pendingUtterances.isEmpty()) listen()
            } else listen()
        }
        s.launch { stt!!.events.collect { onStt(it) } }
        s.launch { tts!!.events.collect { onTts(it) } }
        s.launch { orchestrator.activity.collect { a -> onActivity(a.phase, a.detail) } }
        s.launch { orchestrator.streamingText.collect { onStreamText(it) } }
    }

    /** Ends the voice session. Background tasks keep running. */
    fun end() {
        eavesdropping = false
        if (_state.value.active) ZedSounds.play(context, soundStyle, start = false)
        BeepSuppressor.restore(context)
        AudioRoute.endSession(context)
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
        utteranceText.clear()
        _state.value = LiveUiState()
    }

    fun toggleMute() {
        val muted = !_state.value.micMuted
        _state.update { it.copy(micMuted = muted) }
        if (muted) {
            eavesdropping = false
            stt?.cancel(); monitor?.stop()
            if (_state.value.orb == OrbState.LISTENING) setOrb(OrbState.IDLE, "Microphone muted")
        } else if (_state.value.orb == OrbState.IDLE) listen()
    }

    fun toggleSpeaker() {
        val on = !_state.value.speakerOn
        _state.update { it.copy(speakerOn = on) }
        if (!on) { stopEavesdrop(); tts?.stop(); pendingUtterances.clear(); monitor?.stop(); focus?.release(); if (replyFinished) listen() }
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

    /** ZED speaks unprompted (an announcement), then listens for a reply. */
    private fun say(text: String) {
        _state.update { it.copy(transcript = it.transcript + LiveTurn(false, text)) }
        replyFinished = true
        if (_state.value.speakerOn) speakChunk(text)
        if (pendingUtterances.isEmpty()) listen()
    }

    /** Something new to announce while LIVE is already open (a reminder comes due mid-conversation). */
    fun announce(text: String) {
        if (!_state.value.active) return
        eavesdropping = false
        listenJob?.cancel(); stt?.cancel(); monitor?.stop()
        say(text)
    }

    /** Sends typed text or text from the system voice-input popup (fallbacks when live recognition fails). */
    fun submitText(text: String) {
        if (!_state.value.active || text.isBlank()) return
        eavesdropping = false
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
        stopEavesdrop()
        tts?.stop(); pendingUtterances.clear(); replyFinished = true
        monitor?.stop()
        _state.update { it.copy(looking = false) }
        setOrb(OrbState.IDLE, "Request cancelled")
    }

    /** Stops listening for "stop" (ZED finished or was cut off). */
    private fun stopEavesdrop() {
        if (!eavesdropping) return
        eavesdropping = false
        stt?.cancel()
    }

    /** While ZED talks on the loudspeaker: the on-device recognizer listens for a stop word that ZED isn't saying itself. */
    private fun startEavesdrop() {
        if (!stopWordEnabled || eavesdropping || _state.value.micMuted || stt !is OnDeviceSpeechToText) return
        eavesdropping = true
        listenJob?.cancel()
        stt?.start()
    }

    private fun onEavesdrop(e: SttEvent) {
        when (e) {
            is SttEvent.Partial, is SttEvent.Final -> {
                val heard = if (e is SttEvent.Partial) e.text else (e as SttEvent.Final).text
                val speaking = streamText + " " + utteranceText.values.joinToString(" ") + " " + _state.value.spoken
                if (com.melhive.zed.core.eyes.VoiceInterrupt.heard(heard, speaking)) { interrupt(); return }
                if (e is SttEvent.Final) scope?.launch { delay(150); if (eavesdropping && _state.value.orb == OrbState.SPEAKING) stt?.start() }
            }
            // Nothing heard for a while, or a hiccup: keep listening for as long as ZED talks.
            is SttEvent.Error -> scope?.launch { delay(if (e.recoverable) 300 else 3000); if (eavesdropping && _state.value.orb == OrbState.SPEAKING && e.recoverable) stt?.start() }
            else -> Unit
        }
    }

    private fun interrupt() {
        stopEavesdrop()
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
        eavesdropping = false
        _state.update { it.copy(partial = "", micLevel = 0f, followUp = exchanges > 0, finished = false, looking = false, spoken = "") }
        setOrb(OrbState.LISTENING)
        listenJob?.cancel()
        listenJob = scope?.launch {
            // Make sure the barge-in recorder has released the microphone first.
            monitor?.stopAndJoin()
            delay(120)
            if (_state.value.orb == OrbState.LISTENING && !_state.value.micMuted) {
                // The on-device recognizer makes no beep of its own.
                if (hideBeep && stt !is OnDeviceSpeechToText) BeepSuppressor.suppress(context)
                stt?.start()
            }
        }
    }

    private fun onStt(e: SttEvent) {
        if (eavesdropping) { onEavesdrop(e); return }
        // Leftovers from a recognizer that was just stopped are not a request.
        if (_state.value.orb != OrbState.LISTENING && (e is SttEvent.Partial || e is SttEvent.Final || e is SttEvent.EndOfSpeech)) return
        when (e) {
            is SttEvent.Level -> if (_state.value.orb == OrbState.LISTENING) _state.update { it.copy(micLevel = e.level) }
            is SttEvent.Partial -> _state.update { it.copy(partial = e.text) }
            is SttEvent.Ready, is SttEvent.SpeechStarted -> consecutiveErrors = 0
            is SttEvent.EndOfSpeech -> speechEndedAt = System.currentTimeMillis()
            is SttEvent.Final -> {
                consecutiveErrors = 0
                if (speechEndedAt > 0) com.melhive.zed.diagnostics.SpeedLog.record(com.melhive.zed.diagnostics.SpeedLog.Stage.HEARING, System.currentTimeMillis() - speechEndedAt)
                speechEndedAt = 0
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
        // Conversation mode: after an answer, silence means the user is done.
        if (emptyResults >= quietRounds) {
            emptyResults = 0
            setOrb(OrbState.IDLE, "Tap the orb to talk")
            _state.update { it.copy(followUp = false, finished = true) }
        } else listen()
    }

    /** "What am I looking at?": sends a photo with a question. */
    fun submitImage(image: com.melhive.zed.intelligence.ImageInput, prompt: String) {
        if (!_state.value.active) return
        eavesdropping = false
        listenJob?.cancel(); stt?.cancel(); monitor?.stop()
        tts?.stop(); pendingUtterances.clear()
        if (!replyFinished) { requestJob?.cancel(); orchestrator.cancelCurrent() }
        submit("📷 $prompt", image)
    }

    private fun submit(text: String, image: com.melhive.zed.intelligence.ImageInput? = null) {
        _state.update { it.copy(transcript = it.transcript + LiveTurn(true, text)) }
        setOrb(OrbState.THINKING)
        // Instant acknowledgement: a low thunk the moment ZED has the request, before any answer exists.
        if (soundStyle != com.melhive.zed.core.voice.SoundSynth.Style.OFF) ZedSounds.ack(context)
        streamText = ""; spokenChars = 0; replyFinished = false
        // Eye mode: "what is this?" is answered from what the camera sees right now.
        val camera = frameSource?.takeIf { image == null && com.melhive.zed.core.eyes.VisualAsk.matches(text) }
        if (camera != null) _state.update { it.copy(looking = true) }
        requestJob = scope?.launch {
            // The call is saved under its first words, marked as a voice conversation.
            val conv = conversationId ?: conversations.create("🎙 " + text.removePrefix("📷 ").lineSequence().first().take(46).ifBlank { "Voice conversation" }).also { id ->
                conversationId = id
                _state.update { it.copy(conversationId = id) }
            }
            val seen = image ?: camera?.let { runCatching { it() }.getOrNull() }
            val reply = try { orchestrator.respond(conv, text.removePrefix("📷 "), voice = true, image = seen) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
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
        utteranceText[id] = text
        if (pendingUtterances.size == 1) { queuedAt[id] = System.currentTimeMillis(); _state.update { it.copy(spoken = text) } }
        if (_state.value.orb != OrbState.SPEAKING) {
            focus?.acquire()
            setOrb(OrbState.SPEAKING)
            startBargeIn()
        }
    }

    private fun onReply(reply: AssistantReply) {
        replyFinished = true
        exchanges++
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
                utteranceText.remove(id)
                if (pendingUtterances.isEmpty()) focus?.release()
                if (pendingUtterances.isEmpty() && replyFinished && _state.value.orb == OrbState.SPEAKING) {
                    stopEavesdrop()
                    monitor?.stop()
                    setOrb(OrbState.COMPLETED)
                    scope?.launch { delay(400); if (pendingUtterances.isEmpty() && replyFinished) listen() }
                }
            }
            is TtsEvent.Started -> {
                utteranceText[e.utteranceId]?.let { said -> _state.update { it.copy(spoken = said) } }
                queuedAt.remove(e.utteranceId)?.let { com.melhive.zed.diagnostics.SpeedLog.record(com.melhive.zed.diagnostics.SpeedLog.Stage.VOICE, System.currentTimeMillis() - it) }
            }
        }
    }

    private fun startBargeIn() {
        // On the loudspeaker ZED would hear itself; talk-over interruption needs headphones. Tap the orb otherwise.
        // Also not when ZED speaks from the loudspeaker in earphone-mic mode (the mic would hear it).
        if (!bargeInEnabled || _state.value.micMuted || focus?.headphonesConnected() != true || AudioRoute.speakerForced()) { startEavesdrop(); return }
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
        eavesdropping = false
        tts?.stop(); pendingUtterances.clear(); monitor?.stop(); stt?.cancel(); focus?.release()
        setOrb(OrbState.IDLE, "$reason. Tap the orb to continue.")
    }

}
