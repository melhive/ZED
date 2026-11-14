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

        val SETTING_KEYS = listOf(
            "voice_responses", "speech_rate", "speech_pitch", "voice", "wake_word", "wake_sensitivity", "barge_in", "auto_index", "use_knowledge",
            "theme_glow", "animation", "auto_learning", "web_research", "think_twice", "proactive_hints", "brain_sounds", "ask_before",
            "user_title", "weather_city", "briefing", "briefing_time", "check_updates", "persona", "greeting", "headset_audio",
            "live_sound", "hide_beep", "eye_captions", "eye_color", "stop_word", "listening_time", "caption_size", "live_haptics", "lock_guard", "eye_recognition", "eye_style", "compliments", "owner_voice", "speech_recognition", "announce_reminders", "event_announce", "alarm_briefing", "ai_model", "model_backend",
        )

        /** Screens of the ZED app that [app.show] can open. */
        val SCREENS = listOf("home", "chat", "live", "eye", "private", "private_exit", "people", "tasks", "more", "facts", "task_list", "reminders", "knowledge", "schedule", "taught", "brain",
            "settings", "voice", "models", "files", "skills", "routines", "diagnostics", "backup", "whats_new")

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
                ToolSpec("docs.remove", "Remove from knowledge", "Removes indexed documents from ZED's knowledge (the files themselves stay). Use all to clear the index.", listOf(
                    p("query", STRING, "Words from the document's name or path, or all"),
                ), DESTRUCTIVE),
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
                ToolSpec("calendar.move", "Move calendar event", "Moves an upcoming calendar event to a new time, keeping its length.", listOf(
                    p("query", STRING, "Which event: words from its title, e.g. meeting with Ana"),
                    p("at", DATETIME, "New start, ISO-8601 local date-time"),
                    p("from", DATETIME, "Its current start, when known", required = false),
                    p("keep_day", BOOLEAN, "Only the time changes (same day)", required = false),
                    p("keep_time", BOOLEAN, "Only the day changes (same time)", required = false),
                ), MODIFY),
                ToolSpec("calendar.delete", "Delete calendar event", "Deletes an upcoming calendar event (only that occurrence of a repeating one).", listOf(
                    p("query", STRING, "Which event: words from its title, e.g. dentist"),
                    p("date", STRING, "Its day when known: today, tomorrow, or 2026-11-20", required = false),
                ), DESTRUCTIVE, setOf(CALENDAR_READ)),
                ToolSpec("reminder.create", "Create reminder", "Schedules a notification reminder.", listOf(
                    p("text", STRING, "What to be reminded about"),
                    p("at", DATETIME, "When, ISO-8601 local date-time"),
                ), MODIFY, setOf(NOTIFICATIONS)),
                ToolSpec("reminder.reschedule", "Move reminder", "Moves a scheduled reminder to a new time.", listOf(
                    p("id", INT, "Reminder id", min = 1),
                    p("at", DATETIME, "New time, ISO-8601 local date-time"),
                ), MODIFY, setOf(NOTIFICATIONS)),
                ToolSpec("reminder.snooze", "Snooze reminder", "Reminds again about the reminder that just went off, after some minutes (default 10).", listOf(
                    p("minutes", INT, "How many minutes", required = false, min = 1, max = 1440),
                ), MODIFY, setOf(NOTIFICATIONS)),
                ToolSpec("reminder.list", "List reminders", "Lists upcoming reminders.", emptyList(), READ_ONLY),
                ToolSpec("reminder.cancel", "Cancel reminder", "Cancels a scheduled reminder by id.", listOf(
                    p("id", INT, "Reminder id", min = 1),
                ), MODIFY),
                ToolSpec("reminder.delete", "Delete reminder", "Deletes reminders for good (scheduled or past) by id or words from the text; all deletes every reminder.", listOf(
                    p("item", STRING, "Reminder id, words from its text, or all"),
                ), DESTRUCTIVE),
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
                ToolSpec("todo.delete", "Delete task", "Deletes items from the task list by id or title; done removes completed ones, all clears the list.", listOf(
                    p("item", STRING, "Item id or title, done, or all"),
                ), DESTRUCTIVE),
                ToolSpec("memory.remember", "Remember fact", "Saves a fact you explicitly asked ZED to remember.", listOf(
                    p("fact", STRING, "The fact to remember"),
                ), MODIFY),
                ToolSpec("memory.list", "List memories", "Lists remembered facts.", emptyList(), READ_ONLY),
                ToolSpec("people.forget", "Forget a face", "Forgets a person ZED learned to recognise in Eye mode, by name.", listOf(
                    p("name", STRING, "The person's name"),
                ), DESTRUCTIVE),
                ToolSpec("brain.show", "Show in the Live Brain", "Opens the Live Brain and flies to everything ZED knows about a person, place or topic.", listOf(
                    p("query", STRING, "Who or what to show, e.g. Ana; omit for the whole brain", required = false),
                ), READ_ONLY),
                ToolSpec("memory.forget", "Forget fact", "Deletes remembered facts matching the text; everything forgets all facts.", listOf(
                    p("query", STRING, "Text of the fact(s) to forget"),
                ), DESTRUCTIVE),
                ToolSpec("memory.pin", "Pin memory", "Pins (or unpins) remembered facts so they always count, by words from the fact.", listOf(
                    p("query", STRING, "Words from the fact"),
                    p("pinned", BOOLEAN, "false to unpin", required = false),
                ), MODIFY),
                ToolSpec("memory.confirm", "Confirm learned memory", "Marks things ZED learned by itself as right (confirmed by the user).", listOf(
                    p("query", STRING, "Words from the learned fact, or all"),
                ), MODIFY),
                ToolSpec("chats.clear", "Clear chat history", "Deletes conversations: all of them, or those whose title or messages match. Can be undone.", listOf(
                    p("which", STRING, "all, or words from the conversation"),
                ), DESTRUCTIVE),
                ToolSpec("history.search", "Search history", "Searches previous conversations.", listOf(
                    p("query", STRING, "Search terms"),
                ), READ_ONLY),
                ToolSpec("settings.set", "Change setting", "Changes any ZED setting: on/off switches, voice (a voice name such as Atlas), speech_rate/speech_pitch (0.5–2, or faster/slower), wake_sensitivity (low, balanced, high, maximum), animation (full, balanced, light), ask_before (never, risky, always), user_title, weather_city, briefing (on/off), briefing_time (HH:mm), persona, live_sound (hologram, reactor, neural, scanner, armor, sonar, core, command, off), speech_recognition (on-device/phone), event_announce (minutes before, or off), ai_model (a downloaded model's name), model_backend (gpu/cpu).", listOf(
                    p("key", ENUM, "Setting key", enumValues = SETTING_KEYS),
                    p("value", STRING, "New value"),
                ), MODIFY),
                ToolSpec("app.undo", "Undo", "Brings back what was just deleted (tasks, reminders, memories, events, documents, files, lessons) — the last 30 minutes.", emptyList(), MODIFY),
                ToolSpec("app.show", "Open a ZED screen", "Opens a screen of the ZED app itself (not another app).", listOf(
                    p("screen", ENUM, "Which screen", enumValues = SCREENS),
                ), READ_ONLY),
                ToolSpec("teach.pronounce", "Learn a pronunciation", "Remembers how to pronounce a word or name when speaking.", listOf(
                    p("word", STRING, "The word as written, e.g. Cebu"),
                    p("say", STRING, "How to say it, spelled as it sounds, e.g. seh-boo"),
                ), MODIFY),
                ToolSpec("teach.rule", "Learn how to answer", "Saves a rule the user gave about how ZED should answer or behave, followed from now on.", listOf(
                    p("rule", STRING, "The rule in the user's words, e.g. keep answers short"),
                ), MODIFY),
                ToolSpec("teach.alias", "Learn a shortcut", "Saves the user's own phrase and what it means, e.g. lights out → turn off the lights.", listOf(
                    p("phrase", STRING, "What the user will say"),
                    p("meaning", STRING, "What ZED should do or understand instead"),
                ), MODIFY),
                ToolSpec("teach.forget", "Forget a lesson", "Forgets a pronunciation, shortcut or answering rule the user taught; all forgets every lesson.", listOf(
                    p("what", STRING, "The word, phrase or rule to forget, or all"),
                ), DESTRUCTIVE),
                ToolSpec("teach.list", "What ZED learned", "Lists the rules, pronunciations and shortcuts the user has taught ZED.", emptyList(), READ_ONLY),
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
                ToolSpec("message.reply", "Reply to a message", "Replies inside a messaging app (WhatsApp, Messenger, Telegram, Viber, Signal, Messages…) to someone who messaged you, straight from the notification — no app opens.", listOf(
                    p("to", STRING, "Who to reply to: their name as it appears in the chat"),
                    p("message", TEXT, "What to send"),
                    p("app", STRING, "The app, e.g. WhatsApp or Messenger, when said", required = false),
                ), MODIFY),
                ToolSpec("messages.read", "Read messages", "Reads recent messages from a person or a messaging app (from notifications).", listOf(
                    p("from", STRING, "The person, when said", required = false),
                    p("app", STRING, "The app, e.g. WhatsApp, when said", required = false),
                ), READ_ONLY, setOf(NOTIFICATION_ACCESS)),
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
                ToolSpec("location.get", "Where am I", "The phone's current approximate location: city, area and country (from location services, or the city in settings).", emptyList(), READ_ONLY),
                ToolSpec("weather.get", "Weather", "Current weather and today's forecast for a place (default: the user's city or location).", listOf(
                    p("place", STRING, "City or place; omit for the user's location", required = false),
                ), READ_ONLY),
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
                // ---- Screen awareness and control (accessibility, only when asked) ----
                ToolSpec("screen.read", "Read the screen", "Reads what's on the screen in the app the user is looking at, and summarizes it or answers a question about it.", listOf(
                    p("question", STRING, "What the user wants to know about the screen, when asked", required = false),
                ), READ_ONLY),
                ToolSpec("screen.tap", "Tap on screen", "Taps a button, link or item on screen by its label, e.g. Send, Like, Next.", listOf(
                    p("target", STRING, "The label of what to tap"),
                ), MODIFY, lowRisk = true),
                ToolSpec("screen.scroll", "Scroll", "Scrolls the screen.", listOf(
                    p("direction", ENUM, "Direction", enumValues = listOf("down", "up", "left", "right")),
                ), MODIFY, lowRisk = true),
                ToolSpec("screen.type", "Type on screen", "Types text into the text field on screen (the focused one, or the one named).", listOf(
                    p("text", TEXT, "What to type"),
                    p("field", STRING, "Which field, when said, e.g. search", required = false),
                ), MODIFY, lowRisk = true),
                ToolSpec("screen.action", "Phone button", "Presses Back or Home, opens recent apps, notifications or quick settings, locks the screen or takes a screenshot.", listOf(
                    p("action", ENUM, "Which", enumValues = listOf("back", "home", "recents", "notifications", "quick_settings", "lock", "screenshot")),
                ), MODIFY, lowRisk = true),
                ToolSpec("device.status", "Phone status", "Reports battery, charging, network, storage, volume and Do Not Disturb.", emptyList(), READ_ONLY),

                ToolSpec("web.research", "Search the web", "Searches the web and reads the best pages, for facts that may have changed or that you don't know (news, prices, results, opening hours). Returns numbered sources to cite. Only the query leaves the phone; offline, the request waits for the connection.", listOf(
                    p("query", STRING, "What to search for: a short search query"),
                ), READ_ONLY),
            )
        )
    }
}
