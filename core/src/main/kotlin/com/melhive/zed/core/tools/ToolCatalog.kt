package com.melhive.zed.core.tools

import com.melhive.zed.core.tools.Capability.CALENDAR_APP
import com.melhive.zed.core.tools.Capability.CALENDAR_READ
import com.melhive.zed.core.tools.Capability.CONTACTS
import com.melhive.zed.core.tools.Capability.DND_ACCESS
import com.melhive.zed.core.tools.Capability.HOME_ASSISTANT
import com.melhive.zed.core.tools.Capability.NOTIFICATION_ACCESS
import com.melhive.zed.core.tools.Capability.CLOCK_APP
import com.melhive.zed.core.tools.Capability.NETWORK
import com.melhive.zed.core.tools.Capability.NOTIFICATIONS
import com.melhive.zed.core.tools.Capability.ONLINE_AI
import com.melhive.zed.core.tools.Capability.STORAGE_ACCESS
import com.melhive.zed.core.tools.ParamType.BOOLEAN
import com.melhive.zed.core.tools.ParamType.DATETIME
import com.melhive.zed.core.tools.ParamType.ENUM
import com.melhive.zed.core.tools.ParamType.FILE_NAME
import com.melhive.zed.core.tools.ParamType.INT
import com.melhive.zed.core.tools.ParamType.PATH
import com.melhive.zed.core.tools.ParamType.STRING
import com.melhive.zed.core.tools.ParamType.TEXT
import com.melhive.zed.core.tools.RiskLevel.DESTRUCTIVE
import com.melhive.zed.core.tools.RiskLevel.MODIFY
import com.melhive.zed.core.tools.RiskLevel.READ_ONLY

