package com.melhive.zed.tools.executors

import android.content.Context
import com.melhive.zed.AppContainer
import com.melhive.zed.android.DeviceInfo
import com.melhive.zed.android.Notifications
import com.melhive.zed.android.PermissionCatalog
import com.melhive.zed.android.PhoneControl
import com.melhive.zed.android.ZedNotificationListener
import com.melhive.zed.android.SystemIntents
import com.melhive.zed.core.tools.Capability
import com.melhive.zed.core.tools.ToolRequest
import com.melhive.zed.core.tools.ToolSpec
import com.melhive.zed.core.tools.ValidatedArgs
import com.melhive.zed.memory.TodoRepository
import com.melhive.zed.tasks.TaskTypes
import com.melhive.zed.tools.CapabilityChecker
import com.melhive.zed.tools.ConfirmationText
import com.melhive.zed.tools.ToolManager
import com.melhive.zed.tools.ToolResult
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val dateTimeFmt: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
private fun LocalDateTime.pretty() = format(dateTimeFmt)
private fun Long.prettyMillis() = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneId.systemDefault()).pretty()
private fun LocalDateTime.epochMillis() = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun ok(msg: String) = ToolResult(true, msg)
private fun fail(msg: String) = ToolResult(false, msg)

class AndroidCapabilityChecker(private val context: Context, private val c: AppContainer) : CapabilityChecker {
    private val intents = SystemIntents(context)
    override suspend fun isAvailable(c: Capability): Boolean = when (c) {
        Capability.STORAGE_ACCESS -> this.c.files.hasAnyGrant()
        Capability.NOTIFICATIONS -> Notifications.canPost(context)
        Capability.MICROPHONE -> PermissionCatalog.isGranted(context, PermissionCatalog.MICROPHONE)
        Capability.NETWORK -> DeviceInfo.snapshot(context).online
        Capability.CLOCK_APP -> intents.canSetAlarms()
        // Events are written directly with calendar access; the editor fallback is tried at run time.
        Capability.CALENDAR_APP -> true
        Capability.CONTACTS -> PhoneControl(context).hasContactsPermission()
        Capability.NOTIFICATION_ACCESS -> com.melhive.zed.android.ZedNotificationListener.hasAccess(context)
        Capability.DND_ACCESS -> PhoneControl(context).hasDndAccess()
        Capability.HOME_ASSISTANT -> this.c.homeAssistant() != null
        Capability.CALENDAR_READ -> androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    override fun explain(c: Capability): String = when (c) {
        Capability.STORAGE_ACCESS -> "folder access (grant a folder in Files)"
        Capability.NOTIFICATIONS -> "notification permission (enable it in Permissions & Diagnostics)"
        Capability.MICROPHONE -> "microphone permission"
        Capability.NETWORK -> "an internet connection"
        Capability.CLOCK_APP -> "a clock app that accepts alarm requests"
        Capability.CALENDAR_APP -> "a calendar app"
        Capability.CONTACTS -> "contacts permission (allow it in More → Permissions & Diagnostics)"
        Capability.NOTIFICATION_ACCESS -> "notification access (turn it on in More → Permissions & Diagnostics → What did I miss)"
        Capability.DND_ACCESS -> "Do Not Disturb access (allow it in More → Permissions & Diagnostics)"
        Capability.CALENDAR_READ -> "calendar permission (allow it in More → Permissions & Diagnostics)"
        Capability.HOME_ASSISTANT -> "Home Assistant connected in Settings → Smart home (address and long-lived token)"
    }
}

object DefaultConfirmationText : ConfirmationText {
    override fun describe(spec: ToolSpec, args: ValidatedArgs): String = when (spec.name) {
        "file.delete" -> "Permanently delete “${args.string("path")}”? This cannot be undone."
        "file.create" -> "Create file “${args.string("path")}”" + (args.stringOrNull("content")?.let { " with ${it.length} characters of text" } ?: "") + "?"
        "folder.create" -> "Create folder “${args.string("path")}”?"
        "file.move" -> "Move “${args.string("source")}” into “${args.string("destination")}”?"
        "file.copy" -> "Copy “${args.string("source")}” into “${args.string("destination")}”?"
        "file.rename" -> "Rename “${args.string("path")}” to “${args.string("new_name")}”?"
        "files.organize" -> "Inspect “${args.stringOrNull("path") ?: "granted folder"}” and prepare an organization plan? Nothing moves until you approve the plan."
        "docs.index" -> "Index text documents in “${args.stringOrNull("path") ?: "granted folder"}” for local search?"
        "alarm.set" -> "Set an alarm for %02d:%02d%s?".format(args.int("hour"), args.int("minute"), args.stringOrNull("label")?.let { " ($it)" } ?: "")
        "timer.set" -> "Start a ${args.int("seconds")}-second timer?"
        "calendar.create" -> "Add “${args.string("title")}” to your calendar at ${args.dateTime("start").pretty()}?"
        "reminder.create" -> "Remind you “${args.string("text")}” at ${args.dateTime("at").pretty()}?"
        "reminder.cancel" -> "Cancel reminder #${args.int("id")}?"
        "calendar.move" -> "Move “${args.string("query")}” to " + when {
            args.boolOrNull("keep_day") == true -> args.dateTime("at").format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)) + " the same day"
            args.boolOrNull("keep_time") == true -> args.dateTime("at").format(DateTimeFormatter.ofPattern("EEEE, MMMM d", java.util.Locale.ENGLISH)) + " at the same time"
            else -> args.dateTime("at").pretty()
        } + "?"
        "reminder.reschedule" -> "Move reminder #${args.int("id")} to ${args.dateTime("at").pretty()}?"
        "todo.add" -> "Add “${args.string("title")}” to your task list?"
        "todo.complete" -> "Mark “${args.string("item")}” as done?"
        "memory.remember" -> "Remember permanently: “${args.string("fact")}”?"
        "memory.forget" -> "Forget all memories matching “${args.string("query")}”?"
        "settings.set" -> "Change setting ${args.string("key")} to “${args.string("value")}”?"
        "todo.delete" -> "Delete “${args.string("item")}” from your task list?"
        "reminder.delete" -> "Delete the reminder “${args.string("item")}”?"
        "calendar.delete" -> "Delete “${args.string("query")}” from your calendar?"
        "docs.remove" -> "Remove “${args.string("query")}” from ZED's knowledge?"
        "teach.forget" -> "Forget the lesson “${args.string("what")}”?"
        "workflow.run" -> "Run workflow “${args.string("name")}”?"
        "phone.call" -> "Call ${args.string("who")}?"
        "sms.compose" -> "Text ${args.string("to")}: “${args.string("message")}”?"
        "dnd.set" -> if (args.bool("on")) "Turn on Do Not Disturb?" else "Turn off Do Not Disturb?"
        "home.secure" -> "${args.string("action").replaceFirstChar { it.uppercase() }} ${args.string("target")}?"
        "home.control" -> "${args.string("action").replace('_', ' ').replaceFirstChar { it.uppercase() }} ${args.string("target")}?"
        else -> "Allow ${spec.title}?"
    }
}

