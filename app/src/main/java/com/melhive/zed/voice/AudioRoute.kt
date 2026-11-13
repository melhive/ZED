package com.melhive.zed.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Where ZED listens and speaks when earphones or a headset are connected.
 *  - [Mode.AUTO]: Android's normal routing — ZED talks in the earphones; a wired headset's mic is
 *    used automatically; Bluetooth earbuds use the phone's mic.
 *  - [Mode.EARPHONE_MIC]: ZED listens through the earphone/earbud mic (Bluetooth too) and talks
 *    from the phone's loudspeaker.
 */
object AudioRoute {
    enum class Mode(val id: String) { AUTO("auto"), EARPHONE_MIC("earphone_mic");
        companion object { fun parse(id: String?) = entries.firstOrNull { it.id == id } ?: AUTO } }

    /** Set in each process from settings (the voice process gets it by message). */
    @Volatile var mode: Mode = Mode.AUTO

    private val HEADSET_INPUTS = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)

    private fun am(context: Context) = context.getSystemService(AudioManager::class.java)

    /** A microphone on connected earphones or a headset, if any. */
    fun headsetMic(context: Context): AudioDeviceInfo? = runCatching {
        am(context).getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type in HEADSET_INPUTS || (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }.getOrNull()

    fun phoneSpeaker(context: Context): AudioDeviceInfo? = runCatching {
        am(context).getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    }.getOrNull()

    /** True when ZED's voice is forced to the loudspeaker (so talking over it would be heard back). */
    fun speakerForced(): Boolean = mode == Mode.EARPHONE_MIC

    private fun bluetoothHeadset(context: Context): Boolean = runCatching {
        am(context).getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }.getOrDefault(false)

    @Volatile private var btMicOn = false

    /**
     * Opens the Bluetooth earbuds' microphone for a voice session ([Mode.EARPHONE_MIC] only).
     * Wired earphones need nothing: their mic is used automatically.
     */
    fun beginSession(context: Context) {
        if (mode != Mode.EARPHONE_MIC || !bluetoothHeadset(context)) return
        val am = am(context)
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val dev = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                if (dev != null) btMicOn = am.setCommunicationDevice(dev)
            } else {
                @Suppress("DEPRECATION") am.startBluetoothSco()
                @Suppress("DEPRECATION") run { am.isBluetoothScoOn = true }
                btMicOn = true
            }
        }.onFailure { com.melhive.zed.android.CrashReporter.recordNonFatal("bluetooth mic", it) }
    }

    fun endSession(context: Context) {
        if (!btMicOn) return
        val am = am(context)
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            else { @Suppress("DEPRECATION") run { am.isBluetoothScoOn = false }; @Suppress("DEPRECATION") am.stopBluetoothSco() }
        }
        btMicOn = false
    }
}
