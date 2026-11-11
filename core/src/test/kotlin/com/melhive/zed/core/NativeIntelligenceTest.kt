package com.melhive.zed.core

import com.melhive.zed.core.command.CommandParser
import com.melhive.zed.core.command.ParsedCommand
import com.melhive.zed.core.compute.Calculator
import com.melhive.zed.core.compute.DateMath
import com.melhive.zed.core.compute.UnitConverter
import com.melhive.zed.core.conversation.SmallTalk
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeIntelligenceTest {
    private val parser = CommandParser { LocalDateTime.of(2026, 11, 9, 10, 0) }

    private fun answer(text: String): String {
        val r = parser.parse(text)
        assertIs<ParsedCommand.Answer>(r, "Expected an answer for '$text' but got $r")
        return r.text
    }

    @Test fun calculator() {
        assertEquals(14.0, Calculator.evaluate("2+3*4"))
        assertEquals(20.0, Calculator.evaluate("(2+3)*4"))
        assertEquals(512.0, Calculator.evaluate("2^3^2"))
        assertEquals(-6.0, Calculator.evaluate("-2*3"))
        assertEquals(14.0, Calculator.evaluate("2(3+4)"))
        assertEquals("0.3", Calculator.format(Calculator.evaluate("0.1+0.2")))
        assertEquals("what is 18% of 250 = 45".substringAfter("is "), answer("what is 18% of 250?"))
        assertEquals("12 times 7 = 84", answer("12 times 7"))
        assertEquals("sqrt 144 = 12", answer("sqrt 144").replace("sqrt 144", "sqrt 144"))
        assertEquals("square root of 81 = 9", answer("what's the square root of 81"))
        assertEquals("1,250 divided by 5 = 250", answer("calculate 1,250 divided by 5"))
        assertTrue(answer("5 / 0").contains("division by zero"))
        assertNull(Calculator.normalize("what is the meaning of life"))
        assertNull(Calculator.normalize("route 66"))
        assertNull(Calculator.normalize("2026"))
    }

    @Test fun units() {
        assertEquals("5 km = 3.10685596119 mi", (UnitConverter.tryConvert("convert 5 km to miles") as UnitConverter.Result.Ok).conversion.describe())
        assertEquals("100 °F = 37.7777777778 °C", answer("100 f to c"))
        assertEquals("0 °C = 273.15 K", answer("0 celsius in kelvin"))
        assertEquals("3 ft = 91.44 cm", answer("how many cm in 3 feet"))
        assertEquals("2 GB = 2048 MB", answer("2 gb in mb"))
        assertTrue(answer("5 kg to km").startsWith("Can't convert"))
        assertNull(UnitConverter.tryConvert("move a.txt to Archive"))
    }

    @Test fun dates() {
        val today = LocalDate.of(2026, 11, 9)
        assertEquals("In 10 days it will be Thursday, 19 November 2026.", DateMath.tryAnswer("what day is it in 10 days", today))
        assertEquals("46 days until Friday, 25 December 2026.", DateMath.tryAnswer("how many days until christmas?", today))
        assertEquals("Friday, 25 December 2026 is a Friday.", DateMath.tryAnswer("what day is 2026-12-25", today))
        assertTrue(DateMath.tryAnswer("how many days since 2026-01-01", today)!!.startsWith("312 days"))
        assertEquals("2 weeks ago it was Monday, 26 October 2026.", DateMath.tryAnswer("what date was 2 weeks ago", today))
        assertNull(DateMath.tryAnswer("what day is it", today))
    }

    @Test fun toolCommandsStillWinOverComputation() {
        assertIs<ParsedCommand.Tool>(parser.parse("set a timer for 5 minutes"))
        assertIs<ParsedCommand.Tool>(parser.parse("what time is it"))
        assertIs<ParsedCommand.Tool>(parser.parse("move notes.txt to Archive"))
        val name = parser.parse("my name is ana")
        assertIs<ParsedCommand.Tool>(name)
        assertEquals("My name is Ana", name.request.args["fact"])
        assertEquals(ParsedCommand.NotACommand, parser.parse("call me back later"))
    }

    @Test fun smallTalk() {
        val talk = SmallTalk(Random(1))
        val ctx = SmallTalk.Context(userName = "Ana", hourOfDay = 9)
        listOf("hi", "Hello Zed!", "how are you?", "who are you", "thanks", "good night", "tell me a joke", "you're awesome", "what's my name", "ok").forEach {
            assertNotNull(talk.respond(it, ctx), it)
        }
        assertEquals("Of course — you're Ana.", talk.respond("what's my name?", ctx))
        assertTrue(talk.respond("what's my name", SmallTalk.Context())!!.contains("my name is"))
        assertNull(talk.respond("explain how vaccines work", ctx))
        assertTrue(talk.fallback("set up a meeting sometime", SmallTalk.Context()).contains("schedule meeting"))
    }

    @Test fun conversationalFollowUpsAndFun() {
        val talk = SmallTalk(Random(7))
        val ctx = SmallTalk.Context(userName = "Rommel", hourOfDay = 15)
        assertTrue(talk.respond("how are you?", ctx)!!.isNotBlank())
        assertTrue(talk.respond("and you?", ctx)!!.contains("well") || talk.respond("and you?", ctx)!!.isNotBlank())
        val j1 = talk.respond("tell me a joke", ctx)!!
        val j2 = talk.respond("another one", ctx)!!
        assertTrue(j1 != j2, "jokes should not repeat back to back")
        val riddle = talk.respond("tell me a riddle", ctx)!!
        assertTrue(riddle.endsWith("“I give up”.)"))
        assertTrue(talk.respond("I give up", ctx)!!.startsWith("It's"))
        assertTrue(talk.respond("flip a coin", ctx)!!.matches(Regex("It's (heads|tails)!")))
        assertTrue(talk.respond("roll 2 dice", ctx)!!.contains("total"))
        val n = talk.respond("pick a random number between 5 and 7", ctx)!!.removeSuffix(".").toInt()
        assertTrue(n in 5..7)
        assertTrue(talk.respond("recommend a book", ctx)!!.startsWith("Try"))
        assertTrue(talk.respond("I'm feeling stressed", ctx)!!.isNotBlank())
        assertTrue(talk.respond("what's the weather like today", ctx)!!.contains("weather"))
        assertTrue(talk.respond("do you like music?", ctx)!!.contains("music"))
        assertTrue(talk.respond("what's your favorite color", ctx)!!.contains("Cyan"))
        assertNull(talk.respond("explain how vaccines work", ctx))
    }
}
