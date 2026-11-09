package com.melhive.zenith.core.tasks

/**
 * Dependency and conflict rules for the task queue.
 * - A task may depend on other tasks; it is runnable only when all succeeded.
 * - Two active tasks conflict when they touch the same resource key, where one
 *   key is a prefix path of the other (e.g. "tree:1/Download" and
 *   "tree:1/Download/pdf").
 */
object TaskGraph {
    data class Node(val id: Long, val status: TaskStatus, val dependsOn: Set<Long>, val resources: Set<String>)

    /** Returns true when adding [newDeps] for [taskId] would create a cycle. */
    fun wouldCreateCycle(taskId: Long, newDeps: Set<Long>, existing: Map<Long, Set<Long>>): Boolean {
        val graph = existing.toMutableMap().apply { put(taskId, newDeps) }
        val visiting = HashSet<Long>()
        val done = HashSet<Long>()
        fun dfs(n: Long): Boolean {
            if (n in done) return false
            if (!visiting.add(n)) return true
            for (d in graph[n].orEmpty()) if (dfs(d)) return true
            visiting.remove(n); done.add(n)
            return false
        }
        return graph.keys.any { dfs(it) }
    }

    enum class Readiness { READY, WAITING, BLOCKED }

    fun readiness(node: Node, all: Map<Long, Node>): Readiness {
        var waiting = false
        for (d in node.dependsOn) {
            val dep = all[d] ?: return Readiness.BLOCKED
            when (dep.status) {
                TaskStatus.SUCCEEDED -> Unit
                TaskStatus.FAILED, TaskStatus.CANCELLED -> return Readiness.BLOCKED
                else -> waiting = true
            }
        }
        return if (waiting) Readiness.WAITING else Readiness.READY
    }

    fun resourcesOverlap(a: String, b: String): Boolean =
        a == b || a.startsWith("$b/") || b.startsWith("$a/")

    /** Active tasks (not terminal, not this one) whose resources overlap with [candidate]. */
    fun conflicts(candidate: Node, all: Collection<Node>): List<Node> = all.filter { other ->
        other.id != candidate.id && !other.status.terminal && other.status != TaskStatus.QUEUED &&
            other.resources.any { r -> candidate.resources.any { resourcesOverlap(it, r) } }
    }
}
