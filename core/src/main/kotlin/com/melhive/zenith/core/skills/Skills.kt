package com.melhive.zenith.core.skills

import com.melhive.zenith.core.tools.Capability
import com.melhive.zenith.core.tools.ToolCatalog
import com.melhive.zenith.core.tools.ToolSpec

/**
 * A skill groups related tools into a capability the user can enable or
 * disable. Skills never execute anything themselves: every call still goes
 * through the ToolManager's validation, permission and confirmation pipeline.
 */
data class SkillDescriptor(
    val id: String,
    val name: String,
    val description: String,
    val toolNames: List<String>,
    val requiresOnline: Boolean = false,
    val version: Int = 1,
) {
    fun tools(catalog: ToolCatalog): List<ToolSpec> = toolNames.mapNotNull { catalog.find(it) }
    fun capabilities(catalog: ToolCatalog): Set<Capability> = tools(catalog).flatMap { it.capabilities }.toSet()
}

class SkillRegistry(val catalog: ToolCatalog, skills: List<SkillDescriptor>) {
    val skills: List<SkillDescriptor> = skills

    init {
        val ids = skills.map { it.id }
        require(ids.size == ids.toSet().size) { "Duplicate skill ids" }
        for (s in skills) for (t in s.toolNames) require(catalog.find(t) != null) { "Skill ${s.id} references unknown tool $t" }
    }

    fun find(id: String) = skills.firstOrNull { it.id == id }
    fun skillForTool(tool: String) = skills.firstOrNull { tool in it.toolNames }

    /** Tools exposed to AI providers given the set of enabled skill ids. */
    fun enabledTools(enabled: Set<String>): List<ToolSpec> =
        skills.filter { it.id in enabled }.flatMap { it.tools(catalog) }.distinctBy { it.name }

    companion object {
        val DEFAULT_SKILLS = listOf(
            SkillDescriptor("core.time", "Date & Time", "Tells the current date, time and time zone.", listOf("time.now")),
            SkillDescriptor("files.manage", "File Management", "Create, read, list, move, copy, rename and delete files in folders you granted.",
                listOf("file.create", "folder.create", "file.read", "file.list", "file.move", "file.rename", "file.copy", "file.delete")),
            SkillDescriptor("files.organize", "Document Organizer", "Plans and executes sorting of a folder into category sub-folders as a background task.", listOf("files.organize")),
            SkillDescriptor("knowledge.local", "Local Knowledge", "Indexes your text documents on-device and searches them.", listOf("docs.index", "docs.search")),
            SkillDescriptor("clock.alarms", "Alarms & Timers", "Sets alarms and timers through your clock app.", listOf("alarm.set", "timer.set")),
            SkillDescriptor("calendar.events", "Calendar", "Creates calendar events through your calendar app.", listOf("calendar.create")),
            SkillDescriptor("reminders", "Reminders", "Schedules reminder notifications.", listOf("reminder.create", "reminder.list", "reminder.cancel")),
            SkillDescriptor("tasks.list", "Task List", "Keeps your personal to-do list.", listOf("todo.add", "todo.list", "todo.complete")),
            SkillDescriptor("memory.personal", "Personal Memory", "Remembers facts only when you ask, and forgets on request.", listOf("memory.remember", "memory.list", "memory.forget")),
            SkillDescriptor("history.search", "Conversation History", "Searches your past conversations.", listOf("history.search")),
            SkillDescriptor("app.settings", "Settings Control", "Changes ZENITH settings by voice or chat.", listOf("settings.set")),
            SkillDescriptor("automation.workflows", "Workflows", "Runs predefined multi-step workflows.", listOf("workflow.run")),
            SkillDescriptor("research.online", "Online Research", "Researches questions using your online AI provider's web search.", listOf("web.research"), requiresOnline = true),
        )

        val DEFAULT = SkillRegistry(ToolCatalog.DEFAULT, DEFAULT_SKILLS)
    }
}
