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
        container.appScope.launch {
            // Recover state after process death: streaming messages and interrupted tasks.
            runCatching { container.afterRestore() }
            runCatching { container.conversations.recoverInterrupted() }
            runCatching { container.taskEngine.recover() }
            runCatching { container.reminderScheduler.rescheduleAll() }
            runCatching { container.routines.scheduleAll() }
            runCatching { container.audit.prune() }
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
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            container.appScope.launch { runCatching { container.localRuntime.unload() } }
            // Neural voices hold 100–300 MB; release them only while ZED is in the background.
            if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) runCatching { if (!container.voice.isSpeaking()) container.voice.unload() }
        }
    }

    private fun currentProcessName(): String =
        if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
        else runCatching { java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n') }.getOrDefault(packageName)
}
