package com.melhive.zenith

import android.app.Application
import android.content.ComponentCallbacks2
import com.melhive.zenith.android.CrashReporter
import com.melhive.zenith.android.Notifications
import kotlinx.coroutines.launch

class ZenithApp : Application() {
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
        Notifications.createChannels(this)
        container.appScope.launch {
            // Recover state after process death: streaming messages and interrupted tasks.
            runCatching { container.conversations.recoverInterrupted() }
            runCatching { container.taskEngine.recover() }
            runCatching { container.reminderScheduler.rescheduleAll() }
            runCatching { container.audit.prune() }
            // Update check against GitHub Releases (public metadata only); can be turned off in Settings.
            runCatching { if (container.settings.current().checkUpdates) container.updates.check() }
        }
    }

    /** Releases the on-device model (multi-GB) and the neural voice when Android is short on memory. */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (isVoiceProcess) return
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            container.appScope.launch { runCatching { container.localRuntime.unload() } }
            // Neural voices hold 100–300 MB; release them only while ZENITH is in the background.
            if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) runCatching { if (!container.voice.isSpeaking()) container.voice.unload() }
        }
    }

    private fun currentProcessName(): String =
        if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
        else runCatching { java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n') }.getOrDefault(packageName)
}
