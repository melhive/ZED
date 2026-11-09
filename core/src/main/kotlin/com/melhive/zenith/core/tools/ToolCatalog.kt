package com.melhive.zenith.core.tools

import com.melhive.zenith.core.tools.Capability.CALENDAR_APP
import com.melhive.zenith.core.tools.Capability.CLOCK_APP
import com.melhive.zenith.core.tools.Capability.NETWORK
import com.melhive.zenith.core.tools.Capability.NOTIFICATIONS
import com.melhive.zenith.core.tools.Capability.ONLINE_AI
import com.melhive.zenith.core.tools.Capability.STORAGE_ACCESS
import com.melhive.zenith.core.tools.ParamType.BOOLEAN
import com.melhive.zenith.core.tools.ParamType.DATETIME
import com.melhive.zenith.core.tools.ParamType.ENUM
import com.melhive.zenith.core.tools.ParamType.FILE_NAME
import com.melhive.zenith.core.tools.ParamType.INT
import com.melhive.zenith.core.tools.ParamType.PATH
import com.melhive.zenith.core.tools.ParamType.STRING
import com.melhive.zenith.core.tools.ParamType.TEXT
import com.melhive.zenith.core.tools.RiskLevel.DESTRUCTIVE
import com.melhive.zenith.core.tools.RiskLevel.MODIFY
import com.melhive.zenith.core.tools.RiskLevel.READ_ONLY

/** Registry of every tool ZENITH can execute. Executors live in the Android layer. */
class ToolCatalog(specs: List<ToolSpec>) {
    private val byName = specs.associateBy { it.name }

    init {
        require(byName.size == specs.size) { "Duplicate tool names" }
    }

    val all: List<ToolSpec> = specs
    fun find(name: String): ToolSpec? = byName[name]

