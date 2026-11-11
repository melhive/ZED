package com.melhive.zed.core

import com.melhive.zed.core.files.OrganizationPlanner
import com.melhive.zed.core.intelligence.ChatTurn
import com.melhive.zed.core.intelligence.ContextWindow
import com.melhive.zed.core.intelligence.PromptBuilder
import com.melhive.zed.core.intelligence.Role
import com.melhive.zed.core.knowledge.Bm25
import com.melhive.zed.core.knowledge.TextChunker
import com.melhive.zed.core.skills.SkillRegistry
import com.melhive.zed.core.tasks.TaskGraph
import com.melhive.zed.core.tasks.TaskStateMachine
import com.melhive.zed.core.tasks.TaskStatus
import com.melhive.zed.core.voice.VoiceActivityDetector
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineLogicTest {

    @Test fun taskTransitions() {
        assertTrue(TaskStateMachine.canTransition(TaskStatus.QUEUED, TaskStatus.RUNNING))
        assertTrue(TaskStateMachine.canTransition(TaskStatus.PLANNING, TaskStatus.AWAITING_CONFIRMATION))
        assertTrue(TaskStateMachine.canTransition(TaskStatus.FAILED, TaskStatus.QUEUED))
        assertFalse(TaskStateMachine.canTransition(TaskStatus.SUCCEEDED, TaskStatus.RUNNING))
        assertFalse(TaskStateMachine.canTransition(TaskStatus.QUEUED, TaskStatus.SUCCEEDED))
        assertFailsWith<IllegalArgumentException> { TaskStateMachine.transition(TaskStatus.CANCELLED, TaskStatus.RUNNING) }
    }

    @Test fun taskGraphCyclesReadinessConflicts() {
        val existing = mapOf(1L to setOf(2L), 2L to emptySet())
        assertTrue(TaskGraph.wouldCreateCycle(2L, setOf(1L), existing))
        assertFalse(TaskGraph.wouldCreateCycle(3L, setOf(1L), existing))

        val n1 = TaskGraph.Node(1, TaskStatus.SUCCEEDED, emptySet(), setOf("tree:1/Download"))
        val n2 = TaskGraph.Node(2, TaskStatus.RUNNING, emptySet(), setOf("tree:1/Download/pdf"))
        val n3 = TaskGraph.Node(3, TaskStatus.QUEUED, setOf(1, 2), setOf("tree:1/Download"))
        val all = listOf(n1, n2, n3).associateBy { it.id }
        assertEquals(TaskGraph.Readiness.WAITING, TaskGraph.readiness(n3, all))
        assertEquals(TaskGraph.Readiness.READY, TaskGraph.readiness(n3, all + (2L to n2.copy(status = TaskStatus.SUCCEEDED))))
        assertEquals(TaskGraph.Readiness.BLOCKED, TaskGraph.readiness(n3, all + (2L to n2.copy(status = TaskStatus.FAILED))))
        assertEquals(listOf(n2), TaskGraph.conflicts(n3, all.values))
        assertFalse(TaskGraph.resourcesOverlap("tree:1/Down", "tree:1/Download"))
    }

    @Test fun organizationPlan() {
        val plan = OrganizationPlanner.plan(listOf(
            OrganizationPlanner.Entry("invoice.PDF", false),
            OrganizationPlanner.Entry("photo.jpg", false),
            OrganizationPlanner.Entry("song.mp3", false),
            OrganizationPlanner.Entry("Documents", true),
            OrganizationPlanner.Entry(".nomedia", false),
            OrganizationPlanner.Entry("mystery", false),
            OrganizationPlanner.Entry("app.apk", false),
        ))
        assertEquals(mapOf("Documents" to 1, "Images" to 1, "Audio" to 1, "Apps" to 1), plan.byFolder.mapValues { it.value.size })
        assertEquals(listOf(".nomedia", "mystery"), plan.skipped)
    }

    @Test fun chunkingAndRanking() {
        val text = (1..40).joinToString(" ") { "Sentence number $it talks about telescopes and stars." }
        val chunks = TextChunker.chunk(text, targetChars = 300, overlapChars = 50)
        assertTrue(chunks.size > 3)
        assertTrue(chunks.all { it.length <= 300 })
        val docs = listOf(
            Bm25.Doc(1, "Quarterly budget report with invoice totals"),
            Bm25.Doc(2, "Recipe for banana bread"),
            Bm25.Doc(3, "Invoice from the electricity company, invoice number 42"),
        )
        val hits = Bm25().rank("invoice", docs)
        assertEquals(listOf(3L, 1L), hits.map { it.id })
        assertTrue(Bm25().rank("the of and", docs).isEmpty())
    }

    @Test fun vadDetectsSpeechAndEndpoint() {
        val vad = VoiceActivityDetector(startFrames = 3, hangoverFrames = 5)
        val silence = ShortArray(320) { ((it % 7) - 3).toShort() }
        val speech = ShortArray(320) { (8000 * sin(2 * PI * 220 * it / 16000)).toInt().toShort() }
        repeat(20) { assertEquals(VoiceActivityDetector.Event.NONE, vad.process(silence)) }
        val events = (1..5).map { vad.process(speech) }
        assertTrue(VoiceActivityDetector.Event.SPEECH_START in events)
        assertTrue(vad.inSpeech)
        val endEvents = (1..10).map { vad.process(silence) }
        assertTrue(VoiceActivityDetector.Event.SPEECH_END in endEvents)
        assertFalse(vad.inSpeech)
    }

    @Test fun contextWindowKeepsRecentTurnsAndStartsWithUser() {
        val turns = (1..20).flatMap { listOf(ChatTurn(Role.USER, "q$it ".repeat(20)), ChatTurn(Role.ASSISTANT, "a$it ".repeat(20))) }
        val fit = ContextWindow.fit(turns, 1000)
        assertEquals(Role.USER, fit.first().role)
        assertEquals(turns.last(), fit.last())
        assertTrue(fit.sumOf { it.content.length } <= 1000)
    }

    @Test fun promptContainsToolsAndMemories() {
        val tools = SkillRegistry.DEFAULT.enabledTools(setOf("files.manage"))
        val p = PromptBuilder.systemPrompt(tools, "Monday", listOf("Sister is Ana"), listOf("doc text"), voiceMode = true)
        assertTrue("file.delete(path: path)" in p)
        assertTrue("Sister is Ana" in p)
        assertTrue("zed-tool" in p)
        assertFalse("alarm.set" in p)
    }

    @Test fun compactPromptIsShorterAndOmitsVolatileParts() {
        val tools = SkillRegistry.DEFAULT.enabledTools(SkillRegistry.DEFAULT.skills.map { it.id }.toSet())
        val full = PromptBuilder.systemPrompt(tools, "Monday 10:00", emptyList(), listOf("doc"), voiceMode = false)
        val compact = PromptBuilder.systemPrompt(tools, null, emptyList(), emptyList(), voiceMode = false, compact = true)
        assertTrue(compact.length < full.length * 0.7, "compact ${compact.length} vs full ${full.length}")
        assertTrue("- file.delete(path)" in compact)
        assertFalse("Current local date-time" in compact)
        val ctx = PromptBuilder.volatileContext("Monday 10:00", listOf("notes.txt: hello"))
        assertTrue(ctx.startsWith("[Context — now: Monday 10:00]") && "notes.txt" in ctx)
    }

    @Test fun skillRegistryCoversEveryTool() {
        val reg = SkillRegistry.DEFAULT
        val covered = reg.skills.flatMap { it.toolNames }.toSet()
        assertEquals(reg.catalog.all.map { it.name }.toSet(), covered)
    }
}
