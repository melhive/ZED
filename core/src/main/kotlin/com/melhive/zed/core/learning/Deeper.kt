package com.melhive.zed.core.learning

import com.melhive.zed.core.brain.BrainRecall
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A labelled link between two things ZED knows: (Ana) —sister→ (me). */
data class Relation(val subject: String, val relation: String, val target: String) {
    /** Same link regardless of capitalisation. */
    val key: String get() = "${subject.lowercase()}|${relation.lowercase()}|${target.lowercase()}"
}

/** Reads labelled relationships out of the user's memories. Exact phrasings only; the model adds the rest. */
object RelationPatterns {
    const val ME = "me"
    private const val RELATIONS = "sister|brother|mom|mum|mother|dad|father|wife|husband|partner|girlfriend|boyfriend|son|daughter|boss|best friend|friend|dentist|doctor|grandma|grandmother|grandpa|grandfather|aunt|uncle|cousin|manager|teacher|landlord|dog|cat|neighbou?r|colleague"
    private const val NAME = "((?:Dr\\.? )?[A-Z][\\p{L}'-]+(?: [A-Z][\\p{L}'-]+)?)"
    private const val PLACE = "([A-Z][\\p{L}'-]+(?: [A-Z][\\p{L}'-]+){0,2})"
    private val SAME = mapOf("mum" to "mom", "mother" to "mom", "father" to "dad", "grandmother" to "grandma", "grandfather" to "grandpa", "neighbor" to "neighbour")
    private fun rel(r: String) = r.lowercase().let { SAME[it] ?: it }

    private val rules: List<Pair<Regex, (MatchResult) -> List<Relation>>> = listOf(
        Regex("\\b(?i:my ($RELATIONS)(?:'s name is| is called| is named| is)) $NAME(?: (?i:in|from) $PLACE)?") to { m ->
            listOfNotNull(Relation(m.groupValues[2], rel(m.groupValues[1]), ME), m.groupValues[3].takeIf { it.isNotEmpty() }?.let { Relation(m.groupValues[2], "in", it) })
        },
        Regex("\\b$NAME is my ((?i:$RELATIONS))\\b(?: (?i:in|from) $PLACE)?") to { m ->
            listOfNotNull(Relation(m.groupValues[1], rel(m.groupValues[2]), ME), m.groupValues[3].takeIf { it.isNotEmpty() }?.let { Relation(m.groupValues[1], "in", it) })
        },
        Regex("\\b(?i:i live in) $PLACE") to { m -> listOf(Relation(ME, "lives in", m.groupValues[1])) },
        Regex("\\b(?i:i work (?:at|for)) $PLACE") to { m -> listOf(Relation(ME, "works at", m.groupValues[1])) },
        Regex("\\b$NAME (?i:lives in) $PLACE") to { m -> listOf(Relation(m.groupValues[1], "lives in", m.groupValues[2])) },
        Regex("\\b$NAME (?i:works (?:at|for)) $PLACE") to { m -> listOf(Relation(m.groupValues[1], "works at", m.groupValues[2])) },
        Regex("\\b$NAME(?:'s) (?i:birthday is(?: on)?) ([A-Z][a-z]+ \\d{1,2}|\\d{1,2} [A-Z][a-z]+)") to { m -> listOf(Relation(m.groupValues[1], "birthday", m.groupValues[2])) },
        Regex("\\b$NAME (?i:is allergic to) ([\\p{L} ]{2,30}?)(?=[.,!]|$)") to { m -> listOf(Relation(m.groupValues[1], "allergic to", m.groupValues[2].trim())) },
        Regex("\\b(?i:i(?:'m| am) allergic to) ([\\p{L} ]{2,30}?)(?=[.,!]|$)") to { m -> listOf(Relation(ME, "allergic to", m.groupValues[1].trim())) },
    )

    private val NOT_NAMES = setOf("I", "My", "The", "This", "That", "He", "She", "They", "We", "It")

    fun extract(fact: String): List<Relation> = rules.flatMap { (rx, build) -> rx.findAll(fact).flatMap { build(it) } }
        .filter { it.subject !in NOT_NAMES && it.target !in NOT_NAMES }
        .distinctBy { it.key }

