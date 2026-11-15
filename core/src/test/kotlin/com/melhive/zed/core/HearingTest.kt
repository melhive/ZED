package com.melhive.zed.core

import com.melhive.zed.core.understanding.MemoryFact
import com.melhive.zed.core.understanding.MemoryRanker
import com.melhive.zed.core.voice.HearingFix
import com.melhive.zed.core.voice.LearnAsk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HearingTest {
    @Test fun `spelled letters become one word`() {
        assertEquals("what is XAUUSD", HearingFix.joinLetters("what is x a u u s d"))
        assertEquals("buy BTC now", HearingFix.joinLetters("buy b t c now"))
        assertEquals("the price of XAUUSD?", HearingFix.joinLetters("the price of x a u u s d?"))
        assertEquals("open H2O", HearingFix.joinLetters("open h 2 o"))
        // Ordinary sentences are left alone.
        assertEquals("i am a student", HearingFix.joinLetters("i am a student"))
        assertEquals("plan a or b", HearingFix.joinLetters("plan a or b"))
    }

    @Test fun `words from the user's vocabulary are heard right`() {
        val vocab = listOf("Kaelyn", "Siargao", "XAUUSD", "Rommel", "Termux")
        assertEquals("call Kaelyn tomorrow", HearingFix.apply("call kay lin tomorrow", vocabulary = vocab))
        assertEquals("call Kaelyn", HearingFix.apply("call kaylin", vocabulary = vocab))
        assertEquals("the weather in Siargao.", HearingFix.apply("the weather in siargao.", vocabulary = vocab))
        assertEquals("open Termux", HearingFix.apply("open termux", vocabulary = vocab))
        assertEquals("what is XAUUSD today", HearingFix.apply("what is x a u u s d today", vocabulary = vocab))
        // Everyday words are never turned into names, however alike they sound.
        assertEquals("i will call you later", HearingFix.apply("i will call you later", vocabulary = listOf("Kahl", "Leyter", "Wilcal")))
        assertEquals("what time is it", HearingFix.apply("what time is it", vocabulary = vocab))
        assertEquals("remind me to buy rice", HearingFix.apply("remind me to buy rice", vocabulary = listOf("Reiss", "Bryce")))
        assertEquals("that was a nice sermon", HearingFix.apply("that was a nice sermon", vocabulary = listOf("Surman")))
    }

    @Test fun `corrections are learned and used again`() {
        assertEquals("Kaelyn", HearingFix.correction("no, I said Kaelyn"))
        assertEquals("XAUUSD", HearingFix.correction("I meant XAUUSD."))
        assertEquals(null, HearingFix.correction("what time is it"))
        val placed = HearingFix.place("send a message to kay lin about dinner", "Kaelyn")
        assertEquals("kay lin" to "send a message to Kaelyn about dinner", placed)
        assertEquals(null, HearingFix.place("what is the weather", "Kaelyn"))
        assertEquals(listOf("kay lin" to "Kaelyn"), HearingFix.learnFrom("call kay lin tomorrow", "call Kaelyn tomorrow"))
        assertEquals(listOf("x a u u s d" to "XAUUSD", "to day" to "today"), HearingFix.learnFrom("price of x a u u s d for to day", "price of XAUUSD for today"))
        assertEquals(emptyList(), HearingFix.learnFrom("call ana", "call Ana"))
        assertEquals("call Kaelyn at six", HearingFix.apply("call kay lin at six", learned = mapOf("kay lin" to "Kaelyn")))
    }

    @Test fun `names and symbols are picked out as vocabulary`() {
        assertEquals(listOf("Kaelyn", "Siargao", "XAUUSD"), HearingFix.vocabulary("my friend Kaelyn lives in Siargao and trades XAUUSD every day"))
        assertEquals(emptyList(), HearingFix.vocabulary("remind me to buy milk tomorrow"))
    }

    @Test fun `teaching words, subjects and interests`() {
        assertEquals("XAUUSD", LearnAsk.word("learn the word x a u u s d"))
        assertEquals("Kaelyn", LearnAsk.word("add Kaelyn to your vocabulary"))
        assertEquals(null, LearnAsk.word("learn to cook"))
        assertEquals("forex trading", LearnAsk.topic("research forex trading and learn it"))
        assertEquals("the Philippine eagle", LearnAsk.topic("learn about the Philippine eagle"))
        assertEquals(null, LearnAsk.topic("what is the weather"))
        assertEquals(listOf("forex trading", "astronomy"), LearnAsk.interests("I'm interested in forex trading and astronomy"))
        assertEquals(emptyList(), LearnAsk.interests("I'm tired"))
    }

    @Test fun `only memories that bear on the topic reach an answer`() {
        val facts = listOf(
            MemoryFact(1, "Ana is my sister and she loves hiking"),
            MemoryFact(2, "I work at Acme as a designer"),
            MemoryFact(3, "My dentist is Dr Cruz on Mango Avenue"),
            MemoryFact(4, "I am planning a trip to Siargao in December", pinned = true),
            MemoryFact(5, "I work from home on Fridays"),
            MemoryFact(6, "My work laptop is a ThinkPad"),
            MemoryFact(7, "I started a new work project about onboarding"),
        )
        fun ids(q: String, prev: String? = null) = MemoryRanker.rank(q, facts, previous = prev, bridges = false, strict = true)
        // A distinctive word brings its memory in, and nothing else.
        assertEquals(listOf(1L), ids("what should I get Ana for her birthday").map { it.fact.id })
        assertEquals(listOf(3L), ids("when is my dentist appointment").filter { !it.related }.map { it.fact.id })
        // A pinned memory about something else stays out.
        assertFalse(ids("how do I boil an egg").any { it.fact.id == 4L })
        assertTrue(ids("how do I boil an egg").isEmpty())
        // A word that many memories share proves nothing by itself: at most a couple come back, marked as only related.
        val work = ids("how was work today")
        assertTrue(work.all { it.related }, work.toString())
        assertTrue(work.size <= 2)
        // The previous message alone makes a memory related, not direct.
        val follow = ids("and how far is it", prev = "tell me about my Siargao trip")
        assertEquals(listOf(4L), follow.map { it.fact.id })
        assertTrue(follow.single().related)
        // Asked directly, it is direct.
        assertFalse(ids("what do you know about my Siargao trip").first { it.fact.id == 4L }.related)
    }
}
