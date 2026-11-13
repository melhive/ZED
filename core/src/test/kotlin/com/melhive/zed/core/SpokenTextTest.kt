package com.melhive.zed.core

import com.melhive.zed.core.command.CommandParser
import com.melhive.zed.core.command.ParsedCommand
import com.melhive.zed.core.voice.SpokenText
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** What the on-device recognizer writes, turned into text ZED's commands understand. */
class SpokenTextTest {
    @Test fun numbersAndTimes() {
        assertEquals("Remind me to call ana at 7:30 pm", SpokenText.normalize("remind me to call ana at seven thirty p m"))
        assertEquals("Your meeting is at 9 am and it's 21 degrees", SpokenText.normalize("your meeting is at nine a m and it's twenty one degrees"))
        assertEquals("Set a timer for 5 minutes", SpokenText.normalize("set a timer for five minutes"))
        assertEquals("Wake me up at 6:45 tomorrow", SpokenText.normalize("wake me up at six forty five to morrow"))
        assertEquals("Meet at 9:05", SpokenText.normalize("meet at nine oh five"))
        assertEquals("Set volume to 80%", SpokenText.normalize("set volume to eighty percent"))
        assertEquals("It costs 250 pesos", SpokenText.normalize("it costs two hundred and fifty pesos"))
        assertEquals("1500 steps today", SpokenText.normalize("fifteen hundred steps today"))
        assertEquals("Remind me at 7", SpokenText.normalize("remind me at seven o clock"))
    }

    @Test fun wordsStayWords() {
        assertEquals("Call one of them", SpokenText.normalize("call one of them"))
        assertEquals("Oh well never mind", SpokenText.normalize("oh well never mind"))
        assertEquals("I have 3 things on my list today", SpokenText.normalize("i have three things on my list to day"))
        assertEquals("Add 2 eggs and 7 apples", SpokenText.normalize("add two eggs and seven apples"), "not a time without a time word")
    }

    @Test fun recognizedSpeechWorksWithCommands() {
        val parser = CommandParser { LocalDateTime.of(2026, 11, 13, 10, 0) }
        val r = parser.parse(SpokenText.normalize("remind me to call ana at seven thirty p m"))
        assertIs<ParsedCommand.Tool>(r)
        assertEquals("2026-11-13T19:30", r.request.args["at"])
        val t = parser.parse(SpokenText.normalize("set a timer for ten minutes"))
        assertIs<ParsedCommand.Tool>(t)
        assertEquals("600", t.request.args["seconds"])
    }
}
