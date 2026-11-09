package com.melhive.zenith.tasks

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.melhive.zenith.core.tasks.TaskGraph
import com.melhive.zenith.core.tasks.TaskStateMachine
import com.melhive.zenith.core.tasks.TaskStatus
import com.melhive.zenith.database.TaskDao
import com.melhive.zenith.database.TaskEntity
import com.melhive.zenith.database.TaskLogEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.concurrent.TimeUnit

object TaskTypes {
    const val ORGANIZE = "organize"
    const val INDEX = "index"
    const val MODEL_DOWNLOAD = "model_download"
}

@Serializable
data class PlannedMove(val file: String, val folder: String)

internal val TaskJson = Json { ignoreUnknownKeys = true }

fun TaskEntity.statusEnum(): TaskStatus = runCatching { TaskStatus.valueOf(status) }.getOrDefault(TaskStatus.FAILED)
fun TaskEntity.input(): Map<String, String> = runCatching { TaskJson.decodeFromString<Map<String, String>>(inputJson) }.getOrDefault(emptyMap())
fun TaskEntity.dependencyIds(): Set<Long> = dependsOn.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
fun TaskEntity.resourceKeys(): Set<String> = resources.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
fun TaskEntity.toNode() = TaskGraph.Node(id, statusEnum(), dependencyIds(), resourceKeys())

/**
 * Persistent task queue. Records live in Room; execution is delegated to
 * WorkManager ([TaskWorker]) so tasks survive the UI closing and process death.
 * Ending a voice session or leaving a screen never cancels tasks; only explicit
 * [cancel] / [cancelAll] do.
 */
class TaskEngine(private val context: Context, private val dao: TaskDao) {
    private val mutex = Mutex()
    private val workManager get() = WorkManager.getInstance(context)

    val tasks: Flow<List<TaskEntity>> = dao.observeAll()
    val activeCount: Flow<Int> = tasks.map { list -> list.count { !it.statusEnum().terminal } }
    fun task(id: Long) = dao.observe(id)
    fun logs(id: Long) = dao.observeLogs(id)

    suspend fun log(taskId: Long, message: String, level: String = "info") =
        dao.insertLog(TaskLogEntity(taskId = taskId, timestamp = System.currentTimeMillis(), level = level, message = message))

    suspend fun create(type: String, title: String, input: Map<String, String>, resources: Set<String> = emptySet(), dependsOn: Set<Long> = emptySet()): TaskEntity = mutex.withLock {
        val all = dao.all()
        val existingDeps = all.associate { it.id to it.dependencyIds() }
        val unknown = dependsOn - all.map { it.id }.toSet()
        require(unknown.isEmpty()) { "Unknown dependency task(s): $unknown" }
        val nextId = (all.maxOfOrNull { it.id } ?: 0) + 1
        require(!TaskGraph.wouldCreateCycle(nextId, dependsOn, existingDeps)) { "Task dependencies would form a cycle" }
        val entity = TaskEntity(
            type = type, title = title, status = TaskStatus.QUEUED.name,
            inputJson = TaskJson.encodeToString(input), createdAt = System.currentTimeMillis(),
            dependsOn = dependsOn.joinToString(","), resources = resources.joinToString(","),
        )
        val id = dao.insert(entity)
        log(id, "Queued: $title")
        val saved = dao.get(id)!!
        enqueue(saved)
        dao.get(id)!!
    }

    private suspend fun enqueue(task: TaskEntity) {
        val constraints = Constraints.Builder()
            .apply { if (task.type == TaskTypes.MODEL_DOWNLOAD) setRequiredNetworkType(NetworkType.CONNECTED) }
            .setRequiresStorageNotLow(task.type == TaskTypes.MODEL_DOWNLOAD)
            .build()
        val request = OneTimeWorkRequestBuilder<TaskWorker>()
            .setInputData(workDataOf(TaskWorker.KEY_TASK_ID to task.id))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(workName(task.id), ExistingWorkPolicy.REPLACE, request)
        dao.update(task.copy(workId = request.id.toString()))
    }

    /** Applies a validated status transition. Returns the updated task, or null if illegal. */
    suspend fun transition(id: Long, to: TaskStatus, mutate: (TaskEntity) -> TaskEntity = { it }): TaskEntity? = mutex.withLock {
        val t = dao.get(id) ?: return null
        if (t.statusEnum() == to) return mutate(t).also { dao.update(it) }
        if (!TaskStateMachine.canTransition(t.statusEnum(), to)) return null
        val now = System.currentTimeMillis()
        val updated = mutate(t.copy(
            status = to.name,
            startedAt = if (to == TaskStatus.RUNNING || to == TaskStatus.PLANNING) (t.startedAt ?: now) else t.startedAt,
            finishedAt = if (to.terminal) now else null,
        ))
        dao.update(updated)
        updated
    }

    suspend fun setProgress(id: Long, current: Long?, total: Long?, label: String?) = dao.setProgress(id, current, total, label)

    /** Approves a plan that is awaiting confirmation and queues its execution. */
    suspend fun confirm(id: Long): Boolean {
        val t = transition(id, TaskStatus.QUEUED) ?: return false
        log(id, "Plan approved by user")
        enqueue(t)
        return true
    }

    suspend fun cancel(id: Long): Boolean {
        workManager.cancelUniqueWork(workName(id))
        val t = transition(id, TaskStatus.CANCELLED) { it.copy(error = null, progressLabel = "Cancelled") } ?: return false
        log(t.id, "Cancelled by user", "warn")
        return true
    }

    suspend fun cancelAll(): Int {
        var n = 0
        for (t in dao.active()) if (cancel(t.id)) n++
        return n
    }

    /** Safe retry: re-queues a failed/cancelled task as a new attempt. Organize plans are re-inspected. */
    suspend fun retry(id: Long): Boolean {
        val t = transition(id, TaskStatus.QUEUED) {
            it.copy(attempt = it.attempt + 1, error = null, resultSummary = null, progressCurrent = null, progressTotal = null, progressLabel = null,
                planJson = if (it.type == TaskTypes.ORGANIZE) null else it.planJson, planSummary = if (it.type == TaskTypes.ORGANIZE) null else it.planSummary)
        } ?: return false
        log(id, "Retry requested (attempt ${t.attempt})")
        enqueue(t)
        return true
    }

    suspend fun clearFinished() = dao.clearFinished()

    /** Called at startup: tasks marked RUNNING whose work no longer exists are re-queued. */
    suspend fun recover() {
        for (t in dao.active()) {
            val infos = runCatching { workManager.getWorkInfosForUniqueWork(workName(t.id)).get() }.getOrDefault(emptyList())
            val alive = infos.any { !it.state.isFinished }
            if (!alive && t.statusEnum() != TaskStatus.AWAITING_CONFIRMATION) {
                if (t.statusEnum() == TaskStatus.RUNNING || t.statusEnum() == TaskStatus.PLANNING) {
                    transition(t.id, TaskStatus.FAILED) { it.copy(error = "Interrupted (app or device restarted). Retry to resume.") }
                    log(t.id, "Interrupted; marked failed so it can be retried safely", "warn")
                } else enqueue(t)
            }
        }
    }

    suspend fun get(id: Long) = dao.get(id)
    suspend fun allNodes() = dao.all().associate { it.id to it.toNode() }

    companion object {
        const val TAG = "zenith-task"
        fun workName(id: Long) = "zenith-task-$id"
    }
}
