package com.melhive.zenith.core

import com.melhive.zenith.core.command.CommandParser
import com.melhive.zenith.core.command.ParsedCommand
import com.melhive.zenith.core.security.ConfirmationPolicy
import com.melhive.zenith.core.skills.SkillRegistry
import com.melhive.zenith.core.tools.ToolCatalog
import com.melhive.zenith.core.tools.ToolRequest
import com.melhive.zenith.core.tools.ToolValidator
import com.melhive.zenith.core.tools.ValidationResult
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PhoneControlTest {
    private val parser = CommandParser { LocalDateTime.of(2026, 11, 10, 9, 0) }
    private val validator = ToolValidator(ToolCatalog.DEFAULT)

    private fun tool(input: String): Pair<String, Map<String, String>> {
        val r = parser.parse(input)
        assertIs<ParsedCommand.Tool>(r, "Expected a tool for '$input' but got $r")
        assertIs<ValidationResult.Valid>(validator.validate(r.request), "Invalid for '$input'")
        return r.request.tool to r.request.args
    }

    @Test fun deviceControls() {
        assertEquals("flashlight.set" to mapOf("on" to "true"), tool("turn on the flashlight"))
        assertEquals("flashlight.set" to mapOf("on" to "false"), tool("flashlight off"))
        assertEquals("flashlight.set" to mapOf("on" to "true"), tool("Zenith, turn the torch on"))
        assertEquals("dnd.set" to mapOf("on" to "true"), tool("turn on do not disturb"))
        assertEquals("dnd.set" to mapOf("on" to "false"), tool("disable dnd"))
        assertEquals("volume.set" to mapOf("action" to "up"), tool("volume up"))
        assertEquals("volume.set" to mapOf("action" to "down"), tool("turn it quieter"))
        assertEquals("volume.set" to mapOf("action" to "set", "level" to "40"), tool("set volume to 40%"))
        assertEquals("volume.set" to mapOf("action" to "set", "level" to "70", "stream" to "ring"), tool("set the ringer volume to 70 percent"))
        assertEquals("volume.set" to mapOf("action" to "mute"), tool("mute"))
        assertEquals("device.status" to emptyMap<String, String>(), tool("how much battery left"))
        assertEquals("settings.panel" to mapOf("panel" to "wifi"), tool("open wifi settings"))
        assertEquals("settings.panel" to mapOf("panel" to "bluetooth"), tool("bluetooth settings"))
        assertEquals("camera.open" to mapOf("mode" to "photo"), tool("take a selfie"))
        assertEquals("camera.open" to mapOf("mode" to "video"), tool("record a video"))
    }

    @Test fun mediaAndApps() {
        assertEquals("media.control" to mapOf("action" to "pause"), tool("pause the music"))
        assertEquals("media.control" to mapOf("action" to "play"), tool("resume"))
        assertEquals("media.control" to mapOf("action" to "next"), tool("next song"))
        assertEquals("media.control" to mapOf("action" to "previous"), tool("previous track"))
        assertEquals("music.play" to mapOf("query" to "lo-fi beats"), tool("play lo-fi beats on spotify"))
        assertEquals("app.open" to mapOf("name" to "spotify"), tool("open spotify"))
        assertEquals("app.open" to mapOf("name" to "Gmail"), tool("launch the Gmail app"))
        assertEquals("maps.navigate" to mapOf("destination" to "SM Megamall"), tool("navigate to SM Megamall"))
        assertEquals("maps.navigate" to mapOf("destination" to "home"), tool("take me home"))
        assertEquals("web.open" to mapOf("query" to "best ramen near me"), tool("google best ramen near me"))
        assertEquals("web.open" to mapOf("query" to "github.com/melhive"), tool("go to github.com/melhive"))
    }

    @Test fun callsMessagesNotifications() {
        assertEquals("phone.call" to mapOf("who" to "Mom"), tool("call Mom"))
        assertEquals("phone.call" to mapOf("who" to "0917 555 0142"), tool("dial 0917 555 0142"))
        assertEquals("sms.compose" to mapOf("to" to "Ana", "message" to "I'm running late"), tool("text Ana saying I'm running late"))
        assertEquals("sms.compose" to mapOf("to" to "Ana", "message" to "see you at 5"), tool("send a message to Ana: see you at 5"))
        assertEquals("notifications.recent" to emptyMap<String, String>(), tool("what did I miss"))
        assertEquals("notifications.recent" to emptyMap<String, String>(), tool("any new notifications?"))
        // "call me X" is a name, not a phone call.
        val r = parser.parse("you can call me Rommel")
        assertIs<ParsedCommand.Tool>(r)
        assertEquals("memory.remember", r.request.tool)
    }

    @Test fun fileCommandsStillWin() {
        assertEquals("file.read", tool("open the file notes/todo.txt").first)
        assertEquals("timer.set", tool("start a timer for 5 minutes").first)
    }

    @Test fun riskFlagsDriveConfirmation() {
        val cat = ToolCatalog.DEFAULT
        val model = ToolRequest("x", origin = ToolRequest.Origin.ONLINE_MODEL)
        val native = ToolRequest("x", origin = ToolRequest.Origin.NATIVE)
        assertFalse(ConfirmationPolicy.requiresConfirmation(cat.find("flashlight.set")!!, model))
        assertTrue(ConfirmationPolicy.requiresConfirmation(cat.find("flashlight.set")!!, model, confirmAllChanges = true))
        assertTrue(ConfirmationPolicy.requiresConfirmation(cat.find("phone.call")!!, native))
        assertTrue(ConfirmationPolicy.requiresConfirmation(cat.find("sms.compose")!!, model))
        assertFalse(ConfirmationPolicy.requiresConfirmation(cat.find("notifications.recent")!!, model))
        // Every tool belongs to a skill.
        for (t in cat.all) assertTrue(SkillRegistry.DEFAULT.skillForTool(t.name) != null, "no skill for ${t.name}")
    }
}
