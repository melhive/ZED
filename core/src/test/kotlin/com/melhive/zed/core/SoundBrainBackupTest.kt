package com.melhive.zed.core

import com.melhive.zed.core.backup.BackupCorruptException
import com.melhive.zed.core.backup.BackupCrypto
import com.melhive.zed.core.backup.BackupFormat
import com.melhive.zed.core.backup.BackupManifest
import com.melhive.zed.core.backup.BackupReader
import com.melhive.zed.core.backup.BackupWriter
import com.melhive.zed.core.backup.WrongPasswordException
import com.melhive.zed.core.brain.BrainBuilder
import com.melhive.zed.core.brain.BrainItem
import com.melhive.zed.core.brain.BrainLayout
import com.melhive.zed.core.brain.BrainNodeType
import com.melhive.zed.core.voice.SoundSynth
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoundSynthTest {
    private val styles = SoundSynth.Style.entries.filter { it != SoundSynth.Style.OFF }

    @Test fun everySoundIsShortLoudAndClickFree() {
        for (style in styles) for (start in listOf(true, false)) {
            val pcm = SoundSynth.render(style, start)
            val name = "${style.id}/${if (start) "start" else "stop"}"
            val seconds = pcm.size.toDouble() / SoundSynth.RATE
            assertTrue(seconds in 0.3..0.7, "$name lasts $seconds s")
            val peak = pcm.maxOf { abs(it.toInt()) }
            assertTrue(peak in 17_000..20_000, "$name peak $peak")
            assertEquals(0, pcm.first().toInt(), "$name starts at silence")
            assertEquals(0, pcm.last().toInt(), "$name ends at silence")
            val rms = sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)
            assertTrue(rms > 1500, "$name is too quiet (rms $rms)")
        }
    }

    /** Phone speakers reproduce almost nothing below ~350 Hz; the old Pulse sound lived there and was barely audible. */
    @Test fun energySitsWherePhoneSpeakersAreLoud() {
        for (style in styles) for (start in listOf(true, false)) {
            val pcm = SoundSynth.render(style, start)
            val a = exp(-2 * Math.PI * 350.0 / SoundSynth.RATE)
            var low = 0.0; var total = 0.0; var high = 0.0
            for (s in pcm) { low = (1 - a) * s + a * low; total += s.toDouble() * s; high += (s - low) * (s - low) }
            assertTrue(high / total > 0.6, "${style.id} start=$start keeps only ${high / total} of its energy above 350 Hz")
        }
    }

    /** A machine sound is harmonically rich; a ghostly one is a lone sine. Count zero crossings per second as a brightness check. */
    @Test fun soundsAreBrightNotSineLike() {
        for (style in styles) {
            val pcm = SoundSynth.render(style, true)
            var crossings = 0
            for (i in 1 until pcm.size) if ((pcm[i - 1] < 0) != (pcm[i] < 0)) crossings++
            val perSecond = crossings * SoundSynth.RATE.toDouble() / pcm.size
            assertTrue(perSecond > 1200, "${style.id} is dull: $perSecond crossings/s")
        }
    }

    @Test fun startAndStopDifferAndRenderingIsDeterministic() {
        for (style in styles) {
            assertFalse(SoundSynth.render(style, true).contentEquals(SoundSynth.render(style, false)))
            assertContentEquals(SoundSynth.render(style, true), SoundSynth.render(style, true))
        }
    }

    @Test fun brainTickIsTinyQuietAndVaried() {
        val tick = SoundSynth.tick(0)
        assertTrue(tick.size.toDouble() / SoundSynth.RATE < 0.08)
        assertTrue(tick.maxOf { abs(it.toInt()) } in 8_000..11_000, "quieter than the LIVE sounds")
        assertEquals(0, tick.first().toInt()); assertEquals(0, tick.last().toInt())
        assertFalse(tick.contentEquals(SoundSynth.tick(1)))
        assertContentEquals(SoundSynth.tick(2), SoundSynth.tick(7))
    }

    @Test fun offIsSilentAndRetiredIdsFallBackToArc() {
        assertEquals(0, SoundSynth.render(SoundSynth.Style.OFF, true).size)
        assertEquals(SoundSynth.Style.ARC, SoundSynth.Style.parse("pulse"))
        assertEquals(SoundSynth.Style.ARC, SoundSynth.Style.parse("chime"))
        assertEquals(SoundSynth.Style.ARC, SoundSynth.Style.parse(null))
        assertEquals(SoundSynth.Style.DROID, SoundSynth.Style.parse("droid"))
        assertEquals(SoundSynth.Style.entries.size, SoundSynth.Style.entries.map { it.id }.toSet().size)
    }
}

