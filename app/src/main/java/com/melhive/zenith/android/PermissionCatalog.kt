package com.melhive.zenith.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/** Runtime permissions ZENITH may request, with the reason shown to the user. */
data class PermissionInfo(
    val permission: String,
    val title: String,
    val reason: String,
    val minSdk: Int = 1,
)

object PermissionCatalog {
    val MICROPHONE = PermissionInfo(
        Manifest.permission.RECORD_AUDIO, "Microphone",
        "Used only while you are in ZENITH LIVE or press the mic button. ZENITH never records in the background.",
    )
    val NOTIFICATIONS = PermissionInfo(
        Manifest.permission.POST_NOTIFICATIONS, "Notifications",
        "Lets ZENITH tell you when background tasks finish or fail, and deliver reminders.", minSdk = 33,
    )
    val ALL = listOf(MICROPHONE, NOTIFICATIONS)

    fun applicable(p: PermissionInfo) = Build.VERSION.SDK_INT >= p.minSdk

    fun isGranted(context: Context, p: PermissionInfo): Boolean =
        !applicable(p) || ContextCompat.checkSelfPermission(context, p.permission) == PackageManager.PERMISSION_GRANTED

    fun speechRecognitionAvailable(context: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun onDeviceRecognitionAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
}