    /** Asks the model for relationships in a batch of memories, and reads its "A | relation | B" lines strictly. */
    const val PROMPT_SYSTEM = "You extract relationships between people, places and things from short notes. You never guess."

    fun prompt(facts: List<String>): String = buildString {
        appendLine("From these notes about the user, list relationships as lines \"A | relation | B\" (relation in 1–3 words, e.g. sister, works at, lives in, dentist, allergic to). Use \"me\" for the user. Only what the notes clearly say. If none, write \"none\".")
        appendLine(); facts.take(40).forEachIndexed { i, f -> appendLine("${i + 1}. $f") }
    }

    fun parse(output: String): List<Relation> = output.lineSequence()
        .map { it.trim().removePrefix("-").removePrefix("*").replace(Regex("^\\d+[.)]\\s*"), "").trim() }
        .mapNotNull { line ->
            val p = line.split('|').map { it.trim().trim('"', '.') }
            if (p.size != 3 || p.any { it.isEmpty() || it.length > 40 }) return@mapNotNull null
            if (p[1].split(' ').size > 3) return@mapNotNull null
            val norm = { s: String -> if (s.equals("user", true) || s.equals("the user", true) || s.equals("I", true)) ME else s }
            Relation(norm(p[0]), p[1].lowercase(), norm(p[2]))
        }
        .filter { it.subject != it.target }
        .distinctBy { it.key }.take(12).toList()
}

/** "That's wrong" — and, when given, what is right. */
object Corrections {
    sealed interface Found {
        /** The user said ZED was wrong but not what's right: ask. */
        data object WrongOnly : Found
        data class WithFix(val fix: String) : Found
    }

    private val WRONG = Regex(
        "^(?:no[,.!]*\\s+)?(?:that'?s|that is|this is|it'?s|you'?re|you are|your answer is|nope,?)\\s+(?:completely |totally |not |just )?(?:wrong|incorrect|not right|not true|not correct|mistaken|false)\\b[.!,:;-]*\\s*(.*)$|" +
            "^(?:wrong|incorrect|not true|nope)[.!,:;-]*\\s*(.*)$",
        RegexOption.IGNORE_CASE,
    )
    private val LEAD = Regex("^(?:it'?s|it is|actually|the (?:right|correct) answer is|the answer is|rather|i said|i meant)[,:]?\\s+", RegexOption.IGNORE_CASE)

    fun detect(text: String): Found? {
        val m = WRONG.matchEntire(text.trim()) ?: return null
        val rest = m.groupValues.drop(1).firstOrNull { it.isNotBlank() }.orEmpty().trim()
        val fix = LEAD.replace(rest, "").trim().trimEnd('.', '!')
        return if (fix.length >= 2) Found.WithFix(fix.replaceFirstChar { it.uppercase() }) else Found.WrongOnly
    }

    /** The lesson saved to memory, so recall brings it back next time the topic comes up. */
    fun lesson(question: String, wrong: String, fix: String): String {
        val q = question.trim().replace(Regex("\\s+"), " ").take(120)
        val w = wrong.trim().replace(Regex("\\s+"), " ").take(90)
        return "Correction from the user — about “$q”: $fix" + if (w.isNotEmpty()) " (not “$w”)." else "."
    }
}

/** Something ZED noticed while "dreaming", offered once and never pushed. */
data class Insight(val key: String, val text: String, val action: Action, val payload: String) {
    enum class Action { REMIND, ROUTINE }
}

