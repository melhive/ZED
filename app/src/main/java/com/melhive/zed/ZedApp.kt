package com.melhive.zed

import android.app.Application
import android.content.ComponentCallbacks2
import com.melhive.zed.android.CrashReporter
import com.melhive.zed.android.Notifications
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class ZedApp : Application() {
    lateinit var container: AppContainer
        private set

    /** True in the `:voice` helper process, which only runs neural speech synthesis. */
    var isVoiceProcess = false
        private set

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        container = AppContainer(this)
        com.melhive.zed.diagnostics.SpeedLog.init(this)
        // Why did ZED last stop? (native crashes and freezes don't reach the crash handler)
        if (!currentProcessName().endsWith(":voice")) Thread { CrashReporter.collectExitReasons(this) }.start()
        isVoiceProcess = currentProcessName().endsWith(":voice")
        if (isVoiceProcess) return
        // A verified restore waiting from Backup & restore is swapped in now, before the database or settings are opened.
        runCatching { com.melhive.zed.backup.BackupManager.applyPendingRestore(this) }
            .onFailure { CrashReporter.recordNonFatal("restore", it) }
        // Before an update upgrades the database, keep a copy of it as it was (Backup & restore → Safety copies).
        runCatching { com.melhive.zed.backup.BackupManager.safetyCopyBeforeUpgrade(this, com.melhive.zed.database.ZedDatabase.VERSION) }
            .onFailure { CrashReporter.recordNonFatal("safety-copy", it) }
        // ZED is Gemma-only: delete AI keys saved by older versions.
        runCatching { container.secrets.removeLegacyAiKeys() }
        // If ZED stopped while the recognizer beep was muted, put the volume back.
        runCatching { com.melhive.zed.voice.BeepSuppressor.restore(this) }
        Notifications.createChannels(this)
        // "Hey Zed" asked to start while ZED was in the background starts once ZED is on screen.
        runCatching {
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_START) com.melhive.zed.voice.WakeWordService.onAppVisible(this)
            })
        }
        container.appScope.launch {
            // Recover state after process death: streaming messages and interrupted tasks.
            runCatching { container.afterRestore() }
            runCatching { container.conversations.recoverInterrupted() }
            runCatching { container.taskEngine.recover() }
            runCatching { container.reminderScheduler.rescheduleAll() }
            runCatching { container.routines.scheduleAll() }
            runCatching { container.audit.prune() }
            // Announcements: the next calendar event, and the alarm to brief after.
            runCatching { com.melhive.zed.agents.Announcer.planEvents(this@ZedApp) }
            runCatching { com.melhive.zed.agents.Announcer.rememberNextAlarm(this@ZedApp) }
            // Where ZED listens and speaks with earphones (read by LIVE and the recognizer).
            launch { container.settings.settings.collect { com.melhive.zed.voice.AudioRoute.mode = com.melhive.zed.voice.AudioRoute.Mode.parse(it.headsetAudio) } }
            // Deleted files wait in a hidden trash only while they can be undone (this run of the app).
            runCatching { container.files.emptyTrash() }
            runCatching { com.melhive.zed.backup.AutoBackupWorker.schedule(this@ZedApp, container.settings.current().autoBackupFolder.isNotBlank()) }
            // Automatic learning: after conversations while ZED is open, and twice a day while charging.
            runCatching { com.melhive.zed.learning.Learner.schedule(this@ZedApp, container.settings.current().autoLearning); container.learner.watch() }
            // Rare, useful nudges (leave-now before events with a place).
            runCatching { com.melhive.zed.learning.Dreamer.scheduleHints(this@ZedApp, container.settings.current().proactiveHints) }
            // Anything still waiting for the internet runs as soon as there is a connection.
            runCatching { if (container.db.pendingRequests().waiting().isNotEmpty()) com.melhive.zed.web.PendingQueue.schedule(this@ZedApp) }
            // Keep the home-screen widget's next reminder and task count current.
            launch {
                kotlinx.coroutines.flow.combine(container.db.reminders().observeAll(), container.db.todos().observeAll()) { r, t -> r.size to t.hashCode() + r.hashCode() }
                    .distinctUntilChanged()
                    .collect { runCatching { com.melhive.zed.assist.ZedWidgetProvider.refresh(this@ZedApp) } }
            }
            // Update check against GitHub Releases (public metadata only); can be turned off in Settings.
            runCatching {
                if (container.settings.current().checkUpdates) {
                    val result = container.updates.check()
                    // Tell the user once per new version, even if they don't open the app's screens.
                    if (result is com.melhive.zed.android.UpdateState.Available) {
                        val prefs = getSharedPreferences("zed_updates", MODE_PRIVATE)
                        if (prefs.getString("notified", null) != result.info.versionName) {
                            Notifications.post(this@ZedApp, Notifications.CHANNEL_TASKS, 64_000, "ZED ${result.info.versionName} is available",
                                "Tap to see what's new and download the update.", "whats_new")
                            prefs.edit().putString("notified", result.info.versionName).apply()
                        }
                    }
                }
            }
        }
    }

    /** Releases the on-device model (multi-GB) and the neural voice when Android is short on memory. */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (isVoiceProcess) return
        CrashReporter.crumb("memory pressure level $level")
        // Never free models while ZED is answering or a voice session is open (that's when memory peaks).
        val busy = runCatching { container.orchestrator.activity.value.busy || container.live.state.value.active }.getOrDefault(true)
        if (busy) return
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            container.appScope.launch { runCatching { container.localRuntime.unload() } }
            // Neural voices hold 100–300 MB; release them only while ZED is in the background.
            if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) runCatching { if (!container.voice.isSpeaking()) container.voice.unload() }
            if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && !container.live.state.value.active) runCatching { com.melhive.zed.voice.OnDeviceSpeechToText.Engine.unload() }
        }
    }

    private fun currentProcessName(): String =
        if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
        else runCatching { java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n') }.getOrDefault(packageName)
}
