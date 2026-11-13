package com.melhive.zed.core

import com.melhive.zed.core.eyes.EyeActivity
import com.melhive.zed.core.eyes.EyeArt
import com.melhive.zed.core.eyes.EyeEmotion
import com.melhive.zed.core.eyes.EyeFrame
import com.melhive.zed.core.eyes.EyeMind
import com.melhive.zed.core.eyes.EyeMood
import com.melhive.zed.core.eyes.GazeMode
import com.melhive.zed.core.eyes.SceneWatcher
import com.melhive.zed.core.eyes.VisualAsk
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EyesTest {
    private fun run(m: EyeMind, seconds: Float, each: (Float) -> Unit = {}) {
        var t = 0f
        while (t < seconds) { m.update(1f / 60f); t += 1f / 60f; each(t) }
    }

    @Test fun `eyes follow a face, search when it leaves, then wander`() {
        val m = EyeMind(1)
        m.activity = EyeActivity.LISTENING
        run(m, 1f)
        assertEquals(GazeMode.WANDERING, m.gazeMode)
        m.faceSeen = true; m.faceX = 0.6f; m.faceY = -0.2f
        run(m, 0.6f)
        assertEquals(GazeMode.FACE, m.gazeMode)
        assertTrue(abs(m.left.gazeX - 0.6f) < 0.15f, "looks at the face: ${m.left.gazeX}")
        m.faceSeen = false
        run(m, 0.5f)
        assertEquals(GazeMode.FACE, m.gazeMode, "one bad moment doesn't lose the face")
        run(m, 1f)
        assertEquals(GazeMode.SEARCHING, m.gazeMode)
        run(m, 5f)
        assertEquals(GazeMode.WANDERING, m.gazeMode)
    }

    @Test fun `a shown object wins over the face`() {
        val m = EyeMind(2)
        m.faceSeen = true; m.faceX = -0.5f
        run(m, 1f)
        m.attending = true; m.objectSeen = true; m.objectX = 0.7f; m.objectY = 0.3f
        m.activity = EyeActivity.THINKING
        run(m, 0.6f)
        assertEquals(GazeMode.OBJECT, m.gazeMode)
        assertTrue(m.left.gazeX > 0.5f)
        assertTrue(m.left.pupil < 0.35f, "pupils tighten to focus")
        // Without a clear object it looks toward the middle of the camera's view.
        m.objectSeen = false
        run(m, 0.6f)
        assertTrue(abs(m.left.gazeX) < 0.15f)
        m.attending = false
        run(m, 0.6f)
        assertEquals(GazeMode.FACE, m.gazeMode)
    }

    @Test fun `eyes blink, stay in range and get drowsy when nothing happens`() {
        val m = EyeMind(3)
        var closed = 0; var maxMembrane = 0f; var wasClosed = false
        run(m, 40f) {
            if (m.left.upper > 0.9f) { if (!wasClosed) closed++; wasClosed = true } else wasClosed = false
            maxMembrane = maxOf(maxMembrane, m.left.membrane)
            for (p in listOf(m.left, m.right)) {
                assertTrue(p.gazeX in -1f..1f && p.gazeY in -1f..1f && p.pupil in 0f..1f && p.upper in 0f..1f && p.lower in 0f..1f)
            }
        }
        assertTrue(closed in 5..40, "blinks: $closed")
        assertTrue(maxMembrane > 0.9f, "the inner lid sweeps")
        assertTrue(m.drowsy)
        m.activity = EyeActivity.LISTENING
        run(m, 0.5f)
        assertFalse(m.drowsy)
    }

    @Test fun `moods read from sentences`() {
        assertEquals(EyeMood.HAPPY, EyeEmotion.of("That's great news, congratulations!"))
        assertEquals(EyeMood.CONCERNED, EyeEmotion.of("I'm sorry, I couldn't find that file."))
        assertEquals(EyeMood.SURPRISED, EyeEmotion.of("Wow, that is incredible!"))
        assertEquals(EyeMood.AMUSED, EyeEmotion.of("Haha, that's a funny one."))
        assertEquals(EyeMood.CURIOUS, EyeEmotion.of("Would you like me to set a reminder?"))
        assertEquals(EyeMood.FOCUSED, EyeEmotion.of("Let me check: first, 1200 times 34 is 40800."))
        assertEquals(EyeMood.NEUTRAL, EyeEmotion.of("It is 3 pm."))
        assertEquals(EyeMood.CONCERNED, EyeEmotion.hearing("my dog died yesterday"))
        assertEquals(EyeMood.NEUTRAL, EyeEmotion.hearing("what time is it?"))
    }

    @Test fun `questions that need the camera`() {
        for (q in listOf("what is this", "What's this?", "what is that thing", "look at this", "can you see me", "what am I holding", "what do you see",
            "read this", "what does this say", "what color is this", "how do I look", "is this ripe", "what kind of plant is this", "do you see this")) {
            assertTrue(VisualAsk.matches(q), q)
        }
        for (q in listOf("what is the weather", "what is this song", "read this page", "what's on my screen", "what is the time", "what is this about",
            "set a timer", "what is a neutron star", "summarize this article")) {
            assertFalse(VisualAsk.matches(q), q)
        }
    }

    @Test fun `something held up is noticed and later fades into the background`() {
        val w = 32; val h = 24
        val watcher = SceneWatcher(w, h)
        val empty = IntArray(w * h) { 90 }
        repeat(30) { watcher.update(empty, 0.1f, null) }
        assertFalse(watcher.present)
        val shown = IntArray(w * h) { i -> if (i % w in 22..29 && i / w in 6..15) 200 else 90 }
        repeat(5) { watcher.update(shown, 0.1f, null) }
        assertTrue(watcher.present)
        assertTrue(watcher.x > 0.65f && watcher.y in 0.3f..0.6f, "${watcher.x},${watcher.y}")
        repeat(600) { watcher.update(shown, 0.1f, null) }
        assertFalse(watcher.present, "after a while it is part of the usual view")
        // The whole view changing (lights, the phone being moved) is not an object.
        val bright = IntArray(w * h) { i -> if (i % 2 == 0) 240 else 20 }
        repeat(3) { watcher.update(bright, 0.1f, null) }
        assertFalse(watcher.present)
        // Movement where the face is doesn't count.
        val w2 = SceneWatcher(w, h)
        repeat(30) { w2.update(empty, 0.1f, null) }
        val head = IntArray(w * h) { i -> if (i % w in 12..19 && i / w in 6..15) 200 else 90 }
        repeat(5) { w2.update(head, 0.1f, floatArrayOf(0.38f, 0.25f, 0.62f, 0.65f)) }
        assertFalse(w2.present)
    }

    @Test fun `the drawing has both eyes inside the screen`() {
        val m = EyeMind(4)
        run(m, 1f)
        val frame = EyeFrame()
        EyeArt().draw(frame, 2400f, 1080f, m.left, m.right, m.roll)
        assertTrue(frame.ops.size > 100)
        var clips = 0
        for (op in frame.ops) { if (op.kind == EyeFrame.CLIP) clips++; if (op.kind == EyeFrame.UNCLIP) clips-- ; assertTrue(clips in 0..1) }
        assertEquals(0, clips)
        for (op in frame.ops) if (op.colors != null) {
            val s = op.stops!!
            for (i in 1 until s.size) assertTrue(s[i] > s[i - 1], "gradient stops must rise")
        }
    }

    /** Set ZED_EYE_PREVIEW to a folder to get pictures of every mood. */
    @Test fun `preview pictures`() {
        val dir = System.getenv("ZED_EYE_PREVIEW") ?: return
        val art = EyeArt(); val frame = EyeFrame()
        fun shot(name: String, seconds: Float = 2f, setup: (EyeMind) -> Unit) {
            val m = EyeMind(7); setup(m)
            // Stop on a frame with the eyes open.
            run(m, seconds)
            var guard = 0
            while (m.left.upper > 0.6f && !name.contains("blink") && !m.drowsy && guard++ < 60) m.update(1f / 60f)
            art.draw(frame, 1600f, 720f, m.left, m.right, m.roll)
            EyePreview.render(frame, 1600, 720, File(dir, "$name.png"))
        }
        for (mood in EyeMood.entries) shot(mood.name.lowercase()) { it.mood = mood; it.activity = EyeActivity.SPEAKING; it.faceSeen = true }
        shot("look-right") { it.faceSeen = true; it.faceX = 0.9f; it.faceY = -0.4f; it.activity = EyeActivity.LISTENING }
        shot("object") { it.attending = true; it.objectSeen = true; it.objectX = -0.7f; it.objectY = 0.5f; it.activity = EyeActivity.THINKING }
        shot("drowsy", 30f) { }
        // Mid-blink and mid-sweep of the inner lid.
        val m = EyeMind(7); m.faceSeen = true
        var best = 0f
        for (i in 0 until 3600) { m.update(1f / 60f); if (m.left.membrane > 0.45f && m.left.membrane < 0.7f) break }
        art.draw(frame, 1600f, 720f, m.left, m.right, m.roll); EyePreview.render(frame, 1600, 720, File(dir, "membrane.png"))
        for (i in 0 until 3600) { m.update(1f / 60f); best = m.left.upper; if (best > 0.93f) break }
        art.draw(frame, 1600f, 720f, m.left, m.right, m.roll); EyePreview.render(frame, 1600, 720, File(dir, "blink.png"))
    }
}