/** Patterns in what the user says and does that are worth one gentle suggestion. */
object Insights {
    /**
     * Ongoing matters the user keeps coming back to: a phrase ("my thesis defense", "the car",
     * "Ana's birthday") brought up on at least [minDays] days and [minTimes] times in the last
     * [window] days, where at least one of those messages shows it matters — something to do
     * ("need to", "don't forget"), a date or deadline, an event (exam, interview, trip, bill) or
     * a worry. A word merely repeated is never enough. Skips what a reminder or task already
     * covers ([covered]) and anything offered before ([seen]).
     */
    fun topics(messages: List<Pair<String, Long>>, now: Long, covered: List<String>, seen: Set<String>, zone: ZoneId = ZoneId.systemDefault(),
               minTimes: Int = 3, minDays: Int = 2, window: Int = 14): List<Insight> {
        val since = now - window * 86_400_000L
        val coveredKeys = covered.flatMap { BrainRecall.keys(it) }.toSet()
        class Seen(val phrases: MutableMap<String, Int> = HashMap(), val days: MutableSet<LocalDate> = HashSet(), var mentions: Int = 0, val signals: MutableSet<Topics.Signal> = HashSet(), var signalled: Int = 0)
        val groups = HashMap<String, Seen>()
        for ((text, at) in messages) {
            if (at < since || Topics.isCommand(text)) continue
            val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
            val signals = Topics.signals(text)
            for (phrase in Topics.phrases(text)) {
                val g = groups.getOrPut(phrase.key) { Seen() }
                g.phrases.merge(phrase.text, 1, Int::plus)
                g.days += day; g.mentions++
                if (signals.isNotEmpty()) { g.signals += signals; g.signalled++ }
            }
        }
        return groups.filter { (k, g) ->
            g.mentions >= minTimes && g.days.size >= minDays && g.signalled >= 1 && "topic:$k" !in seen &&
                BrainRecall.keys(k).none { it in coveredKeys }
        }.entries.sortedByDescending { (_, g) -> g.signalled * 3 + g.signals.size * 2 + g.days.size + g.mentions }.take(1)
            .map { (k, g) ->
                val said = g.phrases.maxByOrNull { it.value }!!.key
                Insight("topic:$k", Topics.sentence(said, g.days.size, g.signals), Insight.Action.REMIND, said)
            }
    }

    /** One action done at about the same hour on several days ("Do Not Disturb on, around 10 PM"). */
    data class Done(val tool: String, val args: String, val at: Long)

    private val HABIT_TOOLS = setOf("dnd.set", "flashlight.set", "volume.set", "music.play", "alarm.set", "home.control", "app.open", "media.control", "maps.navigate")

    fun habits(actions: List<Done>, now: Long, seen: Set<String>, zone: ZoneId = ZoneId.systemDefault(), minDays: Int = 3, window: Int = 21): List<Insight> {
        val since = now - window * 86_400_000L
        val groups = actions.filter { it.at >= since && it.tool in HABIT_TOOLS }.groupBy { it.tool + "|" + it.args }
        for ((key, list) in groups.entries.sortedByDescending { it.value.size }) {
            val times = list.map { Instant.ofEpochMilli(it.at).atZone(zone) }
            val days = times.map { it.toLocalDate() }.distinct()
            if (days.size < minDays) continue
            // Most of them within an hour of the typical time.
            val minutes = times.map { it.hour * 60 + it.minute }.sorted()
            val median = minutes[minutes.size / 2]
            val close = times.filter { kotlin.math.abs(it.hour * 60 + it.minute - median) <= 60 }.map { it.toLocalDate() }.distinct()
            if (close.size < minDays || "habit:$key" in seen) continue
            val h = median / 60; val m = median % 60
            val clock = "%d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")
            return listOf(Insight("habit:$key", "You ${describe(list.first())} around $clock most days — want to make it part of a routine?", Insight.Action.ROUTINE, key))
        }
        return emptyList()
    }

    private fun describe(d: Done): String {
        val on = d.args.contains("on=true")
        return when (d.tool) {
            "dnd.set" -> "turn ${if (on) "on" else "off"} Do Not Disturb"
            "flashlight.set" -> "turn ${if (on) "on" else "off"} the flashlight"
            "music.play" -> "play music"
            "alarm.set" -> "set an alarm"
            "home.control" -> "control ${Regex("target=([^,}]+)").find(d.args)?.groupValues?.get(1) ?: "a device"}"
            "app.open" -> "open ${Regex("name=([^,}]+)").find(d.args)?.groupValues?.get(1) ?: "an app"}"
            "volume.set" -> "change the volume"
            "maps.navigate" -> "start navigation"
            else -> "use ${d.tool}"
        }
    }
}

