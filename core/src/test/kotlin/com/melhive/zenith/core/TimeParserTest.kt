package com.melhive.zenith.core

import com.melhive.zenith.core.time.TimeParser
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimeParserTest {
    // Monday 2026-11-09 10:00
    private val now = LocalDateTime.of(2026, 11, 9, 10, 0)

    @Test fun clockFormats() {
        assertEquals(LocalTime.of(7, 30), TimeParser.parseClock("7:30"))
        assertEquals(LocalTime.of(19, 30), TimeParser.parseClock("7:30 pm"))
        assertEquals(LocalTime.of(0, 15), TimeParser.parseClock("12:15am"))
        assertEquals(LocalTime.of(12, 0), TimeParser.parseClock("12 pm"))
        assertEquals(LocalTime.of(17, 0), TimeParser.parseClock("5p.m."))
        assertEquals(LocalTime.NOON, TimeParser.parseClock("noon"))
        assertNull(TimeParser.parseClock("25:00"))
        assertNull(TimeParser.parseClock("13 pm"))
        assertNull(TimeParser.parseClock("banana"))
    }

    @Test fun durations() {
        assertEquals(600, TimeParser.parseDurationSeconds("10 minutes"))
        assertEquals(5400, TimeParser.parseDurationSeconds("1 hour and 30 minutes"))
        assertEquals(90, TimeParser.parseDurationSeconds("90 seconds"))
        assertEquals(1800, TimeParser.parseDurationSeconds("half an hour"))
        assertEquals(3600, TimeParser.parseDurationSeconds("an hour"))
        assertNull(TimeParser.parseDurationSeconds("10 bananas"))
        assertNull(TimeParser.parseDurationSeconds("soon"))
    }

    @Test fun relativeAndAbsoluteDateTimes() {
        assertEquals(now.plusMinutes(20), TimeParser.parseDateTime("in 20 minutes", now))
        assertEquals(LocalDateTime.of(2026, 11, 9, 17, 0), TimeParser.parseDateTime("at 5pm", now))
        // 9am already passed today → tomorrow
        assertEquals(LocalDateTime.of(2026, 11, 10, 9, 0), TimeParser.parseDateTime("at 9am", now))
        assertEquals(LocalDateTime.of(2026, 11, 10, 9, 0), TimeParser.parseDateTime("tomorrow at 9", now))
        assertEquals(LocalDateTime.of(2026, 11, 10, 9, 0), TimeParser.parseDateTime("tomorrow", now))
        assertEquals(LocalDateTime.of(2026, 11, 9, 20, 0), TimeParser.parseDateTime("tonight at 8", now))
        assertEquals(LocalDateTime.of(2026, 11, 13, 15, 0), TimeParser.parseDateTime("on friday at 3pm", now))
        assertEquals(LocalDateTime.of(2026, 11, 16, 15, 0), TimeParser.parseDateTime("monday at 3pm", now))
        assertEquals(LocalDateTime.of(2026, 12, 1, 10, 0), TimeParser.parseDateTime("on 2026-12-01 at 10:00", now))
        assertEquals(LocalDateTime.of(2026, 12, 25, 8, 0), TimeParser.parseDateTime("december 25 at 8am", now))
        assertEquals(LocalDateTime.of(2027, 1, 3, 9, 0), TimeParser.parseDateTime("3rd jan", now))
        assertEquals(LocalDateTime.of(2026, 11, 9, 14, 30), TimeParser.parseDateTime("2026-11-09T14:30", now))
        assertNull(TimeParser.parseDateTime("whenever", now))
        assertNull(TimeParser.parseDateTime("at the office", now))
    }

    @Test fun dates() {
        val today = LocalDate.of(2026, 11, 9)
        assertEquals(LocalDate.of(2026, 11, 11), TimeParser.parseDate("wednesday", today))
        assertEquals(LocalDate.of(2026, 11, 16), TimeParser.parseDate("next monday", today))
        assertNull(TimeParser.parseDate("2026-02-30", today))
    }
}
