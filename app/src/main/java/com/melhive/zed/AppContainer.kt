package com.melhive.zed

import kotlinx.coroutines.flow.stateIn

import kotlinx.coroutines.flow.map

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
class AppContainer(val context: Context) {
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
    val providers by lazy { ProviderRegistry(context, settings, models, localRuntime) }

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

    /** True when the phone is locked with a PIN, pattern or fingerprint. */
    fun phoneLocked(): Boolean = runCatching { context.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceLocked == true }.getOrDefault(false)
    /** What the user asked for while the phone was locked; the screen unlocks and asks again. */
    val unlockRequest = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** Private Connections: encrypted notes shown in the Live Brain only after a fingerprint or PIN. */
    val privateStore by lazy { com.melhive.zed.security.PrivateStore(context) }
    /** "Private mode" / "lock private mode" by voice: true asks the Live Brain to unlock, false to lock. */
    val brainPrivate = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)
    /** People ZED has learned to recognise in Eye mode. Kept on the phone only. */
    val people by lazy { com.melhive.zed.eyes.PeopleStore(context) }
    /** How many voice screens (LIVE, Eye mode) are showing: the session ends when the last one closes, not when switching between them. */
    val voiceScreens = java.util.concurrent.atomic.AtomicInteger(0)
    /** A request LIVE should send as soon as it opens (from a notification or shortcut). */
    val livePrompt = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    val toolManager: ToolManager by lazy {
        ToolManager(catalog, skills, settings, AndroidCapabilityChecker(context, this), confirmations, audit, DefaultConfirmationText).also { tm ->
            tm.locked = { phoneLocked() }
            tm.onLockedRequest = { unlockRequest.value = orchestrator.currentUserText }
            registerExecutors(tm, this, context)
            tm.afterSuccess = { tool -> if (tool.startsWith("calendar.") || tool == "app.undo") appScope.launch { runCatching { com.melhive.zed.agents.Announcer.planEvents(context) } } }
            workflows.toolManager = tm
        }
    }

    val orchestrator by lazy {
        AssistantOrchestrator(appScope, conversations, memory, knowledge, providers, toolManager, skills, settings).also { o ->
            o.routineMatcher = { text -> routines.matchPhrase(text)?.name }
            o.aliases = { teaching.aliases }
            o.awareness = { com.melhive.zed.agents.AwarenessCollector(context, this).collect() }
            o.guest = { settings.current().lockGuard && phoneLocked() }
        }
    }

    /**
     * Loads the on-device model ahead of the first question (ZED opened, wake
     * word heard, LIVE started), so the reply doesn't wait for it. Skipped when
     * the phone is short of memory, or the battery is nearly empty and not charging
     * (loading Gemma costs a few seconds of full CPU).
     */
    fun warmUpModel() {
        appScope.launch {
            runCatching {
                if (com.melhive.zed.android.DeviceInfo.snapshot(context).lowMemory) return@runCatching
                val battery = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                val level = battery?.let { b -> b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 100) * 100 / maxOf(1, b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)) } ?: 100
                val plugged = (battery?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
                if (level < 15 && !plugged) return@runCatching
                (providers.active() as? com.melhive.zed.intelligence.LocalLiteRtProvider)?.warmUp()
            }
        }
    }

    val live by lazy {
        LiveSessionController(context, orchestrator, conversations, settings, { newSpeechToText() }, { voice.sessionHandle() })
            .also { it.userName = { memory.userName() } }
            .also { live ->
                live.lockedNow = { settings.current().lockGuard && phoneLocked() }
                live.onCallEnded = { conv ->
                    // Written a little later and only while ZED is idle, so it never delays the next thing you ask.
                    appScope.launch {
                        kotlinx.coroutines.delay(20_000)
                        if (live.state.value.active || orchestrator.activity.value.busy) return@launch
                        runCatching { orchestrator.summarize(conv)?.let { conversations.setSummary(conv, it) } }
                    }
                }
            }
    }

    val updates = com.melhive.zed.android.UpdateChecker()

    /** Conversation Chat should show next (set by Home's "Ask ZED" box). */
    val chatFocus = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)

    /** Downloadable offline neural voice packs. */
    val voicePacks by lazy { com.melhive.zed.voice.VoicePackRepository(context) }

    /** The voice ZED speaks with everywhere (system or neural, per Settings → Voice). */
    // Lazy: the separate voice process also builds a container and must not open settings.
    private val speechEngine by lazy { settings.settings.map { it.speechEngine }.stateIn(appScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, "auto") }

    /** True when speech is recognized on the phone (model downloaded and not switched off). */
    fun onDeviceSpeech(): Boolean = speechEngine.value != "android" && voicePacks.isInstalled(com.melhive.zed.core.voice.VoiceCatalog.SPEECH)

    /** The speech recognizer to use right now: on-device when available, otherwise the phone's service. */
    fun newSpeechToText(): com.melhive.zed.voice.SpeechToText =
        if (onDeviceSpeech()) com.melhive.zed.voice.OnDeviceSpeechToText(voicePacks.dir(com.melhive.zed.core.voice.VoiceCatalog.SPEECH), context)
        else AndroidSpeechToText(context)

    /** Every delete can be undone for 30 minutes ("undo" or the Undo bar). */
    val undo by lazy { com.melhive.zed.tools.UndoLog(appScope, context) }
    val deleter by lazy { com.melhive.zed.tools.Deleter(this) }
    /** Pronunciations and shortcuts the user taught (loaded before the voice so taught words are said right). */
    val teaching by lazy { com.melhive.zed.learning.TeachingStore(context, settings, appScope) }
    val voice by lazy { teaching; com.melhive.zed.voice.VoiceManager(context, settings, voicePacks, appScope) }

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

    /** Requests that wait for the internet (answered and announced when it is back). */
    val pending by lazy { com.melhive.zed.web.PendingQueue(context, this) }

    /** Dreaming: merges duplicates, reads relationships, notices patterns (while charging). */
    val dreamer by lazy { com.melhive.zed.learning.Dreamer(context, this) }

    /** "Show me everything about Ana": what the Live Brain should fly to when it opens. */
    val brainFocus = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Automatic learning from ordinary conversation. */
    val learner by lazy { com.melhive.zed.learning.Learner(context, this, appScope) }

    /** Diagnostics → Speed test. */
    val speedTest by lazy { com.melhive.zed.diagnostics.SpeedTest(context, this) }

    /** Everything ZED stores, as items for the Live Brain (full screen and the mini brains). */
    val brain by lazy { com.melhive.zed.brain.BrainHub(appScope, this) }

    /** Reads chat replies aloud with the selected voice. */
    val chatTts: com.melhive.zed.voice.TextToSpeechEngine get() = voice
}
