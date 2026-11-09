package com.melhive.zenith

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.melhive.zenith.agents.ConfirmationBroker
import com.melhive.zenith.core.skills.SkillRegistry
import com.melhive.zenith.core.tools.Capability
import com.melhive.zenith.core.tools.ToolCatalog
import com.melhive.zenith.core.tools.ToolRequest
import com.melhive.zenith.database.ZenithDatabase
import com.melhive.zenith.security.AuditLogger
import com.melhive.zenith.settings.SettingsRepository
import com.melhive.zenith.tools.CapabilityChecker
import com.melhive.zenith.tools.ConfirmationText
import com.melhive.zenith.tools.ToolManager
import com.melhive.zenith.tools.ToolOutcome
import com.melhive.zenith.tools.ToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ToolManagerTest {
    private lateinit var db: ZenithDatabase
    private lateinit var broker: ConfirmationBroker
    private lateinit var tm: ToolManager
    private lateinit var settings: SettingsRepository
    private val executed = mutableListOf<String>()
    private var storageGranted = true

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, ZenithDatabase::class.java).allowMainThreadQueries().build()
        broker = ConfirmationBroker()
        settings = SettingsRepository(ctx)
        val caps = object : CapabilityChecker {
            override suspend fun isAvailable(c: Capability) = c != Capability.STORAGE_ACCESS || storageGranted
            override fun explain(c: Capability) = c.label
        }
        tm = ToolManager(ToolCatalog.DEFAULT, SkillRegistry.DEFAULT, settings, caps, broker, AuditLogger(db.audit()), ConfirmationText { s, _ -> s.title })
        listOf("file.delete", "file.create", "time.now", "memory.remember").forEach { name ->
            tm.register(name) { _, _ -> executed += name; ToolResult(true, "$name done") }
        }
    }
    @After fun tearDown() = db.close()

    @Test fun invalidRequestsNeverExecuteAndAreAudited() = runBlocking<Unit> {
        val r = tm.execute(ToolRequest("file.delete", mapOf("path" to "../etc/passwd"), ToolRequest.Origin.ONLINE_MODEL))
        assertIs<ToolOutcome.Invalid>(r)
        val r2 = tm.execute(ToolRequest("shell.run", mapOf("cmd" to "rm -rf /"), ToolRequest.Origin.ONLINE_MODEL))
        assertIs<ToolOutcome.Invalid>(r2)
        assertTrue(executed.isEmpty())
        val audit = db.audit().observeRecent().first()
        assertEquals(listOf("invalid", "invalid"), audit.map { it.outcome })
    }

    @Test fun destructiveActionRequiresConfirmation() = runBlocking<Unit> {
        val pending = async { tm.execute(ToolRequest("file.delete", mapOf("path" to "a.txt"), ToolRequest.Origin.NATIVE)) }
        val req = broker.requests.filter { it.isNotEmpty() }.first().single()
        assertTrue(req.destructive)
        broker.answer(req.id, false)
        assertIs<ToolOutcome.Declined>(pending.await())
        assertTrue(executed.isEmpty())

        val approved = async { tm.execute(ToolRequest("file.delete", mapOf("path" to "a.txt"), ToolRequest.Origin.NATIVE)) }
        broker.answer(broker.requests.filter { it.isNotEmpty() }.first().single().id, true)
        assertIs<ToolOutcome.Executed>(approved.await())
        assertEquals(listOf("file.delete"), executed)
    }

    @Test fun modelRequestedChangesNeedConfirmationButNativeDoNot() = runBlocking<Unit> {
        assertIs<ToolOutcome.Executed>(tm.execute(ToolRequest("file.create", mapOf("path" to "n.txt"), ToolRequest.Origin.NATIVE)))
        val fromModel = async { tm.execute(ToolRequest("memory.remember", mapOf("fact" to "x"), ToolRequest.Origin.LOCAL_MODEL)) }
        val req = broker.requests.filter { it.isNotEmpty() }.first().single()
        assertTrue(req.message.contains("Requested by the AI model"))
        broker.answer(req.id, true)
        assertIs<ToolOutcome.Executed>(fromModel.await())
        // Read-only never asks.
        assertIs<ToolOutcome.Executed>(tm.execute(ToolRequest("time.now", origin = ToolRequest.Origin.ONLINE_MODEL)))
    }

    @Test fun missingCapabilityAndDisabledSkillAreDenied() = runBlocking<Unit> {
        storageGranted = false
        val r = tm.execute(ToolRequest("file.create", mapOf("path" to "n.txt"), ToolRequest.Origin.NATIVE))
        assertIs<ToolOutcome.Denied>(r)
        settings.setSkillEnabled("core.time", false)
        assertIs<ToolOutcome.Denied>(tm.execute(ToolRequest("time.now", origin = ToolRequest.Origin.ONLINE_MODEL)))
        // Native commands are not restricted by skill toggles.
        assertIs<ToolOutcome.Executed>(tm.execute(ToolRequest("time.now", origin = ToolRequest.Origin.NATIVE)))
        settings.setSkillEnabled("core.time", true)
        assertTrue("file.create" !in executed)
    }
}
