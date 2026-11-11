package com.melhive.zed.android

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs

data class DeviceSnapshot(
    val model: String,
    val androidVersion: String,
    val sdk: Int,
    val abis: List<String>,
    val totalRamBytes: Long,
    val availRamBytes: Long,
    val lowMemory: Boolean,
    val freeStorageBytes: Long,
    val online: Boolean,
    val unmetered: Boolean,
) {
    val arm64: Boolean get() = "arm64-v8a" in abis
}

object DeviceInfo {
    fun snapshot(context: Context): DeviceSnapshot {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val stat = StatFs(context.filesDir.absolutePath)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        return DeviceSnapshot(
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.toList(),
            totalRamBytes = mi.totalMem,
            availRamBytes = mi.availMem,
            lowMemory = mi.lowMemory,
            freeStorageBytes = stat.availableBytes,
            online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true,
        )
    }

    /** Number of fast CPU cores (those within 20% of the top clock), 2–6; used for on-device inference threads. */
    fun performanceCores(): Int = runCatching {
        val freqs = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
            java.io.File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
        }
        val top = freqs.maxOrNull() ?: return@runCatching 4
        freqs.count { it >= top * 0.8 }.coerceIn(2, 6)
    }.getOrDefault(4)

    /** System-on-chip name, e.g. "MediaTek MT6878" (Android 12+), else the board name. */
    fun soc(): String = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim() else Build.HARDWARE

    fun formatBytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format("%.2f GB", b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> String.format("%.1f MB", b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> String.format("%.0f KB", b / 1024.0)
        else -> "$b B"
    }
}
