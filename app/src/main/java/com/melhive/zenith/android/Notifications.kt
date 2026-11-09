package com.melhive.zenith.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.melhive.zenith.MainActivity
import com.melhive.zenith.R

object Notifications {
    const val CHANNEL_TASKS = "tasks"
    const val CHANNEL_REMINDERS = "reminders"
    const val CHANNEL_ASSISTANT = "assistant"
    const val CHANNEL_PROGRESS = "progress"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_TASKS, "Task results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Completion and failure of background tasks"
                },
                NotificationChannel(CHANNEL_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Reminders you asked ZENITH to schedule"
                },
                NotificationChannel(CHANNEL_ASSISTANT, "Assistant events", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Important assistant events"
                },
                NotificationChannel(CHANNEL_PROGRESS, "Task progress", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Ongoing progress of long-running tasks"
                },
            )
        )
    }

    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun openAppIntent(context: Context, route: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (route != null) putExtra(MainActivity.EXTRA_ROUTE, route)
        }
        return PendingIntent.getActivity(context, route.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Posts a notification if permitted. Returns false when notifications are blocked. */
    fun post(context: Context, channel: String, id: Int, title: String, text: String, route: String? = null): Boolean {
        if (!canPost(context)) return false
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_zenith)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, route))
            .setPriority(if (channel == CHANNEL_REMINDERS) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(id, n); true
        } catch (e: SecurityException) { false }
    }

    fun progress(context: Context, title: String, text: String, current: Long?, total: Long?) =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_zenith)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(context, "tasks"))
            .apply {
                if (current != null && total != null && total > 0) {
                    setProgress(1000, ((current * 1000) / total).toInt().coerceIn(0, 1000), false)
                } else setProgress(0, 0, true)
            }
            .build()
}
