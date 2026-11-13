package com.melhive.zed.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineNeMoCtcModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.melhive.zed.core.voice.SpokenText
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import kotlin.math.sqrt

/**
 * Speech recognition on the phone itself (sherpa-onnx, NeMo streaming FastConformer): offline,
 * no recognizer beep, words appear while you speak. Each start() is one utterance, ending when
 * you pause, like Android's recognizer. The model stays loaded between utterances ([Engine]).
 */
class OnDeviceSpeechToText(private val dir: File, private val context: android.content.Context) : SpeechToText {
    private val _events = MutableSharedFlow<SttEvent>(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<SttEvent> = _events.asSharedFlow()
    override val engineName: String = "On-device (NeMo FastConformer)"

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var worker: Thread? = null
    @Volatile private var finishNow = false
    @Volatile private var cancelled = false

    override fun start(languageTag: String?) {
        stopWorker(cancel = true)
        cancelled = false; finishNow = false
        val t = Thread({ listen() }, "zed-asr").apply { isDaemon = true }
        worker = t
        t.start()
    }

    /** Loads the model in the background so the first sentence isn't delayed. */
    fun warm() { Thread({ Engine.warm(dir) }, "zed-asr-load").apply { isDaemon = true }.start() }

    /** Ends the utterance now and reports what was heard. */
    override fun stop() { finishNow = true }

    override fun cancel() = stopWorker(cancel = true)

    override fun release() = stopWorker(cancel = true)

    private fun stopWorker(cancel: Boolean) {
        if (cancel) cancelled = true
        finishNow = true
        worker?.let { runCatching { it.join(600) } }
        worker = null
    }

    private fun emit(e: SttEvent) { main.post { _events.tryEmit(e) } }

    @SuppressLint("MissingPermission")
    private fun listen() {
        val recognizer = try { Engine.get(dir) } catch (e: Throwable) {
            com.melhive.zed.android.CrashReporter.recordNonFatal("on-device asr load", e)
            emit(SttEvent.Error(-2, "On-device speech recognition couldn't start", false)); return
        }
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE)) }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release(); emit(SttEvent.Error(android.speech.SpeechRecognizer.ERROR_AUDIO, "The microphone is busy", true)); return
        }
        // Earphone-mic mode: record from the earphones' microphone.
        if (AudioRoute.mode == AudioRoute.Mode.EARPHONE_MIC) AudioRoute.headsetMic(context)?.let { runCatching { rec.setPreferredDevice(it) } }
        val stream = recognizer.createStream("")
        val buf = ShortArray(CHUNK)
        var heardSpeech = false
        var lastText = ""
        var lastChange = System.currentTimeMillis()
        val started = System.currentTimeMillis()
        try {
            rec.startRecording()
            emit(SttEvent.Ready)
            while (!finishNow) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val samples = FloatArray(n) { buf[it] / 32768f }
                var sum = 0.0; for (x in samples) sum += x * x
                emit(SttEvent.Level((sqrt(sum / n) * 12).toFloat().coerceIn(0f, 1f)))
                stream.acceptWaveform(samples, RATE)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                val text = recognizer.getResult(stream).text.trim()
                val now = System.currentTimeMillis()
                if (text != lastText) {
                    if (!heardSpeech && text.isNotEmpty()) { heardSpeech = true; emit(SttEvent.SpeechStarted) }
                    lastText = text; lastChange = now
                    if (text.isNotEmpty()) emit(SttEvent.Partial(SpokenText.normalize(text)))
                }
                // The utterance ends at a pause after speech (or the engine's own endpoint), or after a long silence.
                val paused = heardSpeech && now - lastChange > PAUSE_MS
                if (paused || recognizer.isEndpoint(stream) && heardSpeech) break
                if (!heardSpeech && now - started > NO_SPEECH_MS) {
                    if (!cancelled) emit(SttEvent.Error(android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT, "Nothing heard", true))
                    return
                }
                if (now - started > MAX_MS) break
            }
            if (cancelled) return
            emit(SttEvent.EndOfSpeech)
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            val final = recognizer.getResult(stream).text.trim()
            emit(SttEvent.Final(if (final.isEmpty()) "" else SpokenText.normalize(final)))
        } catch (e: Throwable) {
            com.melhive.zed.android.CrashReporter.recordNonFatal("on-device asr", e)
            if (!cancelled) emit(SttEvent.Error(-3, "Speech recognition stopped: ${e.message ?: e.javaClass.simpleName}", true))
        } finally {
            runCatching { rec.stop() }; rec.release()
            stream.release()
        }
    }

    /** One shared recognizer: loading takes about two seconds, so it stays in memory until [unload]. */
    object Engine {
        @Volatile private var loaded: Pair<String, OnlineRecognizer>? = null

        @Synchronized
        fun get(dir: File): OnlineRecognizer {
            loaded?.let { (path, r) -> if (path == dir.path) return r; r.release() }
            val r = OnlineRecognizer(null, OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    neMoCtc = OnlineNeMoCtcModelConfig(File(dir, "model.int8.onnx").path),
                    tokens = File(dir, "tokens.txt").path,
                    numThreads = 2, // measured fastest on this class of phone
                ),
                endpointConfig = EndpointConfig(EndpointRule(false, 8f, 0f), EndpointRule(true, 0.9f, 0f), EndpointRule(false, 0f, 20f)),
                enableEndpoint = true,
            ))
            loaded = dir.path to r
            return r
        }

        /** Loads ahead of time (LIVE opening) so the first sentence isn't delayed. */
        fun warm(dir: File) { runCatching { get(dir) } }

        @Synchronized
        fun unload() { loaded?.second?.release(); loaded = null }
    }

    private companion object {
        const val RATE = 16_000
        const val CHUNK = 1600 // 100 ms
        const val PAUSE_MS = 1100L
        const val NO_SPEECH_MS = 7000L
        const val MAX_MS = 20_000L
    }
}
