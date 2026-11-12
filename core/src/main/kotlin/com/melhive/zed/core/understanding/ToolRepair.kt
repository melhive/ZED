package com.melhive.zed.core.understanding

import com.melhive.zed.core.time.TimeParser
import com.melhive.zed.core.tools.ParamType
import com.melhive.zed.core.tools.ToolCatalog
import com.melhive.zed.core.tools.ToolRequest
import com.melhive.zed.core.tools.ToolSpec
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/**
 * Fixes the everyday mistakes small models make in tool calls before they are
 * validated: a slightly wrong tool name ("set_alarm"), a wrong argument name
 * ("time" instead of "at"), a date in the wrong format, a duration in words.
 *
 * Times, dates and durations the user actually said are computed from their
 * words and take precedence over the model's values.
 *
 * The repaired request is still untrusted and goes through the normal
 * validation, permission and confirmation pipeline. Repair only renames,
 * reformats or drops arguments; it never adds a tool the model didn't ask for.
 */
class ToolRepair(private val catalog: ToolCatalog, private val clock: () -> LocalDateTime = { LocalDateTime.now() }) {

    data class Repaired(val request: ToolRequest, val fixes: List<String>)

    private val TOOL_ALIASES = mapOf(
        "alarm" to "alarm.set", "set.alarm" to "alarm.set", "alarm.create" to "alarm.set", "create.alarm" to "alarm.set", "wake.up" to "alarm.set",
        "timer" to "timer.set", "set.timer" to "timer.set", "timer.start" to "timer.set", "start.timer" to "timer.set", "timer.create" to "timer.set",
        "reminder" to "reminder.create", "remind" to "reminder.create", "create.reminder" to "reminder.create", "set.reminder" to "reminder.create",
        "reminder.set" to "reminder.create", "reminder.add" to "reminder.create", "add.reminder" to "reminder.create", "remind.me" to "reminder.create",
        "reminders" to "reminder.list", "list.reminders" to "reminder.list", "reminder.move" to "reminder.reschedule", "reminder.update" to "reminder.reschedule",
        "todo" to "todo.add", "task" to "todo.add", "task.add" to "todo.add", "add.task" to "todo.add", "task.create" to "todo.add", "todo.create" to "todo.add", "add.todo" to "todo.add",
        "tasks" to "todo.list", "task.list" to "todo.list", "list.tasks" to "todo.list", "task.complete" to "todo.complete", "todo.done" to "todo.complete",
        "call" to "phone.call", "make.call" to "phone.call", "phone" to "phone.call", "call.contact" to "phone.call", "dial" to "phone.call",
        "sms" to "sms.compose", "sms.send" to "sms.compose", "send.sms" to "sms.compose", "text" to "sms.compose", "message.send" to "sms.compose", "send.message" to "sms.compose", "text.send" to "sms.compose",
        "remember" to "memory.remember", "memory.save" to "memory.remember", "save.memory" to "memory.remember", "memory.add" to "memory.remember", "note" to "memory.remember",
        "memories" to "memory.list", "forget" to "memory.forget",
        "event.create" to "calendar.create", "calendar.add" to "calendar.create", "create.event" to "calendar.create", "calendar.event" to "calendar.create", "add.event" to "calendar.create", "schedule" to "calendar.create",
        "calendar" to "calendar.today", "agenda" to "calendar.today", "calendar.list" to "calendar.today",
        "flashlight" to "flashlight.set", "torch" to "flashlight.set", "flashlight.toggle" to "flashlight.set", "flashlight.on" to "flashlight.set",
        "weather" to "weather.get", "get.weather" to "weather.get", "weather.current" to "weather.get",
        "open.app" to "app.open", "app.launch" to "app.open", "launch.app" to "app.open", "open" to "app.open",
        "play.music" to "music.play", "music" to "music.play", "play" to "music.play", "song.play" to "music.play",
        "navigate" to "maps.navigate", "navigation" to "maps.navigate", "directions" to "maps.navigate", "maps.directions" to "maps.navigate",
        "time" to "time.now", "get.time" to "time.now", "current.time" to "time.now", "clock" to "time.now", "date" to "time.now",
        "volume" to "volume.set", "set.volume" to "volume.set", "dnd" to "dnd.set", "do.not.disturb" to "dnd.set",
        "battery" to "device.status", "status" to "device.status", "notifications" to "notifications.recent",
        "research" to "web.research", "web.search" to "web.research", "search.web" to "web.research", "search" to "web.research", "google" to "web.open",
    )

