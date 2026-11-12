package com.melhive.zed.diagnostics

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.melhive.zed.AppContainer
import com.melhive.zed.BuildConfig
import com.melhive.zed.core.intelligence.ChatTurn
import com.melhive.zed.core.intelligence.Role
import com.melhive.zed.database.MessageEntity
import com.melhive.zed.intelligence.GenerationEvent
import com.melhive.zed.intelligence.GenerationRequest
import com.melhive.zed.intelligence.LocalModelRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves plain-text diagnostics where the user (and whoever helps them) can find them: Downloads/ZED reports. */
object ReportFiles {
    const val FOLDER = "ZED reports"

    /** Returns a description of where the file went. */
    suspend fun save(context: Context, prefix: String, text: String): String = withContext(Dispatchers.IO) {
        val name = "$prefix-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("Couldn't create the file in Downloads.")
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: throw java.io.IOException("Couldn't write the file.")
            "Downloads/$FOLDER/$name"
        } else {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, FOLDER).apply { mkdirs() }
            File(dir, name).writeText(text)
            File(dir, name).path
        }
    }
}

data class SpeedResult(
    val at: Long, val version: String, val model: String, val backend: String,
    /** Seconds until the first word appeared, with the model already loaded. */
    val firstWordMs: Long,
    val wordsPerSecond: Float,
    val words: Int, val totalMs: Long,
    /** How long loading the model took, when this test had to load it. */
    val loadMs: Long?,
) {
    fun line() = listOf(at, version, model.replace('|', ' '), backend, firstWordMs, wordsPerSecond, words, totalMs, loadMs ?: -1).joinToString("|")
    fun summary() = "First word %.1f s · %.1f words/s".format(firstWordMs / 1000f, wordsPerSecond)

    companion object {
        fun parse(line: String): SpeedResult? = runCatching {
            val p = line.split('|')
            SpeedResult(p[0].toLong(), p[1], p[2], p[3], p[4].toLong(), p[5].toFloat(), p[6].toInt(), p[7].toLong(), p[8].toLong().takeIf { it >= 0 })
        }.getOrNull()
    }
}

/**
 * Measures how fast the active model answers on this phone: the wait before
 * the first word and the words per second after it. The first result ever
 * recorded is kept as the baseline every later version is compared against.
 */
class SpeedTest(private val context: Context, private val c: AppContainer) {
    sealed interface State {
        data object Idle : State
        data class Running(val label: String) : State
        data class Done(val result: SpeedResult, val savedTo: String?) : State
        data class Failed(val message: String) : State
    }