    companion object {
        private fun p(name: String, type: ParamType, desc: String, required: Boolean = true, enumValues: List<String> = emptyList(), min: Long? = null, max: Long? = null) =
            ParamSpec(name, type, desc, required, enumValues, min, max)

        val SETTING_KEYS = listOf("voice_responses", "speech_rate", "auto_index", "use_knowledge", "theme_glow")

        val DEFAULT = ToolCatalog(
            listOf(
                ToolSpec("time.now", "Current date and time", "Returns the device's current date, time and time zone.", emptyList(), READ_ONLY),
                ToolSpec("file.create", "Create file", "Creates a text file inside a granted folder.", listOf(
                    p("path", PATH, "Relative path of the new file, e.g. notes/ideas.txt"),
                    p("content", TEXT, "Initial text content", required = false),
                ), MODIFY, setOf(STORAGE_ACCESS)),
                ToolSpec("folder.create", "Create folder", "Creates a folder (and missing parents) inside a granted folder.", listOf(
                    p("path", PATH, "Relative path of the folder"),
                ), MODIFY, setOf(STORAGE_ACCESS)),
                ToolSpec("file.read", "Read file", "Reads a text document from a granted folder.", listOf(
                    p("path", PATH, "Relative path of the file"),
                ), READ_ONLY, setOf(STORAGE_ACCESS)),
                ToolSpec("file.list", "List folder", "Lists the contents of a folder in a granted location.", listOf(
                    p("path", PATH, "Relative folder path; empty for the root", required = false),
                ), READ_ONLY, setOf(STORAGE_ACCESS)),
                ToolSpec("file.move", "Move file", "Moves a file or folder into another folder.", listOf(
                    p("source", PATH, "Relative path of the item to move"),
                    p("destination", PATH, "Relative path of the destination folder"),
                ), MODIFY, setOf(STORAGE_ACCESS)),
                ToolSpec("file.rename", "Rename file", "Renames a file or folder.", listOf(
                    p("path", PATH, "Relative path of the item"),
                    p("new_name", FILE_NAME, "New name (no slashes)"),
                ), MODIFY, setOf(STORAGE_ACCESS)),
                ToolSpec("file.copy", "Copy file", "Copies a file into another folder.", listOf(
                    p("source", PATH, "Relative path of the file to copy"),
                    p("destination", PATH, "Relative path of the destination folder"),
                ), MODIFY, setOf(STORAGE_ACCESS)),
                ToolSpec("file.delete", "Delete file", "Permanently deletes a file or folder. Always asks for confirmation.", listOf(
                    p("path", PATH, "Relative path of the item to delete"),
                ), DESTRUCTIVE, setOf(STORAGE_ACCESS)),
                ToolSpec("files.organize", "Organize folder", "Inspects a folder, proposes sorting files into sub-folders by type, and executes after confirmation as a background task.", listOf(
                    p("path", PATH, "Relative folder path; empty for the root", required = false),
                ), MODIFY, setOf(STORAGE_ACCESS), background = true),
                ToolSpec("docs.index", "Index documents", "Indexes text documents in a granted folder for local search (background task).", listOf(
                    p("path", PATH, "Relative folder path; empty for the root", required = false),
                ), MODIFY, setOf(STORAGE_ACCESS), background = true),
                ToolSpec("docs.search", "Search documents", "Searches the local document index.", listOf(
                    p("query", STRING, "Search terms"),
                ), READ_ONLY),
                ToolSpec("alarm.set", "Set alarm", "Sets an alarm in the system clock app.", listOf(
                    p("hour", INT, "Hour 0-23", min = 0, max = 23),
                    p("minute", INT, "Minute 0-59", min = 0, max = 59),
                    p("label", STRING, "Alarm label", required = false),
                ), MODIFY, setOf(CLOCK_APP)),
                ToolSpec("timer.set", "Set timer", "Starts a countdown timer in the system clock app.", listOf(
                    p("seconds", INT, "Duration in seconds", min = 1, max = 86_400),
                    p("label", STRING, "Timer label", required = false),
                ), MODIFY, setOf(CLOCK_APP)),
                ToolSpec("calendar.create", "Create calendar event", "Opens the calendar app with a pre-filled event for you to save.", listOf(
                    p("title", STRING, "Event title"),
                    p("start", DATETIME, "Start, ISO-8601 local date-time"),
                    p("end", DATETIME, "End, ISO-8601 local date-time", required = false),
                    p("location", STRING, "Location", required = false),
                    p("description", TEXT, "Notes", required = false),
                ), MODIFY, setOf(CALENDAR_APP)),
                ToolSpec("reminder.create", "Create reminder", "Schedules a notification reminder.", listOf(
                    p("text", STRING, "What to be reminded about"),
                    p("at", DATETIME, "When, ISO-8601 local date-time"),
                ), MODIFY, setOf(NOTIFICATIONS)),
                ToolSpec("reminder.list", "List reminders", "Lists upcoming reminders.", emptyList(), READ_ONLY),
                ToolSpec("reminder.cancel", "Cancel reminder", "Cancels a scheduled reminder by id.", listOf(
                    p("id", INT, "Reminder id", min = 1),
                ), MODIFY),
                ToolSpec("todo.add", "Add task", "Adds an item to your task list.", listOf(
                    p("title", STRING, "Task title"),
                    p("due", DATETIME, "Optional due date-time", required = false),
                ), MODIFY),
                ToolSpec("todo.list", "List tasks", "Lists open items in your task list.", listOf(
                    p("include_done", BOOLEAN, "Include completed items", required = false),
                ), READ_ONLY),
                ToolSpec("todo.complete", "Complete task", "Marks a task-list item as done, by id or title.", listOf(
                    p("item", STRING, "Item id or title"),
                ), MODIFY),
                ToolSpec("memory.remember", "Remember fact", "Saves a fact you explicitly asked ZENITH to remember.", listOf(
                    p("fact", STRING, "The fact to remember"),
                ), MODIFY),
                ToolSpec("memory.list", "List memories", "Lists remembered facts.", emptyList(), READ_ONLY),
                ToolSpec("memory.forget", "Forget fact", "Deletes remembered facts matching the text.", listOf(
                    p("query", STRING, "Text of the fact(s) to forget"),
                ), DESTRUCTIVE),
                ToolSpec("history.search", "Search history", "Searches previous conversations.", listOf(
                    p("query", STRING, "Search terms"),
                ), READ_ONLY),
                ToolSpec("settings.set", "Change setting", "Changes an application setting.", listOf(
                    p("key", ENUM, "Setting key", enumValues = SETTING_KEYS),
                    p("value", STRING, "New value"),
                ), MODIFY),
                ToolSpec("workflow.run", "Run workflow", "Runs a predefined workflow by name.", listOf(
                    p("name", STRING, "Workflow name"),
                ), MODIFY),
                ToolSpec("web.research", "Research online", "Researches a question online using the configured online AI provider's web search.", listOf(
                    p("query", STRING, "Research question"),
                ), READ_ONLY, setOf(NETWORK, ONLINE_AI)),
            )
        )
    }
}