/** Memory housekeeping done while "dreaming". */
object DreamLogic {
    data class Fact(val id: Long, val text: String, val pinned: Boolean, val confirmed: Boolean, val uses: Int, val createdAt: Long)

    /** Groups of duplicate memories: which one to keep and which to remove (pinned, then confirmed, then most used, then newest wins). */
    fun duplicates(facts: List<Fact>): List<Pair<Long, List<Long>>> {
        val used = HashSet<Long>()
        val out = mutableListOf<Pair<Long, List<Long>>>()
        // Each memory's words are worked out once (comparing every pair this way stays fast with hundreds of memories).
        val keys = facts.associate { it.id to BrainRecall.keys(it.text) }
        val plain = facts.associate { it.id to it.text.trim().trimEnd('.').lowercase() }
        fun same(a: Fact, b: Fact): Boolean {
            if (plain[a.id] == plain[b.id]) return true
            val ka = keys.getValue(a.id); val kb = keys.getValue(b.id)
            if (ka.isEmpty() || kb.isEmpty()) return false
            return ka.intersect(kb).size.toDouble() / ka.union(kb).size >= 0.6
        }
        for (f in facts) {
            if (f.id in used) continue
            val group = facts.filter { it.id !in used && (it.id == f.id || same(it, f)) }
            if (group.size < 2) continue
            val keep = group.sortedWith(compareByDescending<Fact> { it.pinned }.thenByDescending { it.confirmed }.thenByDescending { it.uses }.thenByDescending { it.createdAt }).first()
            group.forEach { used += it.id }
            out += keep.id to group.map { it.id }.filter { it != keep.id }
        }
        return out
    }
}

/** When ZED may speak up unasked: rarely, and only when it helps. */
object Hints {
    /** At most one hint every [gapHours] hours and [perDay] a day. */
    fun allowed(sent: List<Long>, now: Long, gapHours: Int = 3, perDay: Int = 3): Boolean {
        if (sent.any { now - it < gapHours * 3_600_000L }) return false
        return sent.count { now - it < 86_400_000L } < perDay
    }

    /**
     * "Time to get going": an event with a place, starting in 30–50 minutes,
     * not mentioned before. Returns the message, or null.
     */
    fun leaveNow(title: String, start: Long, location: String?, now: Long, already: Set<String>, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (location.isNullOrBlank()) return null
        val minutes = (start - now) / 60_000
        if (minutes !in 30..50 || "$title@$start" in already) return null
        val t = Instant.ofEpochMilli(start).atZone(zone)
        val clock = "%d:%02d %s".format(if (t.hour % 12 == 0) 12 else t.hour % 12, t.minute, if (t.hour < 12) "AM" else "PM")
        return "“$title” is at $clock at ${location.trim()} — time to get going."
    }
}

/** What makes a subject in the user's messages an ongoing matter rather than a word they happen to repeat. */
object Topics {
    enum class Signal { TODO, TIME, EVENT, WORRY }
    data class Phrase(val key: String, val text: String)

