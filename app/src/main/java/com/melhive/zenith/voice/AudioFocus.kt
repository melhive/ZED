package com.melhive.zenith.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Audio focus for ZENITH's speech output. Focus is held only while ZENITH is
 * speaking (other audio is ducked), never while listening: the speech
 * recognizer takes transient focus itself, and holding focus during
 * listening made every recognition start look like "focus lost".
 *
 * [onInterrupted] fires only for real interruptions: a phone call, or another
 * app taking focus permanently.
 */
class AudioFocus(context: Context, private val onInterrupted: (reason: String) -> Unit) {
    private val am = context.getSystemService(AudioManager::class.java)
    private var held = false

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { change ->
            when {
                isCallActive() -> onInterrupted("Paused for a phone call")
                change == AudioManager.AUDIOFOCUS_LOSS -> onInterrupted("Paused — another app is playing audio")
                else -> Unit // transient losses (recognizer, notifications) are expected
            }
        }
        .build()

    /** Call when ZENITH starts speaking. */
    fun acquire(): Boolean {
        if (held) return true
        held = am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return held
    }

    /** Call when ZENITH stops speaking. */
    fun release() {
        if (!held) return
        am.abandonAudioFocusRequest(request)
        held = false
    }

    /** True when wired, USB or Bluetooth headphones/earbuds are the output (no speaker echo). */
    fun headphonesConnected(): Boolean = runCatching {
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type in setOf(
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
            ) || (android.os.Build.VERSION.SDK_INT >= 31 && it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }.getOrDefault(false)

    fun isCallActive(): Boolean = am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION
}
