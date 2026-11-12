package com.melhive.zed.backup

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.melhive.zed.ZedApp
import java.util.concurrent.TimeUnit

/** Weekly data-and-settings backup into the folder chosen in Backup & restore. */
class AutoBackupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as ZedApp).container
        val folder = c.settings.current().autoBackupFolder.ifBlank { return Result.success() }
        return runNow(c, folder)
    }

    companion object {
        private const val NAME = "zed-auto-backup"

        suspend fun runNow(c: com.melhive.zed.AppContainer, folder: String): Result = try {
            val name = c.backup.autoBackup(Uri.parse(folder))
            c.settings.setAutoBackupStatus("Last backup: $name")
            Result.success()
        } catch (e: Exception) {
            c.settings.setAutoBackupStatus("Last attempt failed: ${e.message ?: e.javaClass.simpleName}")
            Result.failure()
        }

        /** Keeps the weekly schedule in step with the setting. Safe to call at every start. */
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork(NAME); return }
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(7, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