class BrainTest {
    private fun fact(id: Long, text: String, pinned: Boolean = false) = BrainItem(BrainNodeType.FACT, id, text, id, pinned)

    private val items = listOf(
        fact(1, "My name is Rommel"),
        fact(2, "My sister's name is Ana"),
        fact(3, "Ana is allergic to peanuts"),
        fact(4, "I work at MelHive on the ZED project"),
        fact(5, "My dentist is Dr. Cruz in Cebu"),
        fact(6, "I live in Cebu"),
        BrainItem(BrainNodeType.TASK, 1, "Call the dentist", 10),
        BrainItem(BrainNodeType.REMINDER, 1, "Buy a gift for Ana", 11),
        BrainItem(BrainNodeType.DOCUMENT, 1, "trip-plan.txt", 12),
        BrainItem(BrainNodeType.CONVERSATION, 1, "Plan my trip to Kyoto", 13),
    )

    @Test fun itemsAreFiledUnderAreas() {
        val g = BrainBuilder.build(items)
        fun area(key: String) = g.nodes.first { it.key == key }.category
        assertEquals(BrainNodeType.CORE, g.nodes[0].type)
        assertEquals("You", area("FACT:1"))
        assertEquals("People", area("FACT:2"))
        assertEquals("Preferences", area("FACT:3"))
        assertEquals("Work", area("FACT:4"))
        assertEquals("Health", area("FACT:5"))
        assertEquals("Places", area("FACT:6"))
        assertEquals("Health", area("TASK:1"))
        assertEquals("Schedule", area("REMINDER:1"))
        assertEquals("Documents", area("DOCUMENT:1"))
        assertEquals("Conversations", area("CONVERSATION:1"))
        assertEquals(10, g.itemCount)
        // Every area hangs off the core; every item hangs off exactly one area.
        val core = g.neighbors[0].map { g.nodes[it] }
        assertTrue(core.all { it.type == BrainNodeType.CATEGORY })
        for ((i, n) in g.nodes.withIndex()) if (n.itemId != null) assertEquals(1, g.neighbors[i].count { g.nodes[it].type == BrainNodeType.CATEGORY })
    }

    @Test fun sharedWordsBecomeConcepts() {
        val g = BrainBuilder.build(items)
        val ana = g.indexOf("concept:ana")
        assertTrue(ana >= 0, "Ana links three items")
        assertEquals("Ana", g.nodes[ana].label)
        assertEquals(setOf("FACT:2", "FACT:3", "REMINDER:1"), g.neighbors[ana].map { g.nodes[it].key }.toSet())
        assertEquals(setOf("FACT:5", "TASK:1"), g.neighbors[g.indexOf("concept:dentist")].map { g.nodes[it].key }.toSet())
        assertTrue(g.indexOf("concept:cebu") >= 0)
        assertTrue(g.indexOf("concept:trip") >= 0)
        // Filler words never become concepts.
        assertEquals(-1, g.indexOf("concept:name"))
        assertEquals(-1, g.indexOf("concept:the"))
        assertNull(BrainBuilder.conceptKey("my"))
        assertEquals("project", BrainBuilder.conceptKey("Projects"))
    }

