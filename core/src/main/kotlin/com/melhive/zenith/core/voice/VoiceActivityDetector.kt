package com.melhive.zenith.core.voice

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Energy-based voice activity detector with an adaptive noise floor.
 * Feed it 16-bit PCM frames (e.g. 20 ms at 16 kHz = 320 samples).
 *
 * - Speech starts after [startFrames] consecutive frames above floor + [marginDb].
 * - Speech ends (endpoint) after [hangoverFrames] consecutive quiet frames.
 *
 * Used for barge-in: while ZENITH speaks, the mic is monitored with echo
 * cancellation; when user speech starts, playback is stopped.
 */
class VoiceActivityDetector(
    private val marginDb: Double = 12.0,
    private val startFrames: Int = 4,
    private val hangoverFrames: Int = 35,
    private val minFloorDb: Double = -65.0,
) {
    enum class Event { NONE, SPEECH_START, SPEECH_END }

    var noiseFloorDb: Double = -50.0
        private set
    var inSpeech: Boolean = false
        private set
    var lastLevelDb: Double = -90.0
        private set

    private var loudRun = 0
    private var quietRun = 0

    fun reset() { inSpeech = false; loudRun = 0; quietRun = 0; noiseFloorDb = -50.0 }

    fun process(frame: ShortArray, length: Int = frame.size): Event {
        val db = levelDb(frame, length)
        lastLevelDb = db
        val threshold = maxOf(noiseFloorDb, minFloorDb) + marginDb
        val loud = db > threshold
        if (!inSpeech) {
            // Track the noise floor only while not in speech; adapt down fast, up slowly.
            noiseFloorDb = if (db < noiseFloorDb) noiseFloorDb * 0.7 + db * 0.3 else noiseFloorDb * 0.98 + db * 0.02
            if (loud) {
                loudRun++
                if (loudRun >= startFrames) { inSpeech = true; quietRun = 0; return Event.SPEECH_START }
            } else loudRun = 0
        } else {
            if (loud) quietRun = 0 else {
                quietRun++
                if (quietRun >= hangoverFrames) { inSpeech = false; loudRun = 0; return Event.SPEECH_END }
            }
        }
        return Event.NONE
    }

    companion object {
        fun levelDb(frame: ShortArray, length: Int = frame.size): Double {
            if (length <= 0) return -90.0
            var sum = 0.0
            for (i in 0 until length) { val s = frame[i] / 32768.0; sum += s * s }
            val rms = sqrt(sum / length)
            return if (rms <= 1e-9) -90.0 else 20 * log10(rms)
        }

        /** Maps dB (-60..0) to 0..1 for UI amplitude. */
        fun normalizedLevel(db: Double): Float = ((db + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
    }
}
