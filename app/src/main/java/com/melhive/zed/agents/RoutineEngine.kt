package com.melhive.zed.agents

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.melhive.zed.AppContainer
import com.melhive.zed.ZedApp
import com.melhive.zed.android.Notifications
import com.melhive.zed.core.routines.Routine
import com.melhive.zed.core.routines.RoutineCatalog
import com.melhive.zed.core.tools.ToolRequest
import com.melhive.zed.tools.ToolOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * User routines: stored as JSON in settings, run step by step through the
 * ToolManager (WORKFLOW origin: deletes and calls still ask), triggered by
 * voice, a daily time (WorkManager) or a Bluetooth device connecting.
 */
class RoutineEngine(private val context: Context, private val c: AppContainer) {
    val routines: Flow<List<Routine>> = c.settings.settings.map { RoutineCatalog.decode(it.routinesJson) }

    suspend fun all(): List<Routine> = RoutineCatalog.decode(c.settings.current().routinesJson)

    suspend fun save(list: List<Routine>) {
        c.settings.setRoutinesJson(RoutineCatalog.encode(list))
        scheduleAll()
    }

    suspend fun upsert(r: Routine) = save(all().filter { it.id != r.id } + r)
    suspend fun delete(id: String) = save(all().filter { it.id != id })

    /** An enabled routine whose name or phrase matches what the user said. */
    suspend fun matchPhrase(text: String): Routine? = all().firstOrNull { it.enabled && it.matches(text) }

    suspend fun find(name: String): Routine? {
        val n = Routine.normalize(name).removeSuffix(" routine").removeSuffix(" mode")
        val list = all()
        return list.firstOrNull { Routine.normalize(it.name) == n || it.matches(n) }
            ?: list.firstOrNull { Routine.normalize(it.name).contains(n) || n.contains(Routine.normalize(it.name)) }
    }

    /**
     * Runs a routine and reports it as one piece. Actions run in order (a failed one is said plainly and the rest still
     * run) and are summed up in a single sentence. Everything a routine reads out — the briefing, the weather, the
     * calendar, tasks, reminders, what was missed — is gathered first and said once, however many steps ask for it.
     */
    suspend fun run(r: Routine, origin: ToolRequest.Origin = ToolRequest.Origin.WORKFLOW): Pair<Boolean, String> {
        var briefing = false; var weather = false; var today = false; var tomorrow = false
        var tasks = false; var reminders = false; var missed = false
        val done = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val said = mutableListOf<String>()
        var allOk = true
        for (step in r.steps) {
            when {
                step.tool == "workflow.run" && step.args["name"].orEmpty().contains("briefing", ignoreCase = true) -> briefing = true
                step.tool == "weather.get" && step.args["place"].isNullOrBlank() -> weather = true
                step.tool == "calendar.today" && step.args["date"].orEmpty().lowercase() in setOf("", "today") -> today = true
                step.tool == "calendar.today" && step.args["date"].orEmpty().lowercase() == "tomorrow" -> tomorrow = true
                step.tool == "todo.list" -> tasks = true
                step.tool == "reminder.list" -> reminders = true
                step.tool == "notifications.recent" -> missed = true
                else -> {
                    val out = c.toolManager.execute(ToolRequest(step.tool, step.args, origin))
                    val ok = out is ToolOutcome.Executed && out.result.ok
                    if (ok) {
                        val clause = com.melhive.zed.core.briefing.RoutineSpeech.done(step.tool, step.args)
                        if (clause != null) { if (clause !in done) done += clause } else if (out.message !in said) said += out.message
                    } else {
                        allOk = false
                        failed += com.melhive.zed.core.briefing.RoutineSpeech.failure(com.melhive.zed.core.briefing.RoutineSpeech.attempt(step.tool, step.args, step.label), out.message)
                    }
                }
            }
        }
        val text = com.melhive.zed.core.briefing.BriefingText
        val composer = BriefingComposer(context, c)
        val parts = mutableListOf<String>()
        // The briefing already covers the weather, today's calendar, reminders, tasks and what was missed.
        if (briefing) parts += text.compose(composer.facts())
        val actions = listOfNotNull(com.melhive.zed.core.briefing.RoutineSpeech.sentence(done)) + failed + said
        if (actions.isNotEmpty()) parts += actions.joinToString(" ")
        if (!briefing && (weather || today || tasks || reminders || missed)) {
            text.compose(composer.facts(BriefingComposer.Wanted(greet = false, weather = weather, calendar = today, reminders = reminders, tasks = tasks, missed = missed, background = false)))
                .takeIf { it.isNotBlank() }?.let { parts += it }
        }
        if (tomorrow) {
            text.compose(composer.facts(BriefingComposer.Wanted(greet = false, weather = false, calendar = true, tomorrow = true, reminders = false, tasks = false, missed = false, background = false)))
                .takeIf { it.isNotBlank() }?.let { parts += it }
        }
        if (parts.isEmpty()) parts += "Done."
        return allOk to parts.joinToString("\n\n")
    }

