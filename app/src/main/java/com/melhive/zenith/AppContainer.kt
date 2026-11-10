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
    val workflows by lazy { WorkflowEngine(db, taskEngine).also { it.briefing = com.melhive.zenith.agents.BriefingComposer(context, this) } }
    val routines by lazy { com.melhive.zenith.agents.RoutineEngine(context, this) }

    /** A request LIVE should send as soon as it opens (from a notification or shortcut). */
    val livePrompt = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    val toolManager: ToolManager by lazy {
        ToolManager(catalog, skills, settings, AndroidCapabilityChecker(context, this), confirmations, audit, DefaultConfirmationText).also { tm ->
            registerExecutors(tm, this, context)
            workflows.toolManager = tm
        }
    }

    val orchestrator by lazy {
        AssistantOrchestrator(appScope, conversations, memory, knowledge, providers, toolManager, skills, settings).also { o ->
            o.routineMatcher = { text -> routines.matchPhrase(text)?.name }
        }
    }

    val live by lazy {
        LiveSessionController(context, orchestrator, conversations, settings, { AndroidSpeechToText(context) }, { voice.sessionHandle() }, { realtimeConfig() })
    }

    /** Gemini Live settings for ZENITH LIVE, or null when no Gemini key is configured. */
    private suspend fun realtimeConfig(): com.melhive.zenith.voice.RealtimeConfig? {
        val s = settings.current()
        val key = secrets.get(SecretStore.apiKeyName(com.melhive.zenith.settings.OnlineProviderKind.GEMINI)) ?: return null
        val tools = skills.enabledTools(s.enabledSkills).filter { it.name != "web.research" }
        val now = java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd'T'HH:mm (VV)"))
        val prompt = com.melhive.zenith.core.intelligence.PromptBuilder.systemPrompt(
            tools = emptyList(), nowDescription = now, memories = memory.list(30).map { it.text }, knowledge = emptyList(),
            voiceMode = true, userName = memory.userName(),
            persona = com.melhive.zenith.core.intelligence.Persona.parse(s.persona), userTitle = s.userTitle.ifBlank { null },
            nativeTools = tools.isNotEmpty(),
        )
        return com.melhive.zenith.voice.RealtimeConfig(
            apiKey = key,
            model = s.liveModel.ifBlank { com.melhive.zenith.voice.GeminiLiveSession.DEFAULT_MODEL },
            voiceName = s.liveVoice,
            systemPrompt = prompt,
            tools = tools,
            webSearch = true,
            executeTool = { call ->
                val out = toolManager.execute(com.melhive.zenith.core.tools.ToolRequest(call.name, call.args, com.melhive.zenith.core.tools.ToolRequest.Origin.ONLINE_MODEL))
                com.melhive.zenith.intelligence.ToolCallResult(call.id, call.name, out.message, out is com.melhive.zenith.tools.ToolOutcome.Executed && out.result.ok)
            },
        )
    }

    val updates = com.melhive.zenith.android.UpdateChecker()

    /** Conversation Chat should show next (set by Home's "Ask ZENITH" box). */
    val chatFocus = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)

    /** Downloadable offline neural voice packs. */
    val voicePacks by lazy { com.melhive.zenith.voice.VoicePackRepository(context) }

    /** The voice ZENITH speaks with everywhere (system or neural, per Settings → Voice). */
    val voice by lazy { com.melhive.zenith.voice.VoiceManager(context, settings, voicePacks, appScope) }

    /** Reads chat replies aloud with the selected voice. */
    val chatTts: com.melhive.zenith.voice.TextToSpeechEngine get() = voice
}
