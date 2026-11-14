package com.melhive.zed.learning

import kotlinx.coroutines.flow.first
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.melhive.zed.AppContainer
import com.melhive.zed.ZedApp
import com.melhive.zed.core.intelligence.ChatTurn
import com.melhive.zed.core.intelligence.Role
import com.melhive.zed.core.learning.FactPatterns
import com.melhive.zed.core.learning.LearnedFact
import com.melhive.zed.core.learning.LearnedFilter
import com.melhive.zed.core.learning.LearningPrompt
import com.melhive.zed.intelligence.GenerationEvent
import com.melhive.zed.intelligence.GenerationRequest
import com.melhive.zed.intelligence.LocalLiteRtProvider
import com.melhive.zed.intelligence.LocalModelRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Automatic learning: after conversations, ZED picks up lasting facts the
 * user mentioned in passing.
 *
 * - Exact phrasings ("my sister's name is Ana") are always read; they need no model.
 * - The on-device model reads the rest only while ZED is idle and the phone is
 *   charging, or the model is already loaded and the battery is healthy.
 * - The moment the user asks something or opens LIVE, the model's work stops.
 * - Learned facts are marked as learned (less certain) and listed for review.
 */
class Learner(private val context: Context, private val c: AppContainer, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("zed_learning", Context.MODE_PRIVATE)
    private var pending: Job? = null
    private var running: Job? = null
    @Volatile private var modelBusy = false

    private val _newFacts = MutableStateFlow(loadNew())
    /** Memory ids learned since the user last reviewed them (the Home card). */
    val newFacts: StateFlow<List<Long>> = _newFacts.asStateFlow()

    /** Watches ZED: a quiet minute after a conversation, learn; anything from the user stops it at once. */
    fun watch() {
        scope.launch {
            combine(c.orchestrator.activity, c.live.state) { a, l -> a.busy || l.active }.distinctUntilChanged().collect { userActive ->
                if (userActive) {
                    pending?.cancel(); pending = null
                    stopNow()
                } else {
                    pending?.cancel()
                    pending = scope.launch {
                        delay(IDLE_DELAY_MS)
                        runCatching { learn(allowModel = modelAllowed()) }
                    }
                }
            }
        }
    }

    /** Stops the model's part immediately (the user needs it). */
    private fun stopNow() {
        val r = running ?: return
        r.cancel()
        if (modelBusy) c.providers.localRuntime.cancelActive()
    }

    private fun batteryOk(): Pair<Boolean, Int> {
        val b = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull() ?: return false to 0
        val status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val l = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return charging to (if (l >= 0 && s > 0) l * 100 / s else 0)
    }

    /** Charging, or the model is already in memory and the battery is above 40%. Never loads a model on battery just to learn. */
    private fun modelAllowed(): Boolean {
        val (charging, level) = batteryOk()
        val loaded = c.providers.localRuntime.state.value is LocalModelRuntime.State.Ready
        return charging || (loaded && level >= 40)
    }

    /** Reads what the user said since last time and saves what is worth keeping. Returns the facts saved. */
    suspend fun learn(allowModel: Boolean): List<String> {
        if (!c.settings.current().autoLearning) return emptyList()
        if (running?.isActive == true) return emptyList()
        val after = prefs.getLong(KEY_CURSOR, 0L)
        // Conversations held on a locked phone (anyone may have been talking) teach ZED nothing.
        val guestTalks = c.db.conversations().observeAll().first().filter { it.title.startsWith("🔒") }.map { it.id }.toSet()
        val messages = c.db.messages().userMessagesAfter(after, 60).filter { it.conversationId !in guestTalks }
            .filter { it.source == null || it.source == "user" }
            .filter { it.attachmentName == null || !it.content.contains("[Attached file:") }
        if (messages.isEmpty()) { dreamNow(allowModel); return emptyList() }
        val texts = messages.map { it.content.substringBefore("\n\n[Attached file:").trim() }.filter { it.length >= 6 }
        val existing = c.memory.list(400).map { it.text }

        val candidates = texts.flatMap { FactPatterns.extract(it) }.toMutableList()
        var modelFinished = !allowModel
        if (allowModel && texts.isNotEmpty()) {
            val provider = c.providers.active() as? LocalLiteRtProvider
            if (provider != null) {
                val job = scope.launch {
                    modelBusy = true
                    try {
                        val out = StringBuilder()
                        provider.generate(GenerationRequest(
                            system = LearningPrompt.SYSTEM,
                            turns = listOf(ChatTurn(Role.USER, LearningPrompt.build(texts.takeLast(14), existing))),
                            maxOutputTokens = 220, sessionKey = "learning",
                        )).collect { e -> if (e is GenerationEvent.Delta) out.append(e.text) }
                        candidates += LearningPrompt.parse(out.toString())
                        modelFinished = true
                    } finally { modelBusy = false }
                }
                running = job
                job.join()
                running = null
            } else modelFinished = true
        }

        val accepted = LearnedFilter.accept(candidates, existing)
        val savedFacts = accepted.mapNotNull { f -> c.memory.learn(f.text, f.confidence) }
        val saved = savedFacts.map { it.id }
        if (saved.isNotEmpty()) {
            _newFacts.value = (_newFacts.value + saved).distinct().takeLast(20)
            saveNew()
        }
        // The model's part didn't finish (the user came back): read these messages again next time.
        if (modelFinished) prefs.edit().putLong(KEY_CURSOR, messages.maxOf { it.id }).apply()
        dreamNow(allowModel && modelFinished)
        return savedFacts.map { it.text }
    }

    /** Dreaming after learning; its model part stops the moment the user needs the model, like learning's. */
    private suspend fun dreamNow(allowModel: Boolean) {
        val job = scope.launch {
            modelBusy = allowModel
            try { runCatching { c.dreamer.dream(allowModel) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it } } finally { modelBusy = false }
        }
        running = job
        job.join()
        running = null
    }

    /** The user kept a learned fact: it becomes as certain as something they said. */
    suspend fun confirm(id: Long) {
        c.memory.list(400).firstOrNull { it.id == id }?.let { c.memory.update(it.copy(confidence = 1f, source = "learned_confirmed")) }
        dismiss(id)
    }

    suspend fun edit(id: Long, text: String) {
        c.memory.list(400).firstOrNull { it.id == id }?.let { c.memory.update(it.copy(text = text.trim(), confidence = 1f, source = "learned_confirmed")) }
        dismiss(id)
    }

    suspend fun forget(id: Long) {
        c.memory.delete(listOf(id))
        dismiss(id)
    }

    fun dismiss(id: Long) { _newFacts.value = _newFacts.value - id; saveNew() }
    fun dismissAll() { _newFacts.value = emptyList(); saveNew() }

    private fun loadNew(): List<Long> = prefs.getString(KEY_NEW, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
    private fun saveNew() { prefs.edit().putString(KEY_NEW, _newFacts.value.joinToString(",")).apply() }

    companion object {
        private const val KEY_CURSOR = "cursor"
        private const val KEY_NEW = "new"
        private const val IDLE_DELAY_MS = 75_000L
        private const val WORK = "zed-learning"

        /** Twice a day while charging, even when ZED isn't open. */
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork(WORK); return }
            val req = PeriodicWorkRequestBuilder<LearningWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

class LearningWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as ZedApp).container
        if (c.orchestrator.activity.value.busy) return Result.retry()
        runCatching { c.learner.learn(allowModel = true) }.onFailure { com.melhive.zed.android.CrashReporter.recordNonFatal("learning", it) }
        return Result.success()
    }
}
