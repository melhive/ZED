package com.melhive.zed.core

import com.melhive.zed.core.briefing.BriefingText
import com.melhive.zed.core.briefing.DayEvent
import com.melhive.zed.core.briefing.WeatherNow
import com.melhive.zed.core.voice.MicLeveler
import com.melhive.zed.core.voice.SpeechNormalizer
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BriefingTextTest {
    @Test fun greetingReadsLikeSpeech() {
        assertEquals("Good afternoon, sir. It's Wednesday, November 11th, 1:05 PM.", BriefingText.greeting(LocalDateTime.of(2026, 11, 11, 13, 5), "sir"))
        assertEquals("Good morning. It's Monday, November 2nd, 8 AM.", BriefingText.greeting(LocalDateTime.of(2026, 11, 2, 8, 0), null))
        assertEquals("21st", BriefingText.ordinal(21)); assertEquals("12th", BriefingText.ordinal(12)); assertEquals("23rd", BriefingText.ordinal(23))
    }

    @Test fun weatherHasNoSymbolsOrBrackets() {
        val w = BriefingText.weather(WeatherNow(null, 51, 29, 35, 84, 6, 24, 30, 88))
        assertEquals("Right now it's 29 degrees with light drizzle, but it feels like 35. It's humid at 84%, with a light breeze. " +
            "Today expect 24 to 30 degrees and an 88% chance of rain, so you may want an umbrella.", w)
        assertFalse(Regex("[°():•]|km/h").containsMatchIn(w), w)
        val calm = BriefingText.weather(WeatherNow("Manila", 0, 27, 27, 60, 2, 25, 31, 5))
        assertTrue(calm.startsWith("Right now in Manila it's 27 degrees with clear skies."), calm)
        assertTrue("no rain expected" in calm && "the air is calm" in calm, calm)
    }

    @Test fun calendarSpeaksTimesNaturally() {
        val meeting = DayEvent("Clients meeting", LocalTime.of(8, 0))
        assertEquals("You have one event today: Clients meeting at 8 AM.", BriefingText.calendar(listOf(meeting)))
        assertEquals("Your calendar is clear today.", BriefingText.calendar(emptyList()))
        assertEquals("Earlier you had Clients meeting at 8 AM. Still ahead: Gym at 6:30 PM.",
            BriefingText.calendar(listOf(meeting, DayEvent("Gym", LocalTime.of(18, 30))), LocalTime.of(13, 5)))
        assertEquals("You have three events today: A at 9 AM, Offsite, all day, and C at 2:15 PM.",
            BriefingText.calendar(listOf(DayEvent("A", LocalTime.of(9, 0)), DayEvent("Offsite", null), DayEvent("C", LocalTime.of(14, 15)))))
        assertEquals("Reminder today: pay the bills at 3 PM.", BriefingText.reminders(listOf("pay the bills" to LocalTime.of(15, 0))))
    }

    @Test fun rangesAreSpokenAsTo() {
        assertTrue("twenty-four to thirty degrees" in SpeechNormalizer.normalize("24-30 degrees"), SpeechNormalizer.normalize("24-30 degrees"))
        assertTrue("nine to five" in SpeechNormalizer.normalize("Open 9–5 daily"))
        assertFalse(" to " in SpeechNormalizer.normalize("Call 0917-555-0142"))
    }

    @Test fun supertonicGetsAmPmAsSounds() {
        assertEquals("It's one oh five pee em.", SpeechNormalizer.normalize("It's 1:05 PM.", phoneticMeridiem = true))
        assertEquals("Wake me at eight ay em.", SpeechNormalizer.normalize("Wake me at 8am.", phoneticMeridiem = true))
        assertEquals("It's one oh five p m.", SpeechNormalizer.normalize("It's 1:05 PM."))   // Kokoro keeps letters
    }

    private fun rms(x: FloatArray) = sqrt(x.sumOf { (it * it).toDouble() } / x.size)

    @Test fun levelerBoostsQuietSpeechAndNeverClips() {
        val lev = MicLeveler()
        val quiet = FloatArray(1600) { (kotlin.math.sin(it / 5.0) * 0.004).toFloat() }
        var out = quiet
        repeat(60) { out = quiet.copyOf(); lev.process(out) }  // 6 s of quiet room: gain has fully risen
        assertTrue(rms(out) > rms(quiet) * 8, "gain ${lev.gain}")
        val loud = FloatArray(1600) { (kotlin.math.sin(it / 5.0) * 0.9).toFloat() }
        lev.process(loud)
        assertTrue(loud.all { abs(it) <= 1f })
    }
}
