package com.melhive.zenith.core

import com.melhive.zenith.core.conversation.SmallTalk
import com.melhive.zenith.core.intelligence.Persona
import com.melhive.zenith.core.intelligence.PromptBuilder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersonaTest {
    @Test fun identityReflectsPersonaAndTitle() {
        val butler = PromptBuilder.identity(Persona.BUTLER, "sir")
        assertTrue("butler" in butler)
        assertTrue("\"sir\"" in butler)
        assertTrue("Never claim a device action happened" in butler)
        val plain = PromptBuilder.identity()
        assertTrue("companion" in plain)
        assertFalse("Address the user as" in plain)
        assertEquals(Persona.COMPANION, Persona.parse("nonsense"))
        assertEquals(Persona.PLAYFUL, Persona.parse("PLAYFUL"))
    }

    @Test fun systemPromptCarriesPersona() {
        val p = PromptBuilder.systemPrompt(emptyList(), null, emptyList(), emptyList(), voiceMode = false, persona = Persona.PROFESSIONAL, userTitle = "boss")
        assertTrue("concise, efficient professional" in p)
        assertTrue("\"boss\"" in p)
    }

    @Test fun smallTalkGreetsByTitle() {
        val reply = SmallTalk(Random(1)).respond("good morning", SmallTalk.Context(userName = "Rommel", addressAs = "sir", hourOfDay = 8))!!
        assertTrue("sir" in reply, reply)
        assertFalse("Rommel" in reply, reply)
    }
}
