package com.melhive.zed.agents

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.provider.Settings
import com.melhive.zed.ZedApp
import com.melhive.zed.assist.ZedLaunch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * ZED comes onto the screen to say what's due: reminders, calendar events a few minutes before
 * they start, and a briefing after you turn off an alarm. It opens the floating orb (also on the
 * lock screen), speaks, and listens for a reply ("snooze ten minutes"). Without "display over
 * other apps" it speaks in the background instead.
 */
object Announcer {
    const val EXTRA_ANNOUNCE = "announce"
    private const val PREFS = "zed_announcer"
    private const val ACTION_EVENT = "com.melhive.zed.ANNOUNCE_EVENT"
    private const val ACTION_PLAN = "com.melhive.zed.ANNOUNCE_PLAN"
    private const val ACTION_BRIEF = "com.melhive.zed.ANNOUNCE_BRIEF"

    /** Says [text], or [locked] instead while the phone is locked (anyone nearby would hear it). */
    private suspend fun announce(context: Context, text: String, locked: String) {
        val c = (context.applicationContext as ZedApp).container
        announce(context, if (c.settings.current().lockGuard && c.phoneLocked()) locked else text)
    }

    fun announce(context: Context, text: String) {
        if (Settings.canDrawOverlays(context)) {
            val opened = runCatching { context.startActivity(ZedLaunch.overlayIntent(context).putExtra(EXTRA_ANNOUNCE, text)) }.isSuccess
            if (opened) return
        }
        // No overlay permission: say it anyway.
        val c = (context.applicationContext as ZedApp).container
        c.appScope.launch {
            if (c.settings.current().voiceResponses && withTimeoutOrNull(8_000) { c.voice.awaitReady() } == true) c.voice.speak(text, flush = false)
        }
    }

    private fun clock(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))

    // ---- calendar events ----

    private data class Event(val id: Long, val title: String, val begin: Long, val place: String?)

    private fun nextEvent(context: Context, after: Long): Event? {
        if (!CalendarReader(context).granted()) return null
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { android.content.ContentUris.appendId(it, after); android.content.ContentUris.appendId(it, after + 2 * 86_400_000L) }.build()
        return runCatching {
            context.contentResolver.query(uri, arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION), "${CalendarContract.Instances.BEGIN} > ?", arrayOf(after.toString()),
                "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(3) == 1) continue
                    return@use Event(c.getLong(0), c.getString(1)?.takeIf { it.isNotBlank() } ?: "An event", c.getLong(2), c.getString(4)?.takeIf { it.isNotBlank() })
                }
                null
            }
        }.getOrNull()
    }

    private fun pending(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context, code, Intent(context, AnnounceReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Sets one alarm for the next calendar event (lead minutes before), and a 3-hourly re-check for calendar changes. */
    suspend fun planEvents(context: Context) {
        val c = (context.applicationContext as ZedApp).container
        val am = context.getSystemService(AlarmManager::class.java)
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + 3 * 3_600_000L, 3 * 3_600_000L, pending(context, ACTION_PLAN, 9301))
        val lead = c.settings.current().eventAnnounceMinutes
        val p = pending(context, ACTION_EVENT, 9300)
        if (lead <= 0) { am.cancel(p); return }
        val now = System.currentTimeMillis()
        val done = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("events", emptySet()).orEmpty()
        var after = now + 30_000L
        var e: Event? = null
        repeat(5) { if (e == null) { val n = nextEvent(context, after) ?: return@repeat; if ("${n.id}:${n.begin}" in done) after = n.begin else e = n } }
        val event = e ?: run { am.cancel(p); return }
        val at = maxOf(now + 5_000L, event.begin - lead * 60_000L)
        val exact = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        runCatching { if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p) }
    }

    /** The event alarm fired: announce what's coming up, then plan the next one. */
    suspend fun onEventAlarm(context: Context) {
        val now = System.currentTimeMillis()
        val e = nextEvent(context, now - 60_000L) ?: return
        val minutes = ((e.begin - now) / 60_000L).toInt()
        if (minutes > 60) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "${e.id}:${e.begin}"
        val done = prefs.getStringSet("events", emptySet()).orEmpty()
        if (key in done) return
        prefs.edit().putStringSet("events", (done + key).toList().takeLast(40).toSet()).apply()
        val whenText = when { minutes <= 1 -> "is starting now"; else -> "starts in $minutes minutes, at ${clock(e.begin)}" }
        announce(context, "Heads up: ${e.title} $whenText" + (e.place?.let { ", at $it" } ?: "") + ".", com.melhive.zed.core.security.LockPolicy.EVENT)
    }

    // ---- alarms ----

    private fun nextAlarm(context: Context): Long? = context.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime

    /** Remembers when the next alarm is due, so its ringing can be recognised when it changes. */
    fun rememberNextAlarm(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("next_alarm", nextAlarm(context) ?: 0L).apply()
    }

    /**
     * The phone's next alarm changed. Android does this when an alarm starts ringing, so if the alarm
     * we were waiting for is due now, ZED checks back in about 90 seconds — after it has been turned
     * off — and gives the briefing then (unless it was snoozed).
     */
    suspend fun onAlarmChanged(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getLong("next_alarm", 0L)
        prefs.edit().putLong("next_alarm", nextAlarm(context) ?: 0L).apply()
        val now = System.currentTimeMillis()
        val rang = previous in (now - 30 * 60_000L)..(now + 60_000L)
        if (!rang || prefs.getLong("briefed_for", 0L) == previous) return
        val c = (context.applicationContext as ZedApp).container
        if (!c.settings.current().alarmBriefing) return
        prefs.edit().putLong("briefed_for", previous).apply()
        val am = context.getSystemService(AlarmManager::class.java)
        val p = pending(context, ACTION_BRIEF, 9302)
        val at = now + 90_000L
        val exact = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        runCatching { if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p) }
    }

    /** After the alarm: good morning and the day ahead — skipped when the alarm was snoozed (it will ring again). */
    suspend fun onBrief(context: Context) {
        val now = System.currentTimeMillis()
        val upcoming = nextAlarm(context)
        if (upcoming != null && upcoming - now < 20 * 60_000L) return
        val c = (context.applicationContext as ZedApp).container
        val hour = java.time.LocalTime.now().hour
        val text = if (hour in 3..11) withTimeoutOrNull(8_000) { runCatching { BriefingComposer(context, c).compose() }.getOrNull() } ?: "Good morning. It's ${clock(now)}."
            else "It's ${clock(now)}." + (CalendarReader(context).nextEvent(now)?.takeIf { it.second - now < 3 * 3_600_000L }?.let { (t, at) -> " Next up: $t at ${clock(at)}." } ?: "")
        announce(context, text, com.melhive.zed.core.security.LockPolicy.BRIEFING)
    }

    /** A reminder is due. */
    suspend fun onReminder(context: Context, text: String) {
        val c = (context.applicationContext as ZedApp).container
        if (!c.settings.current().announceReminders) return
        announce(context, "Reminder: ${text.trim().trimEnd('.')}.", com.melhive.zed.core.security.LockPolicy.REMINDER)
    }

    class AnnounceReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    when (intent.action) {
                        ACTION_EVENT -> { onEventAlarm(context); planEvents(context) }
                        ACTION_PLAN -> planEvents(context)
                        AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED -> onAlarmChanged(context)
                        ACTION_BRIEF -> onBrief(context)
                    }
                } catch (e: Exception) {
                    com.melhive.zed.android.CrashReporter.recordNonFatal("announcer", e)
                } finally { pending.finish() }
            }
        }
    }
}
