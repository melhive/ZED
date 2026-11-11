package com.melhive.zed.core.voice

import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Automatic gain for the wake-word microphone: quiet or distant speech is boosted (up to [maxGain],
 * about +24 dB) toward a steady level so the detector hears "Zed" whispered or from across the room,
 * while loud audio is softly limited instead of clipping. Works on 16 kHz blocks of ~100 ms.
 */
class MicLeveler(
    private val target: Float = 0.1f,
    private val maxGain: Float = 16f,
) {
    private var level = target
    /** The gain applied to the last block. */
    var gain = 1f
        private set

    fun process(x: FloatArray, n: Int = x.size) {
        if (n <= 0) return
        var sum = 0.0
        for (i in 0 until n) sum += (x[i] * x[i]).toDouble()
        val rms = sqrt(sum / n).toFloat()
        // Follow rising speech quickly and fall back slowly, so a word's start is not over-boosted.
        level += (rms - level) * if (rms > level) 0.6f else 0.08f
        val want = (target / level.coerceAtLeast(1e-5f)).coerceIn(1f, maxGain)
        // Gain drops fast (no blasting when someone starts talking) and rises gently.
        gain += (want - gain) * if (want < gain) 0.7f else 0.15f
        for (i in 0 until n) {
            val v = x[i] * gain
            // Soft knee above 0.5: smooth and continuous, never past ±1.
            val a = kotlin.math.abs(v)
            x[i] = if (a <= 0.5f) v else kotlin.math.sign(v) * (0.5f + 0.5f * tanh(((a - 0.5f) / 0.5f).toDouble()).toFloat())
        }
    }
}
