package com.melhive.zenith.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Alarms, timers and calendar events go through standard system intents.
 * This needs no calendar permission (the calendar app shows the event for the
 * user to save) and uses only the normal SET_ALARM permission for alarms.
 */
class SystemIntents(private val context: Context) {

    sealed interface Result {
        data object Launched : Result
        data class Unavailable(val reason: String) : Result
    }

    private fun launch(intent: Intent, what: String): Result {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) {
            return Result.Unavailable("No app on this device handles $what")
        }
        return try {
            context.startActivity(intent); Result.Launched
        } catch (e: ActivityNotFoundException) {
            Result.Unavailable("No app on this device handles $what")
        } catch (e: SecurityException) {
            Result.Unavailable("The system blocked opening the $what app")
        }
    }

    fun canSetAlarms(): Boolean = Intent(AlarmClock.ACTION_SET_ALARM).resolveActivity(context.packageManager) != null
    fun canInsertEvents(): Boolean = calendarIntent("x", LocalDateTime.now(), null, null, null).resolveActivity(context.packageManager) != null

    fun setAlarm(hour: Int, minute: Int, label: String?): Result = launch(
        Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label) },
        "alarms",
    )

    fun setTimer(seconds: Int, label: String?): Result = launch(
        Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label) },
        "timers",
    )

    private fun calendarIntent(title: String, start: LocalDateTime, end: LocalDateTime?, location: String?, description: String?): Intent {
        val zone = ZoneId.systemDefault()
        val startMs = start.atZone(zone).toInstant().toEpochMilli()
        val endMs = (end ?: start.plusHours(1)).atZone(zone).toInstant().toEpochMilli()
        return Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
            .apply {
                if (!location.isNullOrBlank()) putExtra(CalendarContract.Events.EVENT_LOCATION, location)
                if (!description.isNullOrBlank()) putExtra(CalendarContract.Events.DESCRIPTION, description)
            }
    }

    fun createEvent(title: String, start: LocalDateTime, end: LocalDateTime?, location: String?, description: String?): Result =
        launch(calendarIntent(title, start, end, location, description), "calendar events")
}
