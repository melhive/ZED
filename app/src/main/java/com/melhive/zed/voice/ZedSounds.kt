package com.melhive.zed.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.melhive.zed.core.voice.SoundSynth

/**
 * Plays ZED's LIVE start and stop sounds. The sounds themselves are synthesized
 * by [SoundSynth] (pure Kotlin, no audio files) and cached after first use.
 */
object ZedSounds {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, ShortArray>()

    private fun pcm(style: SoundSynth.Style, start: Boolean): ShortArray =
        cache.getOrPut("${style.id}/$start") { SoundSynth.render(style, start) }

    /** A tiny synapse tick for the Live Brain. [pitch] varies the note. */
    fun tick(context: Context, pitch: Int = 0) {
        runCatching { playPcm(cache.getOrPut("tick/${pitch.mod(5)}") { SoundSynth.tick(pitch) }) }
    }

    private fun playPcm(pcm: ShortArray): Long {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SoundSynth.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(pcm, 0, pcm.size)
        track.play()
        val ms = pcm.size * 1000L / SoundSynth.RATE
        Handler(Looper.getMainLooper()).postDelayed({ runCatching { track.release() } }, ms + 200)
        return ms
    }

    /** Plays the start or stop sound and returns its length in milliseconds (0 when off). */
    fun play(context: Context, style: SoundSynth.Style, start: Boolean): Long {
        if (style == SoundSynth.Style.OFF) return 0
        return runCatching { playPcm(pcm(style, start)) }.getOrDefault(0L)
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
    private const val PREFS = "zed_beep"
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
