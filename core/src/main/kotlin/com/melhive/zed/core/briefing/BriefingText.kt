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

    // ------------------------------------------------------------------ the whole briefing, said once

    /** A task's name, short enough to say: its first clause, at most eight words, without trailing punctuation. */
    fun shorten(title: String): String {
        val words = title.trim().trimEnd('.', '!', '?', ',', ';', ':').split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size <= 8) return words.joinToString(" ").trimEnd(',')
        // Stop at the first comma once there is enough to recognise it by, otherwise after eight words.
        val comma = words.indexOfFirst { it.endsWith(",") }.takeIf { it in 3..7 }
        return words.take(if (comma != null) comma + 1 else 8).joinToString(" ").trimEnd(',', ';', ':')
    }

    private fun minutes(n: Long): String = when {
        n <= 1 -> "any minute now"
        n < 60 -> "in $n minutes"
        else -> "in an hour"
    }

    /**
     * Everything for the briefing as one piece: each fact once, what is urgent first, what is empty
     * folded into a single sentence, and a missing city mentioned only at the very end.
     */
    fun compose(f: BriefingFacts): String {
        val now = f.now.toLocalTime()
        val out = mutableListOf<String>()

        // Greeting, and a word about the hour when it is unusual.
        val opening = StringBuilder()
        if (f.greet) {
            opening.append(greeting(f.now, f.who))
            if (f.now.hour < 5) opening.append(" You're up early.") else if (f.now.hour >= 23) opening.append(" It's late.")
        }
        // What can't wait comes straight after the greeting.
        val soonEvent = if (f.calendar && f.label == "today") f.events.filter { it.start != null && !it.start.isBefore(now) && java.time.Duration.between(now, it.start).toMinutes() <= 60 }.minByOrNull { it.start!! } else null
        val soonReminder = if (f.remindersWanted) f.reminders.filter { !it.second.isBefore(now) && java.time.Duration.between(now, it.second).toMinutes() <= 60 }.minByOrNull { it.second } else null
        if (soonEvent != null) opening.append(if (opening.isEmpty()) "" else " ").append("First up: ${soonEvent.title} at ${clock(soonEvent.start!!)}, ${minutes(java.time.Duration.between(now, soonEvent.start).toMinutes())}.")
        if (soonReminder != null) opening.append(if (opening.isEmpty()) "" else " ").append("${if (soonEvent != null) "And a" else "A"} reminder is due ${minutes(java.time.Duration.between(now, soonReminder.second).toMinutes())}: ${soonReminder.first}.")
        if (opening.isNotEmpty()) out += opening.toString()

        f.weather?.let { out += weather(it) }

        // The day: calendar, reminders, tasks. Whatever is empty is said together, once.
        val day = mutableListOf<String>()
        val noEvents = f.calendar && f.events.isEmpty()
        val noReminders = f.remindersWanted && f.reminders.isEmpty()
        val noTasks = f.tasksWanted && f.tasks.isEmpty()
        when {
            noEvents && noReminders && noTasks -> day += "Your day is clear: nothing on your calendar, no reminders and no open tasks."
            noEvents && noReminders -> day += "Nothing on your calendar and no reminders ${f.label}."
            noEvents -> day += "Your calendar is clear ${f.label}."
        }
        if (f.calendar && f.events.isNotEmpty()) day += calendar(f.events, if (f.label == "today") now else null, f.label)
        if (f.remindersWanted && f.reminders.isNotEmpty()) day += reminders(f.reminders)!!
        else if (noReminders && !noEvents) day += if (noTasks) "No reminders and no open tasks." else "No reminders today."
        if (f.tasksWanted && f.tasks.isNotEmpty()) day += tasks(f.tasks.map(::shorten))!!
        else if (noTasks && !(noEvents && noReminders) && !(noReminders && !noEvents)) day += "No open tasks."
        if (day.isNotEmpty()) out += day.joinToString(" ")

        val rest = mutableListOf<String>()
        notifications(f.notifications, f.topApps)?.let { rest += it }
        if (f.backgroundTasks > 0) rest += "${if (f.backgroundTasks == 1) "One background task is" else "${f.backgroundTasks} background tasks are"} still running."
        if (f.withheld) rest += "Unlock your phone for your calendar, reminders and tasks."
        if (rest.isNotEmpty()) out += rest.joinToString(" ")

        val closing = mutableListOf<String>()
        if (f.greet && !f.withheld) listOf("That's everything for now.", "That's your day so far.", "", "That's all from me.")[Math.floorMod(f.variation, 4)].takeIf { it.isNotEmpty() }?.let { closing += it }
        if (f.mentionCity) closing += "I can add the weather if you tell me your city."
        if (closing.isNotEmpty()) out += closing.joinToString(" ")
        return out.joinToString("\n\n")
    }
}

