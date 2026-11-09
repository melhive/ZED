package com.melhive.zenith.tasks

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.melhive.zenith.ZenithApp
import com.melhive.zenith.android.DeviceInfo
import com.melhive.zenith.android.Notifications
import com.melhive.zenith.core.files.OrganizationPlanner
import com.melhive.zenith.core.tasks.TaskGraph
import com.melhive.zenith.core.tasks.TaskStatus
import com.melhive.zenith.database.TaskEntity
import com.melhive.zenith.models.ModelCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Executes one persistent task. All progress values are real counts from the work itself. */
class TaskWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val container = (appContext as ZenithApp).container
    private val engine = container.taskEngine

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_TASK_ID, -1)
        val task = engine.get(id) ?: return Result.failure()
        if (task.statusEnum().terminal || task.statusEnum() == TaskStatus.AWAITING_CONFIRMATION) return Result.success()

        // Dependencies and conflicts.
        val nodes = engine.allNodes()
        when (TaskGraph.readiness(task.toNode(), nodes)) {
            TaskGraph.Readiness.BLOCKED -> {
                engine.transition(id, TaskStatus.FAILED) { it.copy(error = "A task it depends on failed or was cancelled") }
                engine.log(id, "Blocked by dependency", "error")
                return Result.failure()
            }
            TaskGraph.Readiness.WAITING -> { engine.log(id, "Waiting for dependencies"); return Result.retry() }
            TaskGraph.Readiness.READY -> Unit
        }
        val conflicts = TaskGraph.conflicts(task.toNode(), nodes.values)
        if (conflicts.isNotEmpty()) {
            engine.log(id, "Waiting: conflicts with active task #${conflicts.joinToString { it.id.toString() }} on the same files")
            return Result.retry()
        }

        return try {
            when (task.type) {
                TaskTypes.ORGANIZE -> organize(task)
                TaskTypes.INDEX -> index(task)
                TaskTypes.MODEL_DOWNLOAD -> download(task)
                else -> fail(task, "Unknown task type ${task.type}")
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                val current = engine.get(id)
                if (current != null && !current.statusEnum().terminal && current.statusEnum() != TaskStatus.AWAITING_CONFIRMATION) {
                    // Stopped by the system (constraints, quota), not by the user: mark it failed so a retry is explicit and safe.
                    engine.transition(id, TaskStatus.FAILED) { it.copy(error = "Stopped by the system before finishing. Retry to continue.") }
                    engine.log(id, "Stopped by the system", "warn")
                }
            }
            throw e
        } catch (e: Exception) {
            fail(task, e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun fail(task: TaskEntity, error: String): Result {
        engine.transition(task.id, TaskStatus.FAILED) { it.copy(error = error) }
        engine.log(task.id, "Failed: $error", "error")
        Notifications.post(applicationContext, Notifications.CHANNEL_TASKS, task.id.toInt(), "Task failed: ${task.title}", error, "tasks")
        return Result.failure()
    }

    private suspend fun succeed(task: TaskEntity, summary: String): Result {
        engine.transition(task.id, TaskStatus.SUCCEEDED) { it.copy(resultSummary = summary) }
        engine.log(task.id, "Completed: $summary")
        engine.kickDependents(task.id)
        Notifications.post(applicationContext, Notifications.CHANNEL_TASKS, task.id.toInt(), "Completed: ${task.title}", summary, "tasks")
        return Result.success()
    }

    private suspend fun organize(task: TaskEntity): Result {
        val path = task.input()["path"].orEmpty()
        val files = container.files
        if (task.planJson == null) {
            engine.transition(task.id, TaskStatus.PLANNING) ?: return Result.success()
            engine.log(task.id, "Inspecting ${path.ifEmpty { "granted folder" }}")
            val entries = files.list(path).map { OrganizationPlanner.Entry(it.name, it.isDirectory, it.sizeBytes) }
            val plan = OrganizationPlanner.plan(entries)
            engine.log(task.id, "Found ${entries.size} items; ${plan.moves.size} can be sorted, ${plan.skipped.size} left in place")
            if (plan.moves.isEmpty()) return succeed(task, "Nothing to organize — no files with recognized types at the top level.")
            val json = TaskJson.encodeToString(plan.moves.map { PlannedMove(it.fileName, it.targetFolder) })
            engine.transition(task.id, TaskStatus.AWAITING_CONFIRMATION) {
                it.copy(planJson = json, planSummary = plan.summary() + if (plan.skipped.isNotEmpty()) "\n• ${plan.skipped.size} unrecognized files stay where they are" else "",
                    progressCurrent = 0, progressTotal = plan.moves.size.toLong(), progressLabel = "Awaiting your confirmation")
            }
            Notifications.post(applicationContext, Notifications.CHANNEL_TASKS, task.id.toInt(), "Plan ready: ${task.title}", "Review and confirm ${plan.moves.size} moves in Tasks.", "tasks")
            return Result.success()
        }

        val moves = TaskJson.decodeFromString<List<PlannedMove>>(task.planJson)
        engine.transition(task.id, TaskStatus.RUNNING) ?: return Result.success()
        engine.log(task.id, "Executing ${moves.size} moves")
        var moved = 0; var skipped = 0
        moves.forEachIndexed { i, m ->
            if (isStopped) throw CancellationException("stopped")
            try {
                files.moveChild(path, m.file, m.folder)
                moved++
            } catch (e: Exception) {
                skipped++
                engine.log(task.id, "Skipped ${m.file}: ${e.message}", "warn")
            }
            engine.setProgress(task.id, (i + 1).toLong(), moves.size.toLong(), m.file)
        }
        return succeed(task, "Moved $moved of ${moves.size} files" + if (skipped > 0) " ($skipped skipped — see log)" else "")
    }

    private suspend fun index(task: TaskEntity): Result {
        val path = task.input()["path"].orEmpty()
        engine.transition(task.id, TaskStatus.RUNNING) ?: return Result.success()
        engine.log(task.id, "Scanning for text documents")
        val report = container.knowledge.index(path) { done, total, name ->
            engine.setProgress(task.id, done.toLong(), total.toLong(), name)
        }
        return succeed(task, "Scanned ${report.scanned} documents: ${report.indexed} indexed, ${report.unchanged} unchanged, ${report.failed} failed")
    }

    private suspend fun download(task: TaskEntity): Result {
        val model = ModelCatalog.find(task.input()["model"].orEmpty()) ?: return fail(task, "Unknown model")
        engine.transition(task.id, TaskStatus.RUNNING) ?: return Result.success()
        runCatching { setForeground(foregroundInfo(task, 0, model.sizeBytes, "Starting")) }
            .onFailure { engine.log(task.id, "Running without foreground notification: ${it.message}", "warn") }
        engine.log(task.id, "Downloading ${model.fileName} (${DeviceInfo.formatBytes(model.sizeBytes)}) from ${model.pageUrl}")
        var lastPhase = ""
        container.modelDownloader.download(model) { done, total, phase ->
            if (phase != lastPhase) { engine.log(task.id, phase); lastPhase = phase }
            engine.setProgress(task.id, done, total, phase)
            runCatching { setForeground(foregroundInfo(task, done, total, phase)) }
        }
        return succeed(task, "${model.name} installed and verified (SHA-256)")
    }

    private fun foregroundInfo(task: TaskEntity, done: Long, total: Long, phase: String): ForegroundInfo {
        val text = "$phase · ${DeviceInfo.formatBytes(done)} / ${DeviceInfo.formatBytes(total)}"
        val n = Notifications.progress(applicationContext, task.title, text, done, total)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(50_000 + task.id.toInt(), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(50_000 + task.id.toInt(), n)
    }

    companion object { const val KEY_TASK_ID = "task_id" }
}
