package com.melhive.zed.core.compute

import com.melhive.zed.core.time.TimeParser
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Answers date questions: offsets, countdowns, elapsed days and weekdays. */
object DateMath {
    private val fmt = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)
    private val HOLIDAYS = mapOf(
        "christmas" to MonthDay.of(12, 25), "christmas day" to MonthDay.of(12, 25), "christmas eve" to MonthDay.of(12, 24),
        "new year" to MonthDay.of(1, 1), "new years" to MonthDay.of(1, 1), "new year's day" to MonthDay.of(1, 1), "new years day" to MonthDay.of(1, 1),
        "new year's eve" to MonthDay.of(12, 31), "new years eve" to MonthDay.of(12, 31), "halloween" to MonthDay.of(10, 31),
        "valentine's day" to MonthDay.of(2, 14), "valentines day" to MonthDay.of(2, 14),
    )

    private val OFFSET = Regex("^what (?:day|date) (?:is it|will it be|is) (?:in )?(\\d+) (day|days|week|weeks|month|months|year|years)(?: from (?:now|today))?$", RegexOption.IGNORE_CASE)
    private val OFFSET2 = Regex("^what (?:day|date) (?:is|was) (\\d+) (day|days|week|weeks|month|months|year|years) (from now|from today|ago)$", RegexOption.IGNORE_CASE)
    private val UNTIL = Regex("^how many (days|weeks) (?:until|till|to|before) (.+)$", RegexOption.IGNORE_CASE)
    private val SINCE = Regex("^how many days (?:since|from) (.+?)(?: (?:until|to) (.+))?$", RegexOption.IGNORE_CASE)
    private val WEEKDAY = Regex("^what day (?:of the week )?(?:is|was|will be) (.+)$", RegexOption.IGNORE_CASE)

    private fun resolveDate(text: String, today: LocalDate): LocalDate? {
        val t = text.trim().lowercase().removePrefix("the ").removeSuffix(" this year")
        HOLIDAYS[t]?.let { md -> var d = md.atYear(today.year); if (d.isBefore(today)) d = d.plusYears(1); return d }
        if (t == "today") return today
        if (t == "tomorrow") return today.plusDays(1)
        if (t == "yesterday") return today.minusDays(1)
        return TimeParser.parseDate(t, today)
    }

    private fun past(text: String, today: LocalDate): LocalDate? {
        val t = text.trim().lowercase()
        Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").matchEntire(t)?.let { return runCatching { LocalDate.parse(t) }.getOrNull() }
        val d = resolveDate(t, today) ?: return null
        return if (d.isAfter(today)) d.minusYears(1) else d
    }

    /** Returns an answer, or null when the text isn't a date question. */
    fun tryAnswer(text: String, today: LocalDate): String? {
        val t = text.trim().trimEnd('?', '.', '!').trim()
        fun offset(n: Long, unit: String, sign: Int): LocalDate = when {
            unit.startsWith("day") -> today.plusDays(sign * n)
            unit.startsWith("week") -> today.plusWeeks(sign * n)
            unit.startsWith("month") -> today.plusMonths(sign * n)
            else -> today.plusYears(sign * n)
        }
        OFFSET.matchEntire(t)?.let { m -> return "In ${m.groupValues[1]} ${m.groupValues[2]} it will be ${offset(m.groupValues[1].toLong(), m.groupValues[2].lowercase(), 1).format(fmt)}." }
        OFFSET2.matchEntire(t)?.let { m ->
            val ago = m.groupValues[3].lowercase() == "ago"
            val d = offset(m.groupValues[1].toLong(), m.groupValues[2].lowercase(), if (ago) -1 else 1)
            return "${m.groupValues[1]} ${m.groupValues[2]} ${if (ago) "ago it was" else "from now it will be"} ${d.format(fmt)}."
        }
        UNTIL.matchEntire(t)?.let { m ->
            val target = resolveDate(m.groupValues[2], today) ?: return null
            val days = ChronoUnit.DAYS.between(today, target)
            return if (m.groupValues[1].lowercase() == "weeks") "${Calculator.format(days / 7.0)} weeks until ${target.format(fmt)} ($days days)."
            else if (days == 0L) "That's today — ${target.format(fmt)}." else "$days day${if (days == 1L) "" else "s"} until ${target.format(fmt)}."
        }
        SINCE.matchEntire(t)?.let { m ->
            val from = past(m.groupValues[1], today) ?: return null
            val to = m.groupValues[2].takeIf { it.isNotBlank() }?.let { resolveDate(it, today) } ?: today
            val days = ChronoUnit.DAYS.between(from, to)
            return "$days days from ${from.format(fmt)} to ${to.format(fmt)}."
        }
        WEEKDAY.matchEntire(t)?.let { m ->
            val raw = m.groupValues[1].trim().lowercase()
            if (raw in setOf("it", "it today", "today")) return null // handled by time.now
            val d = resolveDate(raw, today) ?: return null
            return "${d.format(fmt)} is a ${d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}."
        }
        return null
    }
}
