package com.melhive.zed.agents

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.melhive.zed.AppContainer
import com.melhive.zed.intelligence.providers.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Weather from Open-Meteo (free, no API key). Location: a place name, the user's city setting, or coarse location. */
class WeatherService(private val context: Context) {
    data class Place(val name: String, val lat: Double, val lon: Double)

    private fun JsonObject.d(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    suspend fun geocode(name: String): Place? = withContext(Dispatchers.IO) {
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name=" + Uri.encode(name.trim())
        Http.getJson(url, emptyMap()).getOrNull()?.let { root ->
            val r = (root["results"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@let null
            val label = listOfNotNull(r.s("name"), r.s("admin1"), r.s("country")).distinct().joinToString(", ")
            Place(label, r.d("latitude") ?: return@let null, r.d("longitude") ?: return@let null)
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastLocation(): Place? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = context.getSystemService(LocationManager::class.java)
        val loc = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time } ?: return null
        return Place("your location", loc.latitude, loc.longitude)
    }

    /** Resolves [place] (or the default) and returns a one-paragraph forecast. */
    suspend fun report(place: String?, defaultCity: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val p = when {
                !place.isNullOrBlank() -> geocode(place) ?: error("I couldn't find “$place”.")
                else -> lastLocation() ?: defaultCity.takeIf { it.isNotBlank() }?.let { geocode(it) }
                    ?: error("Tell me your city in Settings → Briefing, or allow approximate location, so I know where to check.")
            }
            val url = "https://api.open-meteo.com/v1/forecast?latitude=${p.lat}&longitude=${p.lon}" +
                "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code&timezone=auto&forecast_days=1"
            val root = Http.getJson(url, emptyMap()).getOrThrow()
            val cur = root["current"] as? JsonObject ?: error("No weather data.")
            val daily = root["daily"] as? JsonObject
            fun first(k: String) = ((daily?.get(k) as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.doubleOrNull
            val t = cur.d("temperature_2m")?.roundToInt()
            val feels = cur.d("apparent_temperature")?.roundToInt()
            val code = cur.d("weather_code")?.toInt() ?: -1
            buildString {
                append("${p.name}: ${describe(code)}, ${t}°C")
                if (feels != null && feels != t) append(" (feels like $feels°C)")
                cur.d("relative_humidity_2m")?.let { append(", humidity ${it.roundToInt()}%") }
                cur.d("wind_speed_10m")?.let { append(", wind ${it.roundToInt()} km/h") }
                append(". Today ")
                append("${first("temperature_2m_min")?.roundToInt()}–${first("temperature_2m_max")?.roundToInt()}°C")
                first("precipitation_probability_max")?.let { append(", ${it.roundToInt()}% chance of rain") }
                append(".")
            }
        }
    }

    /** WMO weather interpretation codes. */
    private fun describe(code: Int) = when (code) {
        0 -> "clear sky"; 1 -> "mainly clear"; 2 -> "partly cloudy"; 3 -> "overcast"
        45, 48 -> "fog"
        51, 53, 55 -> "drizzle"; 56, 57 -> "freezing drizzle"
        61 -> "light rain"; 63 -> "rain"; 65 -> "heavy rain"; 66, 67 -> "freezing rain"
        71, 73, 75, 77 -> "snow"
        80 -> "light showers"; 81 -> "showers"; 82 -> "heavy showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorms"; 96, 99 -> "thunderstorms with hail"
        else -> "unknown conditions"
    }
}

/** Reads (never writes) calendar events for a day. */
class CalendarReader(private val context: Context) {
    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun parseDay(text: String?): LocalDate? = when (text?.trim()?.lowercase()) {
        null, "", "today" -> LocalDate.now()
        "tomorrow" -> LocalDate.now().plusDays(1)
        "yesterday" -> LocalDate.now().minusDays(1)
        else -> runCatching { LocalDate.parse(text.trim()) }.getOrNull()
    }

    fun events(day: LocalDate): List<String> {
        if (!granted()) return emptyList()
        val zone = ZoneId.systemDefault()
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { android.content.ContentUris.appendId(it, start); android.content.ContentUris.appendId(it, end) }.build()
        val fmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
        val out = mutableListOf<String>()
        context.contentResolver.query(
            uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION),
            null, null, "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "(no title)"
                val allDay = c.getInt(3) == 1
                val begin = java.time.Instant.ofEpochMilli(c.getLong(1)).atZone(zone)
                val loc = c.getString(4)?.takeIf { it.isNotBlank() }?.let { " at $it" } ?: ""
                out += if (allDay) "All day: $title$loc" else "${begin.format(fmt)}: $title$loc"
            }
        }
        return out
    }

    /** The next event starting after [now] in the coming week: title and start time. */
    fun nextEvent(now: Long): Pair<String, Long>? {
        if (!granted()) return null
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { android.content.ContentUris.appendId(it, now); android.content.ContentUris.appendId(it, now + 7 * 86_400_000L) }.build()
        return context.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY),
            "${CalendarContract.Instances.BEGIN} > ?", arrayOf(now.toString()), "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) { if (c.getInt(2) == 1) continue; return@use (c.getString(0) ?: "(no title)") to c.getLong(1) }
            null
        }
    }

