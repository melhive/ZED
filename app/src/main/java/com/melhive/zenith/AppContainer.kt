package com.melhive.zenith

import android.content.Context
import com.melhive.zenith.agents.AssistantOrchestrator
import com.melhive.zenith.agents.ConfirmationBroker
import com.melhive.zenith.agents.WorkflowEngine
import com.melhive.zenith.android.ReminderScheduler
import com.melhive.zenith.core.skills.SkillRegistry
import com.melhive.zenith.core.tools.ToolCatalog
import com.melhive.zenith.database.ZenithDatabase
import com.melhive.zenith.files.SafFileSystem
import com.melhive.zenith.intelligence.LocalModelRuntime
import com.melhive.zenith.intelligence.ProviderRegistry
import com.melhive.zenith.knowledge.KnowledgeRepository
import com.melhive.zenith.memory.ConversationRepository
import com.melhive.zenith.memory.MemoryRepository
import com.melhive.zenith.memory.TodoRepository
import com.melhive.zenith.models.ModelDownloader
import com.melhive.zenith.models.ModelRepository
import com.melhive.zenith.security.AuditLogger
import com.melhive.zenith.security.SecretStore
import com.melhive.zenith.settings.SettingsRepository
import com.melhive.zenith.tasks.TaskEngine
import com.melhive.zenith.tools.ToolManager
import com.melhive.zenith.tools.executors.AndroidCapabilityChecker
import com.melhive.zenith.tools.executors.DefaultConfirmationText
import com.melhive.zenith.tools.executors.registerExecutors
import com.melhive.zenith.voice.AndroidSpeechToText
import com.melhive.zenith.voice.AndroidTextToSpeech
import com.melhive.zenith.voice.LiveSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Manual dependency container. One instance per process, owned by [ZenithApp]. */
class AppContainer(private val context: Context) {
    /** Background scope: failures are recorded for Diagnostics instead of crashing the app. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        com.melhive.zenith.android.CrashReporter.recordNonFatal("background", e)
    })

    val db: ZenithDatabase by lazy { ZenithDatabase.build(context) }
    val settings by lazy { SettingsRepository(context) }
    val secrets by lazy { SecretStore(context) }
    val audit by lazy { AuditLogger(db.audit()) }

    val catalog: ToolCatalog = ToolCatalog.DEFAULT
    val skills: SkillRegistry = SkillRegistry.DEFAULT

    val files by lazy { SafFileSystem(context, db.folders()) }
    val conversations by lazy { ConversationRepository(db) }
    val memory by lazy { MemoryRepository(db) }
    val todos by lazy { TodoRepository(db) }
    val knowledge by lazy { KnowledgeRepository(db.knowledge(), files) }
    val reminderScheduler by lazy { ReminderScheduler(context, db.reminders()) }

    val models by lazy { ModelRepository(context, db.models()) }
    val modelDownloader by lazy { ModelDownloader(models) { secrets.get(SecretStore.HF_TOKEN) } }
    val localRuntime by lazy { LocalModelRuntime(File(context.cacheDir, "litertlm").apply { mkdirs() }) }
    val providers by lazy { ProviderRegistry(context, settings, secrets, models, localRuntime) }

    val taskEngine by lazy { TaskEngine(context, db.tasks()) }
    val confirmations = ConfirmationBroker()
    val workflows by lazy { WorkflowEngine(db, taskEngine) }

    val toolManager: ToolManager by lazy {
        ToolManager(catalog, skills, settings, AndroidCapabilityChecker(context, this), confirmations, audit, DefaultConfirmationText).also { tm ->
            registerExecutors(tm, this, context)
            workflows.toolManager = tm
        }
    }

    val orchestrator by lazy {
        AssistantOrchestrator(appScope, conversations, memory, knowledge, providers, toolManager, skills, settings)
    }

    val live by lazy {
        LiveSessionController(context, orchestrator, conversations, settings, { AndroidSpeechToText(context) }, { AndroidTextToSpeech(context) })
    }

    val updates = com.melhive.zenith.android.UpdateChecker()

    /** A one-shot TTS engine for reading chat replies aloud. */
    val chatTts by lazy { AndroidTextToSpeech(context) }
}
