package com.melhive.zenith.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * ZENITH's own LIVE start and stop sounds, synthesized in code (no audio files):
 *  - arc:   a rising power-up sweep with a bright two-tone shimmer / a falling power-down
 *  - pulse: two deep synth pulses, low to high / high to low
 *  - chime: a three-note crystal chime, up / down
 */
object ZenithSounds {
    enum class Style(val id: String, val label: String, val description: String) {
        ARC("arc", "Arc", "Power-up sweep with a bright shimmer"),
        PULSE("pulse", "Pulse", "Two deep synth pulses"),
        CHIME("chime", "Chime", "Soft three-note crystal chime"),
        OFF("off", "Off", "No sound");

        companion object { fun parse(id: String?) = entries.firstOrNull { it.id == id } ?: ARC }
    }

    private const val RATE = 44_100

    /** Plays the start or stop sound and returns its length in milliseconds (0 when off). */
    fun play(context: Context, style: Style, start: Boolean): Long {
        if (style == Style.OFF) return 0
        val pcm = render(style, start)
        return runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            val ms = pcm.size * 1000L / RATE
            Handler(Looper.getMainLooper()).postDelayed({ runCatching { track.release() } }, ms + 200)
            ms
        }.getOrDefault(0L)
    }

    fun render(style: Style, start: Boolean): ShortArray {
        val seconds = when (style) { Style.ARC -> if (start) 0.62 else 0.48; Style.PULSE -> 0.42; Style.CHIME -> 0.6; Style.OFF -> 0.0 }
        val n = (seconds * RATE).toInt()
        val out = DoubleArray(n)
        when (style) {
            Style.ARC -> if (start) arcUp(out) else arcDown(out)
            Style.PULSE -> pulses(out, if (start) listOf(98.0, 147.0) else listOf(147.0, 98.0))
            Style.CHIME -> chime(out, if (start) listOf(1046.5, 1318.5, 1568.0) else listOf(1568.0, 1318.5, 1046.5))
            Style.OFF -> Unit
        }
        val peak = out.maxOfOrNull { kotlin.math.abs(it) }?.takeIf { it > 0 } ?: 1.0
        val gain = 0.55 / peak
        // 4 ms fade at both ends: no clicks.
        val fade = RATE / 250
        return ShortArray(n) { i ->
            val edge = min(1.0, min(i, n - 1 - i).toDouble() / fade)
            (out[i] * gain * edge * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun arcUp(out: DoubleArray) {
        var phase = 0.0
        val sweep = 0.36
        for (i in out.indices) {
            val t = i.toDouble() / RATE
            // Exponential sweep 220 → 880 Hz with a soft second harmonic.
            if (t < sweep + 0.08) {
                val f = 220.0 * 4.0.pow(min(t, sweep) / sweep)
                phase += 2 * PI * f / RATE
                val env = min(1.0, t / 0.03) * if (t > sweep) max(0.0, 1 - (t - sweep) / 0.08) else 1.0
                out[i] += env * (sin(phase) + 0.3 * sin(2 * phase)) * 0.6
            }
            // Low thump at the start.
            out[i] += 0.5 * sin(2 * PI * 85 * t) * exp(-t / 0.07)
            // Shimmer: E6 + A6 with a light vibrato, entering as the sweep peaks.
            val s = t - 0.24
            if (s >= 0) {
                val vib = 1 + 0.004 * sin(2 * PI * 6 * s)
                val env = min(1.0, s / 0.02) * exp(-s / 0.16)
                out[i] += env * (0.45 * sin(2 * PI * 1318.5 * vib * s) + 0.35 * sin(2 * PI * 1760.0 * vib * s))
            }
        }
    }

    private fun arcDown(out: DoubleArray) {
        var phase = 0.0
        val sweep = 0.4
        for (i in out.indices) {
            val t = i.toDouble() / RATE
            val f = 880.0 * (196.0 / 880.0).pow(min(t, sweep) / sweep)
            phase += 2 * PI * f / RATE
            val env = min(1.0, t / 0.02) * exp(-t / 0.2)
            out[i] += env * (sin(phase) + 0.25 * sin(2 * phase)) * 0.7
            // A quiet high glint at the start, a soft low tail at the end.
            out[i] += 0.25 * sin(2 * PI * 1760 * t) * exp(-t / 0.05)
            val tail = t - 0.3
            if (tail >= 0) out[i] += 0.35 * sin(2 * PI * 98 * tail) * min(1.0, tail / 0.02) * exp(-tail / 0.08)
        }
    }

    private fun pulses(out: DoubleArray, freqs: List<Double>) {
        freqs.forEachIndexed { k, f ->
            val start = k * 0.16
            for (i in out.indices) {
                val t = i.toDouble() / RATE - start
                if (t < 0) continue
                val env = min(1.0, t / 0.008) * exp(-t / 0.09)
                out[i] += env * (sin(2 * PI * f * t) + 0.5 * sin(2 * PI * 2 * f * t) + 0.15 * sin(2 * PI * 4 * f * t))
            }
        }
    }

    private fun chime(out: DoubleArray, notes: List<Double>) {
        notes.forEachIndexed { k, f ->
            val start = k * 0.09
            for (i in out.indices) {
                val t = i.toDouble() / RATE - start
                if (t < 0) continue
                val env = min(1.0, t / 0.004) * exp(-t / 0.22)
                // Bell: fundamental plus an inharmonic partial that dies quickly.
                out[i] += env * (sin(2 * PI * f * t) + 0.25 * sin(2 * PI * f * 2.76 * t) * exp(-t / 0.05))
            }
        }
    }
}

/**
 * Silences the speech recognizer's own start beep (the "Google" sound) by
 * muting the system, notification and media streams for a moment while
 * listening starts. Only streams that were not already muted are touched, the
 * set is persisted, and [restore] runs on a timer, when LIVE ends and at app
 * start — so a crash can never leave the phone muted.
 */
object BeepSuppressor {
    private const val PREFS = "zenith_beep"
    private const val KEY = "muted"
    private val STREAMS = intArrayOf(AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_MUSIC)
    private val handler = Handler(Looper.getMainLooper())

    fun suppress(context: Context, millis: Long = 900) {
        val am = context.getSystemService(AudioManager::class.java)
        val muted = STREAMS.filter { s ->
            !am.isStreamMute(s) && runCatching { am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0) }.isSuccess
        }
        if (muted.isEmpty()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, muted.joinToString(",")).commit()
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ restore(context) }, millis)
    }

    fun restore(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY, null) ?: return
        val am = context.getSystemService(AudioManager::class.java)
        saved.split(',').mapNotNull { it.toIntOrNull() }.forEach { s -> runCatching { am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) } }
        prefs.edit().remove(KEY).commit()
    }
}