    /** (Re)schedules the daily time triggers and the morning briefing. */
    suspend fun scheduleAll() {
        val wm = WorkManager.getInstance(context)
        val now = LocalDateTime.now()
        for (r in all()) {
            val name = "routine-${r.id}"
            val next = if (r.enabled) r.nextRun(now) else null
            if (next == null) { wm.cancelUniqueWork(name); continue }
            wm.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<RoutineWorker>()
                .setInitialDelay(Duration.between(now, next)).setInputData(workDataOf(RoutineWorker.KEY_ID to r.id)).build())
        }
        val s = c.settings.current()
        val briefingAt = Routine.parseTime(s.briefingTime)
        if (s.briefingEnabled && briefingAt != null) {
            var next = now.toLocalDate().atTime(briefingAt)
            if (!next.isAfter(now)) next = next.plusDays(1)
            wm.enqueueUniqueWork(BriefingWorker.NAME, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<BriefingWorker>().setInitialDelay(Duration.between(now, next)).build())
        } else wm.cancelUniqueWork(BriefingWorker.NAME)
    }

    fun runInBackground(id: String) {
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<RoutineWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(RoutineWorker.KEY_ID to id, RoutineWorker.KEY_ONCE to true)).build())
    }
}

/** Runs a routine from a trigger and posts the result; time-triggered routines reschedule themselves. */
class RoutineWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun getForegroundInfo(): androidx.work.ForegroundInfo =
        androidx.work.ForegroundInfo(61_000, Notifications.progress(applicationContext, "Running routine", "ZED", 0, 0))

    override suspend fun doWork(): Result {
        val c = (applicationContext as ZedApp).container
        val engine = c.routines
        val r = engine.all().firstOrNull { it.id == inputData.getString(KEY_ID) } ?: return Result.success()
        if (r.enabled) {
            val (ok, text) = runCatching { engine.run(r) }.getOrElse { false to "I couldn't finish ${r.name}: ${it.message ?: "something went wrong"}." }
            Notifications.post(applicationContext, Notifications.CHANNEL_TASKS, 62_000 + Math.floorMod(r.id.hashCode(), 1000), if (ok) "${r.name} done" else "${r.name}: some steps failed", text, "home")
        }
        if (!inputData.getBoolean(KEY_ONCE, false)) engine.scheduleAll()
        return Result.success()
    }

    companion object { const val KEY_ID = "routine_id"; const val KEY_ONCE = "once" }
}

/** Posts the daily briefing as a notification; tapping it opens LIVE, which reads it aloud. */
class BriefingWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as ZedApp).container
        val text = runCatching { BriefingComposer(applicationContext, c).compose() }.getOrElse { "Your briefing is ready — open ZED to hear it." }
        Notifications.post(applicationContext, Notifications.CHANNEL_TASKS, 63_000, text.lineSequence().firstOrNull() ?: "Your briefing", text, "live:brief me")
        c.routines.scheduleAll()
        return Result.success()
    }

    companion object { const val NAME = "daily-briefing" }
}

/** Runs routines tied to a Bluetooth device (e.g. "Driving" when the car connects). */
class RoutineBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val device: BluetoothDevice = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)) ?: return
        val name = runCatching { device.name }.getOrNull() ?: return
        val app = context.applicationContext as? ZedApp ?: return
        val pending = goAsync()
        app.container.appScope.launch {
            try {
                app.container.routines.all().filter { it.enabled && it.bluetoothDevice.equals(name, ignoreCase = true) }
                    .forEach { app.container.routines.runInBackground(it.id) }
            } finally { pending.finish() }
        }
    }
}
