package com.melhive.zed.core

import com.melhive.zed.core.learning.Aliases
import com.melhive.zed.core.learning.Pronunciations
import com.melhive.zed.core.learning.Teaching
import com.melhive.zed.core.learning.Teaching.Taught
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TeachingTest {
    @Test fun recognisesLessons() {
        assertEquals(Taught.Pronounce("Cebu", "seh-boo"), Teaching.detect("Hey Zed, say Cebu like “seh-boo”."))
        assertEquals(Taught.Pronounce("Siargao", "shar-gow"), Teaching.detect("you're pronouncing Siargao wrong, it's shar-gow"))
        assertEquals(Taught.Rule("Be more brief."), Teaching.detect("be more brief"))
        assertEquals(Taught.Rule("Answer in Tagalog when I speak Tagalog."), Teaching.detect("answer in Tagalog when I speak Tagalog"))
        assertEquals(Taught.Rule("Never apologize so much."), Teaching.detect("never apologize so much"))
        assertEquals(Taught.Rule("I prefer you to explain things simply."), Teaching.detect("I prefer you to explain things simply"))
        assertEquals(Taught.Alias("good night", "run my good night routine"), Teaching.detect("whenever I say \"good night\", do run my good night routine"))
        assertEquals(Taught.Speed(true), Teaching.detect("speak a bit faster please"))
    }

    @Test fun ordinaryRequestsAreNotLessons() {
        listOf("don't forget to buy milk", "stop the music", "never mind", "from now on remind me to drink water every hour",
            "I prefer coffee", "say hello to Ana", "always lock the door", "don't add milk to my list", "don't start the timer").forEach {
            assertNull(Teaching.detect(it), it)
        }
    }

    @Test fun taughtPronunciationsAreSpoken() {
        val words = mapOf("Cebu" to "seh-boo", "ZED" to "zed", "New York" to "noo york")
        assertEquals("Flights to seh-boo and noo york from seh-boo.", Pronunciations.apply("Flights to Cebu and New York from CEBU.", words))
        assertEquals("Cebuano is a language.", Pronunciations.apply("Cebuano is a language.", words), "whole words only")
        assertEquals("unchanged", Pronunciations.apply("unchanged", emptyMap()))
    }

    @Test fun shortcutsExpand() {
        val aliases = mapOf("lights out" to "turn off the lights and turn on do not disturb")
        assertEquals("turn off the lights and turn on do not disturb", Aliases.expand("Hey Zed, lights out!", aliases))
        assertEquals("turn off the lights and turn on do not disturb", Aliases.expand("lights out please", aliases))
        assertNull(Aliases.expand("are the lights out?", aliases))
    }
}
