package com.melhive.zenith.core

import com.melhive.zenith.core.routines.Routine
import com.melhive.zenith.core.routines.RoutineCatalog
import com.melhive.zenith.core.tools.ToolCatalog
import com.melhive.zenith.core.tools.ToolRequest
import com.melhive.zenith.core.tools.ToolValidator
import com.melhive.zenith.core.tools.ValidationResult
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutinesTest {
    @Test fun presetStepsAreValidToolCalls() {
        val v = ToolValidator(ToolCatalog.DEFAULT)
        for (r in RoutineCatalog.presets) for (s in r.steps)
            assertIs<ValidationResult.Valid>(v.validate(ToolRequest(s.tool, s.args, ToolRequest.Origin.WORKFLOW)), "${r.name}: ${s.tool} ${s.args}")
        for (t in RoutineCatalog.templates) assertTrue(ToolCatalog.DEFAULT.find(t.tool) != null, t.tool)
    }

    @Test fun templateValues() {
        val timer = RoutineCatalog.step(RoutineCatalog.template("timer")!!, "25")
        assertEquals(mapOf("seconds" to "1500"), timer.args)
        assertEquals("Timer (minutes) 25 min", timer.label)
        val vol = RoutineCatalog.step(RoutineCatalog.template("volume")!!, "30")
        assertEquals(mapOf("action" to "set", "stream" to "media", "level" to "30"), vol.args)
    }

    @Test fun voiceMatching() {
        val night = RoutineCatalog.presets.first { it.id == "good_night" }
        assertTrue(night.matches("Good night!"))
        assertTrue(night.matches("I'm going to bed"))
        assertTrue(!night.matches("good night moon"))
    }

    @Test fun nextRun() {
        val r = Routine("x", "X", emptyList(), time = "07:00", days = setOf(1, 2, 3, 4, 5)) // weekdays
        val tueMorning = LocalDateTime.of(2026, 11, 10, 6, 0)   // Tuesday
        assertEquals(LocalDateTime.of(2026, 11, 10, 7, 0), r.nextRun(tueMorning))
        assertEquals(LocalDateTime.of(2026, 11, 11, 7, 0), r.nextRun(LocalDateTime.of(2026, 11, 10, 7, 0)))
        assertEquals(LocalDateTime.of(2026, 11, 16, 7, 0), r.nextRun(LocalDateTime.of(2026, 11, 14, 9, 0))) // Sat → Mon
        assertNull(Routine("y", "Y", emptyList()).nextRun(tueMorning))
        assertNull(Routine("z", "Z", emptyList(), time = "25:99").nextRun(tueMorning))
        assertEquals(LocalDateTime.of(2026, 11, 10, 7, 30), Routine("w", "W", emptyList(), time = "7:30").nextRun(tueMorning))
    }

    @Test fun jsonRoundTripAndFallback() {
        val list = RoutineCatalog.presets.map { it.copy(enabled = true) }
        assertEquals(list, RoutineCatalog.decode(RoutineCatalog.encode(list)))
        assertEquals(RoutineCatalog.presets, RoutineCatalog.decode(null))
        assertEquals(RoutineCatalog.presets, RoutineCatalog.decode("not json"))
        assertTrue("every day" in RoutineCatalog.presets[0].triggerSummary())
    }

    @Test fun parserRoutesWeatherAgendaRoutines() {
        val parser = com.melhive.zenith.core.command.CommandParser { LocalDateTime.of(2026, 11, 10, 9, 0) }
        fun t(x: String) = (parser.parse(x) as com.melhive.zenith.core.command.ParsedCommand.Tool).request.let { it.tool to it.args }
        assertEquals("weather.get" to emptyMap<String, String>(), t("what's the weather like today"))
        assertEquals("weather.get" to mapOf("place" to "Cebu"), t("weather in Cebu"))
        assertEquals("weather.get" to emptyMap<String, String>(), t("will it rain today"))
        assertEquals("calendar.today" to mapOf("date" to "tomorrow"), t("what's on my calendar tomorrow"))
        assertEquals("calendar.today" to mapOf("date" to "today"), t("what do I have today"))
        assertEquals("routine.run" to mapOf("name" to "good night"), t("run my good night routine"))
    }
}
