package com.melhive.zenith.core.command

import com.melhive.zenith.core.time.TimeParser
import com.melhive.zenith.core.tools.ToolRequest
import java.time.LocalDateTime

sealed interface ParsedCommand {
    data class Tool(val request: ToolRequest) : ParsedCommand
    data object Help : ParsedCommand
    data class Clarify(val message: String) : ParsedCommand
    data object NotACommand : ParsedCommand
}

/**
 * Deterministic natural-language command router. It recognizes a fixed set of
 * phrasings and maps them to structured tool requests. It does NOT reason:
 * anything outside these patterns returns [ParsedCommand.NotACommand] so the
 * caller can hand it to an AI provider (if one is available).
 */
class CommandParser(private val clock: () -> LocalDateTime = { LocalDateTime.now() }) {

    private val opt = setOf(RegexOption.IGNORE_CASE)
    private fun rx(p: String) = Regex(p, opt)

    private val leadIns = rx("^(?:hey |ok |okay )?(?:zenith[,:]?\\s+)?(?:please\\s+|can you\\s+|could you\\s+|would you\\s+|i want you to\\s+|i need you to\\s+)*")

    fun parse(input: String): ParsedCommand {
        var text = input.trim().replace(Regex("\\s+"), " ")
        text = leadIns.replace(text, "").trim()
        text = text.trimEnd('?', '!').trimEnd()
        if (text.endsWith(".") && !Regex("\\.[A-Za-z0-9]{1,5}\\.$").containsMatchIn(text) || text.endsWith("..")) text = text.dropLast(1).trimEnd()
        if (text.isEmpty()) return ParsedCommand.NotACommand
        for (rule in rules) {
            val m = rule.regex.matchEntire(text) ?: continue
            val result = rule.build(m) ?: continue
            return result
        }
        return ParsedCommand.NotACommand
    }

    private class Rule(val regex: Regex, val build: (MatchResult) -> ParsedCommand?)

    private fun tool(name: String, vararg args: Pair<String, String?>): ParsedCommand =
        ParsedCommand.Tool(ToolRequest(name, args.filter { !it.second.isNullOrBlank() }.associate { it.first to clean(it.second!!) }, ToolRequest.Origin.NATIVE))

    private fun clean(s: String): String = s.trim().removeSurrounding("\"").removeSurrounding("'").removeSurrounding("“", "”").trim()

    private val timeKeywords = Regex("\\b(in|at|on|tomorrow|today|tonight)\\b", RegexOption.IGNORE_CASE)

    /** Splits "call mom at the office at 5pm" into ("call mom at the office", 17:00). */
    private fun splitTrailingTime(text: String): Pair<String, LocalDateTime>? {
        val now = clock()
        for (m in timeKeywords.findAll(text)) {
            if (m.range.first == 0) continue
            val head = text.substring(0, m.range.first).trim()
            val tail = text.substring(m.range.first).trim()
            val at = TimeParser.parseDateTime(tail, now) ?: continue
            if (head.isNotEmpty()) return head to at
        }
        return null
    }

