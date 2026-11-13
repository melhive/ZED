package com.melhive.zed.core.understanding

import com.melhive.zed.core.brain.BrainRecall
import com.melhive.zed.core.tools.ToolSpec
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A memory as recall sees it. */
data class MemoryFact(val id: Long, val text: String, val pinned: Boolean = false, val strength: Float = 0.5f)

/**
 * Chooses the memories a question is connected to, the way the Live Brain
 * links them: memories that share a meaningful word with the question first,
 * then memories linked to those through a shared idea (two steps away), then
 * a light preference for pinned and often-used ones. Recency alone never
 * gets a memory in.
 */
object MemoryRanker {
    private val SYNONYMS = mapOf(
        "mom" to "mother", "mum" to "mother", "mama" to "mother", "dad" to "father", "papa" to "father",
        "bday" to "birthday", "born" to "birthday", "doc" to "doctor", "physician" to "doctor",
        "kid" to "child", "kids" to "child", "son" to "child", "daughter" to "child", "wifey" to "wife", "hubby" to "husband",
        "car" to "vehicle", "auto" to "vehicle", "job" to "work", "office" to "work", "boss" to "work",
        "allergic" to "allergy", "allergies" to "allergy", "meds" to "medicine", "medication" to "medicine", "pills" to "medicine",
    )

    fun keys(text: String): Set<String> = BrainRecall.keys(text).map { SYNONYMS[it] ?: it }.toSet()

    data class Ranked(val fact: MemoryFact, val score: Float)

    /**
     * The memories connected to [question] (and, more weakly, to the [previous] message), best first.
     * [bridges] also adds memories that only share an idea with a matched one (good for the Live
     * Brain; off for answers, where they drag unrelated old topics into the conversation).
     */
    fun rank(question: String, facts: List<MemoryFact>, previous: String? = null, limit: Int = 6, bridges: Boolean = true): List<Ranked> {
        val q = keys(question)
        val prev = previous?.let { keys(it) - q }.orEmpty()
        if (q.isEmpty() && prev.isEmpty()) return emptyList()
        val factKeys = facts.associate { it.id to keys(it.text) }
        val direct = HashMap<Long, Float>()
        for (f in facts) {
            val k = factKeys.getValue(f.id)
            val score = 3f * k.count { it in q } + 1f * k.count { it in prev }
            if (score > 0f) direct[f.id] = score
        }
        // Two steps: memories that share an idea with a directly matched memory.
        val bridge = direct.keys.flatMap { factKeys.getValue(it) }.toSet() - q
        val scored = facts.mapNotNull { f ->
            val k = factKeys.getValue(f.id)
            var s = direct[f.id] ?: 0f
            if (s == 0f) s = if (bridges && direct.isNotEmpty()) 1f * k.count { it in bridge }.coerceAtMost(2) else 0f
            if (s <= 0f) return@mapNotNull null
            Ranked(f, s + (if (f.pinned) 0.5f else 0f) + 0.5f * f.strength)
        }
        return scored.sortedByDescending { it.score }.take(limit)
    }
}

/**
 * Everything ZED knows about the moment, in a few short lines attached to the
 * user's message (never to the system prompt, so an on-device model keeps the
 * work it has already done on the conversation).
 */
data class Awareness(
    val now: ZonedDateTime,
    val place: String? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val online: Boolean? = null,
    /** Next calendar event: title and start (epoch millis). */
    val nextEvent: Pair<String, Long>? = null,
    /** Running timers, alarms and reminders: label and end (epoch millis). */
    val countdowns: List<Pair<String, Long>> = emptyList(),
    /** The conversation's state ("the person just mentioned is Ana"). */
    val dialogue: String? = null,
    /** A compact profile: the user's name and a few pinned facts. */
    val profile: List<String> = emptyList(),
    /** Memories connected to this message. */
    val memories: List<String> = emptyList(),
    /** Exact results ZED computed for this message (sums, dates, durations). */
    val computed: List<String> = emptyList(),
    /** Tools the model may use for this message (on-device models). */
    val tools: List<ToolSpec> = emptyList(),
    /** Excerpts from the user's documents. */
    val knowledge: List<String> = emptyList(),
    /** Think twice: the plan made for this answer. */
    val plan: String? = null,
) {
    fun render(): String = buildString {
        val time = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, h:mm a", Locale.ENGLISH).format(now)
        append("[Now: ").append(time)
        place?.let { append(" · in ").append(it) }
        batteryPercent?.let { append(" · battery ").append(it).append('%'); if (charging == true) append(" charging") }
        online?.let { append(if (it) " · online" else " · offline") }
        append("]")
        val soon = buildList {
            nextEvent?.let { (title, at) -> add("next event “$title” at ${clock(at)} (${until(at)})") }
            countdowns.take(3).forEach { (label, at) -> add("$label ends ${clock(at)} (${until(at)})") }
        }
        if (soon.isNotEmpty()) append("\n[Coming up: ").append(soon.joinToString(" · ")).append("]")
        dialogue?.let { append("\n[Conversation: ").append(it).append("]") }
        if (profile.isNotEmpty()) append("\n[About the user: ").append(profile.joinToString("; ")).append("]")
        if (memories.isNotEmpty()) {
            append("\n[Related memories:")
            memories.forEach { append("\n- ").append(it) }
            append("]")
        }
        if (computed.isNotEmpty()) append("\n[Computed exactly — use these: ").append(computed.joinToString("; ")).append("]")
        if (knowledge.isNotEmpty()) {
            append("\n").append(com.melhive.zed.core.intelligence.PromptBuilder.KNOWLEDGE_HEADER)
            knowledge.forEachIndexed { i, k -> append("\n[${i + 1}] ").append(k) }
        }
        plan?.let { append("\n[Your plan for this answer — follow it, check each point:\n").append(it.trim()).append("]") }
        if (tools.isNotEmpty()) {
            append("\n[Tools for this message (? = optional):")
            tools.forEach { t -> append("\n- ").append(t.name).append("(").append(t.params.joinToString(", ") { p -> p.name + if (p.required) "" else "?" }).append(") — ").append(t.title) }
            append("]")
        }
    }

    private fun clock(at: Long): String = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH).format(java.time.Instant.ofEpochMilli(at).atZone(now.zone))

    private fun until(at: Long): String {
        val secs = Duration.between(now.toInstant(), java.time.Instant.ofEpochMilli(at)).seconds
        if (secs <= 0) return "now"
        val h = secs / 3600; val m = (secs % 3600) / 60
        return "in " + listOfNotNull(h.takeIf { it > 0 }?.let { "$it h" }, m.takeIf { it > 0 || h == 0L }?.let { "$it min" }).joinToString(" ")
    }

    companion object {
        /** A readable place from a time zone ("Asia/Manila" → "Manila"). */
        fun placeFromZone(zone: String): String? = zone.substringAfterLast('/').takeIf { it.isNotBlank() && zone.contains('/') }?.replace('_', ' ')
    }
}

