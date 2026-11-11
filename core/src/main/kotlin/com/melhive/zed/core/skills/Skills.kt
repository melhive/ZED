package com.melhive.zed.core.skills

import com.melhive.zed.core.tools.Capability
import com.melhive.zed.core.tools.ToolCatalog
import com.melhive.zed.core.tools.ToolSpec

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
            SkillDescriptor("core.time", "Date & Time", "Tells the exact date and time and how long is left on timers, alarms and reminders.", listOf("time.now", "time.left")),
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
            SkillDescriptor("app.settings", "Settings Control", "Changes ZED settings by voice or chat.", listOf("settings.set")),
            SkillDescriptor("automation.workflows", "Workflows", "Runs predefined multi-step workflows.", listOf("workflow.run")),
            SkillDescriptor("phone.comms", "Calls & Messages", "Finds contacts, places calls (always confirmed) and prepares text messages.", listOf("contacts.find", "phone.call", "sms.compose")),
            SkillDescriptor("device.control", "Phone Control", "Flashlight, volume, Do Not Disturb, settings panels, camera and phone status.",
                listOf("flashlight.set", "volume.set", "dnd.set", "settings.panel", "camera.open", "device.status")),
            SkillDescriptor("apps.media", "Apps, Media & Maps", "Opens apps, controls and plays music, navigates and opens web pages.", listOf("app.open", "media.control", "music.play", "maps.navigate", "web.open")),
            SkillDescriptor("notifications.digest", "What Did I Miss", "Reads your recent notifications when you ask (needs notification access).", listOf("notifications.recent")),
            SkillDescriptor("daily.briefing", "Briefing, Weather & Agenda", "Weather, today's calendar and your daily briefing.", listOf("weather.get", "calendar.today")),
            SkillDescriptor("automation.routines", "Routines", "Runs your routines (Good morning, Good night, Focus, Driving…).", listOf("routine.run")),
            SkillDescriptor("smart.home", "Smart Home", "Controls Home Assistant lights, switches, climate, locks and covers.", listOf("home.status", "home.control", "home.secure"), requiresOnline = true),
            SkillDescriptor("research.online", "Online Research", "Researches questions using your online AI provider's web search.", listOf("web.research"), requiresOnline = true),
        )

        val DEFAULT = SkillRegistry(ToolCatalog.DEFAULT, DEFAULT_SKILLS)
    }
}
