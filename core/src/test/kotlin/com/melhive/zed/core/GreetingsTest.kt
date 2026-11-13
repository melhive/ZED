package com.melhive.zed.core

import com.melhive.zed.core.voice.Greetings
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GreetingsTest {
    @Test fun manyVariationsThatReadNaturally() {
        assertTrue(Greetings.all.size >= 30, "only ${Greetings.all.size} greetings")
        val r = Random(7)
        val said = (0 until 200).map { Greetings.pick(it % 24, "sir", null, random = r) }
        assertTrue(said.toSet().size >= 20, "too little variety: ${said.toSet().size}")
        assertTrue(said.all { it.contains(", sir") && !it.contains("{who}") }, said.firstOrNull { !it.contains(", sir") }.toString())
        val plain = (0 until 100).map { Greetings.pick(it % 24, null, null, random = r) }
        assertTrue(plain.none { it.contains(" ,") || it.contains(",.") || it.contains(",?") || it.contains("{") }, plain.toString())
    }

    @Test fun titleWinsOverNameAndNeverRepeatsTwiceInARow() {
        assertTrue(Greetings.pick(10, "boss", "Rommel", random = Random(1)).contains("boss"))
        assertTrue(Greetings.pick(10, "", "Rommel", random = Random(1)).contains("Rommel"))
        var last: String? = null
        val r = Random(3)
        repeat(100) { val g = Greetings.pick(20, "sir", null, last, r); assertNotEquals(last, g); last = g }
    }

    @Test fun lateNightGreetingsFitTheHour() {
        val night = (0 until 60).map { Greetings.pick(2, null, null, random = Random(it)) }
        assertTrue(night.none { it.startsWith("Good morning") || it.startsWith("Good afternoon") || it.startsWith("Good evening") }, night.toString())
    }
}