    @Test fun graphIsWellFormedAndSearchable() {
        val g = BrainBuilder.build(items)
        assertEquals(g.nodes.size, g.nodes.map { it.key }.toSet().size)
        for (e in g.edges) { assertTrue(e.a in g.nodes.indices && e.b in g.nodes.indices && e.a != e.b) }
        assertEquals(setOf("FACT:5", "TASK:1", "concept:dentist"), g.search("dentist").map { g.nodes[it].key }.toSet())
        assertTrue(g.search("").isEmpty())
        val empty = BrainBuilder.build(emptyList())
        assertEquals(1, empty.nodes.size)
    }

    @Test fun largeBrainsAreCappedAndPinnedItemsKept() {
        val many = (1..400L).map { fact(it, "Fact number $it about topic${it % 7}") } + fact(999, "Old but important", pinned = true).copy(timestamp = 0)
        val g = BrainBuilder.build(many)
        assertEquals(110, g.itemCount)
        assertTrue(g.indexOf("FACT:999") >= 0)
        assertTrue(g.conceptCount <= 26)
    }

    @Test fun layoutSettlesWithoutOverlapOrExplosion() {
        val many = items + (20..90L).map { fact(it, "Note $it about subject${it % 9} and theme${it % 5}") }
        val g = BrainBuilder.build(many)
        val layout = BrainLayout()
        assertTrue(layout.setGraph(g).isEmpty(), "the first graph has no 'new' nodes to animate")
        layout.settle(1800)
        for (i in g.nodes.indices) assertTrue(layout.x[i].isFinite() && layout.y[i].isFinite() && layout.z[i].isFinite())
        assertTrue(layout.radius() in 200f..1400f, "radius ${layout.radius()}")
        var closest = Float.MAX_VALUE
        for (i in g.nodes.indices) for (j in i + 1 until g.nodes.size) {
            val dx = layout.x[i] - layout.x[j]; val dy = layout.y[i] - layout.y[j]; val dz = layout.z[i] - layout.z[j]
            closest = minOf(closest, sqrt(dx * dx + dy * dy + dz * dz))
        }
        // It really uses the third dimension.
        assertTrue(layout.z.maxOf { abs(it) } > 80f, "the brain is flat")
        assertTrue(closest > 12f, "nodes overlap: $closest")
        // It has come to rest: one more second moves nothing far.
        val bx = layout.x.copyOf(); val by = layout.y.copyOf()
        layout.settle(60)
        for (i in g.nodes.indices) assertTrue(abs(layout.x[i] - bx[i]) < 25f && abs(layout.y[i] - by[i]) < 25f, "node $i still moving")
    }

    @Test fun strongMemoriesSitNearerTheCoreAndLookBigger() {
        val now = 1_000L * 86_400_000
        val fresh = com.melhive.zed.core.brain.BrainStrength.of(uses = 12, lastUsedAt = now, createdAt = 0, pinned = false, now = now)
        val stale = com.melhive.zed.core.brain.BrainStrength.of(uses = 0, lastUsedAt = 0, createdAt = now - 400L * 86_400_000, pinned = false, now = now)
        val pinnedOld = com.melhive.zed.core.brain.BrainStrength.of(0, 0, now - 400L * 86_400_000, pinned = true, now = now)
        assertTrue(fresh > 0.9f && stale < 0.2f && pinnedOld >= 0.85f, "$fresh $stale $pinnedOld")
        assertTrue(com.melhive.zed.core.brain.BrainStrength.of(0, 0, now, false, now) in 0.5f..0.6f, "a brand-new memory starts in the middle")

        // Same area, same wording pattern: half strong, half faint.
        val list = (1..40L).map { fact(it, "Random note $it zq$it").copy(strength = if (it % 2 == 0L) 1f else 0f) }
        val g = BrainBuilder.build(list)
        val layout = BrainLayout().also { it.setGraph(g); it.settle(2500) }
        fun avg(strong: Boolean) = g.nodes.indices.filter { g.nodes[it].itemId != null && (g.nodes[it].strength > 0.5f) == strong }.map { layout.distance(it) }.average()
        assertTrue(avg(true) < avg(false) - 15, "strong ${avg(true)} vs faint ${avg(false)}")
        assertTrue(g.nodes.first { it.key == "FACT:2" }.weight > g.nodes.first { it.key == "FACT:1" }.weight)
    }