/** Registry of every tool ZED can execute. Executors live in the Android layer. */
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
                ToolSpec("time.now", "Current date and time", "Returns the device's current date and exact time (with seconds) and time zone.", emptyList(), READ_ONLY),
                ToolSpec("time.left", "Time left", "Exact time remaining (hours, minutes, seconds) until the user's timers, alarms set by ZED, reminders and next calendar event.", listOf(
                    p("target", STRING, "timer, alarm, reminder, meeting, or a name; omit for everything", required = false),
                ), READ_ONLY),
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
                ToolSpec("reminder.reschedule", "Move reminder", "Moves a scheduled reminder to a new time.", listOf(
                    p("id", INT, "Reminder id", min = 1),
                    p("at", DATETIME, "New time, ISO-8601 local date-time"),
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
                ToolSpec("memory.remember", "Remember fact", "Saves a fact you explicitly asked ZED to remember.", listOf(
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
                // ---- Phone and device control ----
                ToolSpec("contacts.find", "Find contact", "Looks up phone numbers in the user's contacts by name.", listOf(
                    p("name", STRING, "Contact name or part of it"),
                ), READ_ONLY, setOf(CONTACTS)),
                ToolSpec("phone.call", "Call", "Calls a contact by name or a phone number. The user always confirms first.", listOf(
                    p("who", STRING, "Contact name or phone number"),
                ), MODIFY, alwaysConfirm = true),
                ToolSpec("sms.compose", "Text message", "Opens the messaging app with a text to a contact or number, ready for the user to send.", listOf(
                    p("to", STRING, "Contact name or phone number"),
                    p("message", TEXT, "Message text"),
                ), MODIFY),
                ToolSpec("app.open", "Open app", "Opens an installed app by name.", listOf(
                    p("name", STRING, "App name, e.g. Spotify, Camera, Settings"),
                ), MODIFY, lowRisk = true),
                ToolSpec("media.control", "Media control", "Controls whatever is playing (music, podcasts, video).", listOf(
                    p("action", ENUM, "What to do", enumValues = listOf("play", "pause", "toggle", "next", "previous", "stop")),
                ), MODIFY, lowRisk = true),
                ToolSpec("music.play", "Play music", "Asks the music app to play a song, artist, album or playlist.", listOf(
                    p("query", STRING, "What to play"),
                ), MODIFY, lowRisk = true),
                ToolSpec("volume.set", "Volume", "Changes the phone volume.", listOf(
                    p("action", ENUM, "Change", enumValues = listOf("set", "up", "down", "mute", "unmute")),
                    p("level", INT, "Level in percent for 'set'", required = false, min = 0, max = 100),
                    p("stream", ENUM, "Which volume", required = false, enumValues = listOf("media", "ring", "alarm", "notification")),
                ), MODIFY, lowRisk = true),
                ToolSpec("flashlight.set", "Flashlight", "Turns the flashlight on or off.", listOf(
                    p("on", BOOLEAN, "true to turn on"),
                ), MODIFY, lowRisk = true),
                ToolSpec("dnd.set", "Do Not Disturb", "Turns Do Not Disturb on or off.", listOf(
                    p("on", BOOLEAN, "true to turn on"),
                ), MODIFY, setOf(DND_ACCESS)),
                ToolSpec("settings.panel", "Open phone setting", "Opens a phone settings screen or quick panel for the user.", listOf(
                    p("panel", ENUM, "Which settings", enumValues = listOf("wifi", "bluetooth", "internet", "volume", "nfc", "location", "battery", "display", "airplane", "notifications", "apps")),
                ), MODIFY, lowRisk = true),
                ToolSpec("maps.navigate", "Navigate", "Starts navigation or shows directions in the maps app.", listOf(
                    p("destination", STRING, "Place or address"),
                    p("mode", ENUM, "Travel mode", required = false, enumValues = listOf("driving", "walking", "transit", "bicycling")),
                ), MODIFY, lowRisk = true),
                ToolSpec("web.open", "Open web", "Opens a web address or a web search in the browser.", listOf(
                    p("query", STRING, "URL or search terms"),
                ), MODIFY, lowRisk = true),
                ToolSpec("camera.open", "Camera", "Opens the camera app.", listOf(
                    p("mode", ENUM, "Photo or video", required = false, enumValues = listOf("photo", "video")),
                ), MODIFY, lowRisk = true),
                ToolSpec("notifications.recent", "Recent notifications", "Lists the user's recent notifications (what they missed), newest first.", listOf(
                    p("app", STRING, "Only from this app", required = false),
                    p("hours", INT, "How far back, in hours (default 12)", required = false, min = 1, max = 72),
                ), READ_ONLY, setOf(NOTIFICATION_ACCESS)),
                ToolSpec("weather.get", "Weather", "Current weather and today's forecast for a place (default: the user's city or location).", listOf(
                    p("place", STRING, "City or place; omit for the user's location", required = false),
                ), READ_ONLY, setOf(NETWORK)),
                ToolSpec("calendar.today", "Calendar events", "Lists the user's calendar events for a day.", listOf(
                    p("date", STRING, "today, tomorrow, or a date like 2026-11-12 (default today)", required = false),
                ), READ_ONLY, setOf(CALENDAR_READ)),
                ToolSpec("routine.run", "Run routine", "Runs one of the user's routines (e.g. Good night, Focus) by name.", listOf(
                    p("name", STRING, "Routine name"),
                ), MODIFY),
                // ---- Smart home (Home Assistant) ----
                ToolSpec("home.status", "Smart home status", "Reports the state of smart-home devices (lights, switches, sensors, climate, locks), optionally filtered by name or room.", listOf(
                    p("target", STRING, "Device or room name; omit for an overview", required = false),
                ), READ_ONLY, setOf(HOME_ASSISTANT, NETWORK)),
                ToolSpec("home.control", "Smart home control", "Turns lights, switches, fans and media on/off or sets brightness or temperature. Plural targets (\"living room lights\") control every match.", listOf(
                    p("target", STRING, "Device or room name, e.g. bedroom lamp, living room lights"),
                    p("action", ENUM, "What to do", enumValues = listOf("turn_on", "turn_off", "toggle", "brightness", "temperature")),
                    p("value", INT, "Brightness percent or temperature", required = false, min = 0, max = 100),
                ), MODIFY, setOf(HOME_ASSISTANT, NETWORK), lowRisk = true),
                ToolSpec("home.secure", "Locks and doors", "Locks/unlocks locks, opens/closes doors, gates and covers, arms/disarms alarms. Always confirmed.", listOf(
                    p("target", STRING, "Device name, e.g. front door, garage"),
                    p("action", ENUM, "What to do", enumValues = listOf("lock", "unlock", "open", "close", "arm", "disarm")),
                ), MODIFY, setOf(HOME_ASSISTANT, NETWORK), alwaysConfirm = true),
                ToolSpec("device.status", "Phone status", "Reports battery, charging, network, storage, volume and Do Not Disturb.", emptyList(), READ_ONLY),

                ToolSpec("web.research", "Research online", "Researches a question online using the configured online AI provider's web search.", listOf(
                    p("query", STRING, "Research question"),
                ), READ_ONLY, setOf(NETWORK, ONLINE_AI)),
            )
        )
    }
}
