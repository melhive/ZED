package com.melhive.zed.core.command

import com.melhive.zed.core.compute.Calculator
import com.melhive.zed.core.compute.DateMath
import com.melhive.zed.core.compute.UnitConverter
import com.melhive.zed.core.time.TimeParser
import com.melhive.zed.core.tools.ToolRequest
import java.time.LocalDateTime

sealed interface ParsedCommand {
    data class Tool(val request: ToolRequest) : ParsedCommand
    data object Help : ParsedCommand
    data class Clarify(val message: String) : ParsedCommand
    /** A deterministic answer computed locally (calculation, conversion, date math). */
    data class Answer(val text: String) : ParsedCommand
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

    private val leadIns = rx("^(?:hey |ok |okay )?(?:(?:zed|zedd|zet)[,:]?\\s+)?(?:please\\s+|can you\\s+|could you\\s+|would you\\s+|i want you to\\s+|i need you to\\s+)*")

    fun parse(input: String): ParsedCommand {
        var text = input.trim().replace(Regex("\\s+"), " ")
        text = leadIns.replace(text, "").trim()
        text = text.trimEnd('?', '!').trimEnd()
        if (text.endsWith(".") && !Regex("\\.[A-Za-z0-9]{1,5}\\.$").containsMatchIn(text) || text.endsWith("..")) text = text.dropLast(1).trimEnd()
        if (text.isEmpty()) return ParsedCommand.NotACommand
        teach(text)?.let { return it }
        for (rule in rules) {
            val m = rule.regex.matchEntire(text) ?: continue
            val result = rule.build(m) ?: continue
            return result
        }
        return compute(text) ?: ParsedCommand.NotACommand
    }

    /** Deterministic computations that need no tool and no model. */
    private fun compute(text: String): ParsedCommand? {
        when (val c = UnitConverter.tryConvert(text)) {
            is UnitConverter.Result.Ok -> return ParsedCommand.Answer(c.conversion.describe())
            is UnitConverter.Result.Error -> return ParsedCommand.Answer(c.message)
            null -> Unit
        }
        DateMath.tryAnswer(text, clock().toLocalDate())?.let { return ParsedCommand.Answer(it) }
        Calculator.normalize(text)?.let { expr ->
            return try {
                ParsedCommand.Answer("${Calculator.display(text)} = ${Calculator.format(Calculator.evaluate(expr))}")
            } catch (e: Calculator.CalcException) {
                ParsedCommand.Answer("I can't compute that: ${e.message}.")
            }
        }
        return null
    }

    private class Rule(val regex: Regex, val build: (MatchResult) -> ParsedCommand?)

    /** Lessons: pronunciations, answering rules, shortcuts and speaking speed. */
    private fun teach(text: String): ParsedCommand? = when (val t = com.melhive.zed.core.learning.Teaching.detect(text)) {
        is com.melhive.zed.core.learning.Teaching.Taught.Pronounce -> tool("teach.pronounce", "word" to t.word, "say" to t.say)
        is com.melhive.zed.core.learning.Teaching.Taught.Rule -> tool("teach.rule", "rule" to t.rule)
        is com.melhive.zed.core.learning.Teaching.Taught.Alias -> tool("teach.alias", "phrase" to t.phrase, "meaning" to t.meaning)
        is com.melhive.zed.core.learning.Teaching.Taught.Speed -> tool("settings.set", "key" to "speech_rate", "value" to if (t.faster) "faster" else "slower")
        null -> null
    }

    private val DAY = "today|tomorrow|tonight|\\d{4}-\\d{2}-\\d{2}|(?:next )?(?:mon|tues|wednes|thurs|fri|satur|sun)day"

    /** Spoken names of ZED's own screens → [com.melhive.zed.core.tools.ToolCatalog.SCREENS]. */
    private fun screen(name: String): String? = when (name.lowercase().replace(Regex("\\s+"), " ").trim()) {
        "facts", "memories", "memory", "my memories" -> "facts"
        "task list", "to-do list", "todo list", "to do list" -> "task_list"
        "reminders" -> "reminders"
        "knowledge", "knowledge base", "document index", "documents index", "index" -> "knowledge"
        "schedule", "calendar screen" -> "schedule"
        "settings", "zed settings", "app settings" -> "settings"
        "voice settings", "voices", "voice screen" -> "voice"
        "models", "model manager", "ai models" -> "models"
        "routines" -> "routines"
        "diagnostics", "permissions" -> "diagnostics"
        "backup", "backups" -> "backup"
        "skills" -> "skills"
        "what's new", "whats new", "changelog" -> "whats_new"
        "lessons", "what you learned", "what you've learned", "taught", "teachings" -> "taught"
        "home", "home screen", "dashboard" -> "home"
        "chat" -> "chat"
        "background tasks", "tasks screen" -> "tasks"
        else -> null
    }

