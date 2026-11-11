package com.melhive.zed.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/** Runtime permissions ZED may request, with the reason shown to the user. */
data class PermissionInfo(
    val permission: String,
    val title: String,
    val reason: String,
    val minSdk: Int = 1,
)

object PermissionCatalog {
    val MICROPHONE = PermissionInfo(
        Manifest.permission.RECORD_AUDIO, "Microphone",
        "Used only while you are in ZED LIVE or press the mic button. ZED never records in the background.",
    )
    val NOTIFICATIONS = PermissionInfo(
        Manifest.permission.POST_NOTIFICATIONS, "Notifications",
        "Lets ZED tell you when background tasks finish or fail, and deliver reminders.", minSdk = 33,
    )
    val CONTACTS = PermissionInfo(
        Manifest.permission.READ_CONTACTS, "Contacts",
        "Lets ZED find a number when you say “call Mom” or “text Ana”. Contacts never leave the phone unless an online AI needs the matching name.",
    )
    val CALL_PHONE = PermissionInfo(
        Manifest.permission.CALL_PHONE, "Phone calls",
        "Optional. Lets ZED place a call after you confirm it. Without it, ZED opens the dialer and you tap call.",
    )
    val CALENDAR = PermissionInfo(
        Manifest.permission.READ_CALENDAR, "Calendar (read)",
        "Lets the briefing, Schedule and “what's on today” read your events.",
    )
    val CALENDAR_WRITE = PermissionInfo(
        Manifest.permission.WRITE_CALENDAR, "Calendar (add events)",
        "Lets “schedule a meeting tomorrow at 3” add the event directly, without opening the calendar app.",
    )
    val SMS = PermissionInfo(
        Manifest.permission.SEND_SMS, "Send texts",
        "Lets “text Ana I'm late” send right away. Without it, ZED opens your messaging app for you to press send.",
    )
    val LOCATION = PermissionInfo(
        Manifest.permission.ACCESS_COARSE_LOCATION, "Approximate location",
        "Optional. Used only for the weather in your briefing (city-level). You can type a city in Settings instead.",
    )
    val ALL = listOf(MICROPHONE, NOTIFICATIONS, CONTACTS, CALL_PHONE, CALENDAR, CALENDAR_WRITE, SMS, LOCATION)

    /** Everything not yet granted, for the one-tap "Allow everything" request. */
    fun missing(context: Context): Array<String> = ALL.filter { applicable(it) && !isGranted(context, it) }.map { it.permission }.toTypedArray()

    fun applicable(p: PermissionInfo) = Build.VERSION.SDK_INT >= p.minSdk

    fun isGranted(context: Context, p: PermissionInfo): Boolean =
        !applicable(p) || ContextCompat.checkSelfPermission(context, p.permission) == PackageManager.PERMISSION_GRANTED

    fun speechRecognitionAvailable(context: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun onDeviceRecognitionAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
}
