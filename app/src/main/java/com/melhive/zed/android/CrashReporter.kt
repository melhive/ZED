package com.melhive.zed.android

import android.content.Context
import android.os.Build
import com.melhive.zed.BuildConfig
import com.melhive.zed.core.security.SecretRedactor
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records crashes and non-fatal background errors to app-private files so they
 * can be viewed and shared from Permissions & Diagnostics (no logcat needed).
 * Nothing is sent anywhere automatically. Secrets are redacted.
 */
object CrashReporter {
    private const val CRASH_FILE = "last-crash.txt"
    private const val ERRORS_FILE = "errors.log"
    private lateinit var dir: File
    private lateinit var app: Context

    fun install(context: Context) {
        app = context.applicationContext
        dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // An error in a background task or coroutine doesn't have to take the whole app down.
            val main = thread === android.os.Looper.getMainLooper().thread
            val fromCoroutine = Thread.currentThread().stackTrace.any { it.className.startsWith("kotlinx.coroutines") }
            if ((!main || fromCoroutine) && survivable(error)) { caught(error, thread.name); return@setDefaultUncaughtExceptionHandler }
            val text = report("Crash on thread ${thread.name} (${processName()})", error)
            runCatching { File(dir, CRASH_FILE).writeText(text) }
            runCatching { saveToDownloads(text) }
            previous?.uncaughtException(thread, error)
        }
        // Errors thrown on the main thread outside drawing (a button's action, a callback) are caught
        // here and recorded instead of closing the app.
        if (Build.FINGERPRINT != "robolectric") android.os.Handler(android.os.Looper.getMainLooper()).post {
            while (true) {
                try { android.os.Looper.loop() } catch (e: Throwable) {
                    if (!survivable(e)) throw e
                    caught(e, "main")
                }
            }
        }
    }

    private val recent = ArrayDeque<Long>()

    /**
     * Whether ZED can safely keep running after [e]: not out of memory, not a failure while drawing
     * the screen (the UI could be left broken), and not repeating (more than 5 in 30 s means stop).
     */
    private fun survivable(e: Throwable): Boolean {
        if (e is OutOfMemoryError || e is StackOverflowError) return false
        if (generateSequence(e) { it.cause }.any { t -> t.stackTrace.any { it.className.startsWith("androidx.compose.runtime") || it.className.startsWith("androidx.compose.ui.node") } }) return false
        val now = System.currentTimeMillis()
        synchronized(recent) {
            while (recent.isNotEmpty() && now - recent.first() > 30_000) recent.removeFirst()
            if (recent.size >= 5) return false
            recent.addLast(now)
        }
        return true
    }

    private fun caught(e: Throwable, where: String) {
        recordNonFatal("kept running after an error on $where", e)
        runCatching { File(dir, CRASH_FILE).writeText(report("Recovered from an error on $where (${processName()}) — ZED kept running", e)) }
    }

    private fun processName(): String = if (Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName() else "zed"

    /**
     * Native crashes (AI model, voices, speech engine), freezes and out-of-memory kills skip the
     * handler above. Android remembers why the app last stopped; on the next start that is written
     * up like a crash, with the native trace when Android provides one.
     */
    fun collectExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < 30 || !::dir.isInitialized) return
        runCatching {
            val am = context.getSystemService(android.app.ActivityManager::class.java)
            val prefs = context.getSharedPreferences("zed_crash", Context.MODE_PRIVATE)
            val seen = prefs.getLong("seen_exit", 0L)
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 10).filter { it.timestamp > seen }
            val bad = exits.filter { it.reason in setOf(android.app.ApplicationExitInfo.REASON_CRASH_NATIVE, android.app.ApplicationExitInfo.REASON_CRASH,
                android.app.ApplicationExitInfo.REASON_ANR, android.app.ApplicationExitInfo.REASON_LOW_MEMORY, android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE) }
            exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("seen_exit", it).apply() }
            val worst = bad.maxByOrNull { it.timestamp } ?: return
            val kind = when (worst.reason) {
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
                android.app.ApplicationExitInfo.REASON_CRASH -> "Crash"
                android.app.ApplicationExitInfo.REASON_ANR -> "Not responding (ANR)"
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "Stopped by Android: low memory"
                else -> "Stopped by Android: too much resource use"
            }
            val trace = runCatching { worst.traceInputStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
            val text = buildString {
                appendLine("ZED crash report")
                appendLine("Time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(worst.timestamp)))
                appendLine("App: ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("$kind in ${worst.processName}: ${worst.description ?: ""}")
                appendLine("Memory: ${worst.pss / 1024} MB used, importance ${worst.importance}")
                if (bad.size > 1) appendLine("Other recent stops: " + bad.filter { it !== worst }.joinToString { "${it.reason}@${SimpleDateFormat("HH:mm", Locale.US).format(Date(it.timestamp))}" })
                appendLine()
                append(trace.take(30_000))
            }
            File(dir, CRASH_FILE).writeText(text)
            saveToDownloads(text)
        }
    }

    /** Also keeps a copy in Download/ZED, so a crash can be looked at without opening the app. */
    private fun saveToDownloads(text: String) {
        if (Build.VERSION.SDK_INT < 29 || !::app.isInitialized) return
        val name = "zed-crash-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/ZED")
        }
        val uri = app.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        app.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
    }

    /** Records a non-fatal error (kept to the most recent ~50 entries). */
    fun recordNonFatal(where: String, error: Throwable) {
        if (!::dir.isInitialized) return
        runCatching {
            val f = File(dir, ERRORS_FILE)
            val entry = "${timestamp()} [$where] ${SecretRedactor.redact(error.toString())}\n" +
                error.stackTrace.take(6).joinToString("\n") { "    at $it" } + "\n"
            val existing = if (f.exists()) f.readText().split("\n---\n").takeLast(49) else emptyList()
            f.writeText((existing + entry).joinToString("\n---\n"))
        }
    }

    fun lastCrash(): String? = if (::dir.isInitialized) File(dir, CRASH_FILE).takeIf { it.exists() }?.readText() else null
    fun errors(): String? = if (::dir.isInitialized) File(dir, ERRORS_FILE).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() } else null

    fun clear() {
        if (!::dir.isInitialized) return
        File(dir, CRASH_FILE).delete(); File(dir, ERRORS_FILE).delete()
    }

    private fun timestamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun report(title: String, error: Throwable): String {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        return buildString {
            appendLine("ZED crash report")
            appendLine("Time: ${timestamp()}")
            appendLine("App: ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine(title)
            appendLine()
            append(SecretRedactor.redact(sw.toString()).take(20_000))
        }
    }
}