    @Test fun timeLapseShowsTheBrainAsItWas() {
        val g = BrainBuilder.build(items)
        assertEquals(1L to 13L, g.timeRange())
        fun keysAt(t: Long) = g.visibleAt(t).let { v -> g.nodes.indices.filter { v[it] }.map { g.nodes[it].key }.toSet() }
        assertEquals(setOf("core"), keysAt(0))
        assertEquals(setOf("core", "area:You", "FACT:1", "area:People", "FACT:2"), keysAt(2))
        // "Ana" becomes a concept only once a second item mentions her.
        assertFalse("concept:ana" in keysAt(2))
        assertTrue("concept:ana" in keysAt(3))
        assertEquals(g.nodes.size, keysAt(13).size)
        assertNull(BrainBuilder.build(emptyList()).timeRange())
    }

    @Test fun signalsTravelFromTheCoreThroughTheArea() {
        val g = BrainBuilder.build(items)
        val path = g.pathFromCore(g.indexOf("FACT:3")).map { g.nodes[it].key }
        assertEquals(listOf("core", "area:Preferences", "FACT:3"), path)
        assertEquals(listOf("core"), g.pathFromCore(0).map { g.nodes[it].key })
        assertEquals(4, g.pathFromCore(g.indexOf("concept:ana")).size) // core → area → item → concept
        assertTrue(g.pathFromCore(-5).isEmpty())
    }

    @Test fun layoutKeepsPositionsWhenTheGraphGrows() {
        val layout = BrainLayout()
        val g1 = BrainBuilder.build(items)
        layout.setGraph(g1); layout.settle()
        val i1 = g1.indexOf("FACT:2"); val x = layout.x[i1]; val y = layout.y[i1]
        val g2 = BrainBuilder.build(items + fact(50, "Ana loves mango shakes"))
        val added = layout.setGraph(g2)
        assertTrue("FACT:50" in added)
        val i2 = g2.indexOf("FACT:2")
        assertEquals(x, layout.x[i2]); assertEquals(y, layout.y[i2])
        layout.step(10f) // a long pause between frames must not blow it up
        assertTrue(layout.radius() < 2000f)
    }
}

class BackupArchiveTest {
    private fun archive(build: (BackupWriter) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        BackupWriter(out).use { build(it); it.finish() }
        return out.toByteArray()
    }

    private val big = ByteArray(300_000) { (it * 31 % 251).toByte() }

    private fun sample() = archive { w ->
        w.entry(BackupFormat.MANIFEST, BackupManifest(appVersion = "0.1.20", dbVersion = 1, models = listOf("gemma.litertlm"), counts = mapOf("memories" to 3)).encode())
        w.entry(BackupFormat.DB, big.size.toLong(), ByteArrayInputStream(big))
        w.entry("models/gemma.litertlm", ByteArray(0))
        w.entry("voices/kokoro/model.onnx", byteArrayOf(1, 2, 3))
    }

    @Test fun roundTrip() {
        BackupReader(ByteArrayInputStream(sample())).use { r ->
            val m = BackupManifest.decode(r.next()!!.readBytes())
            assertEquals("0.1.20", m.appVersion); assertEquals(3, m.counts["memories"]); assertEquals(listOf("gemma.litertlm"), m.models)
            val db = r.next()!!
            assertEquals(BackupFormat.DB, db.name); assertEquals(big.size.toLong(), db.size)
            assertContentEquals(big, db.stream.readBytes())
            assertEquals(0, r.next()!!.stream.readBytes().size)
            val voice = r.next()!!
            assertEquals("voices/kokoro/model.onnx", voice.name)
            assertContentEquals(byteArrayOf(1, 2, 3), voice.stream.readBytes())
            assertNull(r.next()); assertNull(r.next())
        }
    }

