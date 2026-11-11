package com.melhive.zed.core.time

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.temporal.TemporalAdjusters

/**
 * Deterministic parser for common English time expressions. It does not guess:
 * anything it cannot parse returns null so the caller can ask for clarification.
 */
object TimeParser {

    private val CLOCK = Regex("^(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?$")
    private val DURATION_PART = Regex("(\\d+|an?|one|two|three|four|five|ten|fifteen|twenty|thirty|half an?)\\s*(seconds?|secs?|s|minutes?|mins?|m|hours?|hrs?|h)\\b")
    private val ISO_DATE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")
    private val MONTH_DAY = Regex("^([a-z]+)\\s+(\\d{1,2})(?:st|nd|rd|th)?$")
    private val DAY_MONTH = Regex("^(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?([a-z]+)$")

    private val WORD_NUMBERS = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30)

    /** Parses a time of day: "7", "7:30", "7:30 pm", "19:05", "noon", "midnight". */
    fun parseClock(text: String): LocalTime? {
        val t = text.trim().lowercase()
        when (t) {
            "noon", "midday" -> return LocalTime.NOON
            "midnight" -> return LocalTime.MIDNIGHT
        }
        val m = CLOCK.matchEntire(t) ?: return null
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        val suffix = m.groupValues[3].replace(".", "")
        if (minute > 59) return null
        when (suffix) {
            "am" -> { if (hour !in 1..12) return null; if (hour == 12) hour = 0 }
            "pm" -> { if (hour !in 1..12) return null; if (hour != 12) hour += 12 }
            else -> if (hour > 23) return null
        }
        return LocalTime.of(hour, minute)
    }

    /** Parses durations such as "10 minutes", "1 hour 30 minutes", "90s", "half an hour". Returns seconds. */
    fun parseDurationSeconds(text: String): Long? {
        val t = text.trim().lowercase().replace(" and ", " ")
        var total = 0L
        var matchedChars = 0
        for (m in DURATION_PART.findAll(t)) {
            val qtyRaw = m.groupValues[1]
            val unit = m.groupValues[2]
            val seconds = when {
                unit.startsWith("h") -> 3600L
                unit.startsWith("m") -> 60L
                else -> 1L
            }
            if (qtyRaw.startsWith("half")) {
                total += seconds / 2
            } else {
                val qty = qtyRaw.toLongOrNull() ?: WORD_NUMBERS[qtyRaw]?.toLong() ?: return null
                total += qty * seconds
            }
            matchedChars += m.value.length
        }
        val leftover = DURATION_PART.replace(t, "").replace(Regex("[\\s,]+"), "")
        if (total <= 0 || leftover.isNotEmpty()) return null
        return total
    }

    /**
     * Parses a point in time relative to [now]:
     * "in 10 minutes", "at 5pm", "tomorrow at 9", "today at 17:30", "tonight at 8",
     * "on monday at 3pm", "on 2026-12-01 at 10:00", "on december 1 at 10am", "tomorrow" (09:00).
     * A bare time that has already passed today resolves to tomorrow.
     */
    fun parseDateTime(text: String, now: LocalDateTime): LocalDateTime? {
        var t = text.trim().lowercase().removeSuffix(".").trim()
        if (t.isEmpty()) return null

        if (t.startsWith("in ")) {
            val secs = parseDurationSeconds(t.removePrefix("in ")) ?: return null
            return now.plusSeconds(secs).withNano(0)
        }
        try { return LocalDateTime.parse(t.uppercase()).withNano(0) } catch (_: Exception) {}

        var date: LocalDate? = null
        var dateExplicit = false
        fun takeDayWord(word: String, d: LocalDate) {
            if (t == word || t.startsWith("$word ")) {
                date = d; dateExplicit = true; t = t.removePrefix(word).trim()
            }
        }
        takeDayWord("today", now.toLocalDate())
        takeDayWord("tonight", now.toLocalDate())
        if (date == null) takeDayWord("tomorrow", now.toLocalDate().plusDays(1))
        val isTonight = text.trim().lowercase().startsWith("tonight")

        if (date == null && t.startsWith("on ")) t = t.removePrefix("on ").trim()
        if (date == null) {
            // Date part ends at " at " when present.
            val datePart = t.substringBefore(" at ").trim()
            val parsedDate = parseDate(datePart, now.toLocalDate())
            if (parsedDate != null) {
                date = parsedDate; dateExplicit = true
                t = if (t.contains(" at ")) "at " + t.substringAfter(" at ") else ""
            }
        }

        t = t.removePrefix("at ").trim()
        val time: LocalTime = when {
            t.isEmpty() -> if (dateExplicit) (if (isTonight) LocalTime.of(20, 0) else LocalTime.of(9, 0)) else return null
            else -> {
                var clock = parseClock(t) ?: return null
                // "tonight at 8" means 20:00.
                if (isTonight && clock.hour in 1..11 && !t.contains("am")) clock = clock.plusHours(12)
                clock
            }
        }
        val day = date ?: now.toLocalDate()
        var result = LocalDateTime.of(day, time)
        if (!dateExplicit && !result.isAfter(now)) result = result.plusDays(1)
        return result
    }

    /** Parses "monday", "next friday", "2026-12-01", "december 1", "1 december". */
    fun parseDate(text: String, today: LocalDate): LocalDate? {
        val t = text.trim().lowercase().removePrefix("next ").trim()
        weekday(t)?.let { return today.with(TemporalAdjusters.next(it)) }
        ISO_DATE.matchEntire(t)?.let { m ->
            return try { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) } catch (_: Exception) { null }
        }
        val md = MONTH_DAY.matchEntire(t)
        val dm = DAY_MONTH.matchEntire(t)
        val (monthName, dayNum) = when {
            md != null -> md.groupValues[1] to md.groupValues[2].toInt()
            dm != null -> dm.groupValues[2] to dm.groupValues[1].toInt()
            else -> return null
        }
        val month = month(monthName) ?: return null
        return try {
            var d = LocalDate.of(today.year, month, dayNum)
            if (d.isBefore(today)) d = d.plusYears(1)
            d
        } catch (_: Exception) { null }
    }

    private fun weekday(t: String): DayOfWeek? = DayOfWeek.entries.firstOrNull {
        val n = it.name.lowercase()
        t == n || t == n.take(3)
    }

    private fun month(t: String): Month? = Month.entries.firstOrNull {
        val n = it.name.lowercase()
        t == n || (t.length >= 3 && n.startsWith(t))
    }
}
