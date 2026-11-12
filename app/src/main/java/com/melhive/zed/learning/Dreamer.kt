package com.melhive.zed.learning

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.melhive.zed.AppContainer
import com.melhive.zed.ZedApp
import com.melhive.zed.android.Notifications
import com.melhive.zed.core.intelligence.ChatTurn
import com.melhive.zed.core.intelligence.Role
import com.melhive.zed.core.learning.DreamLogic
import com.melhive.zed.core.learning.Hints
import com.melhive.zed.core.learning.Insight
import com.melhive.zed.core.learning.Insights
import com.melhive.zed.core.learning.Relation
import com.melhive.zed.core.learning.RelationPatterns
import com.melhive.zed.database.RelationEntity
import com.melhive.zed.intelligence.GenerationEvent
import com.melhive.zed.intelligence.GenerationRequest
import com.melhive.zed.intelligence.LocalLiteRtProvider
import com.melhive.zed.ui.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * "Dreaming": quiet housekeeping while the phone charges. Duplicate memories
 * are merged, relationships are read out of memories (by pattern, and by the
 * model when it is allowed), and patterns worth one gentle suggestion are
 * noticed. Nothing here ever runs while the user is talking to ZED.
 */
class Dreamer(private val context: Context, private val c: AppContainer) {
    private val prefs = context.getSharedPreferences("zed_dreams", Context.MODE_PRIVATE)
    private val _insights = MutableStateFlow(loadInsights())
    /** Suggestions waiting on Home. */
    val insights: StateFlow<List<Insight>> = _insights.asStateFlow()

    /** Dreams at most every six hours (two while charging), however often ZED goes quiet. */
    suspend fun dream(allowModel: Boolean) {
        val now = System.currentTimeMillis()
        val gap = if (allowModel) 2 * 3_600_000L else 6 * 3_600_000L
        if (now - prefs.getLong(KEY_LAST_DREAM, 0L) < gap) return
        prefs.edit().putLong(KEY_LAST_DREAM, now).apply()
        mergeDuplicates()
        relationships(allowModel)
        notice()
    }

    private suspend fun mergeDuplicates() {
        val facts = c.memory.list(500).map { DreamLogic.Fact(it.id, it.text, it.pinned, it.source != "learned", it.uses, it.createdAt) }
        val groups = DreamLogic.duplicates(facts)
        if (groups.isEmpty()) return
        val all = c.memory.list(500).associateBy { it.id }
        for ((keep, remove) in groups) {
            // The kept memory inherits how often the others were used.
            val k = all[keep] ?: continue
            c.memory.update(k.copy(uses = k.uses + remove.sumOf { all[it]?.uses ?: 0 }))
            c.memory.delete(remove)
        }
    }

    private suspend fun relationships(allowModel: Boolean) {
        val dao = c.db.relations()
        dao.pruneOrphans()
        val known = dao.all().map { Relation(it.subject, it.relation, it.target).key }.toMutableSet()
        val facts = c.memory.list(500)
        suspend fun save(r: Relation, factId: Long?, source: String) {
            if (!known.add(r.key)) return
            dao.insert(RelationEntity(subject = r.subject, relation = r.relation, target = r.target, factId = factId, createdAt = System.currentTimeMillis(), source = source))
        }
        for (f in facts) for (r in RelationPatterns.extract(f.text)) save(r, f.id, "learned")

        // The model reads memories added since its last pass (only when charging, or already loaded).
        val newest = facts.maxOfOrNull { it.id } ?: return
        val seenUpTo = prefs.getLong(KEY_REL_CURSOR, 0L)
        if (!allowModel || newest <= seenUpTo) return
        val provider = c.providers.active() as? LocalLiteRtProvider ?: return
        val batch = facts.filter { it.id > seenUpTo }.take(40)
        val out = StringBuilder()
        provider.generate(GenerationRequest(system = RelationPatterns.PROMPT_SYSTEM, turns = listOf(ChatTurn(Role.USER, RelationPatterns.prompt(batch.map { it.text }))), maxOutputTokens = 240, sessionKey = "dreaming"))
            .collect { e -> if (e is GenerationEvent.Delta) out.append(e.text) }
        RelationPatterns.parse(out.toString()).forEach { save(it, null, "model") }
        prefs.edit().putLong(KEY_REL_CURSOR, newest).apply()
    }