    @Test fun skippedEntriesAreStillVerified() {
        BackupReader(ByteArrayInputStream(sample())).use { r ->
            var n = 0
            while (r.next() != null) n++
            assertEquals(4, n)
        }
        val damaged = sample().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertFailsWith<BackupCorruptException> { BackupReader(ByteArrayInputStream(damaged)).use { r -> while (true) { r.next() ?: break } } }
    }

    @Test fun truncatedAndForeignFilesAreRejected() {
        val bytes = sample()
        for (cut in listOf(4, 20, 200, bytes.size / 2, bytes.size - 9, bytes.size - 1)) {
            assertFailsWith<BackupCorruptException>("cut at $cut") {
                BackupReader(ByteArrayInputStream(bytes.copyOf(cut))).use { r -> while (true) { val e = r.next() ?: break; e.stream.readBytes() } }
            }
        }
        assertFailsWith<BackupCorruptException> { BackupReader(ByteArrayInputStream("PK\u0003\u0004 not a backup".toByteArray())) }
        assertFailsWith<BackupCorruptException> { BackupReader(ByteArrayInputStream(ByteArray(0))) }
        assertFailsWith<BackupCorruptException> { BackupManifest.decode("{nope".toByteArray()) }
    }

    @Test fun entryNamesCannotEscapeTheRestoreFolder() {
        for (ok in listOf("manifest.json", "db/zed.db-wal", "models/gemma-4-E2B_it.litertlm", "voices/kokoro-en-v0_19/voices.bin", "settings/zed_settings.preferences_pb"))
            assertTrue(BackupFormat.isSafeName(ok), ok)
        for (bad in listOf("", "../x", "a/../b", "/etc/passwd", "a//b", "a\\b", "models/..", "a/b/c/d/e", ".hidden", "sp ace", "a/./b"))
            assertFalse(BackupFormat.isSafeName(bad), bad)
        assertFailsWith<IllegalArgumentException> { archive { it.entry("../evil", byteArrayOf(1)) } }
        // A hand-made archive with a bad name is refused by the reader.
        val out = ByteArrayOutputStream()
        java.io.DataOutputStream(out).apply { write(BackupFormat.MAGIC); writeByte(1); writeUTF("../../databases/zed.db"); writeLong(0); writeLong(0); writeByte(0); writeLong(1) }
        assertFailsWith<BackupCorruptException> { BackupReader(ByteArrayInputStream(out.toByteArray())).next() }
    }

    @Test fun aFileThatShrinksWhileBackingUpFails() {
        assertFailsWith<java.io.IOException> { archive { it.entry("db/zed.db", 10, ByteArrayInputStream(ByteArray(4))) } }
    }

    @Test fun progressCountsEveryByte() {
        var seen = 0L
        archive { it.entry("db/zed.db", big.size.toLong(), ByteArrayInputStream(big)) { n -> seen += n } }
        assertEquals(big.size.toLong(), seen)
    }

    @Test fun keysNeedTheRightPassword() {
        val keys = mapOf("api_key_gemini" to "AIza-secret", "home_assistant_token" to "ey.long.token")
        val enc = BackupCrypto.encrypt(BackupCrypto.encodeKeys(keys), "correct horse".toCharArray())
        assertFalse(String(enc, Charsets.ISO_8859_1).contains("AIza"))
        assertEquals(keys, BackupCrypto.decodeKeys(BackupCrypto.decrypt(enc, "correct horse".toCharArray())))
        assertFailsWith<WrongPasswordException> { BackupCrypto.decrypt(enc, "wrong".toCharArray()) }
        assertFailsWith<BackupCorruptException> { BackupCrypto.decrypt(ByteArray(10), "x".toCharArray()) }
        // Fresh salt and IV every time.
        assertFalse(enc.contentEquals(BackupCrypto.encrypt(BackupCrypto.encodeKeys(keys), "correct horse".toCharArray())))
        assertNotNull(BackupCrypto.decodeKeys(BackupCrypto.encodeKeys(emptyMap())))
    }
}
