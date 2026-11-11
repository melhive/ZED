package com.melhive.zed.core.routines

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/** One action in a routine: a tool call with fixed arguments. */
@Serializable
data class RoutineStep(val tool: String, val args: Map<String, String> = emptyMap(), val label: String = "")

/**
 * A user routine: named steps run in order, by voice ("good night"), at a time
 * of day, or when a Bluetooth device (e.g. the car) connects. Steps run
 * through the ToolManager like any other request.
 */
@Serializable
data class Routine(
    val id: String,
    val name: String,
    val steps: List<RoutineStep>,
    val enabled: Boolean = true,
    /** "HH:mm" local time, or null. */
    val time: String? = null,
    /** ISO day numbers 1 (Mon) .. 7 (Sun); empty = every day. */
    val days: Set<Int> = emptySet(),
    /** Name of a Bluetooth device whose connection runs the routine, or null. */
    val bluetoothDevice: String? = null,
    /** Extra phrases that run it by voice, besides its name. */
    val phrases: List<String> = emptyList(),
) {
    fun matches(phrase: String): Boolean {
        val p = normalize(phrase)
        return normalize(name) == p || phrases.any { normalize(it) == p }
    }

    fun triggerSummary(): String = buildList {
        time?.let { t -> add("at $t" + if (days.isEmpty() || days.size == 7) " every day" else " on " + days.sorted().joinToString { DayOfWeek.of(it).name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() } }) }
        bluetoothDevice?.let { add("when $it connects") }
        add("by voice: “${phrases.firstOrNull() ?: name.lowercase()}”")
    }.joinToString(" · ")

    /** Next time the time trigger fires after [now], or null without a valid time. */
    fun nextRun(now: LocalDateTime): LocalDateTime? {
        val t = parseTime(time) ?: return null
        var candidate = now.toLocalDate().atTime(t)
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
        repeat(8) {
            if (days.isEmpty() || candidate.dayOfWeek.value in days) return candidate
            candidate = candidate.plusDays(1)
        }
        return null
    }

    companion object {
        fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()
        fun parseTime(t: String?): LocalTime? = t?.let { runCatching { LocalTime.parse(it.trim().padStart(5, '0')) }.getOrNull() }
    }
}

/** A reusable action for the routine editor. [param] names the one argument the user fills in, if any. */
data class StepTemplate(val id: String, val title: String, val tool: String, val fixedArgs: Map<String, String> = emptyMap(), val param: String? = null, val paramHint: String = "")

object RoutineCatalog {
    val templates = listOf(
        StepTemplate("briefing", "Morning briefing", "workflow.run", mapOf("name" to "morning briefing")),
        StepTemplate("weather", "Weather", "weather.get"),
        StepTemplate("agenda_today", "Today's calendar", "calendar.today", mapOf("date" to "today")),
        StepTemplate("agenda_tomorrow", "Tomorrow's calendar", "calendar.today", mapOf("date" to "tomorrow")),
        StepTemplate("missed", "What did I miss", "notifications.recent"),
        StepTemplate("dnd_on", "Do Not Disturb on", "dnd.set", mapOf("on" to "true")),
        StepTemplate("dnd_off", "Do Not Disturb off", "dnd.set", mapOf("on" to "false")),
        StepTemplate("pause", "Pause media", "media.control", mapOf("action" to "pause")),
        StepTemplate("play", "Resume media", "media.control", mapOf("action" to "play")),
        StepTemplate("music", "Play music…", "music.play", param = "query", paramHint = "e.g. morning jazz"),
        StepTemplate("volume", "Media volume to…", "volume.set", mapOf("action" to "set", "stream" to "media"), param = "level", paramHint = "percent, e.g. 30"),
        StepTemplate("ring_volume", "Ring volume to…", "volume.set", mapOf("action" to "set", "stream" to "ring"), param = "level", paramHint = "percent, e.g. 0"),
        StepTemplate("flash_off", "Flashlight off", "flashlight.set", mapOf("on" to "false")),
        StepTemplate("timer", "Timer (minutes)…", "timer.set", param = "minutes", paramHint = "e.g. 25"),
        StepTemplate("app", "Open app…", "app.open", param = "name", paramHint = "e.g. Spotify"),
        StepTemplate("navigate", "Navigate to…", "maps.navigate", param = "destination", paramHint = "e.g. Home"),
        StepTemplate("todo", "Read task list", "todo.list"),
        StepTemplate("reminders", "Read reminders", "reminder.list"),
    )

    fun template(id: String) = templates.firstOrNull { it.id == id }

    /** Builds a step from a template and the user's value (minutes are converted to the timer's seconds). */
    fun step(t: StepTemplate, value: String?): RoutineStep {
        val args = t.fixedArgs.toMutableMap()
        if (t.param != null && !value.isNullOrBlank()) {
            if (t.param == "minutes") args["seconds"] = ((value.trim().toDoubleOrNull() ?: 25.0) * 60).toLong().toString()
            else args[t.param] = value.trim()
        }
        return RoutineStep(t.tool, args, if (t.param != null && !value.isNullOrBlank()) t.title.removeSuffix("…") + " " + value.trim() + if (t.param == "minutes") " min" else if (t.param == "level") "%" else "" else t.title)
    }

    val presets = listOf(
        Routine("good_morning", "Good morning", listOf(step(template("briefing")!!, null), step(template("weather")!!, null), step(template("agenda_today")!!, null)),
            enabled = false, time = "07:00", phrases = listOf("good morning", "start my day")),
        Routine("good_night", "Good night", listOf(step(template("dnd_on")!!, null), step(template("pause")!!, null), step(template("flash_off")!!, null), step(template("agenda_tomorrow")!!, null)),
            phrases = listOf("good night", "goodnight", "i'm going to bed", "bedtime")),
        Routine("focus", "Focus", listOf(step(template("dnd_on")!!, null), step(template("timer")!!, "25")),
            phrases = listOf("focus mode", "start focus", "i need to focus")),
        Routine("driving", "Driving", listOf(step(template("volume")!!, "60"), step(template("play")!!, null)),
            enabled = false, phrases = listOf("driving mode", "i'm driving")),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(list: List<Routine>): String = json.encodeToString(list)
    fun decode(text: String?): List<Routine> = text?.let { runCatching { json.decodeFromString<List<Routine>>(it) }.getOrNull() } ?: presets
}
