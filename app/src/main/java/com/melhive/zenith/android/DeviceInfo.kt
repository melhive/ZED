package com.melhive.zenith.android

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

    fun formatBytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format("%.2f GB", b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> String.format("%.1f MB", b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> String.format("%.0f KB", b / 1024.0)
        else -> "$b B"
    }
}
