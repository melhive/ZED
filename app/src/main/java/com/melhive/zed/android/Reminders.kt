package com.melhive.zed.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.melhive.zed.ZedApp
import com.melhive.zed.database.ReminderDao
import com.melhive.zed.database.ReminderEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Schedules reminder notifications with AlarmManager. Uses exact alarms only
 * when the user granted "Alarms & reminders"; otherwise inexact
 * (setAndAllowWhileIdle), which Android may delay by several minutes in Doze.
 */
class ReminderScheduler(private val context: Context, private val dao: ReminderDao) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()

    private fun pending(id: Long): PendingIntent = PendingIntent.getBroadcast(
        context, id.toInt(),
        Intent(context, ReminderReceiver::class.java).putExtra(ReminderReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Schedules and returns whether the alarm is exact. */
    fun schedule(r: ReminderEntity): Boolean {
        val exact = canScheduleExact()
        try {
            if (exact) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerAt, pending(r.id))
            else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerAt, pending(r.id))
        } catch (e: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerAt, pending(r.id))
            return false
        }
        return exact
    }

    fun cancel(id: Long) = alarmManager.cancel(pending(id))

    suspend fun create(text: String, triggerAt: Long): ReminderEntity {
        val id = dao.insert(ReminderEntity(text = text, triggerAt = triggerAt, createdAt = System.currentTimeMillis()))
        val entity = dao.get(id)!!
        val exact = schedule(entity)
        dao.setExact(id, exact)
        return entity.copy(exact = exact)
    }

    suspend fun cancelReminder(id: Long): Boolean {
        val r = dao.get(id) ?: return false
        if (r.status != "scheduled") return false
        cancel(id)
        dao.setStatus(id, "cancelled")
        return true
    }

    /** Removes a reminder for good (any status), cancelling its alarm first. */
    suspend fun deleteReminder(id: Long) {
        cancel(id)
        dao.delete(id)
    }

    /** Re-registers alarms after reboot/update; overdue reminders fire immediately. */
    suspend fun rescheduleAll() {
        val now = System.currentTimeMillis()
        for (r in dao.scheduled()) schedule(if (r.triggerAt < now) r.copy(triggerAt = now + 5_000) else r)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        if (id <= 0) return
        val pending = goAsync()
        val container = (context.applicationContext as ZedApp).container
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val r = container.db.reminders().get(id)
                if (r != null && r.status == "scheduled") {
                    Notifications.post(context, Notifications.CHANNEL_REMINDERS, (100_000 + id).toInt(), "Reminder", r.text, "memory")
                    container.db.reminders().setStatus(id, "fired")
                    // So "snooze ten minutes" knows which reminder.
                    context.getSharedPreferences("zed_reminders", Context.MODE_PRIVATE).edit().putLong("last_fired", id).apply()
                    // ZED comes onto the screen and says it.
                    runCatching { com.melhive.zed.agents.Announcer.onReminder(context, r.text) }
                }
            } catch (e: Exception) {
                CrashReporter.recordNonFatal("reminder", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object { const val EXTRA_ID = "reminder_id" }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val container = (context.applicationContext as ZedApp).container
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching { container.reminderScheduler.rescheduleAll() }.onFailure { CrashReporter.recordNonFatal("boot reschedule", it) }
                runCatching { com.melhive.zed.agents.Announcer.planEvents(context) }
                runCatching { com.melhive.zed.agents.Announcer.rememberNextAlarm(context) }
            } finally { pending.finish() }
        }
    }
}
