package com.melhive.zed.core.learning

import com.melhive.zed.core.brain.BrainRecall

/** Something ZED picked up about the user from ordinary conversation. */
data class LearnedFact(val text: String, val confidence: Float, val from: Source) {
    enum class Source { PATTERN, MODEL }
}

/**
 * Exact phrasings that state a lasting fact about the user ("my sister's name
 * is Ana", "I'm allergic to peanuts", "I work at Accenture"). Precise, cheap,
 * and needs no model, so it runs after every conversation.
 */
object FactPatterns {
    private val I = setOf(RegexOption.IGNORE_CASE)
    private const val RELATIONS = "sister|brother|mom|mum|mother|dad|father|wife|husband|partner|girlfriend|boyfriend|son|daughter|boss|best friend|friend|dentist|doctor|grandma|grandmother|grandpa|grandfather|aunt|uncle|cousin|manager|teacher|landlord|dog|cat|neighbou?r"
    private const val NAME = "((?:Dr\\.? )?[A-Z][\\p{L}'-]+(?: [A-Z][\\p{L}'-]+)?)"
    private const val THING = "([\\p{L}0-9][\\p{L}0-9 '&.-]{1,48}?)"

    private val rules: List<Pair<Regex, (MatchResult) -> String?>> = listOf(
        Regex("\\b(?i:my ($RELATIONS)(?:'s name is| is called| is named| is)) $NAME\\b") to { m ->
            if (m.groupValues[2].first().isUpperCase()) "My ${m.groupValues[1].lowercase()}'s name is ${m.groupValues[2]}." else null
        },
        Regex("\\b$NAME is my ($RELATIONS)\\b") to { m -> "My ${m.groupValues[2].lowercase()}'s name is ${m.groupValues[1]}." },
        Regex("\\bi(?:'m| am) (?:very |really |severely )?allergic to $THING(?=[.,!;]|$| and | but )", I) to { m -> "I'm allergic to ${m.groupValues[1].trim()}." },
        Regex("\\bi (?:really |absolutely )?(love|like|enjoy|prefer|hate|dislike|can't stand|don't like|do not like) $THING(?=[.,!;]|$| but | because | so )", I) to { m ->
            val what = m.groupValues[2].trim()
            if (what.split(' ').first().lowercase() in setOf("it", "that", "this", "you", "them", "him", "her", "when", "how", "the way", "what")) null
            else "I ${m.groupValues[1].lowercase()} $what."
        },
        Regex("\\bi work (at|for|as) $THING(?=[.,!;]|$| and | but | since )", I) to { m -> "I work ${m.groupValues[1].lowercase()} ${m.groupValues[2].trim()}." },
        Regex("\\bi live in $THING(?=[.,!;]|$| and | but | with )", I) to { m -> "I live in ${m.groupValues[1].trim()}." },
        Regex("\\bmy birthday is (?:on )?$THING(?=[.,!;]|$| and )", I) to { m -> "My birthday is ${m.groupValues[1].trim()}." },
        Regex("\\bi was born on $THING(?=[.,!;]|$| and )", I) to { m -> "My birthday is ${m.groupValues[1].trim()}." },
        Regex("\\bmy favou?rite ([a-z]{3,20}) is $THING(?=[.,!;]|$| and | but )", I) to { m -> "My favourite ${m.groupValues[1].lowercase()} is ${m.groupValues[2].trim()}." },
        Regex("\\bi(?:'m| am) (?:a |an )?(vegetarian|vegan|pescatarian|student|teacher|nurse|doctor|developer|programmer|software engineer|engineer|designer|freelancer|lawyer|accountant|night owl|morning person)\\b", I) to { m ->
            "I'm ${if (m.groupValues[1].lowercase() in setOf("vegetarian", "vegan", "pescatarian", "night owl", "morning person")) "" else "a "}${m.groupValues[1].lowercase()}.".replace("I'm a a", "I'm an a").replace("I'm a e", "I'm an e")
        },
        Regex("\\bi(?:'m| am) from $THING(?=[.,!;]|$| and | but )", I) to { m -> "I'm from ${m.groupValues[1].trim()}." },
    )

    /** Questions, wishes and hypotheticals don't state facts. */
    private val NOT_A_STATEMENT = Regex("^(?:what|who|where|when|why|how|do|does|did|is|are|can|could|would|should|will|if|maybe|perhaps|imagine|suppose)\\b|\\?\\s*$|\\b(?:if i|would i|i wish|i wonder|pretend|let's say)\\b", RegexOption.IGNORE_CASE)

    fun extract(message: String): List<LearnedFact> = message.split(Regex("(?<=[.!?])\\s+|\\n+")).flatMap { sentence ->
        val s = sentence.trim()
        if (s.isEmpty() || NOT_A_STATEMENT.containsMatchIn(s)) emptyList()
        else rules.mapNotNull { (rx, build) -> rx.find(s)?.let(build) }.map { LearnedFact(it, 0.8f, LearnedFact.Source.PATTERN) }
    }.distinctBy { it.text.lowercase() }
}