    fun describe(day: LocalDate): String {
        if (!granted()) return "I need calendar permission to read your events (More → Permissions & Diagnostics)."
        val label = when (day) { LocalDate.now() -> "today"; LocalDate.now().plusDays(1) -> "tomorrow"; else -> day.toString() }
        val ev = events(day)
        return if (ev.isEmpty()) "Nothing on your calendar $label." else "Your calendar $label (${ev.size}):\n" + ev.joinToString("\n") { "• $it" }
    }
}

/** Composes the daily briefing from real data: weather, calendar, reminders, tasks, notifications and background work. */
class BriefingComposer(private val context: Context, private val c: AppContainer) {
    suspend fun compose(): String {
        val s = c.settings.current()
        val now = java.time.LocalDateTime.now()
        val hour = now.hour
        val greeting = when { hour < 12 -> "Good morning"; hour < 18 -> "Good afternoon"; else -> "Good evening" }
        val who = s.userTitle.ifBlank { null } ?: c.memory.userName()
        val parts = mutableListOf<String>()
        parts += "$greeting${who?.let { ", $it" } ?: ""}. It's ${now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, h:mm a", Locale.getDefault()))}."
        WeatherService(context).report(null, s.weatherCity).onSuccess { parts += it }
        val cal = CalendarReader(context)
        if (cal.granted()) parts += cal.describe(LocalDate.now())
        val nowMs = System.currentTimeMillis()
        val endOfDay = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val reminders = c.db.reminders().scheduled().filter { it.triggerAt in nowMs until endOfDay }
        if (reminders.isNotEmpty()) parts += "Reminders today: " + reminders.joinToString("; ") {
            java.time.Instant.ofEpochMilli(it.triggerAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a")) + " " + it.text
        } + "."
        val todos = c.todos.list(false)
        if (todos.isNotEmpty()) parts += "${todos.size} open task${if (todos.size == 1) "" else "s"}: " + todos.take(5).joinToString("; ") { it.title } + if (todos.size > 5) "…" else "."
        if (com.melhive.zed.android.ZedNotificationListener.hasAccess(context)) {
            val missed = com.melhive.zed.android.ZedNotificationListener.recent(context, null, 10)
            if (missed.isNotEmpty()) parts += "${missed.size} notification${if (missed.size == 1) "" else "s"} since last night, mostly from " +
                missed.groupingBy { it.app }.eachCount().entries.sortedByDescending { it.value }.take(3).joinToString { it.key } + "."
        }
        val active = c.taskEngine.activeCount.first()
        if (active > 0) parts += "$active background task${if (active == 1) " is" else "s are"} running."
        return parts.joinToString("\n\n")
    }
}

/** Adds events straight into the phone's calendar (no editor, no Save button). */
class CalendarWriter(private val context: Context) {
    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    data class Target(val id: Long, val name: String)

    /** The primary writable calendar: the phone's own primary flag first, then a Google account, then any visible one. */
    fun target(): Target? {
        if (!granted()) return null
        val c = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.ACCOUNT_TYPE),
            "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
            null, null,
        ) ?: return null
        val all = mutableListOf<Triple<Target, Boolean, String>>()
        c.use { while (it.moveToNext()) all += Triple(Target(it.getLong(0), it.getString(1) ?: "Calendar"), it.getInt(2) == 1, it.getString(3).orEmpty()) }
        return (all.firstOrNull { it.second } ?: all.firstOrNull { it.third == "com.google" } ?: all.firstOrNull())?.first
    }

    /** Inserts the event and a 10-minute reminder; returns the new event id. */
    fun insert(title: String, start: java.time.LocalDateTime, end: java.time.LocalDateTime, location: String?, description: String?): Pair<Long, Target>? {
        val cal = target() ?: return null
        val zone = ZoneId.systemDefault()
        val values = android.content.ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, cal.id)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start.atZone(zone).toInstant().toEpochMilli())
            put(CalendarContract.Events.DTEND, end.atZone(zone).toInstant().toEpochMilli())
            put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            if (!location.isNullOrBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
            if (!description.isNullOrBlank()) put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.HAS_ALARM, 1)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return null
        val id = android.content.ContentUris.parseId(uri)
        runCatching {
            context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, android.content.ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, id)
                put(CalendarContract.Reminders.MINUTES, 10)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            })
        }
        return id to cal
    }
}
