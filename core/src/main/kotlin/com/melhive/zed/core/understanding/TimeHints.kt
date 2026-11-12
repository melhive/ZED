package com.melhive.zed.core.understanding

import com.melhive.zed.core.time.TimeParser
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * Finds the date and time a person meant inside a whole sentence ("remind me
 * to call Ana at 5 tomorrow", "next Friday at 9", "in 20 minutes"), so ZED
 * computes it instead of trusting the model to.
 *
 * A day part and a time-of-day part are found separately, in any order, then
 * combined. Nothing is guessed: plain numbers only count as a time after "at"
 * or with am/pm or a colon.
 */
object TimeHints {

    /** What a sentence said about when. [day] and [time] are null when not mentioned. */
    data class When(
        val day: LocalDate?,
        val time: LocalTime?,
        /** "in 20 minutes": an exact moment, no separate day or time. */
        val exact: LocalDateTime? = null,
        /** The time had no am/pm ("at 5"), so it could be 05:00 or 17:00. */
        val ambiguousHalf: Boolean = false,
        val tonight: Boolean = false,
    ) {
        val hasDay get() = day != null
        val hasTime get() = time != null || exact != null

        /** The single moment meant, relative to [now] (bare times that already passed today move to tomorrow). */
        fun resolve(now: LocalDateTime): LocalDateTime? {
            exact?.let { return it }
            if (day == null && time == null) return null
            var t = time
            if (t != null && ambiguousHalf && (tonight && t.hour in 1..11 || t.hour in 1..6)) t = t.plusHours(12)
            if (day == null) {
                var at = LocalDateTime.of(now.toLocalDate(), pm(t!!, now.toLocalTime()))
                if (!at.isAfter(now)) at = at.plusDays(1)
                return at
            }
            return LocalDateTime.of(day, t ?: if (tonight) LocalTime.of(20, 0) else LocalTime.of(9, 0))
        }

        /** "at 5" said at 3 pm means 5 pm: a bare small hour that has already passed this morning is taken as afternoon. */
        private fun pm(t: LocalTime, nowTime: LocalTime): LocalTime =
            if (ambiguousHalf && t.hour in 1..11 && t.isBefore(nowTime) && t.plusHours(12).isAfter(nowTime)) t.plusHours(12) else t
    }

    private val WEEKDAYS = DayOfWeek.entries.associateBy { it.name.lowercase() } + DayOfWeek.entries.associateBy { it.name.lowercase().take(3) }
    private const val MONTHS = "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"

    private val IN_DURATION = Regex("\\bin ((?:(?:\\d+|an?|one|two|three|four|five|ten|fifteen|twenty|thirty|half an?)\\s*(?:seconds?|secs?|minutes?|mins?|hours?|hrs?)(?:\\s*(?:and\\s*)?)?)+)", RegexOption.IGNORE_CASE)
    private val DAY_WORD = Regex("\\b(today|tonight|tomorrow|day after tomorrow)\\b", RegexOption.IGNORE_CASE)
    private val WEEKDAY = Regex("\\b(?:(next|this|on|coming) )?(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun)\\b", RegexOption.IGNORE_CASE)
    private val ISO_DATE = Regex("\\b(\\d{4}-\\d{2}-\\d{2})\\b")
    private val MONTH_DAY = Regex("\\b($MONTHS)\\.? (\\d{1,2})(?:st|nd|rd|th)?\\b", RegexOption.IGNORE_CASE)
    private val DAY_MONTH = Regex("\\b(\\d{1,2})(?:st|nd|rd|th)? (?:of )?($MONTHS)\\b", RegexOption.IGNORE_CASE)
    private val CLOCK_AMPM = Regex("\\b(\\d{1,2})(?::(\\d{2}))? ?([ap])\\.?m\\.?(?=\\W|$)", RegexOption.IGNORE_CASE)
    private val CLOCK_COLON = Regex("\\b(\\d{1,2}):(\\d{2})\\b")
    private val CLOCK_AT = Regex("\\b(?:at|by|around) (\\d{1,2})(?:\\s*o'?clock)?\\b(?!\\s*(?:%|percent|minutes?|mins?|hours?|days?|seconds?|degrees|times|people|items))", RegexOption.IGNORE_CASE)
    private val NAMED = Regex("\\b(noon|midday|midnight)\\b", RegexOption.IGNORE_CASE)
    private val PART_OF_DAY = Regex("\\b(?:in the |this )?(morning|afternoon|evening)\\b", RegexOption.IGNORE_CASE)

    /** Everything the sentence says about when, or null when it says nothing. */
    fun find(text: String, now: LocalDateTime): When? {
        val t = text.lowercase()
        IN_DURATION.find(t)?.let { m ->
            TimeParser.parseDurationSeconds(m.groupValues[1].trim())?.let { return When(null, null, exact = now.plusSeconds(it).withNano(0)) }
        }
        val today = now.toLocalDate()
        var tonight = false
        var day: LocalDate? = null
        DAY_WORD.find(t)?.let { m ->
            day = when (m.groupValues[1]) {
                "today" -> today
                "tonight" -> { tonight = true; today }
                "tomorrow" -> today.plusDays(1)
                else -> today.plusDays(2)
            }
        }
        if (day == null) WEEKDAY.find(t)?.let { m ->
            // "sat" or "sun" alone are too often ordinary words; only take short forms after "on"/"next".
            if (m.groupValues[2].length == 3 && m.groupValues[1].isEmpty()) return@let
            // "Friday", "on Friday", "next Friday": the coming one (as the command parser reads it).
            day = today.with(TemporalAdjusters.next(WEEKDAYS.getValue(m.groupValues[2])))
        }
        if (day == null) ISO_DATE.find(t)?.let { m -> day = runCatching { LocalDate.parse(m.groupValues[1]) }.getOrNull() }
        if (day == null) (MONTH_DAY.find(t)?.let { it.groupValues[1] to it.groupValues[2] } ?: DAY_MONTH.find(t)?.let { it.groupValues[2] to it.groupValues[1] })?.let { (month, d) ->
            day = TimeParser.parseDate("$month $d", today)
        }

        var time: LocalTime? = null
        var ambiguous = false
        CLOCK_AMPM.find(t)?.let { m ->
            time = TimeParser.parseClock("${m.groupValues[1]}:${m.groupValues[2].ifEmpty { "00" }} ${m.groupValues[3]}m")
        }
        if (time == null) NAMED.find(t)?.let { m -> time = TimeParser.parseClock(m.groupValues[1]) }
        if (time == null) CLOCK_COLON.find(t)?.let { m ->
            time = TimeParser.parseClock("${m.groupValues[1]}:${m.groupValues[2]}")
            ambiguous = time != null && m.groupValues[1].toInt() in 1..11
        }
        if (time == null) CLOCK_AT.find(t)?.let { m ->
            val h = m.groupValues[1].toInt()
            if (h in 0..23) { time = LocalTime.of(h, 0); ambiguous = h in 1..11 }
        }
        // "at 7 in the evening", "tomorrow morning".
        PART_OF_DAY.find(t)?.let { m ->
            val part = m.groupValues[1]
            val tm = time
            if (tm == null) time = when (part) { "morning" -> LocalTime.of(9, 0); "afternoon" -> LocalTime.of(15, 0); else -> LocalTime.of(19, 0) }
            else if (ambiguous) {
                time = if (part != "morning" && tm.hour in 1..11) tm.plusHours(12) else tm
                ambiguous = false
            }
        }
        if (day == null && time == null) return null
        return When(day, time, ambiguousHalf = ambiguous, tonight = tonight)
    }

    /** The single moment a sentence names, or null. */
    fun moment(text: String, now: LocalDateTime): LocalDateTime? = find(text, now)?.resolve(now)

    /** A duration named in a sentence ("for 10 minutes", "a 90 second timer"), in seconds. */
    fun duration(text: String): Long? {
        val m = Regex("((?:(?:\\d+|an?|one|two|three|four|five|ten|fifteen|twenty|thirty|half an?)\\s*(?:seconds?|secs?|minutes?|mins?|hours?|hrs?)\\s*(?:and\\s*)?)+)", RegexOption.IGNORE_CASE).find(text.lowercase()) ?: return null
        return TimeParser.parseDurationSeconds(m.groupValues[1].trim())
    }

    /**
     * Applies a correction to an earlier moment: "Friday" keeps the earlier
     * time of day, "5" keeps the earlier day (and its half of the day),
     * "tomorrow at 6" replaces both.
     */
    fun adjust(previous: LocalDateTime, correction: When, now: LocalDateTime): LocalDateTime {
        correction.exact?.let { return it }
        val day = correction.day ?: previous.toLocalDate()
        var time = correction.time ?: previous.toLocalTime()
        if (correction.time != null && correction.ambiguousHalf && previous.hour >= 12 && time.hour in 1..11) time = time.plusHours(12)
        var at = LocalDateTime.of(day, time)
        if (correction.day == null && !at.isAfter(now)) at = at.plusDays(1)
        return at
    }
}
