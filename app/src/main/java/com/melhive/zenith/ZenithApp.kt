package com.melhive.zenith

import android.app.Application
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
}
