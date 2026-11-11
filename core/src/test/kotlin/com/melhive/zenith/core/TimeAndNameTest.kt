package com.melhive.zenith.core

import com.melhive.zenith.core.command.CommandParser
import com.melhive.zenith.core.command.ParsedCommand
import com.melhive.zenith.core.time.Durations
import com.melhive.zenith.core.voice.SpeechNormalizer
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TimeAndNameTest {
    private val parser = CommandParser { LocalDateTime.of(2026, 11, 11, 9, 0) }
    private fun tool(x: String) = (parser.parse(x) as ParsedCommand.Tool).request.let { it.tool to it.args }

    @Test fun durations() {
        assertEquals("4 minutes and 12 seconds", Durations.describe(252))
        assertEquals("1 hour, 2 minutes and 3 seconds", Durations.describe(3723))
        assertEquals("1 second", Durations.describe(1))
        assertEquals("2 days and 3 hours", Durations.describe(2 * 86_400 + 3 * 3600 + 15))
        assertEquals("1 hour and 1 minute", Durations.describe(3660 + 30, withSeconds = false))
        assertEquals("now", Durations.describe(0))
    }

    @Test fun spokenTimesWithSecondsAndMeridiem() {
        assertEquals("It's three forty-two and seventeen seconds p m.", SpeechNormalizer.normalize("It's 3:42:17 PM."))
        assertEquals("at nine thirty a m", SpeechNormalizer.normalize("at 9:30 A M"))
        assertEquals("at seven p m", SpeechNormalizer.normalize("at 7 P M"))
    }

    @Test fun timeLeftCommands() {
        assertEquals("time.left" to emptyMap<String, String>(), tool("how much time is left"))
        assertEquals("time.left" to mapOf("target" to "timer"), tool("how much time is left on my timer"))
        assertEquals("time.left" to mapOf("target" to "meeting"), tool("how long until my meeting"))
        assertEquals("time.left" to mapOf("target" to "reminder"), tool("when is my next reminder"))
        assertEquals("time.now", tool("what time is it").first)
    }

    @Test fun answersToZed() {
        assertEquals("flashlight.set", tool("Zed, turn on the flashlight").first)
        assertEquals("flashlight.set", tool("hey zed turn on the flashlight").first)
        assertEquals("time.now", tool("zed what time is it").first)
        val greet = com.melhive.zenith.core.conversation.SmallTalk(kotlin.random.Random(1)).respond("hey zed", com.melhive.zenith.core.conversation.SmallTalk.Context())
        assertTrue(greet != null)
        assertTrue("ZED" in com.melhive.zenith.core.intelligence.PromptBuilder.identity())
    }
}