    /** Spoken names of ZED's switches → settings keys. */
    private fun switch(name: String): String? = when (name.lowercase().replace(Regex("\\s+"), " ").trim().removeSuffix(" mode")) {
        "wake word", "hey zed", "wake up word", "hotword", "wake-word" -> "wake_word"
        "web research", "web search", "internet research", "online research" -> "web_research"
        "learning", "auto learning", "automatic learning", "auto-learning" -> "auto_learning"
        "hints", "proactive hints", "suggestions", "leave now hints", "leave-now hints" -> "proactive_hints"
        "brain sounds", "brain sound" -> "brain_sounds"
        "think twice", "thinking twice", "double checking" -> "think_twice"
        "barge in", "barge-in", "interruptions", "interrupting" -> "barge_in"
        "glow", "glow effects" -> "theme_glow"
        "auto index", "automatic indexing", "auto indexing" -> "auto_index"
        "document context", "knowledge context" -> "use_knowledge"
        "briefing", "daily briefing", "morning briefing" -> "briefing"
        "update checks", "updates", "update check" -> "check_updates"
        "voice responses", "voice replies", "spoken responses", "spoken replies", "voice" -> "voice_responses"
        else -> null
    }

    private val TITLES = setOf("sir", "ma'am", "maam", "madam", "boss", "chief", "captain", "master", "my lord", "my lady", "commander", "doc", "professor")

    private fun tool(name: String, vararg args: Pair<String, String?>): ParsedCommand =
        ParsedCommand.Tool(ToolRequest(name, args.filter { !it.second.isNullOrBlank() }.associate { it.first to clean(it.second!!) }, ToolRequest.Origin.NATIVE))

    /** First non-empty group of an on/off match as "true"/"false". */
    private fun onOff(m: MatchResult): String =
        if (m.groupValues.drop(1).first { it.isNotEmpty() }.lowercase() in setOf("on", "enable")) "true" else "false"

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

        // Countdowns: exact time left (before reminders).
        Rule(rx("(?:how (?:long|much time|many (?:minutes|hours|seconds))(?: is it| do i have| is there| is left| are left| left| remaining)?|time (?:left|remaining)) (?:until|till|til|before|to) (?:my |the )?(.+)")) { m -> tool("time.left", "target" to m.groupValues[1]) },
        Rule(rx("how much (?:time )?(?:is )?left(?: on (?:my |the )?(.+?))?|how (?:long|much time) (?:is )?(?:left|remaining)(?: (?:on|for|until) (?:my |the )?(.+?))?|time (?:left|remaining)(?: on (?:my |the )?(.+?))?|how long (?:until|till|before) (?:my |the )?(.+?)|when(?:'s| is) my next (reminder|alarm|meeting|event|timer)")) { m ->
            tool("time.left", "target" to m.groupValues.drop(1).firstOrNull { it.isNotEmpty() })
        },