    private val TODO = Regex("\\b(need to|needs to|have to|has to|must|should|gotta|got to|don'?t forget|do not forget|remember to|supposed to|still haven'?t|haven'?t yet|plan to|planning|going to)\\b", RegexOption.IGNORE_CASE)
    private const val DAYS = "monday|tuesday|wednesday|thursday|friday|saturday|sunday|weekend"
    private val TIME = Regex("\\b(deadline|due|overdue|tomorrow|tonight|next (week|month|year|$DAYS)|this ($DAYS|week|month)|on ($DAYS)|by ($DAYS|tomorrow|the \\d+)|in \\d+ days|(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]* \\d{1,2})\\b", RegexOption.IGNORE_CASE)
    private val EVENTS = setOf("exam", "exams", "test", "quiz", "appointment", "interview", "meeting", "birthday", "anniversary", "trip", "flight", "vacation", "bill", "bills",
        "rent", "payment", "loan", "tax", "taxes", "project", "presentation", "thesis", "defense", "wedding", "surgery", "checkup", "check-up", "dentist", "doctor", "application",
        "visa", "passport", "renewal", "enrollment", "enrolment", "report", "assignment", "homework", "deadline", "move", "moving", "repair", "insurance", "registration", "license", "licence", "party", "event", "reunion")
    private val WORRY = Regex("\\b(worried|worry|worrying|anxious|nervous|stressed|stress|problem|issue|broken|broke down|sick|ill|hurts?|pain|struggling|scared|afraid|late|behind|can'?t afford|urgent)\\b", RegexOption.IGNORE_CASE)
    /** Requests to ZED itself ("turn on the lights", "play music") are not topics of the user's life. */
    private val COMMAND = Regex("^(hey |ok |okay )?(zed[,!.]? )?(please )?(turn|switch|set|play|open|call|text|send|stop|pause|resume|start|show|launch|navigate|take|increase|decrease|raise|lower|mute|unmute|what('?s| is) the (time|weather)|what time|read|search|look up|find|index|delete|remove|forget|cancel|move|change|go to|remember that)\\b", RegexOption.IGNORE_CASE)
    private val DETERMINERS = setOf("my", "the", "our", "his", "her", "their", "this", "that")
    /** Words that end a phrase: function words, time words, verbs that follow a noun. */
    private val STOP = setOf("a", "an", "and", "or", "but", "so", "to", "for", "of", "in", "on", "at", "by", "with", "about", "from", "is", "are", "was", "were", "be", "been",
        "it", "its", "i", "me", "you", "we", "they", "he", "she", "this", "that", "these", "those", "there", "here", "is", "isn't", "has", "have", "had", "do", "does", "did",
        "will", "would", "can", "could", "should", "might", "must", "not", "no", "yes", "just", "still", "again", "also", "too", "very", "really", "now", "then", "today",
        "tomorrow", "tonight", "yesterday", "week", "weekend", "month", "year", "day", "morning", "afternoon", "evening", "night", "soon", "later", "time", "next", "last",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "keeps", "is", "needs", "need", "went", "got", "gets", "was", "hurts", "hurt", "broke",
        "same", "other", "one", "thing", "things", "stuff", "way", "lot", "first", "whole", "only", "best", "new", "old", "big", "little", "good", "bad", "other")
    /** Phone, ZED and everyday chatter — never an "ongoing matter". */
    private val TRIVIAL = setOf("phone", "app", "zed", "weather", "music", "song", "alarm", "timer", "reminder", "reminders", "light", "lights", "lamp", "volume",
        "flashlight", "battery", "wifi", "screen", "answer", "question", "name", "sister", "brother", "mom", "dad", "friend", "house", "home", "room", "food", "news",
        "movie", "video", "game", "end", "rest", "point", "fact", "idea", "problem", "issue", "pain")

    /** Words ending in -ing that name things, not actions. */
    private val ING_NOUNS = setOf("wedding", "meeting", "building", "training", "hearing", "booking", "funding", "housing", "painting", "ceiling", "parking", "wiring", "plumbing", "clothing", "flooring", "screening", "christening", "offering")

    fun isCommand(text: String) = COMMAND.containsMatchIn(text.trim())

    fun signals(text: String): Set<Signal> = buildSet {
        if (TODO.containsMatchIn(text)) add(Signal.TODO)
        if (TIME.containsMatchIn(text)) add(Signal.TIME)
        if (words(text).any { it in EVENTS }) add(Signal.EVENT)
        if (WORRY.containsMatchIn(text)) add(Signal.WORRY)
    }

    private fun words(text: String) = Regex("[\\p{L}][\\p{L}'-]*").findAll(text.lowercase().replace('’', '\'')).map { it.value.trim('\'', '-') }.toList()

    private fun usable(w: String) = w.length >= 3 && !w.endsWith("'s") && w !in STOP && w !in DETERMINERS && !w.endsWith("ly") && (!w.endsWith("ing") || w in ING_NOUNS) && w.none { it.isDigit() }

