package com.melhive.zenith.agents

import com.melhive.zenith.core.tools.ToolRequest
import com.melhive.zenith.database.ZenithDatabase
import com.melhive.zenith.tasks.TaskEngine
import com.melhive.zenith.tools.ToolManager
import com.melhive.zenith.tools.ToolOutcome
import com.melhive.zenith.tools.ToolResult
import kotlinx.coroutines.flow.first

/** A predefined multi-step workflow. Each step is a tool request run through the ToolManager. */
data class Workflow(val id: String, val name: String, val description: String, val aliases: List<String>)

class WorkflowEngine(private val db: ZenithDatabase, private val taskEngine: TaskEngine) {
    lateinit var toolManager: ToolManager
    /** Full briefing (weather, calendar, notifications); falls back to the basic one when unset. */
    var briefing: BriefingComposer? = null

    val workflows = listOf(
        Workflow("morning_briefing", "Morning briefing", "Date and time, open tasks, today's reminders and background task status.", listOf("morning briefing", "daily briefing", "briefing", "good morning")),
        Workflow("organize_downloads", "Organize downloads", "Plans sorting of your Download folder by file type (you approve the plan).", listOf("organize downloads", "organize my downloads", "clean downloads")),
        Workflow("tidy_and_index", "Tidy and index downloads", "Plans sorting of Download, then indexes it for search once the sort completes (dependent tasks).", listOf("tidy and index", "tidy and index downloads")),
        Workflow("index_documents", "Index documents", "Indexes text documents in your granted folders for local search.", listOf("index documents", "index my documents", "update index")),
    )

    fun find(name: String): Workflow? {
        val n = name.trim().lowercase()
        return workflows.firstOrNull { w -> w.id == n || w.name.lowercase() == n || n in w.aliases }
    }

    private suspend fun step(tool: String, args: Map<String, String> = emptyMap()): ToolOutcome =
        toolManager.execute(ToolRequest(tool, args, ToolRequest.Origin.WORKFLOW))

    suspend fun run(name: String): ToolResult {
        val w = find(name) ?: return ToolResult(false, "Unknown workflow “$name”. Available: " + workflows.joinToString { it.name })
        return when (w.id) {
            "morning_briefing" -> {
                briefing?.let { return ToolResult(true, it.compose()) }
                val parts = mutableListOf<String>()
                parts += "Good morning. It is " + step("time.now").message + "."
                parts += step("todo.list").message
                val now = System.currentTimeMillis()
                val endOfDay = java.time.LocalDate.now().plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                val today = db.reminders().scheduled().filter { it.triggerAt in now until endOfDay }
                parts += if (today.isEmpty()) "No reminders for the rest of today." else "Today's reminders: " + today.joinToString("; ") { it.text }
                val active = taskEngine.activeCount.first()
                parts += if (active == 0) "No background tasks are running." else "$active background task(s) in progress."
                ToolResult(true, parts.joinToString("\n\n"))
            }
            "organize_downloads" -> step("files.organize", mapOf("path" to "Download")).let { ToolResult(it is ToolOutcome.Executed && it.result.ok, it.message) }
            "tidy_and_index" -> {
                val organize = step("files.organize", mapOf("path" to "Download"))
                val organizeTask = (organize as? ToolOutcome.Executed)?.result?.taskId
                    ?: return ToolResult(false, organize.message)
                val index = taskEngine.create(
                    com.melhive.zenith.tasks.TaskTypes.INDEX, "Index Download (after #$organizeTask)",
                    mapOf("path" to "Download"), dependsOn = setOf(organizeTask),
                )
                ToolResult(true, organize.message + "\nTask #${index.id} will index the folder after #$organizeTask completes.")
            }
            "index_documents" -> step("docs.index").let { ToolResult(it is ToolOutcome.Executed && it.result.ok, it.message) }
            else -> ToolResult(false, "Workflow not implemented")
        }
    }
}
