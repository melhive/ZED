package com.melhive.zed.core.understanding

import com.melhive.zed.core.brain.BrainRecall
import java.time.LocalDateTime

/**
 * "Move my meeting with Ana to 4", "reschedule the dentist to Friday":
 * which event is meant, and when it should start now.
 */
object CalendarMove {
    data class Event(val id: Long, val title: String, val start: LocalDateTime, val end: LocalDateTime, val recurring: Boolean = false)

    /** Generic words that say "an event" without naming one. */
    private val GENERIC = setOf("meeting", "event", "appointment", "call", "calendar", "thing")

    /** True when every meaningful word of [query] appears in [title] ("meeting with Ana" ↔ "Lunch meeting with Ana Cruz"). */
    fun matches(query: String, title: String): Boolean {
        val q = BrainRecall.keys(query)
        val t = BrainRecall.keys(title)
        val specific = q - GENERIC
        // "my meeting" names no particular event: the next one is meant.
        return specific.isEmpty() || specific.all { it in t }
    }

    /**
     * The event meant: matching [query], at [from] when the conversation knows
     * its start, otherwise the next upcoming match. Repeating events are left
     * alone (moving one would move the whole series).
     */
    fun pick(events: List<Event>, query: String, from: LocalDateTime?, now: LocalDateTime): Event? {
        val candidates = events.filter { matches(query, it.title) && it.end.isAfter(now) }
        val exact = from?.let { f -> candidates.firstOrNull { !it.start.isBefore(f.minusMinutes(1)) && !it.start.isAfter(f.plusMinutes(1)) } }
        return exact ?: candidates.sortedBy { it.start }.firstOrNull()
    }

    /**
     * The new start. [keepDay]: only a time was said ("to 4") — same day.
     * [keepTime]: only a day was said ("to Friday") — same time.
     */
    fun newStart(event: Event, at: LocalDateTime, keepDay: Boolean, keepTime: Boolean): LocalDateTime = when {
        keepDay && keepTime -> event.start
        keepDay -> LocalDateTime.of(event.start.toLocalDate(), at.toLocalTime())
        keepTime -> LocalDateTime.of(at.toLocalDate(), event.start.toLocalTime())
        else -> at
    }

    /** The parser's arguments for a spoken change: the new start and what to keep from the old one. */
    fun arguments(change: String, now: LocalDateTime): Map<String, String>? {
        val hint = TimeHints.find(change, now) ?: change.trim().takeIf { Regex("^\\d{1,2}(?::\\d{2})?$").matches(it) }?.let { TimeHints.find("at $it", now) } ?: return null
        val at = hint.resolve(now) ?: return null
        return buildMap {
            put("at", at.withSecond(0).withNano(0).toString())
            if (hint.exact == null && hint.day == null) put("keep_day", "true")
            if (hint.exact == null && hint.time == null) put("keep_time", "true")
        }
    }
}
