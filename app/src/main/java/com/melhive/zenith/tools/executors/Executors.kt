package com.melhive.zenith.tools.executors

import android.content.Context
import com.melhive.zenith.AppContainer
import com.melhive.zenith.android.DeviceInfo
import com.melhive.zenith.android.Notifications
import com.melhive.zenith.android.PermissionCatalog
import com.melhive.zenith.android.SystemIntents
import com.melhive.zenith.core.tools.Capability
import com.melhive.zenith.core.tools.ToolRequest
import com.melhive.zenith.core.tools.ToolSpec
import com.melhive.zenith.core.tools.ValidatedArgs
import com.melhive.zenith.intelligence.GenerationEvent
import com.melhive.zenith.intelligence.GenerationRequest
import com.melhive.zenith.core.intelligence.ChatTurn
import com.melhive.zenith.core.intelligence.Role
import com.melhive.zenith.memory.TodoRepository
import com.melhive.zenith.tasks.TaskTypes
import com.melhive.zenith.tools.CapabilityChecker
import com.melhive.zenith.tools.ConfirmationText
import com.melhive.zenith.tools.ToolManager
import com.melhive.zenith.tools.ToolResult
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
        Capability.ONLINE_AI -> this.c.providers.research() != null
        Capability.CLOCK_APP -> intents.canSetAlarms()
        Capability.CALENDAR_APP -> intents.canInsertEvents()
    }

    override fun explain(c: Capability): String = when (c) {
        Capability.STORAGE_ACCESS -> "folder access (grant a folder in Files)"
        Capability.NOTIFICATIONS -> "notification permission (enable it in Permissions & Diagnostics)"
        Capability.MICROPHONE -> "microphone permission"
        Capability.NETWORK -> "an internet connection"
        Capability.ONLINE_AI -> "an online AI provider with web search (Gemini, OpenAI or Claude) configured in Model Manager"
        Capability.CLOCK_APP -> "a clock app that accepts alarm requests"
        Capability.CALENDAR_APP -> "a calendar app"
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
        "calendar.create" -> "Open your calendar with event “${args.string("title")}” at ${args.dateTime("start").pretty()}?"
        "reminder.create" -> "Remind you “${args.string("text")}” at ${args.dateTime("at").pretty()}?"
        "reminder.cancel" -> "Cancel reminder #${args.int("id")}?"
        "todo.add" -> "Add “${args.string("title")}” to your task list?"
        "todo.complete" -> "Mark “${args.string("item")}” as done?"
        "memory.remember" -> "Remember permanently: “${args.string("fact")}”?"
        "memory.forget" -> "Forget all memories matching “${args.string("query")}”?"
        "settings.set" -> "Change setting ${args.string("key")} to “${args.string("value")}”?"
        "workflow.run" -> "Run workflow “${args.string("name")}”?"
        else -> "Allow ${spec.title}?"
    }
}