/**
 * The facts a briefing is made from. Only the parts that were asked for are spoken: the morning briefing
 * asks for all of them, a routine that just reads tomorrow's calendar asks for one.
 */
data class BriefingFacts(
    val now: LocalDateTime,
    val who: String? = null,
    /** Start with "Good morning, … It's Sunday, …". */
    val greet: Boolean = true,
    val weather: WeatherNow? = null,
    /** The weather was wanted but there is no city yet, and the user hasn't been told before. */
    val mentionCity: Boolean = false,
    /** The calendar was read (false: not asked for, or not allowed). */
    val calendar: Boolean = false,
    val events: List<DayEvent> = emptyList(),
    /** "today" or "tomorrow": which day [events] are for. */
    val label: String = "today",
    val remindersWanted: Boolean = false,
    val reminders: List<Pair<String, LocalTime>> = emptyList(),
    val tasksWanted: Boolean = false,
    val tasks: List<String> = emptyList(),
    val notifications: Int = 0,
    val topApps: List<String> = emptyList(),
    val backgroundTasks: Int = 0,
    /** The phone is locked: personal parts were left out. */
    val withheld: Boolean = false,
    /** Picks between equivalent closings so every day doesn't sound identical (the day of the year). */
    val variation: Int = 0,
)

/** How a routine reports what it did: one natural sentence for all its actions, failures said plainly. */
object RoutineSpeech {
    /** What an action step did, as a clause ("Do Not Disturb is on"), or null when the step's own message should be used. */
    fun done(tool: String, args: Map<String, String>): String? = when (tool) {
        "dnd.set" -> "Do Not Disturb is ${if (args["on"].isTrue()) "on" else "off"}"
        "flashlight.set" -> "the flashlight is ${if (args["on"].isTrue()) "on" else "off"}"
        "media.control" -> when (args["action"]?.lowercase()) { "pause", "stop" -> "the music is paused"; "play", "resume" -> "the music is playing"; "next" -> "the next track is playing"; else -> null }
        "volume.set" -> args["level"]?.let { "${args["stream"]?.takeIf { it != "media" }?.let { "$it volume" } ?: "the volume"} is at $it percent" }
        "timer.set" -> args["seconds"]?.toLongOrNull()?.let { s -> if (s % 60 == 0L) "a ${s / 60}-minute timer is running" else "a $s-second timer is running" }
        "app.open" -> args["name"]?.let { "$it is open" }
        "music.play" -> args["query"]?.let { "$it is playing" }
        "maps.navigate" -> args["destination"]?.let { "navigation to $it has started" }
        else -> null
    }

    private fun String?.isTrue() = this?.lowercase() in setOf("true", "on", "yes", "1")

    /** What an action step was trying to do, for "I couldn't …". */
    fun attempt(tool: String, args: Map<String, String>, label: String): String = when (tool) {
        "dnd.set" -> "turn Do Not Disturb ${if (args["on"].isTrue()) "on" else "off"}"
        "flashlight.set" -> "turn the flashlight ${if (args["on"].isTrue()) "on" else "off"}"
        "media.control" -> "${args["action"] ?: "control"} the music"
        "volume.set" -> "set the volume"
        "timer.set" -> "start the timer"
        "app.open" -> "open ${args["name"] ?: "the app"}"
        "music.play" -> "play ${args["query"] ?: "music"}"
        "maps.navigate" -> "start navigation"
        else -> label.ifBlank { tool }.replaceFirstChar { it.lowercase() }
    }

    /** "Do Not Disturb is on, the music is paused and the flashlight is off." */
    fun sentence(clauses: List<String>): String? {
        if (clauses.isEmpty()) return null
        val joined = when (clauses.size) { 1 -> clauses[0]; 2 -> "${clauses[0]} and ${clauses[1]}"; else -> clauses.dropLast(1).joinToString(", ") + " and " + clauses.last() }
        return joined.replaceFirstChar { it.uppercase() } + "."
    }

    /** A failure, kept to its first sentence and stripped of the tool's own heading. */
    fun failure(what: String, reason: String): String {
        val why = reason.substringAfter(" failed: ", reason).trim().split(Regex("(?<=[.!?])\\s+")).firstOrNull().orEmpty().trimEnd('.', '!')
        return "I couldn't $what" + (if (why.isBlank()) "." else ": ${why.replaceFirstChar { it.lowercase() }}.")
    }
}
