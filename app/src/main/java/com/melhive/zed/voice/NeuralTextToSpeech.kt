package com.melhive.zed.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.melhive.zed.core.voice.EnglishPhonemizer
import com.melhive.zed.core.voice.NeuralVoice
import com.melhive.zed.core.voice.PronunciationLexicon
import com.melhive.zed.core.voice.SpeechNormalizer
import com.melhive.zed.core.voice.VoiceCatalog
import com.melhive.zed.core.voice.VoiceEngineKind
import com.melhive.zed.core.voice.VoicePack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * Offline neural text-to-speech (sherpa-onnx) with a downloaded [VoicePack].
 *
 * Pipeline: [speak] splits text into sentences → a dedicated synthesis thread
 * renders one sentence at a time (staying at most two sentences ahead) → a
 * playback coroutine streams PCM into an [AudioTrack]. [stop] silences the
 * track immediately and discards queued work, so barge-in and "stop" feel
 * instant. Word events are emitted from the real audio loudness, so the LIVE
 * orb moves with the voice.
 *
 * Kokoro voices get text normalized by [SpeechNormalizer] and phonemized by
 * [EnglishPhonemizer] (Kokoro's own dictionary frontend skips unknown words and
 * digits). Supertonic reads raw text and handles numbers itself.
 */
