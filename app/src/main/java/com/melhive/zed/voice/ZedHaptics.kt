package com.melhive.zed.voice

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Short vibrations that go with the LIVE sounds, so ZED can be felt as well as heard. */
object ZedHaptics {
    private fun vibrator(context: Context): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
    }.getOrNull()?.takeIf { it.hasVibrator() }

    private fun play(context: Context, timings: LongArray, strengths: IntArray) {
        val v = vibrator(context) ?: return
        runCatching {
            if (v.hasAmplitudeControl()) v.vibrate(VibrationEffect.createWaveform(timings, strengths, -1))
            else v.vibrate(VibrationEffect.createWaveform(timings, -1))
        }
    }

    /** ZED wakes: a soft pulse, then a firmer one. */
    fun wake(context: Context) = play(context, longArrayOf(0, 28, 90, 42), intArrayOf(0, 90, 0, 170))
    /** ZED heard the request: one light tap. */
    fun heard(context: Context) = play(context, longArrayOf(0, 18), intArrayOf(0, 80))
    /** The call ends: one soft pulse. */
    fun end(context: Context) = play(context, longArrayOf(0, 45), intArrayOf(0, 100))
    /** Something went wrong: two low pulses. */
    fun error(context: Context) = play(context, longArrayOf(0, 60, 110, 60), intArrayOf(0, 150, 0, 150))
}
