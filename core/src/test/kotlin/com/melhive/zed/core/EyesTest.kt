package com.melhive.zed.core

import com.melhive.zed.core.eyes.EyeActivity
import com.melhive.zed.core.eyes.EyeArt
import com.melhive.zed.core.eyes.EyeEmotion
import com.melhive.zed.core.eyes.EyeFrame
import com.melhive.zed.core.eyes.EyeMind
import com.melhive.zed.core.eyes.EyeMood
import com.melhive.zed.core.eyes.GazeMode
import com.melhive.zed.core.eyes.SceneWatcher
import com.melhive.zed.core.eyes.EyeColors
import com.melhive.zed.core.eyes.KindWords
import com.melhive.zed.core.eyes.VisualAsk
import com.melhive.zed.core.eyes.VoiceInterrupt
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

    @Test fun `stop words cut in only when ZED isn't saying them`() {
        assertTrue(VoiceInterrupt.heard("the weather today stop", "The weather today is sunny with a high of 30."))
        assertTrue(VoiceInterrupt.heard("okay that's enough", "Here is a long story."))
        assertTrue(VoiceInterrupt.heard("hold on", "First, open the box."))
        assertFalse(VoiceInterrupt.heard("the bus will stop at the corner", "The bus will stop at the corner."))
        assertFalse(VoiceInterrupt.heard("it is sunny today", "It is sunny today."))
        assertTrue(KindWords.matches("thanks Zed")); assertTrue(KindWords.matches("good night"))
        assertFalse(KindWords.matches("what time is it"))
        assertEquals("amber", EyeColors.next("cyan")); assertEquals("cyan", EyeColors.next("violet"))
    }

    @Test fun `eyes fall asleep after a long quiet time and wake on touch`() {
        val m = EyeMind(5)
        run(m, 80f)
        assertTrue(m.asleep)
        assertTrue(m.left.upper > 0.95f)
        m.wake()
        run(m, 1.5f)
        assertFalse(m.asleep)
        assertTrue(m.left.upper < 0.6f)
    }

    @Test fun `only safe actions run on a locked phone`() {
        val lock = com.melhive.zed.core.security.LockPolicy
        for (t in listOf("time.now", "timer.set", "alarm.set", "flashlight.set", "volume.set", "weather.get", "web.research")) assertTrue(lock.allows(t), t)
        for (t in listOf("memory.list", "messages.read", "contacts.find", "phone.call", "sms.compose", "calendar.today", "reminder.list", "file.read",
            "notifications.recent", "history.search", "settings.set", "screen.read", "app.open", "location.get", "time.left", "people.forget")) assertFalse(lock.allows(t), t)
        // Every allowed name is a real tool.
        for (t in lock.allowed) assertTrue(com.melhive.zed.core.tools.ToolCatalog.DEFAULT.find(t) != null, t)
    }

    @Test fun `names are picked out of an introduction`() {
        val n = com.melhive.zed.core.eyes.NameAnswer
        assertEquals("Ana", n.parse("my name is Ana"))
        assertEquals("Ana", n.parse("I'm Ana."))
        assertEquals("Juan Cruz", n.parse("it's juan cruz"))
        assertEquals("Mika", n.parse("Mika"))
        assertEquals(null, n.parse("what time is it"))
        assertEquals(null, n.parse("no"))
        assertEquals(null, n.parse("why do you want to know my name"))
        assertTrue(VisualAsk.followUp("how do I use it?")); assertTrue(VisualAsk.followUp("is that expensive"))
        assertFalse(VisualAsk.followUp("what time is the meeting"))
    }

    @Test fun `faces and voices are matched to people`() {
        val rnd = kotlin.random.Random(9)
        fun face(seed: Int, noise: Int): FloatArray {
            val r = kotlin.random.Random(seed)
            val base = IntArray(64 * 64) { r.nextInt(256) }
            // Smooth blobs, so it looks more like a face than like static.
            val smooth = IntArray(64 * 64) { i -> val x = i % 64; val y = i / 64; (base[(y / 6) * 64 + x / 6] * 2 + base[(y / 12) * 64 + x / 12]) / 3 }
            return com.melhive.zed.core.eyes.FacePrint.describe(IntArray(64 * 64) { (smooth[it] + rnd.nextInt(-noise, noise + 1)).coerceIn(0, 255) })
        }
        val book = com.melhive.zed.core.eyes.PeopleBook()
        assertTrue(book.whose(face(1, 2)).stranger, "nobody is known yet")
        val me = book.add("Rommel", true, List(4) { face(1, 3) })
        book.add("Ana", false, List(4) { face(2, 3) })
        val seen = book.whose(face(1, 3))
        assertTrue(seen.known && seen.person === me, "distance ${seen.distance}")
        assertEquals("Ana", book.whose(face(2, 3)).person?.name)
        assertTrue(book.whose(face(3, 3)).stranger)
        assertEquals(null, book.ownerVoice(floatArrayOf(1f, 0f)))
        me.voices += floatArrayOf(1f, 0f, 0f)
        assertTrue(book.ownerVoice(floatArrayOf(0.9f, 0.1f, 0f))!! > 0.9f)
        assertTrue(book.ownerVoice(floatArrayOf(0f, 1f, 0f))!! < 0.1f)
        assertTrue(book.forget("ana")); assertEquals(1, book.people.size)
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

    /** Set ZED_FACE_SET to a folder of person folders (ORL layout) to measure the face recogniser. */
    @Test fun `face recogniser on a real face set`() {
        val dir = System.getenv("ZED_FACE_SET") ?: return
        System.setProperty("java.awt.headless", "true")
        fun load(f: File): FloatArray {
            val img = javax.imageio.ImageIO.read(f)
            // The middle of the picture, roughly eyes-to-chin like the phone's crop.
            val side = (minOf(img.width, img.height) * 0.86).toInt()
            val x0 = (img.width - side) / 2; val y0 = ((img.height - side) * 0.55).toInt()
            val grey = IntArray(64 * 64) { i ->
                val x = x0 + (i % 64) * side / 64; val y = y0 + (i / 64) * side / 64
                val rgb = img.getRGB(x, y); ((rgb shr 16 and 255) * 3 + (rgb shr 8 and 255) * 6 + (rgb and 255)) / 10
            }
            return com.melhive.zed.core.eyes.FacePrint.describe(grey)
        }
        val folders = File(dir).listFiles()!!.filter { it.isDirectory }.sortedBy { it.name.toInt() }
        val book = com.melhive.zed.core.eyes.PeopleBook()
        val known = folders.take(20); val strangers = folders.drop(20)
        val probes = ArrayList<Pair<String, FloatArray>>()
        for (f in known) {
            val pics = f.listFiles()!!.sortedBy { it.nameWithoutExtension.toInt() }.map { load(it) }
            book.add(f.name, false, pics.take(5))
            pics.drop(5).forEach { probes += f.name to it }
        }
        val right = ArrayList<Float>(); var wrong = 0
        for ((name, face) in probes) { val m = book.whose(face); if (m.person?.name == name) right += m.distance else wrong++ }
        val strange = strangers.flatMap { f -> f.listFiles()!!.map { book.whose(load(it)).distance } }.sorted()
        right.sort()
        fun pct(l: List<Float>, p: Double) = l[((l.size - 1) * p).toInt()]
        println("FACES known probes=${probes.size} wrong-person=$wrong  right distance p10=${pct(right, .1)} p50=${pct(right, .5)} p90=${pct(right, .9)} max=${right.last()}")
        println("FACES strangers=${strange.size} nearest distance min=${strange.first()} p05=${pct(strange, .05)} p10=${pct(strange, .1)} p50=${pct(strange, .5)}")
        for (t in listOf(0.14f, 0.16f, 0.18f, 0.20f, 0.22f, 0.24f, 0.26f, 0.28f, 0.30f)) {
            println("FACES threshold $t: known accepted ${right.count { it <= t } * 100 / probes.size}%  strangers mistaken ${strange.count { it <= t } * 100 / strange.size}%")
        }
    }
}
