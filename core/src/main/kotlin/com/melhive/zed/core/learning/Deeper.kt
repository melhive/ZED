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
    private val TRIVIAL = setOf("thank", "thanks", "okay", "good", "morning", "night", "time", "what", "know", "think", "really", "something", "thing",
        "people", "right", "today", "yes", "yeah", "weather", "alarm", "timer", "music", "play", "call", "text", "message", "open")

    /**
     * Topics mentioned on at least [minDays] different days and [minTimes]
     * times in the last [window] days, not already covered by a reminder or
     * task ([covered]) or offered before ([seen]).
     */
    fun topics(messages: List<Pair<String, Long>>, now: Long, covered: List<String>, seen: Set<String>, zone: ZoneId = ZoneId.systemDefault(),
               minTimes: Int = 3, minDays: Int = 2, window: Int = 14): List<Insight> {
        val since = now - window * 86_400_000L
        val coveredKeys = covered.flatMap { BrainRecall.keys(it) }.toSet()
        val counts = HashMap<String, MutableList<LocalDate>>()
        for ((text, at) in messages) {
            if (at < since) continue
            val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
            for (k in BrainRecall.keys(text)) if (k.length >= 4 && k !in TRIVIAL) counts.getOrPut(k) { mutableListOf() } += day
        }
        return counts.filter { (k, days) -> days.size >= minTimes && days.distinct().size >= minDays && k !in coveredKeys && "topic:$k" !in seen }
            .entries.sortedByDescending { it.value.size }.take(1)
            .map { (k, days) -> Insight("topic:$k", "You've mentioned the $k ${days.size} times lately — want me to remind you about it?", Insight.Action.REMIND, k) }
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
        for (f in facts) {
            if (f.id in used) continue
            val group = facts.filter { it.id !in used && (it.id == f.id || LearnedFilter.isDuplicate(it.text, f.text)) }
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
