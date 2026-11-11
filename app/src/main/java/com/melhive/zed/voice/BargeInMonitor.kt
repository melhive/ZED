package com.melhive.zed.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.melhive.zed.core.voice.VoiceActivityDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Listens to the microphone while ZED is speaking and reports when the
 * user starts talking (barge-in). Uses the VOICE_RECOGNITION source: the
 * VOICE_COMMUNICATION source would switch the phone into call mode, which
 * routes ZED's voice through the quiet call path. Because the loudspeaker
 * would otherwise trigger it, LIVE only enables barge-in when headphones are
 * connected. Only runs during a visible ZED LIVE session.
 *
 * Reliability depends on the device's echo canceller; this requires
 * physical-device validation.
 */
class BargeInMonitor(private val scope: CoroutineScope) {
    private var job: Job? = null
    @Volatile var echoCancellerActive: Boolean = false
        private set

    @SuppressLint("MissingPermission") // Caller checks RECORD_AUDIO before starting.
    fun start(onLevel: (Float) -> Unit, onSpeech: () -> Unit) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            val rate = 16_000
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) return@launch
            val record = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 3200) * 2)
            } catch (e: Exception) { return@launch }
            if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); return@launch }
            val aec = if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
            val ns = if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
            echoCancellerActive = aec?.enabled == true
            // Stricter thresholds than normal listening to avoid triggering on residual echo.
            val vad = VoiceActivityDetector(marginDb = if (echoCancellerActive) 14.0 else 20.0, startFrames = 6, hangoverFrames = 20)
            val frame = ShortArray(320) // 20 ms
            try {
                record.startRecording()
                // Short warm-up so the noise floor adapts to playback level.
                var frames = 0
                while (isActive) {
                    val n = record.read(frame, 0, frame.size)
                    if (n <= 0) continue
                    frames++
                    val ev = vad.process(frame, n)
                    onLevel(VoiceActivityDetector.normalizedLevel(vad.lastLevelDb))
                    if (frames > 15 && ev == VoiceActivityDetector.Event.SPEECH_START) {
                        onSpeech()
                        break
                    }
                }
            } finally {
                runCatching { record.stop() }
                record.release()
                aec?.release(); ns?.release()
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    /** Stops and waits until the microphone is released (before another recorder opens it). */
    suspend fun stopAndJoin() { job?.let { it.cancel(); it.join() }; job = null }

    companion object {
        /** Barge-in is only reliable with a hardware echo canceller; otherwise ZED would hear itself. */
        fun supported(): Boolean = runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false)
    }
}