    /** Argument names models use, per tool, mapped to the real ones. */
    private val ARG_ALIASES: Map<String, Map<String, String>> = mapOf(
        "reminder.create" to aliases("text" to listOf("message", "title", "task", "reminder", "what", "content", "description", "note", "subject", "name"), "at" to listOf("time", "datetime", "date_time", "when", "due", "start", "date", "trigger", "trigger_at")),
        "reminder.reschedule" to aliases("id" to listOf("reminder_id", "reminder"), "at" to listOf("time", "datetime", "when", "new_time", "date")),
        "reminder.cancel" to aliases("id" to listOf("reminder_id", "reminder")),
        "alarm.set" to aliases("label" to listOf("name", "title", "message", "text")),
        "timer.set" to aliases("seconds" to listOf("duration", "time", "length", "secs", "duration_seconds"), "label" to listOf("name", "title")),
        "calendar.create" to aliases("title" to listOf("name", "event", "summary", "subject", "what"), "start" to listOf("at", "time", "when", "datetime", "date", "start_time", "begin"), "end" to listOf("end_time", "until", "finish"), "location" to listOf("place", "where", "address"), "description" to listOf("notes", "details", "note")),
        "todo.add" to aliases("title" to listOf("task", "text", "item", "name", "todo", "what", "description"), "due" to listOf("at", "when", "date", "deadline", "time", "due_date")),
        "todo.complete" to aliases("item" to listOf("title", "task", "id", "name", "todo")),
        "phone.call" to aliases("who" to listOf("name", "contact", "number", "to", "person", "phone", "recipient")),
        "sms.compose" to aliases("to" to listOf("who", "contact", "recipient", "name", "number", "person", "phone"), "message" to listOf("text", "body", "content", "msg")),
        "contacts.find" to aliases("name" to listOf("who", "contact", "query", "person")),
        "memory.remember" to aliases("fact" to listOf("text", "memory", "content", "info", "note", "value", "detail")),
        "memory.forget" to aliases("query" to listOf("fact", "text", "memory", "what")),
        "app.open" to aliases("name" to listOf("app", "app_name", "application", "package")),
        "music.play" to aliases("query" to listOf("song", "artist", "track", "title", "music", "playlist", "album", "name")),
        "maps.navigate" to aliases("destination" to listOf("to", "place", "address", "location", "where")),
        "weather.get" to aliases("place" to listOf("location", "city", "where")),
        "web.research" to aliases("query" to listOf("q", "question", "search", "topic", "text")),
        "web.open" to aliases("query" to listOf("url", "q", "search", "site")),
        "docs.search" to aliases("query" to listOf("q", "search", "text", "terms")),
        "history.search" to aliases("query" to listOf("q", "search", "text", "terms")),
        "flashlight.set" to aliases("on" to listOf("state", "enabled", "value", "action", "status", "turn")),
        "dnd.set" to aliases("on" to listOf("state", "enabled", "value", "action", "status")),
        "volume.set" to aliases("level" to listOf("value", "percent", "volume")),
        "calendar.today" to aliases("date" to listOf("day", "when")),
        "routine.run" to aliases("name" to listOf("routine")),
        "home.control" to aliases("target" to listOf("device", "entity", "name", "room"), "value" to listOf("level", "brightness", "temperature")),
    )

