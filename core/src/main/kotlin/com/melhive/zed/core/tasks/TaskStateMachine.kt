package com.melhive.zed.core.tasks

enum class TaskStatus(val terminal: Boolean) {
    QUEUED(false),
    PLANNING(false),
    AWAITING_CONFIRMATION(false),
    RUNNING(false),
    SUCCEEDED(true),
    FAILED(true),
    CANCELLED(true),
}

/** Allowed task status transitions. Anything else is a programming error and is rejected. */
object TaskStateMachine {
    private val allowed: Map<TaskStatus, Set<TaskStatus>> = mapOf(
        TaskStatus.QUEUED to setOf(TaskStatus.PLANNING, TaskStatus.RUNNING, TaskStatus.CANCELLED, TaskStatus.FAILED),
        TaskStatus.PLANNING to setOf(TaskStatus.AWAITING_CONFIRMATION, TaskStatus.RUNNING, TaskStatus.FAILED, TaskStatus.CANCELLED, TaskStatus.SUCCEEDED),
        TaskStatus.AWAITING_CONFIRMATION to setOf(TaskStatus.RUNNING, TaskStatus.CANCELLED, TaskStatus.QUEUED),
        TaskStatus.RUNNING to setOf(TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.CANCELLED),
        // Safe retry: a failed or cancelled task can be re-queued as a fresh attempt.
        TaskStatus.FAILED to setOf(TaskStatus.QUEUED),
        TaskStatus.CANCELLED to setOf(TaskStatus.QUEUED),
        TaskStatus.SUCCEEDED to emptySet(),
    )

    fun canTransition(from: TaskStatus, to: TaskStatus): Boolean = to in (allowed[from] ?: emptySet())

    fun transition(from: TaskStatus, to: TaskStatus): TaskStatus {
        require(canTransition(from, to)) { "Illegal task transition $from → $to" }
        return to
    }
}
