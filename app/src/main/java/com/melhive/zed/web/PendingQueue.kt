package com.melhive.zed.web

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.melhive.zed.AppContainer
import com.melhive.zed.ZedApp
import com.melhive.zed.android.Notifications
import com.melhive.zed.core.intelligence.ChatTurn
import com.melhive.zed.core.intelligence.Role
import com.melhive.zed.core.web.Research
import com.melhive.zed.database.PendingRequestEntity
import com.melhive.zed.intelligence.GenerationEvent
import com.melhive.zed.intelligence.GenerationRequest
import com.melhive.zed.ui.navigation.Routes
import java.util.concurrent.TimeUnit

/**
 * Requests that need the internet while the phone is offline. They are kept,
 * and a background job runs them the moment a connection is back, adds the
 * answer to the conversation they came from and posts a notification.
 */
class PendingQueue(private val context: Context, private val c: AppContainer) {
    /** How many requests are waiting for a connection. */
    val waiting = c.db.pendingRequests().observeWaiting()

    suspend fun enqueue(kind: String, text: String, conversationId: Long?): Long {
        val id = c.db.pendingRequests().insert(PendingRequestEntity(text = "$kind:$text", conversationId = conversationId, createdAt = System.currentTimeMillis(), status = "waiting"))
        schedule(context)
        return id
    }

    /** Runs everything waiting. Returns false when it should be tried again later (ZED busy, still offline). */
    suspend fun runAll(): Boolean {
        val list = c.db.pendingRequests().waiting()
        for (r in list) {
            // Never compete with the user for the model: try again shortly.
            if (c.orchestrator.activity.value.busy) return false
            val kind = r.text.substringBefore(':')
            val body = r.text.substringAfter(':')
            val answer = try {
                when (kind) {
                    "research" -> answerResearch(body)
                    "weather" -> com.melhive.zed.agents.WeatherService(context).report(body.ifBlank { null }, c.settings.current().weatherCity).getOrThrow()
                    else -> { c.db.pendingRequests().finish(r.id, "cancelled", null); continue }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: WebSearcher.Unavailable) {
                if (!com.melhive.zed.android.DeviceInfo.snapshot(context).online) return false
                e.message ?: "I couldn't look that up."
            } catch (e: Exception) {
                if (!com.melhive.zed.android.DeviceInfo.snapshot(context).online) return false
                "I tried to look up “$body” but it failed: ${e.message ?: e.javaClass.simpleName}"
            }
            val title = if (kind == "weather") "Weather, now that you're back online" else "Back online: “${body.take(60)}”"
            val conv = r.conversationId?.takeIf { c.conversations.get(it) != null } ?: c.conversations.create("Answered when back online")
            c.conversations.addMessage(conv, "assistant", answer, source = "research")
            c.db.pendingRequests().finish(r.id, "done", answer.take(4000))
            Notifications.post(context, Notifications.CHANNEL_ASSISTANT, NOTIFICATION_BASE + (r.id % 1000).toInt(), title, answer.take(400), Routes.chat(conv))
        }
        return true
    }

    /** Researches [question] and lets the on-device model answer from the sources (or lists them when no model is set up). */
    private suspend fun answerResearch(question: String): String {
        val found = WebSearcher().research(question)
        val provider = c.providers.active() ?: return Research.plainAnswer(found.query, found.pages)
        val sources = Research.toolResult(found.query, found.pages)
        val text = StringBuilder()
        var error: String? = null
        provider.generate(GenerationRequest(
            system = "You are ZED, a personal assistant. Answer the user's question from the web sources given, briefly and accurately, citing them like [1].",
            turns = listOf(ChatTurn(Role.USER, "$sources\n\nQuestion: $question")),
            maxOutputTokens = 512, sessionKey = "pending-research",
        )).collect { e -> when (e) { is GenerationEvent.Delta -> text.append(e.text); is GenerationEvent.Error -> error = e.message; else -> Unit } }
        if (text.isBlank()) return Research.plainAnswer(found.query, found.pages) + (error?.let { "\n\n(The model couldn't summarise it: $it)" } ?: "")
        return Research.withSources(text.toString().trim(), Research.sourcesFrom(sources))
    }

    companion object {
        private const val WORK = "zed-pending-requests"
        private const val NOTIFICATION_BASE = 7300

        /** Runs the queue as soon as there is a connection (now, if there is one). */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<PendingWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
        }
    }
}

class PendingWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as ZedApp).container
        return try {
            if (c.pending.runAll()) Result.success() else Result.retry()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            com.melhive.zed.android.CrashReporter.recordNonFatal("pending", e)
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}
