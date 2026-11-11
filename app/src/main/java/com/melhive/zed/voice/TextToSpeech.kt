package com.melhive.zed.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

sealed interface TtsEvent {
    data class Started(val utteranceId: String) : TtsEvent
    /** Emitted at each word boundary while speaking (drives the orb pulse). */
    data class Word(val utteranceId: String) : TtsEvent
    data class Done(val utteranceId: String) : TtsEvent
    data class Stopped(val utteranceId: String) : TtsEvent
    data class Error(val utteranceId: String?, val message: String) : TtsEvent
}

data class VoiceInfo(val engine: String, val language: String, val offlineVoiceAvailable: Boolean, val voiceName: String?)

/** An installed Android text-to-speech voice. */
data class SystemVoice(val name: String, val languageTag: String, val displayLanguage: String, val quality: Int, val needsNetwork: Boolean) {
    val highQuality: Boolean get() = quality >= android.speech.tts.Voice.QUALITY_HIGH
}

/** Replaceable text-to-speech engine. */
interface TextToSpeechEngine {
    val events: SharedFlow<TtsEvent>
    suspend fun awaitReady(): Boolean
    /** Queues text; returns the utterance id. */
    fun speak(text: String, flush: Boolean): String?
    fun stop()
    fun setRate(rate: Float)
    /** Voice pitch, 1.0 = natural. */
    fun setPitch(pitch: Float) {}
    fun isSpeaking(): Boolean
    fun info(): VoiceInfo?
    fun release()
}

/**
 * Android TextToSpeech. Prefers an installed voice that does not require a
 * network connection, so speech works offline when a voice pack is present.
 */
class AndroidTextToSpeech(context: Context, private val voiceName: String? = null) : TextToSpeechEngine {
    private val _events = MutableSharedFlow<TtsEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<TtsEvent> = _events.asSharedFlow()
    private val ready = CompletableDeferred<Boolean>()
    private val counter = AtomicInteger()
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configure()
                ready.complete(true)
            } else ready.complete(false)
        }
    }

    private fun configure() {
        val t = tts ?: return
        t.setAudioAttributes(
            // Media usage: plays at normal media loudness on the loudspeaker and follows the media volume keys.
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        val locale = Locale.getDefault()
        val chosen = voiceName?.let { name -> runCatching { t.voices?.firstOrNull { it.name == name } }.getOrNull() }
        if (chosen != null) { t.voice = chosen; attachListener(t); return }
        val offline = runCatching {
            t.voices?.filter { !it.isNetworkConnectionRequired && it.locale.language == locale.language }
                ?.sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale == locale }.thenByDescending { it.quality })
                ?.firstOrNull()
        }.getOrNull()
        if (offline != null) t.voice = offline else t.language = locale
        attachListener(t)
    }

    private fun attachListener(t: TextToSpeech) {
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) { _events.tryEmit(TtsEvent.Started(utteranceId)) }
            override fun onDone(utteranceId: String) { _events.tryEmit(TtsEvent.Done(utteranceId)) }
            override fun onStop(utteranceId: String, interrupted: Boolean) { _events.tryEmit(TtsEvent.Stopped(utteranceId)) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) { _events.tryEmit(TtsEvent.Error(utteranceId, "Speech synthesis failed")) }
            override fun onError(utteranceId: String, errorCode: Int) { _events.tryEmit(TtsEvent.Error(utteranceId, "Speech synthesis failed ($errorCode)")) }
            override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) { _events.tryEmit(TtsEvent.Word(utteranceId)) }
        })
    }

    override suspend fun awaitReady(): Boolean = ready.await()

    override fun speak(text: String, flush: Boolean): String? {
        val t = tts ?: return null
        val clean = SpeechText.clean(text)
        if (clean.isBlank()) return null
        val id = "zed-${counter.incrementAndGet()}"
        val res = t.speak(clean, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), id)
        return if (res == TextToSpeech.SUCCESS) id else { _events.tryEmit(TtsEvent.Error(id, "Speech synthesis unavailable")); null }
    }

    override fun stop() { tts?.stop() }
    override fun setRate(rate: Float) { tts?.setSpeechRate(rate) }
    override fun setPitch(pitch: Float) { tts?.setPitch(pitch.coerceIn(0.5f, 2f)) }

    /** Installed voices, offline ones first, then by quality. */
    fun voices(): List<SystemVoice> = runCatching {
        tts?.voices.orEmpty()
            .filter { !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .map { SystemVoice(it.name, it.locale.toLanguageTag(), it.locale.displayName, it.quality, it.isNetworkConnectionRequired) }
            .sortedWith(compareBy<SystemVoice> { it.needsNetwork }.thenByDescending { it.quality }.thenBy { it.displayLanguage }.thenBy { it.name })
    }.getOrDefault(emptyList())
    override fun isSpeaking(): Boolean = tts?.isSpeaking == true

    override fun info(): VoiceInfo? {
        val t = tts ?: return null
        if (!ready.isCompleted) return null
        val v = runCatching { t.voice }.getOrNull()
        val offline = runCatching { t.voices?.any { !it.isNetworkConnectionRequired } == true }.getOrDefault(false)
        return VoiceInfo(t.defaultEngine ?: "unknown", (v?.locale ?: Locale.getDefault()).toLanguageTag(), offline, v?.name)
    }

    override fun release() { tts?.shutdown(); tts = null }
}

object SpeechText {
    /** Strips markdown and code so it reads naturally aloud. */
    fun clean(text: String): String = com.melhive.zed.core.voice.SpokenName.fix(text)
        .replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
        .replace(Regex("[*_#>]+"), "")
        .replace(Regex("https?://\\S+"), "link")
        .replace(Regex("[📁📄✓○•]"), "")
        // "3:45 PM" / "9am" → "3:45 P M": every voice spells it out instead of saying the word "am".
        .replace(Regex("\\b(\\d{1,2}(?::\\d{2}){0,2})\\s*([AaPp])\\.?\\s?[Mm]\\b\\.?")) { "${it.groupValues[1]} ${it.groupValues[2].uppercase()} M" }
        .replace(Regex("\\s+"), " ")
        .trim()
}