    private suspend fun notice() {
        val now = System.currentTimeMillis()
        val seen = prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty()
        val pending = _insights.value
        if (pending.size >= 3) return
        val messages = c.db.messages().userMessagesSince(now - 14 * 86_400_000L).map { it.content.substringBefore("\n\n[Attached file:") to it.createdAt }
        val covered = c.db.reminders().scheduled().map { it.text } + c.todos.list(false).map { it.title }
        val actions = c.db.audit().between(now - 21 * 86_400_000L, now).filter { it.outcome == "executed" }.map { Insights.Done(it.tool, it.argsSummary, it.timestamp) }
        val found = (Insights.topics(messages, now, covered, seen) + Insights.habits(actions, now, seen))
            .filter { f -> pending.none { it.key == f.key } }.take(1)
        if (found.isEmpty()) return
        val next = pending + found
        _insights.value = next
        saveInsights(next)
        prefs.edit().putStringSet(KEY_SEEN, seen + found.map { it.key }).apply()
        // Rarely, say it out loud too.
        if (c.settings.current().proactiveHints && Hints.allowed(sentHints(), now)) {
            Notifications.post(context, Notifications.CHANNEL_ASSISTANT, 7500, "ZED noticed something", found.first().text, Routes.HOME)
            recordHint(now)
        }
    }

    fun dismiss(key: String) { _insights.value = _insights.value.filter { it.key != key }; saveInsights(_insights.value) }

    // ---- hint bookkeeping, shared with the leave-now check ----
    fun sentHints(): List<Long> = prefs.getString(KEY_HINTS, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
    fun recordHint(at: Long) { prefs.edit().putString(KEY_HINTS, (sentHints() + at).takeLast(10).joinToString(",")).apply() }
    fun notifiedEvents(): Set<String> = prefs.getStringSet(KEY_EVENTS, emptySet()).orEmpty()
    fun recordEvent(key: String) { prefs.edit().putStringSet(KEY_EVENTS, (notifiedEvents() + key).toList().takeLast(30).toSet()).apply() }

    private fun loadInsights(): List<Insight> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_INSIGHTS, "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { Insight(it.getString("k"), it.getString("t"), Insight.Action.valueOf(it.getString("a")), it.getString("p")) }
    }.getOrDefault(emptyList())

    private fun saveInsights(list: List<Insight>) {
        prefs.edit().putString(KEY_INSIGHTS, JSONArray(list.map { JSONObject().put("k", it.key).put("t", it.text).put("a", it.action.name).put("p", it.payload) }).toString()).apply()
    }

    companion object {
        private const val KEY_REL_CURSOR = "relations_cursor"
        private const val KEY_LAST_DREAM = "last_dream"
        private const val KEY_SEEN = "seen"
        private const val KEY_INSIGHTS = "insights"
        private const val KEY_HINTS = "hints"
        private const val KEY_EVENTS = "events"
        private const val HINT_WORK = "zed-hints"
        private const val HINT_PLANNER = "zed-hints-planner"

        /**
         * Leave-now hints without waking the phone all day: a light check every
         * three hours plans one wake-up 40 minutes before the next event that has
         * a place.
         */
        fun scheduleHints(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(HINT_WORK) // the old every-15-minutes check
            if (!enabled) { wm.cancelUniqueWork(HINT_PLANNER); wm.cancelUniqueWork(HINT_AT); return }
            wm.enqueueUniquePeriodicWork(HINT_PLANNER, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<HintWorker>(3, TimeUnit.HOURS).build())
        }

        private const val HINT_AT = "zed-hints-at"

        /** One wake-up at [at] (epoch millis) for the leave-now check. */
        fun planHintAt(context: Context, at: Long) {
            val delay = (at - System.currentTimeMillis()).coerceAtLeast(0)
            WorkManager.getInstance(context).enqueueUniqueWork(HINT_AT, androidx.work.ExistingWorkPolicy.REPLACE,
                androidx.work.OneTimeWorkRequestBuilder<HintWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build())
        }
    }
}

/** "Time to get going": once per event with a place, within the hint limits. */
class HintWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as ZedApp).container
        if (!c.settings.current().proactiveHints) return Result.success()
        val now = System.currentTimeMillis()
        val event = runCatching { com.melhive.zed.agents.CalendarReader(applicationContext).nextEventWithPlace(now, hours = 4) }.getOrNull() ?: return Result.success()
        val (title, start, place) = event
        // Too early: wake up again 40 minutes before it (once), instead of polling.
        if (start - now > 50 * 60_000L) { Dreamer.planHintAt(applicationContext, start - 40 * 60_000L); return Result.success() }
        val message = Hints.leaveNow(title, start, place, now, c.dreamer.notifiedEvents()) ?: return Result.success()
        if (!Hints.allowed(c.dreamer.sentHints(), now)) return Result.success()
        Notifications.post(applicationContext, Notifications.CHANNEL_REMINDERS, 7600, "Time to go", message, "live:directions to $place")
        c.dreamer.recordEvent("$title@$start")
        c.dreamer.recordHint(now)
        return Result.success()
    }
}