    /** "my thesis defense", "the car", "my sister's wedding", or an event word on its own ("dentist"). Keyed by the first noun. */
    fun phrases(text: String): List<Phrase> {
        val w = words(text)
        val out = LinkedHashMap<String, Phrase>()
        var i = 0
        while (i < w.size) {
            var lead: String? = when {
                w[i] in DETERMINERS -> if (w[i] == "this" || w[i] == "that") "the" else w[i]
                possessive(w[i]) -> w[i]
                else -> null
            }
            var j = i + 1
            while (lead != null && j < w.size && possessive(w[j])) { lead += " " + w[j]; j++ }
            if (lead != null && j < w.size && usable(w[j])) {
                val nouns = mutableListOf(w[j])
                if (j + 1 < w.size && usable(w[j + 1])) nouns += w[j + 1]
                val key = singular(nouns.first())
                if (key !in TRIVIAL) out.putIfAbsent(key, Phrase(key, "$lead ${nouns.joinToString(" ")}"))
                i = j + nouns.size
                continue
            }
            if (w[i] in EVENTS) singular(w[i]).let { if (it !in TRIVIAL) out.putIfAbsent(it, Phrase(it, "the ${w[i]}")) }
            i++
        }
        return out.values.toList()
    }

    private fun possessive(w: String) = w.endsWith("'s") && w.length > 3 && w.dropLast(2) !in STOP

    private fun singular(w: String) = if (w.length > 4 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("is") && !w.endsWith("us")) w.dropLast(1) else w

    /** "We've talked about your thesis defense on 3 different days, and it sounds like there's a deadline. Want a reminder?" */
    fun sentence(said: String, days: Int, signals: Set<Signal>): String {
        val subject = said.replaceFirst(Regex("^my "), "your ").replaceFirst(Regex("^our "), "our ")
        val why = when {
            Signal.TIME in signals -> " and there's a date coming up"
            Signal.WORRY in signals -> " and it sounds like it's been on your mind"
            Signal.TODO in signals -> " and it sounds like something you still need to do"
            else -> ""
        }
        return "We've talked about $subject on $days different days$why. Want me to remind you about it?"
    }

    /** For the on-device model: the matters that genuinely count, not words that recur. */
    const val PROMPT_SYSTEM = "You help a personal assistant notice what matters to its user. Read the user's recent messages and name at most 2 ongoing matters that are " +
        "important to them: plans, deadlines, appointments, problems, worries, commitments, or people's events. Ignore small talk, questions to the assistant, phone commands, and " +
        "words that just repeat. Answer one per line as: topic | short reason in second person. Write NONE if nothing qualifies."

    fun prompt(messages: List<Pair<String, String>>): String =
        "Recent messages (day: text):\n" + messages.takeLast(60).joinToString("\n") { (day, t) -> "$day: ${t.replace('\n', ' ').take(160)}" }

    /**
     * Parses the model's lines and keeps only topics grounded in the messages: one of the
     * topic's words must appear in messages on at least [minDays] days.
     */
    fun parse(output: String, messages: List<Pair<String, Long>>, zone: ZoneId = ZoneId.systemDefault(), minDays: Int = 2): List<Pair<String, String>> =
        output.lines().mapNotNull { line ->
            val parts = line.trim().trimStart('-', '*', '•', ' ').split('|').map { it.trim() }
            if (parts.size < 2 || parts[0].isBlank() || parts[0].equals("none", true)) return@mapNotNull null
            val topic = parts[0].trim('"', '“', '”', '.').take(60)
            val reason = parts[1].trim().trimEnd('.').take(140)
            val keys = words(topic).filter { usable(it) && it !in TRIVIAL }.map { singular(it) }
            if (keys.isEmpty() || reason.isBlank()) return@mapNotNull null
            val days = messages.filter { (t, _) -> words(t).map { singular(it) }.any { it in keys } }
                .map { Instant.ofEpochMilli(it.second).atZone(zone).toLocalDate() }.distinct().size
            if (days < minDays) null else topic to reason
        }.take(2)
}
