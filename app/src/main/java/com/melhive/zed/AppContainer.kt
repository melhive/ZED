package com.melhive.zed

import android.content.Context
import com.melhive.zed.agents.AssistantOrchestrator
import com.melhive.zed.agents.ConfirmationBroker
import com.melhive.zed.agents.WorkflowEngine
import com.melhive.zed.android.ReminderScheduler
import com.melhive.zed.core.skills.SkillRegistry
import com.melhive.zed.core.tools.ToolCatalog
import androidx.room.withTransaction
import com.melhive.zed.database.ZedDatabase
import com.melhive.zed.files.SafFileSystem
import com.melhive.zed.intelligence.LocalModelRuntime
import com.melhive.zed.intelligence.ProviderRegistry
import com.melhive.zed.knowledge.KnowledgeRepository
import com.melhive.zed.memory.ConversationRepository
import com.melhive.zed.memory.MemoryRepository
import com.melhive.zed.memory.TodoRepository
import com.melhive.zed.models.ModelDownloader
import com.melhive.zed.models.ModelRepository
import com.melhive.zed.security.AuditLogger
import com.melhive.zed.security.SecretStore
import com.melhive.zed.settings.SettingsRepository
import com.melhive.zed.tasks.TaskEngine
import com.melhive.zed.tools.ToolManager
import com.melhive.zed.tools.executors.AndroidCapabilityChecker
import com.melhive.zed.tools.executors.DefaultConfirmationText
import com.melhive.zed.tools.executors.registerExecutors
import com.melhive.zed.voice.AndroidSpeechToText
import com.melhive.zed.voice.LiveSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Manual dependency container. One instance per process, owned by [ZedApp]. */
class AppContainer(private val context: Context) {
    /** Background scope: failures are recorded for Diagnostics instead of crashing the app. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        com.melhive.zed.android.CrashReporter.recordNonFatal("background", e)
    })

    val db: ZedDatabase by lazy { ZedDatabase.build(context) }
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
    val workflows by lazy { WorkflowEngine(db, taskEngine).also { it.briefing = com.melhive.zed.agents.BriefingComposer(context, this) } }
    val routines by lazy { com.melhive.zed.agents.RoutineEngine(context, this) }

    /** Home Assistant client when an address and token are configured, else null. */
    suspend fun homeAssistant(): com.melhive.zed.android.HomeAssistant? {
        val url = settings.current().homeAssistantUrl.ifBlank { return null }
        val token = secrets.get(SecretStore.HOME_ASSISTANT_TOKEN) ?: return null
        return com.melhive.zed.android.HomeAssistant(url, token)
    }

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
            o.awareness = { com.melhive.zed.agents.AwarenessCollector(context, this).collect() }
        }
    }

    /**
     * Loads the on-device model ahead of the first question (ZED opened, wake
     * word heard, LIVE started), so the reply doesn't wait for it. Skipped when
     * an online model answers, or when the phone is short of memory.
     */
    fun warmUpModel() {
        appScope.launch {
            runCatching {
                if (com.melhive.zed.android.DeviceInfo.snapshot(context).lowMemory) return@runCatching
                (providers.active() as? com.melhive.zed.intelligence.LocalLiteRtProvider)?.warmUp()
            }
        }
    }

    val live by lazy {
        LiveSessionController(context, orchestrator, conversations, settings, { AndroidSpeechToText(context) }, { voice.sessionHandle() }, { realtimeConfig() })
    }

    /** Gemini Live settings for ZED LIVE, or null when no Gemini key is configured. */
    private suspend fun realtimeConfig(): com.melhive.zed.voice.RealtimeConfig? {
        val s = settings.current()
        val key = secrets.get(SecretStore.apiKeyName(com.melhive.zed.settings.OnlineProviderKind.GEMINI)) ?: return null
        val tools = skills.enabledTools(s.enabledSkills).filter { it.name != "web.research" }
        val now = java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd'T'HH:mm:ss (h:mm:ss a, VV)", java.util.Locale.ENGLISH))
        val prompt = com.melhive.zed.core.intelligence.PromptBuilder.systemPrompt(
            tools = emptyList(), nowDescription = now, memories = memory.list(30).map { it.text }, knowledge = emptyList(),
            voiceMode = true, userName = memory.userName(),
            persona = com.melhive.zed.core.intelligence.Persona.parse(s.persona), userTitle = s.userTitle.ifBlank { null },
            nativeTools = tools.isNotEmpty(),
        )
        return com.melhive.zed.voice.RealtimeConfig(
            apiKey = key,
            model = s.liveModel.ifBlank { com.melhive.zed.voice.GeminiLiveSession.DEFAULT_MODEL },
            voiceName = s.liveVoice,
            systemPrompt = prompt,
            tools = tools,
            webSearch = true,
            executeTool = { call ->
                val out = toolManager.execute(com.melhive.zed.core.tools.ToolRequest(call.name, call.args, com.melhive.zed.core.tools.ToolRequest.Origin.ONLINE_MODEL))
                com.melhive.zed.intelligence.ToolCallResult(call.id, call.name, out.message, out is com.melhive.zed.tools.ToolOutcome.Executed && out.result.ok)
            },
        )
    }

    val updates = com.melhive.zed.android.UpdateChecker()

    /** Conversation Chat should show next (set by Home's "Ask ZED" box). */
    val chatFocus = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)

    /** Downloadable offline neural voice packs. */
    val voicePacks by lazy { com.melhive.zed.voice.VoicePackRepository(context) }

    /** The voice ZED speaks with everywhere (system or neural, per Settings → Voice). */
    val voice by lazy { com.melhive.zed.voice.VoiceManager(context, settings, voicePacks, appScope) }

    /** Backup to a file and restore from one (More → Backup & restore). */
    val backup by lazy {
        com.melhive.zed.backup.BackupManager(
            context, appScope, ZedDatabase.VERSION, BuildConfig.VERSION_NAME,
            lockDatabase = { block ->
                // Fold the write-ahead log into the main file first (best effort), then copy while writers wait.
                runCatching { db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() } }
                db.withTransaction { block() }
            },
            readSecrets = { secrets.configured.value.mapNotNull { name -> secrets.get(name)?.let { name to it } }.toMap() },
            writeSecret = { name, value -> secrets.put(name, value) },
            counts = {
                mapOf(
                    "memories" to memory.list(100_000).size,
                    "tasks" to todos.list(includeDone = true).size,
                    "reminders" to db.reminders().count(),
                    "conversations" to db.conversations().count(),
                    "indexed documents" to db.knowledge().docCount(),
                )
            },
        )
    }

    /**
     * Runs once after a restore was swapped in: forgets models whose files did
     * not come with the backup, so they show as "not downloaded" instead of failing to load.
     */
    suspend fun afterRestore() {
        if (!backup.consumeNotice()) return
        for (m in db.models().all()) if (!models.fileFor(m).exists()) db.models().delete(m.id)
        val active = settings.current().activeLocalModelId
        if (active != null && models.get(active) == null) settings.setActiveLocalModel(models.firstInstalled()?.id)
    }

    /** Diagnostics → Speed test. */
    val speedTest by lazy { com.melhive.zed.diagnostics.SpeedTest(context, this) }

    /** Everything ZED stores, as items for the Live Brain (full screen and the mini brains). */
    val brain by lazy { com.melhive.zed.brain.BrainHub(appScope, this) }

    /** Reads chat replies aloud with the selected voice. */
    val chatTts: com.melhive.zed.voice.TextToSpeechEngine get() = voice
}
