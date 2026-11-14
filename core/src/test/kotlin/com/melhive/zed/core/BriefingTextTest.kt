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

    private val sunday = LocalDateTime.of(2026, 11, 15, 4, 48)
    private fun facts(now: LocalDateTime = sunday) = com.melhive.zed.core.briefing.BriefingFacts(now = now, who = "sir", calendar = true, remindersWanted = true, tasksWanted = true)

    @Test fun `the briefing says each thing once`() {
        val text = BriefingText.compose(facts().copy(tasks = listOf("Study the setup of Ubuntu on termux, File management, and Coding agents"), mentionCity = true))
        assertEquals(
            "Good morning, sir. It's Sunday, November 15th, 4:48 AM. You're up early.\n\n" +
                "Nothing on your calendar and no reminders today. You have one open task: Study the setup of Ubuntu on termux.\n\n" +
                "That's everything for now. I can add the weather if you tell me your city.",
            text,
        )
        assertEquals(1, Regex("calendar").findAll(text).count())
        assertEquals(1, Regex("open task").findAll(text).count())
        assertFalse(text.contains("#"))
        assertFalse(text.contains("routine", ignoreCase = true))
        assertTrue(text.endsWith("I can add the weather if you tell me your city."))
    }

    @Test fun `an empty day is one sentence`() {
        val text = BriefingText.compose(facts(LocalDateTime.of(2026, 11, 16, 8, 0)).copy(variation = 2))
        assertEquals("Good morning, sir. It's Monday, November 16th, 8 AM.\n\nYour day is clear: nothing on your calendar, no reminders and no open tasks.", text)
    }

    @Test fun `a busy day leads with what is urgent`() {
        val now = LocalDateTime.of(2026, 11, 16, 8, 30)
        val text = BriefingText.compose(facts(now).copy(
            weather = WeatherNow("Cebu", 0, 29, 29, 60, 3, 25, 31, 5),
            events = listOf(DayEvent("Standup", LocalTime.of(9, 0)), DayEvent("Gym", LocalTime.of(18, 30))),
            reminders = listOf("pay the bills" to LocalTime.of(15, 0)), tasks = listOf("groceries", "laundry"),
            notifications = 4, topApps = listOf("Messenger"), backgroundTasks = 1, variation = 2,
        ))
        val parts = text.split("\n\n")
        assertEquals("Good morning, sir. It's Monday, November 16th, 8:30 AM. First up: Standup at 9 AM, in 30 minutes.", parts[0])
        assertTrue(parts[1].startsWith("Right now in Cebu it's 29 degrees"))
        assertEquals("You have two events today: Standup at 9 AM and Gym at 6:30 PM. Reminder today: pay the bills at 3 PM. You have two open tasks: groceries and laundry.", parts[2])
        assertEquals("You've had four notifications since last night, mostly from Messenger. One background task is still running.", parts[3])
        assertEquals(4, parts.size)
    }

    @Test fun `only what was asked for is said`() {
        // "Good night" reads tomorrow's calendar and nothing else: no greeting, no tasks.
        val tomorrow = BriefingText.compose(com.melhive.zed.core.briefing.BriefingFacts(now = sunday, greet = false, calendar = true, label = "tomorrow", events = listOf(DayEvent("Dentist", LocalTime.of(9, 0)))))
        assertEquals("You have one event tomorrow: Dentist at 9 AM.", tomorrow)
        assertEquals("Your calendar is clear tomorrow.", BriefingText.compose(com.melhive.zed.core.briefing.BriefingFacts(now = sunday, greet = false, calendar = true, label = "tomorrow")))
        // Locked phone: the personal parts are held back.
        val locked = BriefingText.compose(com.melhive.zed.core.briefing.BriefingFacts(now = LocalDateTime.of(2026, 11, 16, 8, 0), withheld = true))
        assertEquals("Good morning. It's Monday, November 16th, 8 AM.\n\nUnlock your phone for your calendar, reminders and tasks.", locked)
    }

    @Test fun `long task names are shortened and routines report in one sentence`() {
        assertEquals("Study the setup of Ubuntu on termux", BriefingText.shorten("Study the setup of Ubuntu on termux, File management, and Coding agents"))
        assertEquals("Buy milk", BriefingText.shorten("Buy milk."))
        val r = com.melhive.zed.core.briefing.RoutineSpeech
        assertEquals("Do Not Disturb is on, the music is paused and the flashlight is off.", r.sentence(listOfNotNull(
            r.done("dnd.set", mapOf("on" to "true")), r.done("media.control", mapOf("action" to "pause")), r.done("flashlight.set", mapOf("on" to "false")))))
        assertEquals("Do Not Disturb is on and a 25-minute timer is running.", r.sentence(listOfNotNull(r.done("dnd.set", mapOf("on" to "true")), r.done("timer.set", mapOf("seconds" to "1500")))))
        assertEquals("I couldn't turn Do Not Disturb on: allow Do Not Disturb access first.", r.failure(r.attempt("dnd.set", mapOf("on" to "true"), ""), "Do Not Disturb failed: Allow Do Not Disturb access first. Then try again."))
        assertEquals(null, r.sentence(emptyList()))
        // The built-in Good morning is the briefing alone.
        assertEquals(1, com.melhive.zed.core.routines.RoutineCatalog.presets.first { it.id == "good_morning" }.steps.size)
    }
}
