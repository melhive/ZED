package com.melhive.zed.core

import com.melhive.zed.core.ui.ActionLabels
import com.melhive.zed.core.ui.PlasmaShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrbAndLabelsTest {
    @Test fun surfaceStaysRoundishAndCalmAtRest() {
        val r = PlasmaShape.radii(72, 3.7f)
        assertTrue(r.all { it in 0.85f..1.15f }, "rest shape: ${r.min()}..${r.max()}")
        val spread = r.max() - r.min()
        assertTrue(spread in 0.02f..0.25f, "a gentle drift, not a perfect circle: $spread")
        // Layers differ, so stacked layers read as plasma rather than one ring.
        assertTrue(PlasmaShape.radii(72, 3.7f, layer = 1).zip(r.toList()).any { (a, b) -> kotlin.math.abs(a - b) > 0.01f })
    }

    @Test fun voiceAndSpeechMoveTheSurface() {
        val calm = PlasmaShape.radii(72, 1f)
        val loud = PlasmaShape.radii(72, 1f, mic = 1f)
        assertTrue((loud.max() - loud.min()) > (calm.max() - calm.min()) + 0.05f, "a loud voice energises the surface")
        val wave = FloatArray(24).also { it[0] = 1f } // speech energy at the top only
        val spoken = PlasmaShape.radii(72, 1f, wave = wave)
        assertTrue(spoken[0] > calm[0] + 0.1f, "the top bulges with speech")
        assertTrue(kotlin.math.abs(spoken[36] - calm[36]) < 0.02f, "the bottom stays put")
        // Mirrored: left and right of the top move alike.
        assertEquals(spoken[5] - calm[5], spoken[67] - calm[67], 0.03f)
        assertTrue(PlasmaShape.radii(72, 1f, mic = 1f, wave = FloatArray(24) { 1f }).all { it <= 1.3f }, "never explodes")
    }

    @Test fun ripplesTravelInward() {
        val a = PlasmaShape.inwardRipples(1.00f, 0.5f).first().first
        val b = PlasmaShape.inwardRipples(1.05f, 0.5f).first().first
        assertTrue(b < a || a < 0.1f, "radius shrinks over time: $a → $b")
        val quiet = PlasmaShape.inwardRipples(2f, 0f).maxOf { it.second }
        val loud = PlasmaShape.inwardRipples(2f, 1f).maxOf { it.second }
        assertTrue(loud > quiet)
    }

    @Test fun speechEnvelopeFollowsLoudness() {
        val pcm = ShortArray(2400) { i -> if (i < 1200) 0 else (if (i % 2 == 0) 12000 else -12000).toShort() }
        val env = PlasmaShape.envelope(pcm, bins = 4)
        assertEquals(0f, env[0]); assertEquals(0f, env[1])
        assertTrue(env[2] > 0.5f && env[3] > 0.5f)
        val shown = FloatArray(4)
        PlasmaShape.follow(shown, env, 0.05f)
        assertTrue(shown[3] in 0.3f..env[3], "fast attack, but eased")
    }

    @Test fun thinkingIsCheap() {
        assertEquals(20, PlasmaShape.framesPerSecond("THINKING", light = false))
        assertEquals(60, PlasmaShape.framesPerSecond("SPEAKING", light = false))
        assertEquals(12, PlasmaShape.framesPerSecond("SPEAKING", light = true))
        assertTrue(PlasmaShape.synapses(4.2f).all { (x, y, b) -> x in -0.7f..0.7f && y in -0.7f..0.7f && b in 0f..1f })
    }

    @Test fun wakePhrasesAreCheckedAgainstTheModel() {
        val known = setOf("▁", "Z", "ED", "▁HE", "Y", "▁HI")
        val kw = "▁ Z ED @ZED\n▁HE Y ▁ Z ED @ZED\n▁OKAY ▁ Z ED @ZED\n▁HI ▁ Z ED @ZED"
        assertEquals("▁ Z ED @ZED\n▁HE Y ▁ Z ED @ZED\n▁HI ▁ Z ED @ZED", com.melhive.zed.core.voice.WakeWords.safe(kw, known), "a phrase with an unknown token is dropped")
        assertEquals(com.melhive.zed.core.voice.WakeWords.FALLBACK, com.melhive.zed.core.voice.WakeWords.safe("▁OKAY ▁ZED @ZED", known))
        assertEquals(kw, com.melhive.zed.core.voice.WakeWords.safe(kw, emptySet()), "no token list: leave as is")
    }

    @Test fun actionLabelsAndChips() {
        assertEquals("Setting alarm…", ActionLabels.progress("alarm.set"))
        assertEquals("Searching the web…", ActionLabels.progress("web.research"))
        assertEquals("Fly…", ActionLabels.progress("fly.drone"))
        assertEquals("Alarm 6:05", ActionLabels.chip("alarm.set", mapOf("hour" to "6", "minute" to "5")))
        assertEquals("Timer 5 min", ActionLabels.chip("timer.set", mapOf("seconds" to "300")))
        assertEquals("Timer 1 h", ActionLabels.chip("timer.set", mapOf("seconds" to "3600")))
        assertEquals("Timer 1 min 30 s", ActionLabels.chip("timer.set", mapOf("seconds" to "90")))
        assertEquals("Reminder 5:00 PM", ActionLabels.chip("reminder.create", mapOf("text" to "x", "at" to "2026-11-12T17:00")))
        assertEquals("Flashlight on", ActionLabels.chip("flashlight.set", mapOf("on" to "true")))
        assertEquals("Message to Ana", ActionLabels.chip("sms.compose", mapOf("to" to "Ana", "message" to "hi")))
        assertNull(ActionLabels.chip("time.now", emptyMap()))
        assertEquals("weather", ActionLabels.cardKind("weather.get"))
        assertEquals("schedule", ActionLabels.cardKind("calendar.today"))
        assertNull(ActionLabels.cardKind("alarm.set"))
    }
}