    private fun aliases(vararg pairs: Pair<String, List<String>>): Map<String, String> =
        pairs.flatMap { (real, others) -> others.map { it to real } }.toMap()

    /** Finds the real tool for a name a model wrote ("set_alarm", "Reminder.Create", "create_reminder"). */
    fun toolName(raw: String): String? {
        val n = raw.trim().lowercase().replace(Regex("[\\s_\\-/:]+"), ".").trim('.')
        if (catalog.find(n) != null) return n
        TOOL_ALIASES[n]?.let { return it }
        // "create.reminder" → "reminder.create".
        val parts = n.split('.')
        if (parts.size == 2) {
            val swapped = "${parts[1]}.${parts[0]}"
            if (catalog.find(swapped) != null) return swapped
        }
        return null
    }

    fun repair(request: ToolRequest, userText: String?): Repaired {
        val fixes = mutableListOf<String>()
        val name = toolName(request.tool) ?: return Repaired(request, fixes)
        if (name != request.tool) fixes += "tool ${request.tool} → $name"
        val spec = catalog.find(name) ?: return Repaired(request, fixes)
        val now = clock()

        // Rename arguments; keep the first value for each real name.
        val renamed = LinkedHashMap<String, String>()
        val aliases = ARG_ALIASES[name].orEmpty()
        val extras = LinkedHashMap<String, String>()
        for ((k0, v) in request.args) {
            val k = k0.trim().lowercase().replace('-', '_').replace(' ', '_')
            val real = when {
                spec.param(k) != null -> k
                aliases[k] != null -> aliases.getValue(k)
                else -> null
            }
            if (real == null) { extras[k] = v; continue }
            if (real != k0) fixes += "arg $k0 → $real"
            if (real !in renamed && v.isNotBlank()) renamed[real] = v
        }

        // Tool-specific shapes.
        when (name) {
            "alarm.set" -> {
                val text = listOfNotNull(extras["time"], extras["at"], extras["when"], extras["datetime"], renamed["hour"]?.takeIf { !it.all(Char::isDigit) }).firstOrNull()
                if (text != null) clockOf(text, now)?.let { (h, m) -> renamed["hour"] = h.toString(); renamed["minute"] = m.toString(); fixes += "time “$text” → $h:$m" }
                if (renamed["hour"] != null && renamed["minute"] == null) renamed["minute"] = "0"
            }
            "timer.set" -> {
                val raw = renamed["seconds"]
                val minutes = extras["minutes"]?.toLongOrNull(); val hours = extras["hours"]?.toLongOrNull()
                if (raw == null && (minutes != null || hours != null)) { renamed["seconds"] = ((minutes ?: 0) * 60 + (hours ?: 0) * 3600).toString(); fixes += "minutes/hours → seconds" }
                else if (raw != null && raw.toLongOrNull() == null) TimeParser.parseDurationSeconds(raw)?.let { renamed["seconds"] = it.toString(); fixes += "duration “$raw” → $it s" }
            }
            "flashlight.set", "dnd.set" -> renamed["on"]?.let { v ->
                val b = when (v.lowercase()) { "on", "enable", "enabled", "true", "yes", "1", "turn_on" -> "true"; "off", "disable", "disabled", "false", "no", "0", "turn_off" -> "false"; else -> v }
                if (b != v) { renamed["on"] = b; fixes += "on “$v” → $b" }
            }
        }

        // Dates and times in any format → ISO local.
        for (p in spec.params) if (p.type == ParamType.DATETIME) renamed[p.name]?.let { v ->
            val iso = isoDateTime(v, now)
            if (iso != null && iso != v) { renamed[p.name] = iso; fixes += "${p.name} “$v” → $iso" }
        }

        // What the user actually said wins over the model's arithmetic.
        if (userText != null) userOverrides(name, spec, userText, now, renamed, fixes)

        for (p in spec.params) if (p.type == ParamType.INT) renamed[p.name]?.let { v ->
            val n = v.trim().removeSuffix(".0").toLongOrNull()
            if (n != null && n.toString() != v) { renamed[p.name] = n.toString(); fixes += "${p.name} “$v” → $n" }
        }
        if (extras.isNotEmpty()) fixes += "dropped ${extras.keys.joinToString()}"
        return Repaired(ToolRequest(name, renamed, request.origin), fixes)
    }