/** Wires every tool in the catalog to its Android implementation. */
fun registerExecutors(tm: ToolManager, c: AppContainer, context: Context) {
    val intents = SystemIntents(context)

    tm.register("time.now") { _, _ ->
        val now = java.time.ZonedDateTime.now()
        ok(now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm")) + " (${now.zone.id})")
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
            SystemIntents.Result.Launched -> ok("Alarm request sent to your clock app for %02d:%02d.".format(a.int("hour"), a.int("minute")))
            is SystemIntents.Result.Unavailable -> fail(r.reason)
        }
    }
    tm.register("timer.set") { a, _ ->
        when (val r = intents.setTimer(a.int("seconds").toInt(), a.stringOrNull("label"))) {
            SystemIntents.Result.Launched -> ok("Timer request sent to your clock app (${a.int("seconds")} seconds).")
            is SystemIntents.Result.Unavailable -> fail(r.reason)
        }
    }
    tm.register("calendar.create") { a, _ ->
        val start = a.dateTime("start")
        val end = a.dateTimeOrNull("end")
        if (end != null && !end.isAfter(start)) return@register fail("The event must end after it starts.")
        when (val r = intents.createEvent(a.string("title"), start, end, a.stringOrNull("location"), a.stringOrNull("description"))) {
            SystemIntents.Result.Launched -> ok("Opened your calendar with “${a.string("title")}” on ${start.pretty()}. Save it there to confirm.")
            is SystemIntents.Result.Unavailable -> fail(r.reason)
        }
    }

    // ---- Reminders ----
    tm.register("reminder.create") { a, _ ->
        val at = a.dateTime("at")
        if (!at.isAfter(LocalDateTime.now())) return@register fail("That time (${at.pretty()}) has already passed.")
        val r = c.reminderScheduler.create(a.string("text"), at.epochMillis())
        ok("Reminder #${r.id} set for ${at.pretty()}: “${r.text}”." + if (!r.exact) " (Approximate timing: allow “Alarms & reminders” in Permissions for exact delivery.)" else "")
    }
    tm.register("reminder.list") { _, _ ->
        val list = c.db.reminders().scheduled()
        if (list.isEmpty()) ok("You have no upcoming reminders.")
        else ok("Upcoming reminders:\n" + list.joinToString("\n") { "#${it.id} · ${it.triggerAt.prettyMillis()} · ${it.text}" })
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
        ok("I'll remember: “${f.text}”.")
    }
    tm.register("memory.list") { _, _ ->
        val list = c.memory.list(50)
        if (list.isEmpty()) ok("I haven't been asked to remember anything yet.")
        else ok("I remember:\n" + list.joinToString("\n") { "• ${it.text}" })
    }
    tm.register("memory.forget") { a, _ ->
        val matches = c.memory.find(a.string("query"))
        if (matches.isEmpty()) fail("No memories match “${a.string("query")}”.")
        else { c.memory.delete(matches.map { it.id }); ok("Forgot ${matches.size} memor${if (matches.size == 1) "y" else "ies"}: " + matches.joinToString("; ") { it.text }) }
    }
    tm.register("history.search") { a, _ ->
        val hits = c.conversations.search(a.string("query"))
        if (hits.isEmpty()) ok("No past messages mention “${a.string("query")}”.")
        else ok("Found ${hits.size} message(s):\n" + hits.take(10).joinToString("\n") { "• ${it.createdAt.prettyMillis()} (${it.role}): ${it.content.take(160).replace('\n', ' ')}" })
    }

    // ---- Settings ----
    tm.register("settings.set") { a, _ ->
        val value = a.string("value").trim().lowercase()
        fun bool(): Boolean? = when (value) { "on", "true", "yes", "enable", "enabled" -> true; "off", "false", "no", "disable", "disabled" -> false; else -> null }
        when (a.string("key")) {
            "voice_responses" -> bool()?.let { c.settings.setVoiceResponses(it); ok("Voice responses ${if (it) "on" else "off"}.") } ?: fail("Use on or off.")
            "auto_index" -> bool()?.let { c.settings.setAutoIndex(it); ok("Automatic indexing ${if (it) "on" else "off"}.") } ?: fail("Use on or off.")
            "use_knowledge" -> bool()?.let { c.settings.setUseKnowledge(it); ok("Document context ${if (it) "on" else "off"}.") } ?: fail("Use on or off.")
            "theme_glow" -> bool()?.let { c.settings.setGlow(it); ok("Glow effects ${if (it) "on" else "off"}.") } ?: fail("Use on or off.")
            "speech_rate" -> value.toFloatOrNull()?.takeIf { it in 0.5f..2.0f }?.let { c.settings.setSpeechRate(it); ok("Speech rate set to $it.") } ?: fail("Speech rate must be between 0.5 and 2.0.")
            else -> fail("Unknown setting.")
        }
    }

    // ---- Workflows ----
    tm.register("workflow.run") { a, _ -> c.workflows.run(a.string("name")) }

    // ---- Research ----
    tm.register("web.research") { a, _ ->
        val provider = c.providers.research() ?: return@register fail("Online research needs Gemini, OpenAI or Claude configured in Model Manager.")
        val sb = StringBuilder()
        var error: String? = null
        provider.generate(
            GenerationRequest(
                system = "You are a research assistant. Search the web, then give a concise, factual answer with the key sources (titles and URLs).",
                turns = listOf(ChatTurn(Role.USER, a.string("query"))),
                maxOutputTokens = 2048,
                webSearch = true,
            )
        ).collect { e ->
            when (e) {
                is GenerationEvent.Delta -> sb.append(e.text)
                is GenerationEvent.Error -> error = e.message
                else -> Unit
            }
        }
        if (error != null && sb.isEmpty()) fail("Research failed: $error") else ok("Research results (${provider.label}):\n${sb.toString().trim()}")
    }
}