class NeuralTextToSpeech(
    private val pack: VoicePack,
    private val voice: NeuralVoice,
    private val dir: File,
    private val languageTag: String,
) : TextToSpeechEngine {
    private val _events = MutableSharedFlow<TtsEvent>(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<TtsEvent> = _events.asSharedFlow()

    private val ready = CompletableDeferred<Boolean>()
    private val synthExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "zed-neural-tts").apply { priority = Thread.MAX_PRIORITY } }
    private val synthDispatcher = synthExecutor.asCoroutineDispatcher()
    // Any unexpected error on the voice threads is recorded instead of crashing the app.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
        com.melhive.zed.android.CrashReporter.recordNonFatal("neural tts", e)
        failAll("Speech playback failed: ${e.message ?: e.javaClass.simpleName}")
    })

    private var tts: OfflineTts? = null
    private var phonemizer: EnglishPhonemizer? = null
    @Volatile private var rate = 1f
    @Volatile private var pitch = 1f
    @Volatile private var released = false

    private val counter = AtomicInteger()
    private val generation = AtomicInteger()
    private val pending: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val chunks = Channel<Chunk>(capacity = 2)

    private val trackLock = Any()
    private var track: AudioTrack? = null
    private var sampleRate = 0
    @Volatile private var framesWritten = 0L

    /** Why loading failed (shown in Settings and Diagnostics), or null. */
    @Volatile var loadError: String? = null
        private set
    @Volatile var loadMillis: Long = 0
        private set

    /** Called after each rendered sentence with render time ÷ audio length (below 1 = faster than real time). */
    @Volatile var onRealTimeFactor: ((Float) -> Unit)? = null

    private class Request(val id: String, val sentences: List<String>, val gen: Int)
    private class Chunk(val id: String, val pcm: ShortArray, val first: Boolean, val last: Boolean, val gen: Int)

    init {
        scope.launch(synthDispatcher) {
            load()
            for (r in requests) synthesize(r)
        }
        scope.launch(Dispatchers.IO) {
            for (c in chunks) {
                try {
                    play(c)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    com.melhive.zed.android.CrashReporter.recordNonFatal("neural tts playback", e)
                    synchronized(trackLock) { track?.let { runCatching { it.release() } }; track = null; framesWritten = 0 }
                    finish(c.id, TtsEvent.Error(c.id, "Audio playback failed: ${e.message ?: e.javaClass.simpleName}"))
                }
            }
        }
    }

    private fun file(name: String) = File(dir, name).absolutePath

    private fun load() {
        val t0 = System.currentTimeMillis()
        try {
            val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
            val model = when (pack.kind) {
                VoiceEngineKind.SUPERTONIC -> OfflineTtsModelConfig(
                    supertonic = OfflineTtsSupertonicModelConfig(
                        durationPredictor = file("duration_predictor.int8.onnx"),
                        textEncoder = file("text_encoder.int8.onnx"),
                        vectorEstimator = file("vector_estimator.int8.onnx"),
                        vocoder = file("vocoder.int8.onnx"),
                        ttsJson = file("tts.json"),
                        unicodeIndexer = file("unicode_indexer.bin"),
                        voiceStyle = file("voice.bin"),
                    ),
                    numThreads = threads,
                )
                VoiceEngineKind.KOKORO -> OfflineTtsModelConfig(
                    // No lexicon here: ZED phonemizes the text itself and passes phonemes.
                    kokoro = OfflineTtsKokoroModelConfig(model = file("model.int8.onnx"), voices = file("voices.bin"), tokens = file("tokens.txt")),
                    numThreads = threads,
                )
                VoiceEngineKind.SYSTEM -> error("not a neural pack")
            }
            if (pack.kind == VoiceEngineKind.KOKORO) {
                val lexicon = File(dir, VoiceCatalog.kokoroLexicon(voice)).useLines { PronunciationLexicon.parse(it) }
                phonemizer = EnglishPhonemizer(lexicon)
            }
            val engine = OfflineTts(config = OfflineTtsConfig(model = model))
            tts = engine
            sampleRate = engine.sampleRate()
            loadMillis = System.currentTimeMillis() - t0
            ready.complete(true)
        } catch (e: Throwable) {
            // UnsatisfiedLinkError on devices without the native library (x86), bad files, out of memory.
            loadError = when (e) {
                is UnsatisfiedLinkError -> "Neural voices are not supported on this device's processor"
                is OutOfMemoryError -> "Not enough memory to load the voice"
                else -> e.message ?: e.javaClass.simpleName
            }
            com.melhive.zed.android.CrashReporter.recordNonFatal("neural tts load", e)
            ready.complete(false)
        }
    }

    override suspend fun awaitReady(): Boolean = ready.await()

    override fun speak(text: String, flush: Boolean): String? {
        val id = "neural-${counter.incrementAndGet()}"
        return if (speakWithId(id, text, flush)) id else null
    }

    /** Queues text under a caller-chosen utterance id (used by the voice process). */
    fun speakWithId(id: String, text: String, flush: Boolean): Boolean {
        if (released) return false
        val clean = SpeechText.clean(text)
        if (clean.isBlank()) return false
        if (flush) stop()
        pending += id
        requests.trySend(Request(id, splitSentences(clean), generation.get()))
        return true
    }

    private fun failAll(message: String) {
        val ids = synchronized(pending) { pending.toList().also { pending.clear() } }
        ids.forEach { _events.tryEmit(TtsEvent.Error(it, message)) }
    }

    private suspend fun synthesize(r: Request) {
        val engine = tts
        if (engine == null) { finish(r.id, TtsEvent.Error(r.id, loadError ?: "Voice not loaded")); return }
        r.sentences.forEachIndexed { i, sentence ->
            if (r.gen != generation.get() || r.id !in pending) return
            val samples = try {
                val t0 = System.currentTimeMillis()
                generate(engine, sentence, r.gen).also { out ->
                    val audioMs = out.size * 1000L / maxOf(1, sampleRate)
                    if (audioMs > 300) onRealTimeFactor?.invoke((System.currentTimeMillis() - t0).toFloat() / audioMs)
                }
            } catch (e: Throwable) {
                com.melhive.zed.android.CrashReporter.recordNonFatal("neural tts", e)
                finish(r.id, TtsEvent.Error(r.id, "Speech synthesis failed: ${e.message ?: e.javaClass.simpleName}"))
                return
            }
            if (r.gen != generation.get()) return
            chunks.send(Chunk(r.id, toPcm16(samples), first = i == 0, last = i == r.sentences.lastIndex, gen = r.gen))
        }
    }

    private fun generate(engine: OfflineTts, sentence: String, gen: Int): FloatArray {
        val config = GenerationConfig(sid = voice.sid)
        val text: String
        when (pack.kind) {
            VoiceEngineKind.KOKORO -> {
                text = SpeechNormalizer.normalize(sentence)
                val phonemes = phonemizer!!.phonemize(text)
                if (phonemes.isEmpty()) return FloatArray(0)
                config.phonemeCodepoints = phonemes.map { it.codePoints().toArray() }.toTypedArray()
                config.speed = rate
            }
            else -> {
                text = SupertonicText.clean(sentence)
                if (text.isBlank()) return FloatArray(0)
                config.speed = rate * 1.05f // Supertonic's natural pace is 1.05
                config.numSteps = 5 // measured on an 8×A55 phone: ~2 s per sentence, clear speech
                config.extra = mapOf("lang" to VoiceCatalog.supertonicLanguage(languageTag))
            }
        }
        // No progress callback: sherpa-onnx's JNI looks the callback up by an exact
        // signature that Kotlin lambdas do not have, which aborts the process.
        // An interrupted sentence simply finishes rendering and is discarded.
        return engine.generateWithConfig(text, config).samples
    }

    private fun toPcm16(samples: FloatArray): ShortArray =
        ShortArray(samples.size) { i -> (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }

    private fun ensureTrack(): AudioTrack {
        track?.let { return it }
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, sampleRate / 5 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        applyPitch(t)
        return t
    }

    private fun applyPitch(t: AudioTrack) {
        runCatching { t.playbackParams = PlaybackParams().setSpeed(1f).setPitch(pitch.coerceIn(0.5f, 2f)) }
    }

    private suspend fun play(c: Chunk) {
        if (c.gen != generation.get() || released) return
        val t = synchronized(trackLock) { ensureTrack().also { if (it.playState != AudioTrack.PLAYSTATE_PLAYING) it.play() } }
        if (c.first) _events.tryEmit(TtsEvent.Started(c.id))
        val step = sampleRate / 20 // 50 ms slices: responsive to stop(), smooth orb pulses
        var offset = 0
        var lastPulse = 0L
        while (offset < c.pcm.size) {
            if (c.gen != generation.get() || released) { discardTrack(); return }
            val len = minOf(step, c.pcm.size - offset)
            var sum = 0.0
            for (k in offset until offset + len) { val v = c.pcm[k] / 32768.0; sum += v * v }
            val now = System.currentTimeMillis()
            if (sqrt(sum / len) > 0.04 && now - lastPulse > 110) { _events.tryEmit(TtsEvent.Word(c.id)); lastPulse = now }
            val n = t.write(c.pcm, offset, len, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) break
            offset += n
            framesWritten += n
        }
        if (!c.last) return
        // Wait until the last sample has actually been heard before reporting Done.
        val deadline = System.currentTimeMillis() + 1000L + (framesWritten * 1000L / maxOf(1, sampleRate))
        while (c.gen == generation.get() && !released && (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < framesWritten && System.currentTimeMillis() < deadline) delay(15)
        if (c.gen == generation.get()) finish(c.id, TtsEvent.Done(c.id))
    }

    /** Called on the playback coroutine after an interruption: drop whatever is still buffered. */
    private fun discardTrack() = synchronized(trackLock) {
        track?.let { runCatching { it.pause(); it.flush() } }
        framesWritten = 0
    }

    private fun finish(id: String, event: TtsEvent) {
        if (pending.remove(id)) _events.tryEmit(event)
        if (pending.isEmpty()) synchronized(trackLock) {
            // Reset the frame counter at a quiet point so playbackHeadPosition comparisons stay simple.
            track?.let { t -> if ((t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) >= framesWritten) { runCatching { t.pause(); t.flush() }; framesWritten = 0 } }
        }
    }

    override fun stop() {
        generation.incrementAndGet()
        while (requests.tryReceive().isSuccess) Unit
        while (chunks.tryReceive().isSuccess) Unit
        synchronized(trackLock) { track?.let { runCatching { it.pause(); it.flush() } }; framesWritten = 0 }
        val ids = synchronized(pending) { pending.toList().also { pending.clear() } }
        ids.forEach { _events.tryEmit(TtsEvent.Stopped(it)) }
    }

    override fun setRate(rate: Float) { this.rate = rate.coerceIn(0.5f, 2f) }

    override fun setPitch(pitch: Float) {
        this.pitch = pitch.coerceIn(0.5f, 2f)
        synchronized(trackLock) { track?.let { applyPitch(it) } }
    }

    override fun isSpeaking(): Boolean = pending.isNotEmpty()

    override fun info(): VoiceInfo? = if (!ready.isCompleted) null else VoiceInfo(
        engine = "ZED neural · ${pack.name}" + if (loadError != null) " (failed: $loadError)" else " (loaded in ${loadMillis} ms)",
        language = if (pack.kind == VoiceEngineKind.KOKORO) (if (voice.accent == "British") "en-GB" else "en-US") else VoiceCatalog.supertonicLanguage(languageTag),
        offlineVoiceAvailable = true,
        voiceName = "${voice.name} (${voice.gender.label}, ${voice.accent})",
    )

    override fun release() {
        if (released) return
        stop()
        released = true
        requests.close(); chunks.close()
        scope.cancel()
        synchronized(trackLock) { track?.let { runCatching { it.stop() }; it.release() }; track = null }
        synthExecutor.execute { runCatching { tts?.release() }; tts = null; phonemizer = null }
        synthExecutor.shutdown()
    }

    companion object {
        /** Splits into sentences so speech can start after the first one is rendered; long sentences break at commas. */
        fun splitSentences(text: String): List<String> {
            val sentences = Regex("(?<=[.!?…])\\s+(?=[\\p{Lu}\\d\"“(])|\\n+").split(text).map { it.trim() }.filter { it.isNotEmpty() }
            return sentences.flatMap { s ->
                if (s.length <= 220) listOf(s)
                else {
                    val parts = mutableListOf<String>()
                    val sb = StringBuilder()
                    for (piece in s.split(Regex("(?<=[,;:])\\s+"))) {
                        if (sb.isNotEmpty() && sb.length + piece.length > 220) { parts += sb.toString(); sb.setLength(0) }
                        if (sb.isNotEmpty()) sb.append(' ')
                        sb.append(piece)
                    }
                    if (sb.isNotEmpty()) parts += sb.toString()
                    parts
                }
            }.ifEmpty { listOf(text) }
        }
    }
}

/** Light cleanup for Supertonic, which reads raw text (including numbers and currency) itself. */
object SupertonicText {
    fun clean(text: String): String = com.melhive.zed.core.voice.SpokenName.fix(text)
        .replace(Regex("[\\p{So}\\p{Cn}\\p{Cs}]"), " ") // emoji and other pictographs
        .replace(Regex("[*_#>|~^`]"), " ")
        .replace(Regex("\\s+"), " ")
        .replace(Regex(" ([,.!?;:])"), "$1")
        .trim()
}
