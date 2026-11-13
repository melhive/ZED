package com.melhive.zed.core

import com.melhive.zed.core.brain.BrainBuilder
import com.melhive.zed.core.brain.BrainItem
import com.melhive.zed.core.brain.BrainNodeType
import com.melhive.zed.core.command.CommandParser
import com.melhive.zed.core.command.ParsedCommand
import com.melhive.zed.core.learning.Corrections
import com.melhive.zed.core.learning.DreamLogic
import com.melhive.zed.core.learning.Hints
import com.melhive.zed.core.learning.Insight
import com.melhive.zed.core.learning.Insights
import com.melhive.zed.core.learning.Topics
import com.melhive.zed.core.learning.Relation
import com.melhive.zed.core.learning.RelationPatterns
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeeperIntelligenceTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test fun relationshipsFromMemories() {
        assertEquals(listOf(Relation("Ana", "sister", "me")), RelationPatterns.extract("My sister's name is Ana."))
        assertEquals(listOf(Relation("Dr. Cruz", "dentist", "me"), Relation("Dr. Cruz", "in", "Cebu")), RelationPatterns.extract("Dr. Cruz is my dentist in Cebu"))
        assertEquals(listOf(Relation("me", "works at", "Accenture")), RelationPatterns.extract("I work at Accenture."))
        assertEquals(listOf(Relation("Ana", "allergic to", "peanuts")), RelationPatterns.extract("Ana is allergic to peanuts."))
        assertEquals(listOf(Relation("Mark", "lives in", "Davao City")), RelationPatterns.extract("Mark lives in Davao City"))
        assertTrue(RelationPatterns.extract("I like tea").isEmpty())
    }

    @Test fun modelRelationshipsAreReadStrictly() {
        val out = """
            - Ana | sister | user
            - Dr. Cruz | dentist | me
            - this line has no pipes
            - A | is the very long relation name | B
            - none
        """.trimIndent()
        assertEquals(listOf(Relation("Ana", "sister", "me"), Relation("Dr. Cruz", "dentist", "me")), RelationPatterns.parse(out))
    }

    @Test fun brainDrawsLabelledRelationships() {
        val items = listOf(
            BrainItem(BrainNodeType.FACT, 1, "My sister's name is Ana", 1), BrainItem(BrainNodeType.FACT, 2, "Ana is allergic to peanuts", 2),
            BrainItem(BrainNodeType.FACT, 3, "Dr. Cruz is my dentist in Cebu", 3),
        )
        val links = listOf(BrainBuilder.Link("Ana", "sister", "me"), BrainBuilder.Link("Dr. Cruz", "in", "Cebu"))
        val g = BrainBuilder.build(items, links = links)
        val ana = g.indexOf("concept:ana")
        assertTrue(ana > 0, "Ana reuses the shared concept")
        assertEquals("sister", g.edgeLabel(ana, g.indexOf("area:You").takeIf { it >= 0 } ?: 0))
        val cruz = g.indexOf("concept:cruz")
        val cebu = g.indexOf("concept:cebu")
        assertTrue(cruz > 0 && cebu > 0)
        assertEquals("in", g.edgeLabel(cruz, cebu))
        assertTrue(g.neighbors[cruz].any { g.nodes[it].key == "FACT:3" }, "new names connect to the memories that mention them")
        assertEquals("concept:ana", g.nodes[g.find("Ana").first()].key)
        assertTrue(g.find("").isEmpty())
    }

    @Test fun thatsWrong() {
        assertEquals(Corrections.Found.WrongOnly, Corrections.detect("That's wrong"))
        assertEquals(Corrections.Found.WrongOnly, Corrections.detect("no, that's not right!"))
        assertEquals(Corrections.Found.WithFix("Her birthday is March 3"), Corrections.detect("That's wrong, her birthday is March 3"))
        assertEquals(Corrections.Found.WithFix("Manila is the capital"), Corrections.detect("Wrong. Actually Manila is the capital."))
        assertEquals(Corrections.Found.WithFix("42"), Corrections.detect("you're wrong, the answer is 42"))
        assertNull(Corrections.detect("what's wrong with my phone"))
        assertNull(Corrections.detect("the wrong turn was funny"))
        val lesson = Corrections.lesson("When is Ana's birthday?", "Ana's birthday is May 5.", "Her birthday is March 3")
        assertTrue(lesson.contains("March 3") && lesson.contains("not “Ana's birthday is May 5.”"), lesson)
    }

    @Test fun insightsFromRepeatedTopics() {
        val msgs = listOf("I need to see the dentist" to at(3, 9), "my tooth hurts, dentist soon" to at(5, 20), "remember to call the dentist" to at(9, 8), "nice weather" to at(9, 9))
        val i = Insights.topics(msgs, at(10, 12), covered = emptyList(), seen = emptySet(), zone = zone)
        assertEquals(listOf("topic:dentist"), i.map { it.key })
        assertEquals(Insight.Action.REMIND, i.single().action)
        assertTrue(Insights.topics(msgs, at(10, 12), covered = listOf("Dentist appointment"), seen = emptySet(), zone = zone).isEmpty(), "already has a reminder")
        assertTrue(Insights.topics(msgs, at(10, 12), covered = emptyList(), seen = setOf("topic:dentist"), zone = zone).isEmpty(), "offered once only")
        val sameDay = listOf("dentist" to at(9, 8), "dentist again" to at(9, 9), "dentist!" to at(9, 10))
        assertTrue(Insights.topics(sameDay, at(10, 12), emptyList(), emptySet(), zone).isEmpty(), "needs more than one day")
    }

    @Test fun aRepeatedWordIsNotATopic() {
        // "coffee" said every day, with nothing that makes it matter.
        val chatter = listOf("I love the coffee here" to at(2, 9), "the coffee was great" to at(4, 9), "had the coffee again" to at(6, 9), "my coffee is cold lol" to at(8, 9))
        assertTrue(Insights.topics(chatter, at(10, 12), emptyList(), emptySet(), zone).isEmpty(), "just a repeated word")
        // Requests to ZED are not topics of the user's life.
        val commands = listOf("turn on the lights" to at(2, 20), "turn off the lights tomorrow" to at(4, 20), "set the lights to 50" to at(6, 20))
        assertTrue(Insights.topics(commands, at(10, 12), emptyList(), emptySet(), zone).isEmpty(), "commands")
    }

    @Test fun anOngoingMatterIsATopic() {
        val msgs = listOf(
            "I have to finish my thesis defense slides" to at(2, 21),
            "my thesis defense is on Friday, I'm so nervous" to at(5, 22),
            "can you explain p-values? it's for my thesis" to at(7, 20),
            "I love the coffee here" to at(7, 9), "the coffee was great" to at(8, 9), "had the coffee again" to at(9, 9),
        )
        val i = Insights.topics(msgs, at(10, 12), emptyList(), emptySet(), zone).single()
        assertEquals("topic:thesis", i.key)
        assertEquals("my thesis defense", i.payload)
        assertTrue(i.text.startsWith("We've talked about your thesis defense on 3 different days") && i.text.contains("date"), i.text)
        assertEquals(listOf("my sister's wedding"), Topics.phrases("my sister's wedding is next month").map { it.text })
    }

    @Test fun modelTopicsMustBeGrounded() {
        val msgs = listOf("the visa forms are confusing" to at(2, 9), "I still haven't sent the visa photos" to at(6, 9), "nice weather" to at(7, 9))
        val out = "Visa application | You're still preparing the forms and photos\nMoon landing | You love space\nNONE"
        assertEquals(listOf("Visa application" to "You're still preparing the forms and photos"), Topics.parse(out, msgs, zone))
    }

    @Test fun habitsBecomeRoutineSuggestions() {
        val dnd = (1..4).map { Insights.Done("dnd.set", "{on=true}", at(it, 22, 5 * it)) }
        val h = Insights.habits(dnd, at(6, 12), emptySet(), zone)
        assertEquals(1, h.size)
        assertTrue(h.single().text.contains("Do Not Disturb") && h.single().text.contains("10:"), h.single().text)
        assertEquals(Insight.Action.ROUTINE, h.single().action)
        val scattered = listOf(Insights.Done("dnd.set", "{on=true}", at(1, 8)), Insights.Done("dnd.set", "{on=true}", at(2, 15)), Insights.Done("dnd.set", "{on=true}", at(3, 22)))
        assertTrue(Insights.habits(scattered, at(6, 12), emptySet(), zone).isEmpty(), "different hours aren't a habit")
        assertTrue(Insights.habits((1..4).map { Insights.Done("time.now", "{}", at(it, 9)) }, at(6, 12), emptySet(), zone).isEmpty(), "questions aren't habits")
    }

    @Test fun dreamingMergesDuplicates() {
        val facts = listOf(
            DreamLogic.Fact(1, "My sister's name is Ana", pinned = false, confirmed = true, uses = 1, createdAt = 1),
            DreamLogic.Fact(2, "My sister's name is Ana.", pinned = false, confirmed = false, uses = 5, createdAt = 2),
            DreamLogic.Fact(3, "I like tea", pinned = false, confirmed = true, uses = 0, createdAt = 3),
        )
        assertEquals(listOf(1L to listOf(2L)), DreamLogic.duplicates(facts), "the confirmed one stays")
        assertTrue(DreamLogic.duplicates(facts.take(1) + facts.last()).isEmpty())
    }

    @Test fun hintsStayRare() {
        val now = at(10, 12)
        assertTrue(Hints.allowed(emptyList(), now))
        assertTrue(!Hints.allowed(listOf(now - 3_600_000L), now), "one every three hours")
        assertTrue(!Hints.allowed(listOf(now - 4 * 3_600_000L, now - 8 * 3_600_000L, now - 12 * 3_600_000L), now), "three a day")
        val msg = Hints.leaveNow("Dentist", now + 40 * 60_000L, "Cebu Doctors", now, emptySet(), zone)
        assertEquals("“Dentist” is at 12:40 PM at Cebu Doctors — time to get going.", msg)
        assertNull(Hints.leaveNow("Dentist", now + 40 * 60_000L, null, now, emptySet(), zone), "no place, no travel")
        assertNull(Hints.leaveNow("Dentist", now + 90 * 60_000L, "Cebu", now, emptySet(), zone), "too early")
        assertNull(Hints.leaveNow("Dentist", now + 40 * 60_000L, "Cebu", now, setOf("Dentist@${now + 40 * 60_000L}"), zone), "once per event")
    }

    @Test fun brainByVoice() {
        val p = CommandParser()
        fun tool(t: String) = (p.parse(t) as? ParsedCommand.Tool)?.request?.let { it.tool to it.args }
        assertEquals("brain.show" to mapOf("query" to "Ana"), tool("show me everything about Ana"))
        assertEquals("brain.show" to mapOf("query" to "my dentist"), tool("show my dentist in the brain"))
        assertEquals("brain.show" to mapOf("query" to "work"), tool("what do you know about work in your brain".replace("what do you know", "show me what you know")))
        assertEquals("brain.show" to emptyMap<String, String>(), tool("open the live brain"))
    }
}