/**
 * Sums, percentages, conversions and date questions found inside a longer
 * message ("so if I spend 15% of 2400 on food…"), computed exactly so the
 * model can use the result instead of doing arithmetic itself.
 */
object Computations {
    private val PERCENT_OF = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:%|percent) of \\$?(\\d[\\d,]*(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
    private val ARITH = Regex("(?<![\\w.])(\\d[\\d,]*(?:\\.\\d+)?(?:\\s*[-+*/x×÷^]\\s*\\d[\\d,]*(?:\\.\\d+)?)+)(?![\\w.])")

    fun find(text: String, today: java.time.LocalDate): List<String> {
        val out = mutableListOf<String>()
        for (m in PERCENT_OF.findAll(text)) {
            val pct = m.groupValues[1].toDouble(); val base = m.groupValues[2].replace(",", "").toDouble()
            out += "${m.groupValues[1]}% of ${m.groupValues[2]} = ${com.melhive.zed.core.compute.Calculator.format(pct * base / 100)}"
        }
        for (m in ARITH.findAll(text)) {
            val expr = m.groupValues[1]
            // Dates (2026-11-12) and times (5:30) aren't sums.
            if (Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(expr) || Regex("^\\d{1,2}-\\d{1,2}$").matches(expr) && !expr.contains(' ')) continue
            val norm = com.melhive.zed.core.compute.Calculator.normalize(expr) ?: continue
            runCatching { out += "$expr = ${com.melhive.zed.core.compute.Calculator.format(com.melhive.zed.core.compute.Calculator.evaluate(norm))}" }
        }
        com.melhive.zed.core.compute.DateMath.tryAnswer(text, today)?.let { out += it }
        return out.distinct().take(4)
    }
}

/**
 * Replies that need no thinking at all ("thanks", "ok", "bye"), answered at
 * once instead of waking the model. Only exact, short social messages.
 */
object QuickReplies {
    private val THANKS = Regex("^(?:(?:ok(?:ay)?|great|perfect|cool|nice|awesome)[,!.]?\\s+)?(?:thanks?(?: you)?(?: so much| a lot| very much)?|thank u|thx|ty|cheers|much appreciated)(?: zed)?$", RegexOption.IGNORE_CASE)
    private val ACK = Regex("^(?:ok(?:ay)?|k|got it|alright|all right|cool|nice|great|perfect|awesome|good|sounds good|noted|fine|sure)(?: zed)?$", RegexOption.IGNORE_CASE)
    private val BYE = Regex("^(?:bye|goodbye|good bye|see you|see ya|later|talk later|that'?s all|that'?s it|nothing else|never ?mind)(?: zed)?$", RegexOption.IGNORE_CASE)
    private val NAME = Regex("^(?:what(?:'s| is) my name|who am i|do you know my name|do you know who i am)$", RegexOption.IGNORE_CASE)

    fun reply(text: String, userName: String?): String? {
        val t = text.trim().trimEnd('.', '!', '?').trim()
        return when {
            THANKS.matches(t) -> listOf("You're welcome!", "Anytime.", "Happy to help.")[t.length % 3]
            ACK.matches(t) -> "Okay."
            BYE.matches(t) -> "Okay — I'm here whenever you need me."
            NAME.matches(t) -> userName?.let { "You're $it." } ?: "You haven't told me your name yet. Say “my name is …” and I'll remember it."
            else -> null
        }
    }
}