    private val prefs = context.getSharedPreferences("zed_speed", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun history(): List<SpeedResult> = prefs.getString(KEY_HISTORY, "").orEmpty().lines().mapNotNull { SpeedResult.parse(it) }
    fun baseline(): SpeedResult? = prefs.getString(KEY_BASELINE, null)?.let { SpeedResult.parse(it) }
    fun resetBaseline() { prefs.edit().remove(KEY_BASELINE).apply() }

    suspend fun run() {
        if (_state.value is State.Running) return
        if (c.orchestrator.activity.value.busy) { _state.value = State.Failed("ZED is answering something right now. Try again when it's done."); return }
        val provider = c.providers.active()
        if (provider == null) { _state.value = State.Failed("There's no AI model to test yet. Download one in Models & AI."); return }
        try {
            val runtime = c.providers.localRuntime
            val wasLoaded = runtime.state.value is LocalModelRuntime.State.Ready
            if (!wasLoaded) {
                // Loading is measured on its own, so the reply timing below is always "model already awake".
                _state.value = State.Running("Loading the model…")
                generate(provider, "Reply with the single word: ready", 8, "speedtest-warm")
            }
            _state.value = State.Running("Measuring…")
            val m = generate(provider, PROMPT, 200, "speedtest-${System.currentTimeMillis()}")
            if (m.error != null && m.words < 5) { _state.value = State.Failed(m.error); return }
            val ready = runtime.state.value as? LocalModelRuntime.State.Ready
            val seconds = ((m.totalMs - m.firstMs).coerceAtLeast(1)) / 1000f
            val result = SpeedResult(
                at = System.currentTimeMillis(), version = BuildConfig.VERSION_NAME, model = provider.label,
                backend = ready?.backend?.name ?: "online", firstWordMs = m.firstMs,
                wordsPerSecond = ((m.words - 1).coerceAtLeast(1)) / seconds, words = m.words, totalMs = m.totalMs,
                loadMs = if (!wasLoaded) ready?.loadMillis else null,
            )
            val all = (history() + result).takeLast(20)
            prefs.edit().putString(KEY_HISTORY, all.joinToString("\n") { it.line() }).apply()
            if (baseline() == null) prefs.edit().putString(KEY_BASELINE, result.line()).apply()
            val saved = runCatching { ReportFiles.save(context, "ZED-speed", report(all)) }.getOrNull()
            _state.value = State.Done(result, saved)
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.value = State.Idle; throw e
        } catch (e: Exception) {
            _state.value = State.Failed("The speed test stopped: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private class Measured(val firstMs: Long, val totalMs: Long, val words: Int, val error: String?)

    private suspend fun generate(provider: com.melhive.zed.intelligence.IntelligenceProvider, prompt: String, maxTokens: Int, session: String): Measured {
        val started = System.nanoTime()
        var first = -1L
        val text = StringBuilder()
        var error: String? = null
        provider.generate(GenerationRequest(system = "You are a helpful assistant. Answer in plain sentences.", turns = listOf(ChatTurn(Role.USER, prompt)), maxOutputTokens = maxTokens, sessionKey = session))
            .collect { e ->
                when (e) {
                    is GenerationEvent.Delta -> { if (first < 0 && e.text.isNotBlank()) first = (System.nanoTime() - started) / 1_000_000; text.append(e.text) }
                    is GenerationEvent.Error -> error = e.message
                    else -> Unit
                }
            }
        val total = (System.nanoTime() - started) / 1_000_000
        return Measured(if (first < 0) total else first, total, text.split(Regex("\\s+")).count { it.isNotBlank() }, error)
    }

    fun report(results: List<SpeedResult> = history()): String = buildString {
        appendLine("ZED speed test")
        appendLine(deviceLine(context))
        baseline()?.let { appendLine("Baseline: ${describe(it)}") }
        appendLine()
        results.forEach { appendLine(describe(it)) }
    }

    private fun describe(r: SpeedResult) = "${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(r.at))} · ZED ${r.version} · ${r.model} (${r.backend}) · " +
        "first word ${r.firstWordMs} ms · ${"%.2f".format(Locale.US, r.wordsPerSecond)} words/s · ${r.words} words in ${r.totalMs} ms" + (r.loadMs?.let { " · model load $it ms" } ?: "")

    companion object {
        private const val KEY_HISTORY = "history"
        private const val KEY_BASELINE = "baseline"
        const val PROMPT = "In about 80 words, explain why the sky is blue."

        fun deviceLine(context: Context): String {
            val d = com.melhive.zed.android.DeviceInfo.snapshot(context)
            return "${d.model} · Android ${d.androidVersion} (API ${d.sdk}) · RAM ${com.melhive.zed.android.DeviceInfo.formatBytes(d.totalRamBytes)}, ${com.melhive.zed.android.DeviceInfo.formatBytes(d.availRamBytes)} free · ZED ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        }
    }
}

/**
 * "Report this" on a reply: everything needed to understand what happened in
 * that exchange — what was asked, what ZED did, what it answered and how long
 * each step took — written to one text file. Nothing is sent anywhere; the
 * file stays on the phone until the user shares it.
 */
object ReplyReport {
    suspend fun save(context: Context, c: AppContainer, message: MessageEntity): String = ReportFiles.save(context, "ZED-report", build(context, c, message))

    suspend fun build(context: Context, c: AppContainer, message: MessageEntity): String {
        val all = c.conversations.allMessages(message.conversationId)
        val index = all.indexOfFirst { it.id == message.id }.coerceAtLeast(0)
        // The exchange: from the user message before the reported reply to just before the next user message.
        val start = (index downTo 0).firstOrNull { all[it].role == "user" } ?: index
        val end = (index + 1 until all.size).firstOrNull { all[it].role == "user" } ?: all.size
        val exchange = all.subList(start, end)
        val earlier = all.subList((start - 6).coerceAtLeast(0), start)
        val s = c.settings.current()
        val runtime = c.providers.localRuntime.state.value
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        fun render(m: MessageEntity, marker: String = "") = buildString {
            append("[${time.format(Date(m.createdAt))}] ${m.role.uppercase()}$marker")
            m.source?.let { append(" · $it") }
            m.toolName?.let { append(" · tool ${it}") }
            if (m.status != "complete") append(" · ${m.status}")
            m.firstTokenMs?.let { append(" · first word ${it} ms") }
            m.totalMs?.let { append(" · done ${it} ms") }
            if (m.outputChars != null && m.totalMs != null && m.firstTokenMs != null && m.totalMs > m.firstTokenMs) {
                val words = m.content.split(Regex("\\s+")).count { it.isNotBlank() }
                append(" · ${"%.1f".format(Locale.US, words * 1000f / (m.totalMs - m.firstTokenMs))} words/s")
            }
            m.attachmentName?.let { append(" · attachment $it") }
            append('\n').append(m.content.take(6000)).append('\n')
        }
        return buildString {
            appendLine("ZED reply report")
            appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            appendLine(SpeedTest.deviceLine(context))
            appendLine("Mode ${s.mode} · model ${s.activeLocalModelId ?: "none"} · backend ${s.localBackend} · runtime ${
                when (runtime) {
                    is LocalModelRuntime.State.Ready -> "ready (${runtime.backend}, ${runtime.contextTokens} tokens, loaded in ${runtime.loadMillis} ms)"
                    is LocalModelRuntime.State.Loading -> "loading"
                    is LocalModelRuntime.State.Failed -> "failed: ${runtime.message}"
                    else -> "not loaded"
                }
            }")
            appendLine("Personality ${s.persona} · ask before actions ${s.confirmMode} · voice ${s.voiceId}")
            c.speedTest.history().lastOrNull()?.let { appendLine("Last speed test: ${it.summary()} (ZED ${it.version})") }
            appendLine()
            appendLine("=== The exchange ===")
            exchange.forEach { append(render(it, if (it.id == message.id) "  <<< REPORTED" else "")).append('\n') }
            if (earlier.isNotEmpty()) {
                appendLine("=== Before it (context) ===")
                earlier.forEach { append(render(it)).append('\n') }
            }
            val from = exchange.first().createdAt - 1000
            val to = (all.getOrNull(end)?.createdAt ?: (exchange.last().createdAt + 120_000))
            val audit = runCatching { c.db.audit().between(from, to) }.getOrDefault(emptyList())
            appendLine("=== Actions ZED took (audit log) ===")
            if (audit.isEmpty()) appendLine("none")
            audit.forEach { appendLine("[${time.format(Date(it.timestamp))}] ${it.outcome.uppercase()} ${it.tool} [${it.origin}] ${it.argsSummary} -> ${it.detail}") }
            com.melhive.zed.android.CrashReporter.errors()?.let { appendLine(); appendLine("=== Recent background errors ==="); appendLine(it.take(3000)) }
        }
    }
}