/** Asks the on-device model for lasting facts in a batch of the user's messages, and reads its answer. */
object LearningPrompt {
    const val SYSTEM = "You extract lasting facts about a person from things they said. You never guess or invent."

    fun build(messages: List<String>, known: List<String>): String = buildString {
        appendLine("Below are things the user said to their assistant. List lasting facts about the user that will still be true next month: people in their life (names and relations), preferences, allergies, diet, work, home city, routines, important dates, goals.")
        appendLine("Rules: only what the user clearly stated about themselves; no guesses; nothing temporary (today's plans, moods, one-off tasks); nothing about the world in general; never passwords, card or ID numbers.")
        appendLine("Write each fact on its own line starting with \"- \", in the first person as the user would say it, e.g. \"- My sister's name is Ana.\" If there is nothing lasting, write \"- none\".")
        if (known.isNotEmpty()) {
            appendLine(); appendLine("Already known (don't repeat):")
            known.take(30).forEach { appendLine("• $it") }
        }
        appendLine(); appendLine("What the user said:")
        messages.forEachIndexed { i, m -> appendLine("${i + 1}. ${m.replace('\n', ' ').take(300)}") }
    }

    fun parse(output: String): List<LearnedFact> = output.lineSequence()
        .map { it.trim().removePrefix("-").removePrefix("*").removePrefix("•").replace(Regex("^\\d+[.)]\\s*"), "").trim() }
        .filter { it.length in 8..160 }
        .filter { Regex("^(?:I|I'm|I've|I'd|My)\\b").containsMatchIn(it) } // first person only: facts about the user
        .filterNot { it.equals("none", true) || it.startsWith("none", true) }
        .map { t -> LearnedFact(t.trim().let { if (it.last() in ".!") it else "$it." }, 0.6f, LearnedFact.Source.MODEL) }
        .distinctBy { it.text.lowercase() }
        .take(8).toList()
}

/** Keeps out duplicates of what ZED already knows, and anything sensitive. */
object LearnedFilter {
    private val SENSITIVE = Regex("\\d{6,}|\\b(?:password|passcode|pin code|pin is|cvv|credit card|card number|social security|ssn|bank account|account number|otp|one-time code)\\b", RegexOption.IGNORE_CASE)

    private fun keys(t: String) = BrainRecall.keys(t)

    fun isDuplicate(a: String, b: String): Boolean {
        if (a.trim().trimEnd('.').equals(b.trim().trimEnd('.'), ignoreCase = true)) return true
        val ka = keys(a); val kb = keys(b)
        if (ka.isEmpty() || kb.isEmpty()) return false
        val overlap = ka.intersect(kb).size.toDouble() / ka.union(kb).size
        return overlap >= 0.6
    }

    fun accept(candidates: List<LearnedFact>, existing: List<String>): List<LearnedFact> {
        val out = mutableListOf<LearnedFact>()
        // Pattern facts first: they are the most certain.
        for (c in candidates.sortedByDescending { it.confidence }) {
            if (SENSITIVE.containsMatchIn(c.text)) continue
            if (existing.any { isDuplicate(it, c.text) } || out.any { isDuplicate(it.text, c.text) }) continue
            out += c
        }
        return out
    }
}

/**
 * Questions worth thinking twice about (plan → answer → check): planning,
 * comparing, deciding, multi-step reasoning. Everyday questions don't pay the
 * extra time.
 */
object HardQuestion {
    private val SIGNALS = Regex(
        "\\b(?:plan|planning|itinerary|schedule my|compare|comparison|versus|vs\\.?|pros and cons|trade-?offs?|should i|which is better|what's the best way|best way to|" +
            "step by step|explain why|why does|why do|how would|strategy|budget|calculate|work out|analy[sz]e|decide|difference between|recommend|optimi[sz]e|" +
            "if i .+ how (?:much|many|long)|how (?:much|many|long) .+ if)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun isHard(text: String): Boolean {
        val words = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        return (words >= 8 && SIGNALS.containsMatchIn(text)) || words >= 35
    }

    const val PLAN_SYSTEM = "Before answering, think. Write a short plan for the answer: the key points, facts or steps needed, any numbers to compute (compute them exactly), and what to watch out for. At most 6 short lines. Do not write the answer itself."
    const val CHECK_SYSTEM = "You check an assistant's answer. If it has a factual or arithmetic mistake, contradicts the context, or misses part of the question, reply with the corrected full answer and nothing else. If it is fine, reply with exactly: OK"

    fun checkPrompt(question: String, answer: String) = "Question:\n$question\n\nAnswer:\n$answer"

    /** The corrected answer from a check, or null when the check found nothing to fix. */
    fun correction(check: String?, original: String): String? {
        val c = check?.trim()?.removeSurrounding("\"") ?: return null
        if (c.isEmpty() || Regex("^ok\\b[.!]?", RegexOption.IGNORE_CASE).containsMatchIn(c) || c.length < 20) return null
        if (c.equals(original.trim(), ignoreCase = true)) return null
        return c
    }
}