/** Wires every tool in the catalog to its Android implementation. */
fun registerExecutors(tm: ToolManager, c: AppContainer, context: Context) {
    val intents = SystemIntents(context)

    // ---- Phone and device control ----
    val phone = PhoneControl(context)
    tm.register("contacts.find") { a, _ ->
        val found = phone.findContacts(a.string("name"))
        if (found.isEmpty()) ok("No contacts match “${a.string("name")}”.")
        else ok("Contacts matching “${a.string("name")}”:\n" + found.joinToString("\n") { "• ${it.name} — ${it.number}${if (it.type.isNotBlank()) " (${it.type})" else ""}" })
    }
    tm.register("phone.call") { a, _ ->
        when (val t = phone.resolve(a.string("who"))) {
            is PhoneControl.Target.Problem -> fail(t.message)
            is PhoneControl.Target.Number -> ok("${phone.call(t.number)} ${t.label}")
        }
    }
    tm.register("sms.compose") { a, _ ->
        when (val t = phone.resolve(a.string("to"))) {
            is PhoneControl.Target.Problem -> fail(t.message)
            is PhoneControl.Target.Number -> when {
                phone.sendSms(t.number, a.string("message")) -> ok("Sent to ${t.label}: “${a.string("message")}”")
                phone.composeSms(t.number, a.string("message")) -> ok("Message to ${t.label} is ready — press send. (Allow “Send texts” to send directly.)")
                else -> fail("No messaging app is available.")
            }
        }
    }
    tm.register("app.open") { a, _ -> phone.openApp(a.string("name")).let { if (it.startsWith("Opened")) ok(it) else fail(it) } }
    tm.register("media.control") { a, _ -> ok(phone.media(a.string("action"))) }
    tm.register("music.play") { a, _ -> phone.playMusic(a.string("query")).let { if (it.startsWith("No ")) fail(it) else ok(it) } }
    tm.register("volume.set") { a, _ -> ok(phone.volume(a.string("action"), a.intOrNull("level")?.toInt(), a.stringOrNull("stream"))) }
    tm.register("flashlight.set") { a, _ -> phone.flashlight(a.bool("on")).let { if (it.startsWith("This phone")) fail(it) else ok(it) } }
    tm.register("dnd.set") { a, _ -> phone.dnd(a.bool("on")).let { if (it.startsWith("I need")) fail(it) else ok(it) } }
    tm.register("settings.panel") { a, _ -> phone.settingsPanel(a.string("panel")).let { if (it.startsWith("Couldn't")) fail(it) else ok(it) } }
    tm.register("maps.navigate") { a, _ -> phone.navigate(a.string("destination"), a.stringOrNull("mode")).let { if (it.startsWith("No ")) fail(it) else ok(it) } }
    tm.register("web.open") { a, _ -> phone.openWeb(a.string("query")).let { if (it.startsWith("No ")) fail(it) else ok(it) } }
    tm.register("camera.open") { a, _ -> phone.camera(a.stringOrNull("mode")).let { if (it.startsWith("No ")) fail(it) else ok(it) } }
    tm.register("device.status") { _, _ -> ok(phone.status()) }
    tm.register("notifications.recent") { a, _ ->
        val hours = a.intOrNull("hours")?.toInt() ?: 12
        val items = ZedNotificationListener.recent(context, a.stringOrNull("app"), hours)
        if (items.isEmpty()) ok("No notifications in the last $hours hours" + (a.stringOrNull("app")?.let { " from $it" } ?: "") + ".")
        else ok("${items.size} notification(s) in the last $hours hours (newest first):\n" + ZedNotificationListener.describe(items))
    }

    // ---- Smart home ----
    tm.register("home.status") { a, _ ->
        val ha = c.homeAssistant() ?: return@register fail("Home Assistant isn't connected.")
        val all = ha.states()
        val target = a.stringOrNull("target")
        val list = if (target == null) all.filter { it.domain in setOf("light", "switch", "climate", "lock", "cover", "fan") }
            else com.melhive.zed.android.HomeAssistant.match(all, target, secure = false) + com.melhive.zed.android.HomeAssistant.match(all, target, secure = true) +
                all.filter { it.domain == "sensor" && it.name.contains(target, ignoreCase = true) }
        if (list.isEmpty()) fail("No smart-home device matches “${target ?: "anything"}”.")
        else ok(list.distinctBy { it.id }.take(30).joinToString("\n") { "• " + com.melhive.zed.android.HomeAssistant.describe(it) })
    }
    tm.register("home.control") { a, _ ->
        val ha = c.homeAssistant() ?: return@register fail("Home Assistant isn't connected.")
        val target = a.string("target")
        val matches = com.melhive.zed.android.HomeAssistant.match(ha.states(), target, secure = false)
        val plural = Regex("(lights|lamps|switches|fans)$|^all ", RegexOption.IGNORE_CASE).containsMatchIn(target.trim())
        if (matches.isEmpty()) return@register fail("I couldn't find “$target” in Home Assistant.")
        if (!plural && matches.size > 1) return@register fail("Several devices match “$target”: ${matches.take(6).joinToString { it.name }}. Which one?")
        val action = a.string("action")
        val value = a.intOrNull("value")?.toInt()
        for ((domain, group) in matches.groupBy { it.domain }) {
            val ids = group.map { it.id }
            when (action) {
                "brightness" -> ha.call("light", "turn_on", ids, mapOf("brightness_pct" to (value ?: 50)))
                "temperature" -> ha.call("climate", "set_temperature", ids, mapOf("temperature" to (value ?: 22)))
                "toggle" -> ha.call(if (domain in setOf("scene", "script")) domain else "homeassistant", "toggle", ids)
                else -> {
                    val svc = if (domain in setOf("scene", "script")) "turn_on" else action
                    ha.call(if (domain == "media_player" || domain == "climate" || domain == "scene" || domain == "script") domain else "homeassistant", svc, ids)
                }
            }
        }
        val names = matches.joinToString { it.name }
        ok(when (action) {
            "brightness" -> "Set $names to ${value ?: 50}% brightness."
            "temperature" -> "Set $names to ${value ?: 22}°."
            "turn_on" -> "Turned on $names."
            "turn_off" -> "Turned off $names."
            else -> "Toggled $names."
        })
    }
    tm.register("home.secure") { a, _ ->
        val ha = c.homeAssistant() ?: return@register fail("Home Assistant isn't connected.")
        val target = a.string("target")
        val matches = com.melhive.zed.android.HomeAssistant.match(ha.states(), target, secure = true)
        if (matches.isEmpty()) return@register fail("I couldn't find a lock, door or cover called “$target”.")
        if (matches.size > 1) return@register fail("Several devices match “$target”: ${matches.take(6).joinToString { it.name }}. Which one?")
        val e = matches.first()
        val action = a.string("action")
        val (domain, svc) = when (e.domain) {
            "lock" -> "lock" to (if (action in setOf("unlock", "open")) "unlock" else "lock")
            "cover" -> "cover" to (if (action in setOf("open", "unlock")) "open_cover" else "close_cover")
            else -> "alarm_control_panel" to (if (action in setOf("disarm", "unlock", "open")) "alarm_disarm" else "alarm_arm_away")
        }
        ha.call(domain, svc, listOf(e.id))
        ok("${e.name}: ${svc.replace("alarm_", "").replace("_cover", "").replace('_', ' ')} requested.")
    }

    // ---- Briefing, weather, agenda, routines ----
    tm.register("weather.get") { a, _ ->
        if (!DeviceInfo.snapshot(context).online) {
            c.pending.enqueue("weather", a.stringOrNull("place").orEmpty(), c.orchestrator.currentConversation)
            return@register ok("No internet right now, so I can't check the weather. I'll check as soon as you're back online and let you know.")
        }
        com.melhive.zed.agents.WeatherService(context).report(a.stringOrNull("place"), c.settings.current().weatherCity)
            .fold({ ok(it) }, { fail(it.message ?: "Weather is unavailable right now.") })
    }
    tm.register("calendar.today") { a, _ ->
        val cal = com.melhive.zed.agents.CalendarReader(context)
        val day = cal.parseDay(a.stringOrNull("date")) ?: return@register fail("I don't understand the date “${a.stringOrNull("date")}”.")
        ok(cal.describe(day))
    }
    tm.register("routine.run") { a, _ ->
        val r = c.routines.find(a.string("name")) ?: return@register fail("No routine called “${a.string("name")}”. Your routines: " + c.routines.all().joinToString { it.name })
        val (good, text) = c.routines.run(r)
        ToolResult(good, text)
    }

    tm.register("time.now") { _, _ ->
        val now = java.time.ZonedDateTime.now()
        ok("It's " + now.format(DateTimeFormatter.ofPattern("h:mm:ss a", java.util.Locale.ENGLISH)) + " on " +
            now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", java.util.Locale.ENGLISH)) + ".")
    }
    tm.register("time.left") { a, _ ->
        val now = System.currentTimeMillis()
        val target = a.stringOrNull("target")?.lowercase()?.trim()
        fun left(at: Long) = com.melhive.zed.core.time.Durations.describe((at - now) / 1000)
        fun clock(at: Long) = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm:ss a", java.util.Locale.ENGLISH))
        fun wants(kind: String, label: String = "") = target == null || target.contains(kind) || (label.isNotBlank() && label.lowercase().contains(target))
        // "until 9am", "till 5:30 pm", "until tomorrow at 7": exact time to a clock time.
        val keywords = listOf("timer", "alarm", "reminder", "meeting", "event", "calendar", "appointment")
        if (target != null && keywords.none { target.contains(it) }) {
            val nowLdt = LocalDateTime.now()
            com.melhive.zed.core.time.TimeParser.parseDateTime(target, nowLdt)?.let { at ->
                val atMs = at.epochMillis()
                val day = when (at.toLocalDate()) { nowLdt.toLocalDate() -> ""; nowLdt.toLocalDate().plusDays(1) -> " tomorrow"; else -> " on " + at.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", java.util.Locale.ENGLISH)) }
                return@register ok("It's ${clock(now)}. ${at.format(DateTimeFormatter.ofPattern(if (at.second == 0) "h:mm a" else "h:mm:ss a", java.util.Locale.ENGLISH))}$day is ${left(atMs)} away.")
            }
        }
        val lines = mutableListOf<String>()
        com.melhive.zed.android.Countdowns(context).active(now).forEach { e ->
            if (wants(e.kind, e.label)) lines += "${e.kind.replaceFirstChar { it.uppercase() }}${if (e.label.isNotBlank()) " “${e.label}”" else ""}: ${left(e.endAt)} left (at ${clock(e.endAt)})"
        }
        c.db.reminders().scheduled().filter { it.triggerAt > now }.take(5).forEach { r ->
            if (wants("reminder", r.text)) lines += "Reminder “${r.text}”: ${left(r.triggerAt)} (at ${clock(r.triggerAt)})"
        }
        if (target == null || listOf("meeting", "event", "calendar", "appointment").any { target.contains(it) }) {
            val cal = com.melhive.zed.agents.CalendarReader(context)
            if (cal.granted()) cal.nextEvent(now)?.let { (title, at) -> lines += "Next event “$title”: ${left(at)} (at ${clock(at)})" }
        }
        if (lines.isEmpty()) ok(if (target == null) "Nothing is counting down right now: no timers, alarms or reminders from ZED." else "I don't have a $target counting down.")
        else ok("Right now it's ${clock(now)}.\n" + lines.joinToString("\n"))
    }

    // ---- Files ----
    tm.register("file.create") { a, _ ->
        val item = c.files.createFile(a.string("path"), a.stringOrNull("content"))
        ok("Created file “${item.path}”.")
    }
    tm.register("folder.create") { a, _ -> c.files.mkdirs(a.string("path")); ok("Folder “${a.string("path")}” is ready.") }
    tm.register("file.read") { a, _ ->
        val text = c.files.readText(a.string("path"))
        ok("Contents of “${a.string("path")}”:\n$text")
    }
    tm.register("file.list") { a, _ ->
        val path = a.stringOrNull("path") ?: ""
        val items = c.files.list(path)
        if (items.isEmpty()) ok("“${path.ifEmpty { "Granted folder" }}” is empty.")
        else ok("Contents of “${path.ifEmpty { "granted folder" }}” (${items.size}):\n" + items.take(200).joinToString("\n") {
            if (it.isDirectory) "📁 ${it.name}/" else "📄 ${it.name} · ${DeviceInfo.formatBytes(it.sizeBytes)}"
        } + if (items.size > 200) "\n…and ${items.size - 200} more" else "")
    }
    tm.register("file.move") { a, _ -> val n = c.files.move(a.string("source"), a.string("destination")); ok("Moved “$n” to “${a.string("destination")}”.") }
    tm.register("file.copy") { a, _ -> val n = c.files.copy(a.string("source"), a.string("destination")); ok("Copied to “${a.string("destination")}/$n”.") }
    tm.register("file.rename") { a, _ -> c.files.rename(a.string("path"), a.string("new_name")); ok("Renamed to “${a.string("new_name")}”.") }
    tm.register("file.delete") { a, _ -> c.files.delete(a.string("path")); ok("Deleted “${a.string("path")}”.") }

    // ---- Background tasks ----
    tm.register("files.organize") { a, _ ->
        val path = a.stringOrNull("path") ?: ""
        val resolved = c.files.resolveRoot(path)
        val t = c.taskEngine.create(TaskTypes.ORGANIZE, "Organize ${resolved.displayPath}", mapOf("path" to path), resources = setOf(resolved.resourceKey))
        ToolResult(true, "Started task #${t.id}: inspecting ${resolved.displayPath}. The plan will appear in Tasks for your approval before anything moves.", t.id)
    }
    tm.register("docs.index") { a, _ ->
        val path = a.stringOrNull("path") ?: ""
        val resolved = c.files.resolveRoot(path)
        val t = c.taskEngine.create(TaskTypes.INDEX, "Index ${resolved.displayPath}", mapOf("path" to path))
        ToolResult(true, "Started task #${t.id}: indexing text documents in ${resolved.displayPath}. Progress is shown in Tasks.", t.id)
    }
    tm.register("docs.search") { a, _ ->
        val hits = c.knowledge.search(a.string("query"), 5)
        if (hits.isEmpty()) {
            val empty = c.knowledge.chunkCount() == 0
            ok(if (empty) "The document index is empty. Say “index my documents” after granting a folder." else "No indexed documents match “${a.string("query")}”.")
        } else ok("Top matches for “${a.string("query")}”:\n" + hits.joinToString("\n\n") { "• ${it.docPath}\n  ${it.text.take(280).replace('\n', ' ')}…" })
    }

    // ---- Clock and calendar ----
    tm.register("alarm.set") { a, _ ->
        when (val r = intents.setAlarm(a.int("hour").toInt(), a.int("minute").toInt(), a.stringOrNull("label"))) {
            SystemIntents.Result.Launched -> {
                val h = a.int("hour").toInt(); val m = a.int("minute").toInt()
                var at = java.time.LocalDate.now().atTime(h, m)
                if (!at.isAfter(LocalDateTime.now())) at = at.plusDays(1)
                val atMs = at.epochMillis()
                com.melhive.zed.android.Countdowns(context).add("alarm", a.stringOrNull("label").orEmpty(), atMs)
                ok("Alarm set for ${at.format(DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH))} — that's in ${com.melhive.zed.core.time.Durations.describe((atMs - System.currentTimeMillis()) / 1000, withSeconds = false)}.")
            }
            is SystemIntents.Result.Unavailable -> fail(r.reason)
        }
    }
    tm.register("timer.set") { a, _ ->
        when (val r = intents.setTimer(a.int("seconds").toInt(), a.stringOrNull("label"))) {
            SystemIntents.Result.Launched -> {
                val secs = a.int("seconds")
                com.melhive.zed.android.Countdowns(context).add("timer", a.stringOrNull("label").orEmpty(), System.currentTimeMillis() + secs * 1000)
                ok("Timer started for ${com.melhive.zed.core.time.Durations.describe(secs)}.")
            }
            is SystemIntents.Result.Unavailable -> fail(r.reason)
        }
    }
    tm.register("calendar.create") { a, _ ->
        val start = a.dateTime("start")
        val end = a.dateTimeOrNull("end")
        if (end != null && !end.isAfter(start)) return@register fail("The event must end after it starts.")
        val endTime = end ?: start.plusHours(1)
        val writer = com.melhive.zed.agents.CalendarWriter(context)
        val saved = if (writer.granted()) runCatching { writer.insert(a.string("title"), start, endTime, a.stringOrNull("location"), a.stringOrNull("description")) }.getOrNull() else null
        if (saved != null) {
            val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            return@register ok("Added “${a.string("title")}” to your ${saved.second.name} calendar: ${start.format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))}, " +
                "${start.format(timeFmt)}–${endTime.format(timeFmt)}" + (a.stringOrNull("location")?.let { " at $it" } ?: "") + ". I'll remind you 10 minutes before.")
        }
        // No calendar access yet: fall back to the calendar app's editor.
        when (val r = intents.createEvent(a.string("title"), start, end, a.stringOrNull("location"), a.stringOrNull("description"))) {
            SystemIntents.Result.Launched -> ok("Opened your calendar with “${a.string("title")}” on ${start.pretty()} — tap Save there. To add events directly next time, allow calendar access (Settings → Allow everything).")
            is SystemIntents.Result.Unavailable -> fail(if (writer.granted()) "I couldn't find a calendar to write to. Add a Google account calendar on this phone." else "Allow calendar access (Settings → Allow everything) so I can add events directly.")
        }
    }

    tm.register("calendar.move") { a, _ ->
        val writer = com.melhive.zed.agents.CalendarWriter(context)
        if (!writer.granted()) return@register fail("Allow calendar access (Settings → Allow everything) so I can move events.")
        val now = LocalDateTime.now()
        val query = a.string("query")
        val event = com.melhive.zed.core.understanding.CalendarMove.pick(writer.upcoming(System.currentTimeMillis()), query, a.dateTimeOrNull("from"), now)
            ?: return@register fail("I couldn't find an upcoming event matching “$query” in the next two months.")
        if (event.recurring) return@register fail("“${event.title}” repeats — moving it here would move every one. Change it in your calendar app.")
        val start = com.melhive.zed.core.understanding.CalendarMove.newStart(event, a.dateTime("at"), a.boolOrNull("keep_day") == true, a.boolOrNull("keep_time") == true)
        if (!start.isAfter(now)) return@register fail("That time (${start.pretty()}) has already passed.")
        if (!writer.move(event, start)) return@register fail("Your calendar didn't accept the change. Open “${event.title}” in your calendar app to move it.")
        ok("Moved “${event.title}” to ${start.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", java.util.Locale.ENGLISH))} at ${start.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))}.")
    }

    // ---- Reminders ----
    tm.register("reminder.create") { a, _ ->
        val at = a.dateTime("at")
        if (!at.isAfter(LocalDateTime.now())) return@register fail("That time (${at.pretty()}) has already passed.")
        val r = c.reminderScheduler.create(a.string("text"), at.epochMillis())
        ok("Reminder #${r.id} set for ${at.pretty()}: “${r.text}”." + if (!r.exact) " (Approximate timing: allow “Alarms & reminders” in Permissions for exact delivery.)" else "")
    }
    tm.register("brain.show") { a, _ ->
        val q = a.stringOrNull("query")?.trim()?.removePrefix("my ")?.trim().orEmpty()
        val count = if (q.isEmpty()) 0 else c.memory.find(q).size + c.todos.list(true).count { it.title.contains(q, true) } + c.db.reminders().scheduled().count { it.text.contains(q, true) }
        c.brainFocus.value = q.ifEmpty { null }
        context.startActivity(com.melhive.zed.assist.ZedLaunch.intent(context, com.melhive.zed.ui.navigation.Routes.BRAIN))
        ok(when {
            q.isEmpty() -> "Opening the Live Brain."
            count == 0 -> "Opening the Live Brain — I don't have anything about “$q” yet."
            else -> "Opening the Live Brain at “$q” — $count thing${if (count == 1) "" else "s"} I know."
        })
    }
    tm.register("reminder.reschedule") { a, _ ->
        val at = a.dateTime("at")
        if (!at.isAfter(LocalDateTime.now())) return@register fail("That time (${at.pretty()}) has already passed.")
        val old = c.db.reminders().get(a.int("id"))?.takeIf { it.status == "scheduled" } ?: return@register fail("No scheduled reminder #${a.int("id")}.")
        c.reminderScheduler.cancelReminder(old.id)
        val r = c.reminderScheduler.create(old.text, at.epochMillis())
        ok("Reminder #${r.id} moved to ${at.pretty()}: “${r.text}”.")
    }
    tm.register("reminder.list") { _, _ ->
        val list = c.db.reminders().scheduled()
        if (list.isEmpty()) ok("You have no upcoming reminders.")
        else {
            val now = System.currentTimeMillis()
            ok("Upcoming reminders:\n" + list.joinToString("\n") { "#${it.id} · ${it.triggerAt.prettyMillis()} (in ${com.melhive.zed.core.time.Durations.describe((it.triggerAt - now) / 1000)}) · ${it.text}" })
        }
    }
    tm.register("reminder.cancel") { a, _ ->
        if (c.reminderScheduler.cancelReminder(a.int("id"))) ok("Reminder #${a.int("id")} cancelled.")
        else fail("No scheduled reminder #${a.int("id")}.")
    }

    // ---- Task list ----
    tm.register("todo.add") { a, _ ->
        val t = c.todos.add(a.string("title"), a.dateTimeOrNull("due")?.epochMillis())
        ok("Added task #${t.id}: “${t.title}”" + (t.dueAt?.let { ", due ${it.prettyMillis()}" } ?: "") + ".")
    }
    tm.register("todo.list") { a, _ ->
        val list = c.todos.list(a.boolOrNull("include_done") ?: false)
        if (list.isEmpty()) ok("Your task list is empty.")
        else ok("Your tasks:\n" + list.joinToString("\n") { "${if (it.done) "✓" else "○"} #${it.id} ${it.title}" + (it.dueAt?.let { d -> " (due ${d.prettyMillis()})" } ?: "") })
    }
    tm.register("todo.complete") { a, _ ->
        when (val r = c.todos.complete(a.string("item"))) {
            is TodoRepository.CompleteResult.Done -> ok("Marked “${r.item.title}” as done.")
            is TodoRepository.CompleteResult.Ambiguous -> fail("Several tasks match: " + r.matches.joinToString { "#${it.id} ${it.title}" } + ". Say which number.")
            TodoRepository.CompleteResult.NotFound -> fail("No open task matches “${a.string("item")}”.")
        }
    }

    // ---- Memory and history ----
    tm.register("memory.remember") { a, req ->
        val source = if (req.origin == ToolRequest.Origin.NATIVE || req.origin == ToolRequest.Origin.USER_UI) "user_command" else "model_request"
        val f = c.memory.remember(a.string("fact"), source)
        val name = Regex("^my name is (.+)$", RegexOption.IGNORE_CASE).find(f.text.trim())?.groupValues?.get(1)?.trimEnd('.')
        ok(if (name != null) "Nice to meet you, $name. I'll remember your name." else "Got it — I'll remember that ${f.text.replaceFirstChar { it.lowercase() }}.")
    }
    tm.register("memory.list") { _, _ ->
        val list = c.memory.list(50)
        if (list.isEmpty()) ok("I haven't been asked to remember anything yet.")
        else ok("I remember:\n" + list.joinToString("\n") { "• ${it.text}" })
    }
    tm.register("memory.forget") { a, _ ->
        val q = a.string("query").trim().lowercase()
        if (q in setOf("everything", "all", "all memories", "all facts", "everything about me", "everything you know")) {
            val n = c.memory.list(100_000).size
            c.memory.clear()
            return@register ok("Done — I forgot everything I knew ($n memories).")
        }
        val matches = c.memory.find(a.string("query"))
        if (matches.isEmpty()) fail("No memories match “${a.string("query")}”.")
        else { c.memory.delete(matches.map { it.id }); ok("Forgot ${matches.size} memor${if (matches.size == 1) "y" else "ies"}: " + matches.joinToString("; ") { it.text }) }
    }
    tm.register("history.search") { a, _ ->
        val hits = c.conversations.search(a.string("query"))
        if (hits.isEmpty()) ok("No past messages mention “${a.string("query")}”.")
        else ok("Found ${hits.size} message(s):\n" + hits.take(10).joinToString("\n") { "• ${it.createdAt.prettyMillis()} (${it.role}): ${it.content.take(160).replace('\n', ' ')}" })
    }

    // ---- Workflows ----
    tm.register("workflow.run") { a, _ -> c.workflows.run(a.string("name")) }

    // ---- Full control of ZED's own data, settings and lessons ----
    registerAppControl(tm, c, context)

    // ---- Research ----
    tm.register("web.research") { a, _ ->
        if (!c.settings.current().webResearch) return@register fail("Web research is turned off (Settings → Intelligence).")
        val query = com.melhive.zed.core.web.Research.query(a.string("query"))
        if (!DeviceInfo.snapshot(context).online) {
            c.pending.enqueue("research", query, c.orchestrator.currentConversation)
            return@register ok("No internet right now. I saved “$query” and will look it up as soon as the connection is back, then notify you.")
        }
        try {
            val found = com.melhive.zed.web.WebSearcher().research(query)
            ok(com.melhive.zed.core.web.Research.toolResult(found.query, found.pages))
        } catch (e: com.melhive.zed.web.WebSearcher.Unavailable) {
            fail(e.message ?: "Web search is unavailable right now.")
        }
    }
}