    /** Sentences where the time said isn't the time meant ("30 minutes before my 5pm meeting"): the model's value stands. */
    private val RELATIVE = Regex("\\b(?:before|after|earlier|later|every|each|until|till|between|from)\\b", RegexOption.IGNORE_CASE)

    private fun userOverrides(name: String, spec: ToolSpec, userText: String, now: LocalDateTime, args: MutableMap<String, String>, fixes: MutableList<String>) {
        if (RELATIVE.containsMatchIn(userText)) return
        val target = when (name) {
            "reminder.create", "reminder.reschedule" -> "at"
            "calendar.create" -> "start"
            "todo.add" -> "due"
            else -> null
        }
        if (target != null) {
            val said = TimeHints.moment(userText, now)
            if (said != null) {
                val model = args[target]
                val iso = said.toString()
                if (model != iso && (target != "due" || model != null)) {
                    // Keep an event's length when moving its start.
                    if (name == "calendar.create") {
                        val oldStart = model?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                        val oldEnd = args["end"]?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                        if (oldStart != null && oldEnd != null && oldEnd.isAfter(oldStart)) args["end"] = said.plus(java.time.Duration.between(oldStart, oldEnd)).toString()
                        else if (oldEnd != null && !oldEnd.isAfter(said)) args["end"] = said.plusHours(1).toString()
                    }
                    args[target] = iso
                    fixes += "$target from the user's words → $iso"
                }
            }
        }
        if (name == "timer.set") TimeHints.duration(userText)?.let { secs ->
            if (args["seconds"] != secs.toString()) { args["seconds"] = secs.toString(); fixes += "seconds from the user's words → $secs" }
        }
        if (name == "alarm.set") TimeHints.find(userText, now)?.time?.let { t ->
            val w = TimeHints.find(userText, now)!!
            val h = if (w.ambiguousHalf && w.tonight && t.hour in 1..11) t.hour + 12 else t.hour
            if (args["hour"] != h.toString() || args["minute"] != t.minute.toString()) {
                args["hour"] = h.toString(); args["minute"] = t.minute.toString(); fixes += "alarm time from the user's words → $h:${t.minute}"
            }
        }
    }

    private fun clockOf(text: String, now: LocalDateTime): Pair<Int, Int>? {
        TimeParser.parseClock(text.trim().lowercase())?.let { return it.hour to it.minute }
        isoDateTime(text, now)?.let { iso -> LocalDateTime.parse(iso).let { return it.hour to it.minute } }
        TimeHints.find(text, now)?.time?.let { return it.hour to it.minute }
        return null
    }

    /** "2026-11-12 17:00", "2026-11-12T17:00:00Z", "tomorrow at 5pm" → "2026-11-12T17:00". */
    fun isoDateTime(raw: String, now: LocalDateTime): String? {
        val v = raw.trim()
        runCatching { return LocalDateTime.parse(v).withSecond(0).withNano(0).toString() }
        runCatching { return LocalDateTime.parse(v.replace(' ', 'T')).withSecond(0).withNano(0).toString() }
        runCatching { return OffsetDateTime.parse(v.replace(' ', 'T')).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime().withSecond(0).withNano(0).toString() }
        runCatching { return ZonedDateTime.parse(v.replace(' ', 'T')).withZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime().withSecond(0).withNano(0).toString() }
        runCatching { return java.time.LocalDate.parse(v).atTime(9, 0).toString() }
        TimeParser.parseDateTime(v, now)?.let { return it.withSecond(0).withNano(0).toString() }
        TimeHints.moment(v, now)?.let { return it.toString() }
        return null
    }
}