    private fun normalizeFolderWords(raw: String): String {
        var s = raw.trim()
        s = s.replace(Regex("^(?:in |inside )?(?:my |the )?", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("\\b(?:downloaded|downloads?)\\b", RegexOption.IGNORE_CASE), "Download")
        s = s.replace(Regex("\\s*\\b(?:files?|documents?|docs|folder|directory)\\b\\s*", RegexOption.IGNORE_CASE), " ").trim()
        return s
    }

    private val rules: List<Rule> = listOf(
        Rule(rx("help|commands|what can you do|what are your commands|show commands")) { ParsedCommand.Help },

        Rule(rx("(?:what(?:'s| is) the |tell me the |current )?(?:time|date|day)(?: is it)?(?: now| today)?|what(?:'s| is) today(?:'s date)?|what day is (?:it|today)|what time is it(?: now)?")) { tool("time.now") },

        // Reminders (before generic delete/cancel).
        Rule(rx("(?:list|show)(?: me)?(?: all)? (?:my )?reminders|what are my reminders|reminders")) { tool("reminder.list") },
        Rule(rx("(?:cancel|delete|remove) reminder (?:#|number )?(\\d+)")) { m -> tool("reminder.cancel", "id" to m.groupValues[1]) },
        Rule(rx("remind me (in .+?|at .+?|tomorrow.*?|today.*?|tonight.*?|on .+?) (?:to|about|that) (.+)")) { m ->
            val at = TimeParser.parseDateTime(m.groupValues[1], clock()) ?: return@Rule null
            tool("reminder.create", "text" to m.groupValues[2], "at" to at.toString())
        },
        Rule(rx("remind me (?:to |about |that )?(.+)")) { m ->
            val split = splitTrailingTime(m.groupValues[1])
                ?: return@Rule ParsedCommand.Clarify("When should I remind you? For example: “remind me to call Ana at 5pm” or “in 20 minutes”.")
            tool("reminder.create", "text" to split.first, "at" to split.second.toString())
        },

        // Alarms and timers.
        Rule(rx("(?:set|create|add|make) (?:an |a |my )?alarm (?:for |at )?(.+?)(?: (?:called|named|labell?ed|with label) (.+))?")) { m ->
            alarm(m.groupValues[1], m.groupValues[2])
        },
        Rule(rx("wake me(?: up)? (?:at )?(.+)")) { m -> alarm(m.groupValues[1], "Wake up") },
        Rule(rx("(?:set|start|create) (?:a |an )?timer (?:for )?(.+?)(?: (?:called|named|labell?ed) (.+))?")) { m ->
            val secs = TimeParser.parseDurationSeconds(m.groupValues[1])
                ?: return@Rule ParsedCommand.Clarify("How long should the timer run? For example: “set a timer for 10 minutes”.")
            tool("timer.set", "seconds" to secs.toString(), "label" to m.groupValues[2])
        },
        Rule(rx("(.+?) timer")) { m ->
            val secs = TimeParser.parseDurationSeconds(m.groupValues[1]) ?: return@Rule null
            tool("timer.set", "seconds" to secs.toString())
        },

        // Calendar.
        Rule(rx("(?:create|add|schedule|make|book) (?:an? |the )?(?:calendar )?(?:event(?: called| named| titled| for)? |(?=(?:meeting|appointment)\\b))(.+)")) { m ->
            var body = m.groupValues[1]
            var durationMinutes: Long? = null
            Regex("\\s+for (.+)$", RegexOption.IGNORE_CASE).find(body)?.let { f ->
                val secs = TimeParser.parseDurationSeconds(f.groupValues[1])
                if (secs != null) { durationMinutes = secs / 60; body = body.substring(0, f.range.first) }
            }
            val split = splitTrailingTime(body)
                ?: return@Rule ParsedCommand.Clarify("When is the event? For example: “schedule meeting with Ana tomorrow at 3pm for 1 hour”.")
            val end = split.second.plusMinutes(durationMinutes ?: 60)
            tool("calendar.create", "title" to split.first, "start" to split.second.toString(), "end" to end.toString())
        },

        // Task list (todo).
        Rule(rx("(?:list|show)(?: me)? (?:my )?(?:tasks|todos|to-dos|todo list|to-do list|task list)|what are my (?:tasks|todos)|(?:my )?(?:todo|to-do|task) list")) { tool("todo.list") },
        Rule(rx("add (.+?) to (?:my )?(?:task|todo|to-do) list")) { m -> tool("todo.add", "title" to m.groupValues[1]) },
        Rule(rx("(?:add|create|new) (?:a )?(?:task|todo|to-do)(?: item)?:? (.+?)(?: due (.+))?")) { m ->
            val due = m.groupValues[2].takeIf { it.isNotBlank() }?.let { TimeParser.parseDateTime(it, clock()) }
            if (m.groupValues[2].isNotBlank() && due == null) return@Rule ParsedCommand.Clarify("I couldn't understand the due date “${m.groupValues[2]}”.")
            tool("todo.add", "title" to m.groupValues[1], "due" to due?.toString())
        },
        Rule(rx("(?:complete|finish|check off|done with) (?:task |todo |the task )?(.+)|mark (?:task |todo )?(.+?) (?:as )?(?:done|complete|completed|finished)")) { m ->
            tool("todo.complete", "item" to m.groupValues[1].ifEmpty { m.groupValues[2] })
        },

        Rule(rx("(?:delete|remove|erase) (?:the )?(?:task|todo|to-do)(?: item)? (.+)")) { m ->
            ParsedCommand.Clarify("To finish “${clean(m.groupValues[1])}” say “complete ${clean(m.groupValues[1])}”. To delete a task-list item, open Memory → Task list.")
        },

        // Memory.
        Rule(rx("(?:delete|remove|erase) (?:the )?(?:memory|memories|fact)(?: about)? (.+)")) { m -> tool("memory.forget", "query" to m.groupValues[1]) },
        Rule(rx("what do you (?:remember|know about me)|(?:list|show)(?: me)? (?:my |your )?memor(?:y|ies)|memories")) { tool("memory.list") },
        Rule(rx("remember (?:that )?(.+)")) { m -> tool("memory.remember", "fact" to m.groupValues[1]) },
        Rule(rx("forget (?:that |about )?(.+)")) { m -> tool("memory.forget", "query" to m.groupValues[1]) },

        // History and documents.
        Rule(rx("search (?:my |the )?(?:history|conversations|chats|chat history) (?:for )?(.+)")) { m -> tool("history.search", "query" to m.groupValues[1]) },
        Rule(rx("index (?:my |the )?(?:documents|docs|files)(?: in (.+))?")) { m -> tool("docs.index", "path" to normalizeFolderWords(m.groupValues[1])) },
        Rule(rx("(?:search|find|look for|look up) (?:in )?(?:my |the )?(?:documents|docs|notes) (?:for )?(.+)")) { m -> tool("docs.search", "query" to m.groupValues[1]) },

        // Online research.
        Rule(rx("(?:research|look up online|search the web for|search online for|web search(?: for)?) (.+)")) { m -> tool("web.research", "query" to m.groupValues[1]) },

        // Settings.
        Rule(rx("(?:turn|switch) (on|off) (?:the )?(?:voice responses|voice replies|spoken responses|voice)")) { m -> tool("settings.set", "key" to "voice_responses", "value" to m.groupValues[1].lowercase()) },
        Rule(rx("(enable|disable) (?:the )?(?:voice responses|voice replies|spoken responses|voice)")) { m -> tool("settings.set", "key" to "voice_responses", "value" to if (m.groupValues[1].equals("enable", true)) "on" else "off") },
        Rule(rx("set (?:the )?speech rate to ([0-9.]+)")) { m -> tool("settings.set", "key" to "speech_rate", "value" to m.groupValues[1]) },

        // Workflows.
        Rule(rx("good morning|(?:run |start )?(?:my )?(?:morning|daily) (?:briefing|brief|summary)")) { tool("workflow.run", "name" to "morning briefing") },
        Rule(rx("(?:run|start|execute) (?:the )?workflow (.+)|(?:run|start|execute) (?:the |my )?(.+?) workflow")) { m -> tool("workflow.run", "name" to m.groupValues[1].ifEmpty { m.groupValues[2] }) },

        // Files and folders.
        Rule(rx("(?:organi[sz]e|sort|tidy(?: up)?|clean up) (.*)")) { m -> tool("files.organize", "path" to normalizeFolderWords(m.groupValues[1])) },
        Rule(rx("(?:create|make|new) (?:a |an )?(?:new )?(?:folder|directory) (?:called |named )?(.+)")) { m -> tool("folder.create", "path" to m.groupValues[1]) },
        Rule(rx("(?:create|make|new) (?:a |an )?(?:new )?(?:text )?(?:file|note|document) (?:called |named )?(.+?)(?:\\s+(?:with (?:the )?(?:content|text)|containing|saying|that says)\\s+(.+))?")) { m ->
            tool("file.create", "path" to m.groupValues[1], "content" to m.groupValues[2])
        },
        Rule(rx("(?:list|show)(?: me)? (?:the |my )?(?:files|contents|folders)(?: (?:in|of|inside) (?:the )?(?:folder )?(.+))?|what(?:'s| is) in (?:the )?(?:folder )?(.+)")) { m ->
            tool("file.list", "path" to m.groupValues[1].ifEmpty { m.groupValues[2] })
        },
        Rule(rx("(?:read|open|show)(?: me)? (?:the )?(?:file|document|note) (.+)|read (.+\\.[A-Za-z0-9]{1,5})")) { m -> tool("file.read", "path" to m.groupValues[1].ifEmpty { m.groupValues[2] }) },
        Rule(rx("move (.+?) (?:to|into) (?:the )?(?:folder )?(.+)")) { m -> tool("file.move", "source" to m.groupValues[1], "destination" to m.groupValues[2]) },
        Rule(rx("copy (.+?) (?:to|into) (?:the )?(?:folder )?(.+)")) { m -> tool("file.copy", "source" to m.groupValues[1], "destination" to m.groupValues[2]) },
        Rule(rx("rename (.+?) (?:to|as) (.+)")) { m -> tool("file.rename", "path" to m.groupValues[1], "new_name" to m.groupValues[2]) },
        Rule(rx("(?:delete|remove|erase|trash) (?:the )?(?:file |folder |document )?(.+)")) { m -> tool("file.delete", "path" to m.groupValues[1]) },
    )

    private fun alarm(timeText: String, label: String?): ParsedCommand {
        val raw = timeText.trim().lowercase()
        val time = TimeParser.parseClock(raw)
            ?: TimeParser.parseDateTime(raw, clock())?.toLocalTime()
            ?: return ParsedCommand.Clarify("What time should the alarm be? For example: “set an alarm for 6:45 am”.")
        return tool("alarm.set", "hour" to time.hour.toString(), "minute" to time.minute.toString(), "label" to label)
    }

    companion object {
        val HELP_TEXT = """
            |Native commands (work offline, no AI model needed):
            |• Time — “what time is it”, “what's the date”
            |• Files — “create file notes/ideas.txt with content …”, “create folder Projects”, “read file notes/ideas.txt”, “list files in Projects”, “move a.txt to Archive”, “copy a.txt to Backup”, “rename a.txt to b.txt”, “delete old.txt”
            |• Organize — “organize my downloads”, “index my documents”, “search documents for invoice”
            |• Alarms — “set an alarm for 6:30 am”, “set a timer for 10 minutes”
            |• Calendar — “schedule meeting with Ana tomorrow at 3pm for 1 hour”
            |• Reminders — “remind me to stretch in 30 minutes”, “list reminders”, “cancel reminder 2”
            |• Tasks — “add task buy milk”, “list tasks”, “complete buy milk”
            |• Memory — “remember that my sister's name is Ana”, “what do you remember”, “forget sister”
            |• History — “search history for budget”
            |• Workflows — “good morning” (morning briefing), “run workflow organize downloads”
            |• Settings — “turn off voice responses”, “set speech rate to 1.2”
            |• Research — “research …” (needs an online AI provider)
        """.trimMargin()
    }
}
