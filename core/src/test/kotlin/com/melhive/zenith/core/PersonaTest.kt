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

class NativeToolSchemaTest {
    private val cat = com.melhive.zenith.core.tools.ToolCatalog.DEFAULT
    private val S = com.melhive.zenith.core.tools.NativeToolSchema

    @Test fun namesRoundTrip() {
        for (t in cat.all) {
            val wire = S.wireName(t.name)
            assertTrue(Regex("^[a-zA-Z0-9_-]{1,64}$").matches(wire), wire)
            assertEquals(t.name, S.toolName(wire))
        }
    }

    @Test fun schemaTypesAndRequired() {
        val alarm = S.jsonSchema(cat.find("alarm.set")!!).toString()
        assertTrue("\"hour\":{\"type\":\"integer\"" in alarm, alarm)
        assertTrue("\"required\":[\"hour\",\"minute\"]" in alarm, alarm)
        val setting = S.geminiSchema(cat.find("settings.set")!!).toString()
        assertTrue("\"type\":\"OBJECT\"" in setting)
        assertTrue("\"enum\":[" in setting)
    }

    @Test fun argsBecomeStrings() {
        val json = kotlinx.serialization.json.Json.parseToJsonElement("""{"hour":7,"minute":30,"flag":true,"label":"wake","skip":null}""") as kotlinx.serialization.json.JsonObject
        assertEquals(mapOf("hour" to "7", "minute" to "30", "flag" to "true", "label" to "wake"), S.argsToStrings(json))
    }
}
