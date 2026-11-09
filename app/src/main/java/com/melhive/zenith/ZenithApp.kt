package com.melhive.zenith

import android.app.Application
import android.content.ComponentCallbacks2
import com.melhive.zenith.android.Notifications
import kotlinx.coroutines.launch

class ZenithApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        container.appScope.launch {
            // Recover state after process death: streaming messages and interrupted tasks.
            runCatching { container.conversations.recoverInterrupted() }
            runCatching { container.taskEngine.recover() }
            runCatching { container.reminderScheduler.rescheduleAll() }
            runCatching { container.audit.prune() }
        }
    }

    /** Releases the on-device model (multi-GB) when Android is short on memory. */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            container.appScope.launch { runCatching { container.localRuntime.unload() } }
        }
    }
}
