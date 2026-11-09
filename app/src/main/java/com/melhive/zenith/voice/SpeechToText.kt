package com.melhive.zenith.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface SttEvent {
    data object Ready : SttEvent
    data object SpeechStarted : SttEvent
    /** Normalized microphone level 0..1 reported by the recognizer. */
    data class Level(val level: Float) : SttEvent
    data class Partial(val text: String) : SttEvent
    data class Final(val text: String) : SttEvent
    data object EndOfSpeech : SttEvent
    data class Error(val code: Int, val message: String, val recoverable: Boolean) : SttEvent
}

/** Replaceable speech-to-text engine. Implementations must be driven from the main thread. */
interface SpeechToText {
    val events: SharedFlow<SttEvent>
    val engineName: String
    fun start(languageTag: String? = null)
    fun stop()
    fun cancel()
    fun release()
}

/**
 * Android SpeechRecognizer. Uses the system's default recognition service with
 * EXTRA_PREFER_OFFLINE (offline when the language pack is installed). The
 * dedicated on-device recognizer is used only when no default service exists,
 * because it is unreliable on many phones. The recognizer is recreated after
 * client/busy/disconnect errors. Each start() is one utterance.
 */
class AndroidSpeechToText(private val context: Context) : SpeechToText {
    private val _events = MutableSharedFlow<SttEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<SttEvent> = _events.asSharedFlow()
    private var recognizer: SpeechRecognizer? = null
    private var usingOnDevice = false
    private var onDeviceFailed = false

    override val engineName: String
        get() = if (usingOnDevice) "Android on-device recognizer" else "Android speech recognition service"

    private fun create(): SpeechRecognizer {
        recognizer?.let { return it }
        val onDevice = !onDeviceFailed && !SpeechRecognizer.isRecognitionAvailable(context) &&
            Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        usingOnDevice = onDevice
        val r = if (onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        r.setRecognitionListener(listener)
        recognizer = r
        return r
    }

    override fun start(languageTag: String?) {
        if (!SpeechRecognizer.isRecognitionAvailable(context) && !(Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))) {
            _events.tryEmit(SttEvent.Error(-1, "No speech recognition service is installed on this device", false))
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            if (languageTag != null) putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        }
        try { create().startListening(intent) } catch (e: Exception) {
            _events.tryEmit(SttEvent.Error(-2, "Could not start listening: ${e.message}", true))
        }
    }

    override fun stop() { recognizer?.stopListening() }
    override fun cancel() { recognizer?.cancel() }
    override fun release() { recognizer?.destroy(); recognizer = null }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { _events.tryEmit(SttEvent.Ready) }
        override fun onBeginningOfSpeech() { _events.tryEmit(SttEvent.SpeechStarted) }
        override fun onRmsChanged(rmsdB: Float) { _events.tryEmit(SttEvent.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { _events.tryEmit(SttEvent.EndOfSpeech) }
        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { _events.tryEmit(SttEvent.Partial(it)) }
        }
        override fun onResults(results: Bundle?) {
            _events.tryEmit(SttEvent.Final(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()))
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onError(error: Int) {
            // These leave the recognizer in a bad state on many devices: recreate it next time.
            if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED || error == SpeechRecognizer.ERROR_SERVER) {
                recognizer?.destroy(); recognizer = null
            }
            val (msg, recoverable) = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "I didn't catch that" to true
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected" to true
                SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error" to true
                SpeechRecognizer.ERROR_CLIENT -> "Recognizer client error" to true
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy" to true
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing" to false
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a network connection on this device (no offline language pack)" to true
                SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Speech service error" to true
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    // On-device recognizer lacks this language: fall back to the default service next time.
                    if (usingOnDevice) { onDeviceFailed = true; recognizer?.destroy(); recognizer = null }
                    "Offline speech model for this language is unavailable" to true
                }
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many speech requests" to true
                else -> "Speech recognition error $error" to true
            }
            _events.tryEmit(SttEvent.Error(error, "$msg (code $error)", recoverable))
        }
    }
}
