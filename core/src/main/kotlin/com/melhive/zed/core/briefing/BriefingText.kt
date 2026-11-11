package com.melhive.zed.core.briefing

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Current conditions and today's forecast, as numbers (WMO weather code for the sky). */
data class WeatherNow(
    val place: String?,
    val code: Int,
    val temperature: Int?,
    val feelsLike: Int?,
    val humidity: Int?,
    val windKmh: Int?,
    val min: Int?,
    val max: Int?,
    val rainChance: Int?,
)

/** A calendar event today: [start] is null for all-day events. */
data class DayEvent(val title: String, val start: LocalTime?, val location: String? = null)

/**
 * Turns briefing data into sentences a person would say — they read well on screen and sound
 * natural when spoken (no symbols, colons, brackets or bullet lists for a voice to stumble on).
 */
object BriefingText {
    private val numbers = listOf("no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
    private fun count(n: Int) = numbers.getOrNull(n) ?: n.toString()
    /** "an 8", "an 18", "an 80", "an 11" — the article that matches how the number is said. */
    private fun an(n: Int): String { val s = n.toString(); return if (s.startsWith("8") || n == 11 || n == 18) "an" else "a" }
    private fun plural(n: Int, word: String) = "${count(n)} $word${if (n == 1) "" else "s"}"

    /** "8 AM", "1:05 PM" — minutes only when they matter. */
    fun clock(t: LocalTime): String =
        t.format(DateTimeFormatter.ofPattern(if (t.minute == 0) "h a" else "h:mm a", Locale.ENGLISH))

    fun ordinal(day: Int): String = day.toString() + when {
        day % 100 in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }

    /** "Good afternoon, sir. It's Wednesday, November 11th, 1:05 PM." */
    fun greeting(now: LocalDateTime, who: String?): String {
        val hello = when { now.hour < 12 -> "Good morning"; now.hour < 18 -> "Good afternoon"; else -> "Good evening" }
        val day = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM", Locale.ENGLISH)) + " " + ordinal(now.dayOfMonth)
        return "$hello${who?.let { ", $it" } ?: ""}. It's $day, ${clock(now.toLocalTime())}."
    }

    /** The sky as something you can be "with": "light drizzle", "clear skies". */
    fun sky(code: Int): String? = when (code) {
        0 -> "clear skies"; 1 -> "mostly clear skies"; 2 -> "some clouds"; 3 -> "overcast skies"
        45, 48 -> "fog"
        51, 53 -> "light drizzle"; 55 -> "drizzle"; 56, 57 -> "freezing drizzle"
        61 -> "light rain"; 63 -> "rain"; 65 -> "heavy rain"; 66, 67 -> "freezing rain"
        71, 73, 75, 77 -> "snow"
        80 -> "light showers"; 81 -> "showers"; 82 -> "heavy showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorms"; 96, 99 -> "thunderstorms and hail"
        else -> null
    }

    /**
     * "Right now it's 29 degrees with light drizzle, but it feels like 35. It's humid at 84%, with a light breeze.
     *  Today expect 24 to 30 degrees and an 88% chance of rain, so you may want an umbrella."
     */
    fun weather(w: WeatherNow): String {
        val out = StringBuilder()
        val where = w.place?.takeIf { it.isNotBlank() }?.let { " in $it" } ?: ""
        val sky = sky(w.code)
        if (w.temperature != null) {
            out.append("Right now$where it's ${w.temperature} degrees")
            sky?.let { out.append(" with $it") }
            if (w.feelsLike != null && kotlin.math.abs(w.feelsLike - w.temperature) >= 2) out.append(", but it feels like ${w.feelsLike}")
            out.append(".")
        } else if (sky != null) out.append("Right now$where there's $sky.")

        val air = mutableListOf<String>()
        w.humidity?.let { h -> air += if (h >= 75) "It's humid at $h%" else if (h <= 30) "The air is dry at $h% humidity" else "Humidity is $h%" }
        w.windKmh?.let { k ->
            val wind = when {
                k < 5 -> "the air is calm"
                k < 20 -> "a light breeze"
                k < 40 -> "winds around $k kilometers per hour"
                else -> "strong winds around $k kilometers per hour"
            }
            if (air.isEmpty()) air += wind.replaceFirstChar { it.uppercase() } else air += wind
        }
        if (air.isNotEmpty()) out.append(" ").append(if (air.size == 2) "${air[0]}, ${if (air[1].startsWith("the air")) "and " + air[1] else "with " + air[1]}." else "${air[0]}.")

        val today = mutableListOf<String>()
        if (w.min != null && w.max != null) today += if (w.min == w.max) "around ${w.max} degrees" else "${w.min} to ${w.max} degrees"
        val rain = w.rainChance
        if (rain != null) today += when {
            rain >= 30 -> "${an(rain)} $rain% chance of rain"
            rain >= 10 -> "only a small chance of rain"
            else -> "no rain expected"
        }
        if (today.isNotEmpty()) {
            out.append(" Today expect ").append(today.joinToString(" and "))
            if (rain != null && rain >= 50) out.append(", so you may want an umbrella")
            out.append(".")
        }
        return out.toString().trim()
    }

    private fun event(e: DayEvent): String {
        val where = e.location?.takeIf { it.isNotBlank() }?.let { " at $it" } ?: ""
        return if (e.start == null) "${e.title}$where, all day" else "${e.title}$where at ${clock(e.start)}"
    }

    private fun list(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        2 -> "${items[0]} and ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + ", and " + items.last()
    }

    /**
     * "You have one event today: Clients meeting at 8 AM." With [now], events that already started
     * are mentioned separately: "Earlier you had Clients meeting at 8 AM. Still ahead: Gym at 6 PM."
     */
    fun calendar(events: List<DayEvent>, now: LocalTime? = null, label: String = "today"): String {
        if (events.isEmpty()) return "Your calendar is clear $label."
        val (past, ahead) = if (now == null) emptyList<DayEvent>() to events
            else events.partition { it.start != null && it.start.isBefore(now) }
        return when {
            past.isEmpty() -> "You have ${plural(events.size, "event")} $label: ${list(events.map(::event))}."
            ahead.isEmpty() -> "You had ${plural(past.size, "event")} $label: ${list(past.map(::event))}. Nothing else is on your calendar."
            else -> "Earlier you had ${list(past.map(::event))}. Still ahead: ${list(ahead.map(::event))}."
        }
    }

    /** "Reminders today: pay the bills at 3 PM and call Mom at 7:30 PM." */
    fun reminders(items: List<Pair<String, LocalTime>>): String? =
        if (items.isEmpty()) null else "${if (items.size == 1) "Reminder" else "Reminders"} today: ${list(items.map { "${it.first} at ${clock(it.second)}" })}."

    /** "You have three open tasks: groceries, laundry, and the report." */
    fun tasks(titles: List<String>): String? = when {
        titles.isEmpty() -> null
        titles.size <= 5 -> "You have ${plural(titles.size, "open task")}: ${list(titles)}."
        else -> "You have ${titles.size} open tasks, including ${list(titles.take(3))}."
    }

    /** "You've had 4 notifications since last night, mostly from Messenger and Gmail." */
    fun notifications(total: Int, topApps: List<String>): String? =
        if (total <= 0) null else "You've had ${plural(total, "notification")} since last night" +
            (if (topApps.isEmpty()) "." else ", mostly from ${list(topApps)}.")
}