        // Reminders (before generic delete/cancel).
        Rule(rx("(?:list|show)(?: me)?(?: all)? (?:my )?reminders|what are my reminders|reminders")) { tool("reminder.list") },
        Rule(rx("(?:cancel|delete|remove) reminder (?:#|number )?(\\d+)")) { m -> tool("reminder.cancel", "id" to m.groupValues[1]) },
        Rule(rx("(?:delete|remove|clear|erase) (?:all (?:of )?)?(?:my |the )?reminders")) { tool("reminder.delete", "item" to "all") },
        Rule(rx("(?:delete|remove|erase|cancel) (?:the |my )?reminder (?:about |to |for |that )?(.+)")) { m -> tool("reminder.delete", "item" to m.groupValues[1]) },
        Rule(rx("remind me (in .+?|at .+?|tomorrow.*?|today.*?|tonight.*?|on .+?|next .+?|(?:mon|tues|wednes|thurs|fri|satur|sun)day.*?) (?:to|about|that) (.+)")) { m ->
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

        // Deleting an event: "delete my dentist appointment", "cancel the meeting with Ana tomorrow", "remove lunch from my calendar".
        Rule(rx("(?:delete|remove|cancel) (?:my |the )?(?:calendar )?event (?:called |named |titled )?(.+?)(?: (?:on|for) ($DAY))?")) { m ->
            tool("calendar.delete", "query" to m.groupValues[1], "date" to m.groupValues[2])
        },
        Rule(rx("(?:delete|remove|cancel) (?:my |the )?((?:[\\p{L}'-]+ ){0,3}(?:meeting|appointment|lunch|dinner|class|session|interview|call with|date with|checkup|check-up)(?: (?:with|at|about) .+?)?)(?: (?:on|for) ($DAY)| ($DAY))?")) { m ->
            tool("calendar.delete", "query" to m.groupValues[1], "date" to m.groupValues[2].ifEmpty { m.groupValues[3] })
        },
        Rule(rx("(?:delete|remove|erase|take) (?:the |my )?(.+?) (?:from|off) (?:my |the )?(?:calendar|schedule|agenda)")) { m -> tool("calendar.delete", "query" to m.groupValues[1]) },

        // Moving an event: "move my meeting with Ana to 4", "reschedule the dentist to Friday at 10".
        Rule(rx("(?:move|reschedule|push|shift|change) (?:my |the )?(.+?) (?:to|until|till|for) (.+)")) { m ->
            val what = m.groupValues[1].trim()
            // Files, reminders, alarms and pronouns go elsewhere (file moves, or the conversation's follow-ups).
            if (Regex("[./\\\\]").containsMatchIn(what) || Regex("^(?:it|that|this|reminders?|alarms?|timers?|file|folder|volume)\\b|\\b(?:volume|thermostat|temperature|ac|aircon|air conditioner|heating|lights?|lamps?|brightness|speech rate|fan)$", RegexOption.IGNORE_CASE).containsMatchIn(what)) return@Rule null
            val args = com.melhive.zed.core.understanding.CalendarMove.arguments(m.groupValues[2], clock()) ?: return@Rule null
            ParsedCommand.Tool(ToolRequest("calendar.move", mapOf("query" to clean(what)) + args, ToolRequest.Origin.NATIVE))
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

        Rule(rx("(?:delete|remove|erase|clear) (?:all )?(?:the |my )?(?:completed|done|finished|checked) (?:tasks|todos|to-dos|items)")) { tool("todo.delete", "item" to "done") },
        Rule(rx("(?:delete|remove|erase|clear|empty) (?:all (?:of )?)?(?:my |the )?(?:tasks|todos|to-dos|task list|todo list|to-do list)")) { tool("todo.delete", "item" to "all") },
        Rule(rx("(?:delete|remove|erase) (?:the )?(?:task|todo|to-do)(?: item)? (.+)")) { m -> tool("todo.delete", "item" to m.groupValues[1]) },
        Rule(rx("(?:delete|remove|erase|take|cross) (.+?) (?:off|from|out of) (?:my |the )?(?:task |todo |to-do |shopping )?list")) { m -> tool("todo.delete", "item" to m.groupValues[1]) },

        // Lessons the user taught.
        Rule(rx("what (?:have you|did you) learn(?:ed)?(?: from me)?|what did i teach you|(?:list|show)(?: me)? (?:the |my |your )?(?:lessons|shortcuts|pronunciations|rules)")) { tool("teach.list") },
        Rule(rx("(?:forget|unlearn|remove|delete) (?:all )?(?:the |my |that |your )?(?:pronunciations?|shortcuts?|rules?|lessons?)(?: (?:of|for|about|called|that says))? ?(.*)")) { m -> tool("teach.forget", "what" to m.groupValues[1].ifBlank { "all" }) },

        // Memory.
        Rule(rx("(?:forget|delete|erase|clear|wipe) (?:all (?:of )?)?(?:everything(?: you know)?(?: about me)?|(?:all )?(?:my |your |the )?(?:memories|facts))")) { tool("memory.forget", "query" to "everything") },
        Rule(rx("(?:delete|remove|erase) (?:the )?(?:memory|memories|fact)(?: about)? (.+)")) { m -> tool("memory.forget", "query" to m.groupValues[1]) },
        Rule(rx("what do you (?:remember|know about me)|(?:list|show)(?: me)? (?:my |your )?memor(?:y|ies)|memories")) { tool("memory.list") },
        Rule(rx("(?:call me|address me as|refer to me as) ([\\p{L}][\\p{L} .'-]{0,30})")) { m ->
            val v = clean(m.groupValues[1]).trimEnd('.')
            // Only titles ("call me boss"); names come from "my name is" / "you can call me" ("call me back later" is not a name).
            if (v.lowercase() in TITLES) tool("settings.set", "key" to "user_title", "value" to v) else null
        },
        Rule(rx("(?:my name is|i'?m called|you can call me) ([\\p{L}][\\p{L} .'-]{0,40})")) { m -> tool("memory.remember", "fact" to "My name is ${clean(m.groupValues[1]).replaceFirstChar { it.uppercase() }}") },
        // "remember to X [time]" / "don't forget to X [time]" are reminders (or task-list items without a time).
        Rule(rx("(?:remember|don'?t forget|do not forget) to (.+)")) { m ->
            val split = splitTrailingTime(m.groupValues[1])
            if (split != null) tool("reminder.create", "text" to split.first, "at" to split.second.toString())
            else tool("todo.add", "title" to m.groupValues[1])
        },
        Rule(rx("(?:remember|don'?t forget|do not forget|keep in mind|note|make a note|take note|save (?:this|that)?)(?: that)?[:,]? (.+)")) { m ->
            tool("memory.remember", "fact" to m.groupValues[1])
        },
        Rule(rx("forget (?:that |about )?(.+)")) { m -> tool("memory.forget", "query" to m.groupValues[1]) },

        // The Live Brain, by voice: "show me everything about Ana".
        Rule(rx("(?:show|open|display)(?: me)? (?:everything|all|what you know|what do you know|all you know) (?:about|on) (.+?)(?: in (?:my|the|your) brain)?|show (.+?) in (?:my|the|your) (?:live )?brain|(?:open|show)(?: me)? (?:my |the |your )?(?:live )?brain")) { m ->
            tool("brain.show", "query" to m.groupValues[1].ifEmpty { m.groupValues[2] })
        },

        // History and documents.
        Rule(rx("search (?:my |the )?(?:history|conversations|chats|chat history) (?:for )?(.+)")) { m -> tool("history.search", "query" to m.groupValues[1]) },
        Rule(rx("(?:clear|empty|wipe|reset) (?:my |the |your )?(?:knowledge|index|document index|knowledge base)")) { tool("docs.remove", "query" to "all") },
        Rule(rx("(?:remove|delete|drop) (?:the )?(?:document |file )?(.+?) from (?:my |the |your )?(?:knowledge|index|knowledge base|document index)")) { m -> tool("docs.remove", "query" to m.groupValues[1]) },
        Rule(rx("index (?:my |the )?(?:documents|docs|files)(?: in (.+))?")) { m -> tool("docs.index", "path" to normalizeFolderWords(m.groupValues[1])) },
        Rule(rx("(?:search|find|look for|look up) (?:in )?(?:my |the )?(?:documents|docs|notes) (?:for )?(.+)")) { m -> tool("docs.search", "query" to m.groupValues[1]) },

        // Online research.
        Rule(rx("(?:research|look up online|search the web for|search online for|web search(?: for)?) (.+)")) { m -> tool("web.research", "query" to m.groupValues[1]) },

        // Settings.
        Rule(rx("(?:turn|switch) (on|off) (?:the )?(?:voice responses|voice replies|spoken responses|voice)")) { m -> tool("settings.set", "key" to "voice_responses", "value" to m.groupValues[1].lowercase()) },
        Rule(rx("(enable|disable) (?:the )?(?:voice responses|voice replies|spoken responses|voice)")) { m -> tool("settings.set", "key" to "voice_responses", "value" to if (m.groupValues[1].equals("enable", true)) "on" else "off") },
        Rule(rx("set (?:the )?speech rate to ([0-9.]+)")) { m -> tool("settings.set", "key" to "speech_rate", "value" to m.groupValues[1]) },
        Rule(rx("set (?:the |your )?(?:speech |voice )?pitch to ([0-9.]+)")) { m -> tool("settings.set", "key" to "speech_pitch", "value" to m.groupValues[1]) },
        Rule(rx("(?:change|switch|set) (?:your |the |my )?voice to (?:the )?(.+?)(?: voice)?|use (?:the )?([\\p{L}]+(?: [\\p{L}]+)?) voice")) { m ->
            tool("settings.set", "key" to "voice", "value" to m.groupValues[1].ifEmpty { m.groupValues[2] })
        },
        Rule(rx("(?:turn|switch) (on|off) (?:the |your |my )?(.+?)|(?:turn|switch) (?:the |your |my )?(.+?) (on|off)|(enable|disable) (?:the |your |my )?(.+?)")) { m ->
            val g = m.groupValues
            val (state, name) = when { g[1].isNotEmpty() -> g[1] to g[2]; g[4].isNotEmpty() -> g[4] to g[3]; else -> g[5] to g[6] }
            val key = switch(name) ?: return@Rule null
            tool("settings.set", "key" to key, "value" to if (state.lowercase() in setOf("on", "enable")) "on" else "off")
        },
        Rule(rx("set (?:the |your )?(?:wake(?: word)?|hey zed|wake-word) sensitivity (?:to )?(low|lowest|fewer false alarms|balanced|normal|medium|high|max|maximum|highest|most sensitive)")) { m ->
            tool("settings.set", "key" to "wake_sensitivity", "value" to m.groupValues[1])
        },
        Rule(rx("(?:set|make|change) (?:the |your )?animations? (?:to |level to )?(full|balanced|light|less|more|minimal)")) { m ->
            tool("settings.set", "key" to "animation", "value" to when (m.groupValues[1].lowercase()) { "less", "minimal" -> "light"; "more" -> "full"; else -> m.groupValues[1].lowercase() })
        },
        Rule(rx("(?:set|change) (?:my |the )?(?:weather )?(?:city|home city) to (.+)")) { m -> tool("settings.set", "key" to "weather_city", "value" to m.groupValues[1]) },
        Rule(rx("(?:set|move|change) (?:my |the )?(?:daily |morning )?briefing(?: time)? (?:to|for|at) (.+)")) { m ->
            val t = TimeParser.parseClock(m.groupValues[1].trim().lowercase()) ?: return@Rule ParsedCommand.Clarify("What time should the briefing be? For example: “set my briefing to 7 am”.")
            tool("settings.set", "key" to "briefing_time", "value" to "%02d:%02d".format(t.hour, t.minute))
        },
        Rule(rx("(never|always|only sometimes|sometimes) ask (?:me )?(?:before|for confirmation)(?: .+)?|(?:ask before actions|confirmations?) (?:to )?(never|always|risky)")) { m ->
            val v = m.groupValues[1].ifEmpty { m.groupValues[2] }.lowercase()
            tool("settings.set", "key" to "ask_before", "value" to when { v.startsWith("never") -> "never"; v.startsWith("always") -> "always"; else -> "risky" })
        },
        Rule(rx("(?:set|change|switch) (?:your |the )?(?:personality|persona) to (.+)")) { m -> tool("settings.set", "key" to "persona", "value" to m.groupValues[1]) },
        Rule(rx("(?:open|show|go to|take me to)(?: me)? (?:my |the |your |zed'?s? )?(.+?)(?: screen| page| tab)?")) { m ->
            val raw = m.groupValues[1]
            // Plain "settings" means the phone's Settings app unless it is clearly ZED's.
            if (raw.equals("settings", true) && !Regex("\\b(?:your|zed'?s?|the app'?s)\\b", RegexOption.IGNORE_CASE).containsMatchIn(m.value)) return@Rule null
            screen(raw)?.let { tool("app.show", "screen" to it) }
        },

        // Workflows.
        Rule(rx("(?:run |start |give me |show )?(?:my |the )?(?:morning|daily) (?:briefing|brief|summary)|brief me")) { tool("workflow.run", "name" to "morning briefing") },
        Rule(rx("(?:run|start|execute) (?:the )?workflow (.+)|(?:run|start|execute) (?:the |my )?(.+?) workflow")) { m -> tool("workflow.run", "name" to m.groupValues[1].ifEmpty { m.groupValues[2] }) },

        // Routines and weather.
        Rule(rx("(?:run|start|activate|begin|do) (?:my |the )?(.+?) routine")) { m -> tool("routine.run", "name" to m.groupValues[1]) },
        Rule(rx("(?:what(?:'s| is) the |how(?:'s| is) the |check the |today's )?weather(?: like)?(?: (?:today|now|outside))?(?: (?:in|at|for) (.+?))?(?: (?:today|now))?|(?:is it|will it) (?:going to )?rain(?: today)?(?: in (.+))?")) { m ->
            tool("weather.get", "place" to m.groupValues[1].ifEmpty { m.groupValues[2] })
        },
        Rule(rx("what(?:'s| is) (?:on )?(?:my )?(?:calendar|agenda|schedule)(?: for)? (today|tomorrow)|what(?:'s| is) on (today|tomorrow)|(?:my )?(?:agenda|schedule|calendar) (?:for )?(today|tomorrow)|what do i have (today|tomorrow)")) { m ->
            tool("calendar.today", "date" to m.groupValues.drop(1).first { it.isNotEmpty() }.lowercase())
        },

        // Phone and device control.
        Rule(rx("(?:turn|switch) (on|off) (?:the |my )?(?:flash ?light|torch)|(?:turn|switch) (?:the |my )?(?:flash ?light|torch) (on|off)|(?:flash ?light|torch) (on|off)")) { m ->
            tool("flashlight.set", "on" to onOff(m))
        },
        Rule(rx("(?:turn|switch) (on|off) (?:do not disturb|dnd)|(?:turn|switch) (?:do not disturb|dnd) (on|off)|(enable|disable) (?:do not disturb|dnd)|(?:do not disturb|dnd) (on|off)")) { m ->
            tool("dnd.set", "on" to onOff(m))
        },
        // Smart home (after flashlight and Do Not Disturb).
        Rule(rx("(?:turn|switch) (on|off) (?:the |my |all )?(.+)|(?:turn|switch) (?:the |my |all )?(.+?) (on|off)")) { m ->
            val on = m.groupValues[1].ifEmpty { m.groupValues[4] }.equals("on", true)
            val target = m.groupValues[2].ifEmpty { m.groupValues[3] }
            if (Regex("^(?:wi-?fi|bluetooth|internet|data|airplane mode|location|nfc)$", RegexOption.IGNORE_CASE).matches(target)) null
            else tool("home.control", "target" to target, "action" to if (on) "turn_on" else "turn_off")
        },
        Rule(rx("(?:set|dim|change) (?:the )?(thermostat|temperature|ac|aircon|air conditioner|heating)(?: to)? (\\d{1,2})(?: ?°| degrees)?(?: ?c)?")) { m ->
            tool("home.control", "target" to m.groupValues[1], "action" to "temperature", "value" to m.groupValues[2])
        },
        Rule(rx("(?:set|dim|change) (?:the )?(.+? (?:light|lights|lamp|lamps))(?: to)? (\\d{1,3})(?: ?%| percent)?")) { m ->
            tool("home.control", "target" to m.groupValues[1], "action" to "brightness", "value" to m.groupValues[2])
        },
        Rule(rx("(lock|unlock|open|close) (?:the )?(front door|back door|door|garage(?: door)?|gate|blinds|curtains|shutters|.+? (?:door|lock|gate|garage|blinds|curtains|cover))")) { m ->
            tool("home.secure", "target" to m.groupValues[2], "action" to m.groupValues[1].lowercase())
        },
        Rule(rx("(?:is|are) (?:the )?(.+?) (?:on|off|open|closed|locked|unlocked)|(?:what(?:'s| is) the )?(?:status of|state of) (?:the )?(.+)|(?:what(?:'s| is) the )?(?:temperature|humidity) (?:in|inside) (?:the )?(.+)")) { m ->
            tool("home.status", "target" to m.groupValues.drop(1).first { it.isNotEmpty() })
        },

        Rule(rx("(?:pause|stop)(?: the)?(?: music| song| playback| audio| podcast| video)?")) { tool("media.control", "action" to "pause") },
        Rule(rx("(?:resume|continue|unpause)(?: the)?(?: music| song| playback| podcast)?|play(?: the)? (?:music|song)")) { tool("media.control", "action" to "play") },
        Rule(rx("(?:next|skip)(?: the)?(?: song| track)?|skip (?:this )?(?:song|track)|play (?:the )?next(?: song| track)?")) { tool("media.control", "action" to "next") },
        Rule(rx("(?:previous|last) (?:song|track)|play (?:the )?previous(?: song| track)?|go back a (?:song|track)")) { tool("media.control", "action" to "previous") },
        Rule(rx("play (.+?)(?: on (?:spotify|youtube music|youtube|my phone))?")) { m -> tool("music.play", "query" to m.groupValues[1]) },
        Rule(rx("(?:turn|crank) (?:the )?(?:volume|sound|it) (up|down)|volume (up|down)|(?:turn it|make it) (louder|quieter|softer)")) { m ->
            val dir = listOf(m.groupValues[1], m.groupValues[2], m.groupValues[3]).first { it.isNotEmpty() }.lowercase()
            tool("volume.set", "action" to if (dir == "up" || dir == "louder") "up" else "down")
        },
        Rule(rx("(?:set|change|put) (?:the )?(media |ring(?:er)? |alarm |notification )?volume (?:to |at )?(\\d{1,3})(?: ?%| percent)?")) { m ->
            val stream = m.groupValues[1].trim().lowercase().removeSuffix("er").takeIf { it.isNotEmpty() }
            tool("volume.set", "action" to "set", "level" to m.groupValues[2], "stream" to stream)
        },
        Rule(rx("(mute|unmute)(?: the)?(?: phone| sound| volume| media| music)?")) { m -> tool("volume.set", "action" to m.groupValues[1].lowercase()) },
        Rule(rx("what did i miss|(?:any|do i have any) (?:new )?notifications|(?:read|check|show)(?: me)? (?:my )?notifications|(?:what are|summari[sz]e) my notifications")) { tool("notifications.recent") },
        Rule(rx("(?:how much |what(?:'s| is) (?:my |the )?)?battery(?: level| status| left| percentage)?(?: left)?|phone status|device status|how(?:'s| is) my phone(?: doing)?")) { tool("device.status") },
        Rule(rx("(?:open |show )?(?:the |my )?(wi-?fi|bluetooth|internet|nfc|location|battery|display|airplane mode|notification|app)s? settings")) { m ->
            val panel = m.groupValues[1].lowercase().replace("-", "").replace(" mode", "").let { if (it == "notification") "notifications" else if (it == "app") "apps" else it }
            tool("settings.panel", "panel" to panel)
        },
        Rule(rx("(?:take|snap) a (?:photo|picture|selfie|pic)|open (?:the )?camera|(record|take) a video")) { m -> tool("camera.open", "mode" to if (m.groupValues[1].isNotEmpty()) "video" else "photo") },
        Rule(rx("(?:navigate|directions|take me|drive me|route me) (?:to )?(.+)|how do i get to (.+)")) { m -> tool("maps.navigate", "destination" to m.groupValues[1].ifEmpty { m.groupValues[2] }) },
        Rule(rx("(?:call|phone|ring|dial) (.+?)(?: (?:on|using) (?:speaker|speakerphone))?")) { m ->
            val who = m.groupValues[1]
            if (who.lowercase().startsWith("me ") || who.equals("me", true)) null else tool("phone.call", "who" to who)
        },
        Rule(rx("(?:send (?:a )?(?:text|message|sms) to|text|message|sms) (.+?)(?:,|:| saying| that says| to say| that) (.+)")) { m -> tool("sms.compose", "to" to m.groupValues[1], "message" to m.groupValues[2]) },
        Rule(rx("(?:google|search google for|look up on google) (.+)|(?:open|go to) ((?:https?://)?[\\w-]+(?:\\.[\\w-]+)+(?:/\\S*)?)")) { m -> tool("web.open", "query" to m.groupValues[1].ifEmpty { m.groupValues[2] }) },

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
        Rule(rx("(?:open|launch|start) (?:the )?(?:app )?(.+?)(?: app)?")) { m -> tool("app.open", "name" to m.groupValues[1]) },
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
            |• Settings — “turn off voice responses”, “change your voice to Atlas”, “turn off the wake word”, “open my reminders”
            |• Deleting — “delete task buy milk”, “delete the reminder about rent”, “delete my dentist appointment”, “remove notes.txt from knowledge”
            |• Teaching — “pronounce Cebu as seh-boo”, “from now on keep answers short”, “when I say lights out, I mean turn off the lights”
            |• Research — “research …” (searches the web; only your question leaves the phone)
        """.trimMargin()
    }
}
