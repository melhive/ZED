package com.melhive.zed

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.melhive.zed.agents.AssistantOrchestrator
import com.melhive.zed.agents.ConfirmationBroker
import com.melhive.zed.core.skills.SkillRegistry
import com.melhive.zed.core.tools.Capability
import com.melhive.zed.core.tools.ToolCatalog
import com.melhive.zed.database.ZedDatabase
import com.melhive.zed.files.SafFileSystem
import com.melhive.zed.intelligence.GenerationEvent
import com.melhive.zed.intelligence.GenerationRequest
import com.melhive.zed.intelligence.IntelligenceProvider
import com.melhive.zed.intelligence.LocalModelRuntime
import com.melhive.zed.intelligence.ProviderSource
import com.melhive.zed.intelligence.ProviderStatus
import com.melhive.zed.knowledge.KnowledgeRepository
import com.melhive.zed.memory.ConversationRepository
import com.melhive.zed.memory.MemoryRepository
import com.melhive.zed.security.AuditLogger
import com.melhive.zed.settings.SettingsRepository
import com.melhive.zed.tools.CapabilityChecker
import com.melhive.zed.tools.ConfirmationText
import com.melhive.zed.tools.ToolManager
import com.melhive.zed.tools.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Scripted model: returns the next canned reply on each call and records requests. */
private class FakeProvider(vararg replies: List<GenerationEvent>) : IntelligenceProvider {
    private val queue = ArrayDeque(replies.toList())
    val requests = mutableListOf<GenerationRequest>()
    override val id = "fake"
    override val label = "Fake model"
    override suspend fun status() = ProviderStatus(true, "fake")
    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
        requests += request
        queue.removeFirst().forEach { emit(it) }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OrchestratorTest {
    private lateinit var db: ZedDatabase
    private lateinit var conversations: ConversationRepository
    private val executed = mutableListOf<String>()
    private var provider: IntelligenceProvider? = null

    private fun orchestrator(): AssistantOrchestrator {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val settings = SettingsRepository(ctx)
        val caps = object : CapabilityChecker {
            override suspend fun isAvailable(c: Capability) = true
            override fun explain(c: Capability) = c.label
        }
        val tm = ToolManager(ToolCatalog.DEFAULT, SkillRegistry.DEFAULT, settings, caps, ConfirmationBroker(), AuditLogger(db.audit()), ConfirmationText { s, _ -> s.title })
        tm.register("time.now") { _, _ -> executed += "time.now"; ToolResult(true, "Monday 10:00") }
        tm.register("todo.list") { _, _ -> executed += "todo.list"; ToolResult(true, "Your tasks: buy milk") }
        val source = object : ProviderSource {
            override val localRuntime = LocalModelRuntime(File(ctx.cacheDir, "lm"))
            override suspend fun active() = provider
            override suspend fun research(): IntelligenceProvider? = null
            override suspend fun anyConfigured() = provider != null
        }
        return AssistantOrchestrator(CoroutineScope(Dispatchers.Unconfined), conversations, MemoryRepository(db),
            KnowledgeRepository(db.knowledge(), SafFileSystem(ctx, db.folders())), source, tm, SkillRegistry.DEFAULT, settings)
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ZedDatabase::class.java).allowMainThreadQueries().build()
        conversations = ConversationRepository(db)
    }
    @After fun tearDown() = db.close()

    @Test fun conversationFlowsThroughModelWithHistory() = runBlocking<Unit> {
        val fake = FakeProvider(
            listOf(GenerationEvent.Delta("Hello! "), GenerationEvent.Delta("How can I help?"), GenerationEvent.Done("stop")),
            listOf(GenerationEvent.Delta("Paris is the capital of France."), GenerationEvent.Done("stop")),
        )
        provider = fake
        val o = orchestrator()
        val id = conversations.create()
        assertEquals("Hello! How can I help?", o.respond(id, "Hey, can we chat about geography?").text)
        assertEquals("Paris is the capital of France.", o.respond(id, "What's the capital of France?").text)
        // The second request carries the whole conversation as context.
        val turns = fake.requests[1].turns.map { it.content }
        assertTrue(turns.containsAll(listOf("Hey, can we chat about geography?", "Hello! How can I help?", "What's the capital of France?")))
        assertTrue(fake.requests[1].system.contains("ZED"))
        // Casual chat uses the lean prompt without the tool list.
        assertTrue(!fake.requests[1].system.contains("zed-tool"))
    }

    @Test fun modelToolCallIsExecutedAndResultFedBack() = runBlocking<Unit> {
        val fake = FakeProvider(
            listOf(GenerationEvent.Delta("Let me check.\n```zed-tool\n{\"tool\":\"todo.list\"}\n```"), GenerationEvent.Done("stop")),
            listOf(GenerationEvent.Delta("You need to buy milk."), GenerationEvent.Done("stop")),
        )
        provider = fake
        val o = orchestrator()
        val id = conversations.create()
        val reply = o.respond(id, "What's on my task list?")
        assertEquals("You need to buy milk.", reply.text)
        assertEquals(listOf("todo.list"), executed)
        assertTrue(fake.requests[1].turns.last().content.contains("Your tasks: buy milk"))
        val roles = conversations.messages(id).first().map { it.role }
        assertEquals(listOf("user", "assistant", "tool", "assistant"), roles)
    }

    @Test fun transientErrorsAreRetriedOnce() = runBlocking<Unit> {
        provider = FakeProvider(
            listOf(GenerationEvent.Error("Network error", retryable = true)),
            listOf(GenerationEvent.Delta("Back online."), GenerationEvent.Done("stop")),
        )
        val o = orchestrator()
        val id = conversations.create()
        assertEquals("Back online.", o.respond(id, "Are you there?").text)
        assertEquals(0, conversations.messages(id).first().count { it.status == "error" })
    }

    @Test fun withoutModelNativeLayerAnswers() = runBlocking<Unit> {
        provider = null
        val o = orchestrator()
        val id = conversations.create()
        assertEquals("what is 12 times 7 = 84".removePrefix("what is "), o.respond(id, "what is 12 times 7").text)
        assertTrue(o.respond(id, "hello").ok)
        assertEquals(listOf("time.now"), executed.also { o.respond(id, "what time is it") })
        assertTrue(o.respond(id, "Explain quantum entanglement").text.contains("AI model"))
    }

    @Test fun withModelConversationalRequestsGoToTheModel() = runBlocking<Unit> {
        val fake = FakeProvider(listOf(GenerationEvent.Delta("I can chat, manage files, set reminders…"), GenerationEvent.Done("stop")))
        provider = fake
        val o = orchestrator()
        val id = conversations.create()
        assertEquals("I can chat, manage files, set reminders…", o.respond(id, "what can you do?").text)
        assertEquals(1, fake.requests.size)
        // Exact computations stay native even with a model.
        assertEquals("12 times 7 = 84", o.respond(id, "what is 12 times 7").text)
        assertEquals(1, fake.requests.size)
    }

    @Test fun regenerateReplacesTheLastAnswer() = runBlocking<Unit> {
        provider = FakeProvider(
            listOf(GenerationEvent.Delta("First answer"), GenerationEvent.Done("stop")),
            listOf(GenerationEvent.Delta("Better answer"), GenerationEvent.Done("stop")),
        )
        val o = orchestrator()
        val id = conversations.create()
        o.respond(id, "Give me a slogan")
        o.regenerate(id)?.join()
        val msgs = conversations.messages(id).first()
        assertEquals(listOf("Give me a slogan", "Better answer"), msgs.map { it.content })
    }
}
