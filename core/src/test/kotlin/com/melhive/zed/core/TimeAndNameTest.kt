package com.melhive.zed.core

import com.melhive.zed.core.command.CommandParser
import com.melhive.zed.core.command.ParsedCommand
import com.melhive.zed.core.time.Durations
import com.melhive.zed.core.voice.SpeechNormalizer
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
        assertEquals("time.left" to mapOf("target" to "9am"), tool("how much time left until 9am"))
        assertEquals("time.left" to mapOf("target" to "9am"), tool("how many minutes until 9am?"))
        assertEquals("time.left" to mapOf("target" to "5:30 pm"), tool("how long till 5:30 pm"))
        val at = com.melhive.zed.core.time.TimeParser.parseDateTime("9am", LocalDateTime.of(2026, 11, 11, 8, 56))!!
        assertEquals(LocalDateTime.of(2026, 11, 11, 9, 0), at)
        assertEquals("4 minutes", Durations.describe(java.time.Duration.between(LocalDateTime.of(2026, 11, 11, 8, 56), at).seconds))
    }

    @Test fun answersToZed() {
        assertEquals("flashlight.set", tool("Zed, turn on the flashlight").first)
        assertEquals("flashlight.set", tool("hey zed turn on the flashlight").first)
        assertEquals("time.now", tool("zed what time is it").first)
        val greet = com.melhive.zed.core.conversation.SmallTalk(kotlin.random.Random(1)).respond("hey zed", com.melhive.zed.core.conversation.SmallTalk.Context())
        assertTrue(greet != null)
        assertTrue("ZED" in com.melhive.zed.core.intelligence.PromptBuilder.identity())
    }

    @Test fun nameIsSpokenAsTheWordZed() {
        val v = com.melhive.zed.core.voice.SpokenName
        assertEquals("I'm Zed, your assistant.", v.fix("I'm ZED, your assistant."))
        assertEquals("Hey Zed!", v.fix("Hey Z.E.D!"))
        assertEquals("Zed's ready", v.fix("ZED's ready"))
        assertEquals("ZEDS and ZEDA stay", v.fix("ZEDS and ZEDA stay"))
        // Kokoro: "ZED" must become the word zˈɛd, not the letters Z-E-D.
        val ph = com.melhive.zed.core.voice.EnglishPhonemizer(com.melhive.zed.core.voice.PronunciationLexicon.of(mapOf("this" to "ðˈɪs", "is" to "ɪz"))).phonemize(SpeechNormalizer.normalize("This is ZED.")).joinToString(" ")
        assertTrue("zˈɛd" in ph, ph)
        assertTrue("zˈi" !in ph, ph)
        assertTrue("Zed" in com.melhive.zed.core.intelligence.PromptBuilder.systemPrompt(
            tools = emptyList(), nowDescription = "now", memories = emptyList(), knowledge = emptyList(), voiceMode = true,
        ))
    }
}
