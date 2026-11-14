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
    /** The stack trace of a real crash, written the moment it happens and never overwritten by anything else. */
    private const val FATAL_FILE = "fatal.txt"
    /** A short trail of what ZED was doing (screens, voice session, actions): this run and the one before. */
    private const val CRUMBS = "crumbs.log"
    private const val CRUMBS_BEFORE = "crumbs-before.log"
    private const val ERRORS_FILE = "errors.log"
    private lateinit var dir: File
    private lateinit var app: Context

    fun install(context: Context) {
        app = context.applicationContext
        dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
        // Keep the trail of the previous run (it shows what led up to a crash) and start a new one.
        runCatching {
            if (processName().contains(':')) return@runCatching
            val crumbs = File(dir, CRUMBS)
            if (crumbs.exists()) { File(dir, CRUMBS_BEFORE).delete(); crumbs.renameTo(File(dir, CRUMBS_BEFORE)) }
        }
        crumb("ZED started (${processName()})")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // An error in a background task or coroutine doesn't have to take the whole app down.
            val main = thread === android.os.Looper.getMainLooper().thread
            val fromCoroutine = Thread.currentThread().stackTrace.any { it.className.startsWith("kotlinx.coroutines") }
            if ((!main || fromCoroutine) && survivable(error)) { caught(error, thread.name); return@setDefaultUncaughtExceptionHandler }
            // First, and as simply as possible, get the stack trace onto disk: nothing below may prevent that.
            try {
                val sw = StringWriter(); error.printStackTrace(PrintWriter(sw))
                File(dir, FATAL_FILE).writeText("${timestamp()} thread ${thread.name} (${processName()})\n$sw")
            } catch (_: Throwable) {}
            try {
                crumb("CRASH: $error")
                val text = report("Crash on thread ${thread.name} (${processName()})", error) + "\n\n" + trail(CRUMBS)
                File(dir, CRASH_FILE).writeText(text)
                saveToDownloads(text)
            } catch (_: Throwable) {}
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
        // Android tells the app to crash this way (e.g. a service that didn't start in time); swallowing it
        // only makes Android kill the app without a report.
        if (generateSequence(e) { it.cause }.any { it.javaClass.name.contains("RemoteServiceException") || it.javaClass.name.contains("ForegroundService") || it.javaClass.name.contains("DeadSystem") }) return false
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
        crumb("recovered from $e on $where")
        // Never replaces the record of a real crash that hasn't been collected yet.
        runCatching { if (!File(dir, FATAL_FILE).exists()) File(dir, CRASH_FILE).writeText(report("Recovered from an error on $where (${processName()}) — ZED kept running", e)) }
    }

    /**
     * Notes what ZED is doing right now (a screen opened, a voice session started, an action ran).
     * When ZED stops unexpectedly, the last of these are in the report: they show what led up to it,
     * even when Android gives no stack trace.
     */
    fun crumb(what: String) {
        if (!::dir.isInitialized) return
        runCatching {
            val f = File(dir, CRUMBS)
            if (f.length() > 24_000) f.writeText(f.readText().takeLast(12_000).substringAfter('\n'))
            f.appendText(SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date()) + " " + SecretRedactor.redact(what.take(200)).replace('\n', ' ') + "\n")
        }
    }

    private fun trail(file: String): String = runCatching {
        File(dir, file).takeIf { it.exists() }?.readLines()?.takeLast(60)?.joinToString("\n")?.let { "--- What ZED was doing ---\n$it" }
    }.getOrNull().orEmpty()

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
                appendLine("$kind in ${worst.processName}: ${worst.description ?: ""} (reason ${worst.reason}, status ${worst.status})")
                appendLine("Memory: ${worst.pss / 1024} MB used, importance ${worst.importance}")
                if (bad.size > 1) appendLine("Other recent stops: " + bad.filter { it !== worst }.joinToString { "${it.reason}@${SimpleDateFormat("HH:mm", Locale.US).format(Date(it.timestamp))}" })
                appendLine()
                append(trace.take(30_000))
            }
            // Everything known about it in one report: Android's record, the stack trace written when it happened,
            // what ZED was doing in that run, and the errors it had survived just before.
            val fatal = File(dir, FATAL_FILE)
            val stack = runCatching { fatal.takeIf { it.exists() && kotlin.math.abs(it.lastModified() - worst.timestamp) < 10 * 60_000L }?.readText() }.getOrNull()
            val errors = runCatching { File(dir, ERRORS_FILE).takeIf { it.exists() }?.readText()?.split("\n---\n")?.takeLast(8)?.joinToString("\n---\n") }.getOrNull()
            val full = buildString {
                append(text.trimEnd()); appendLine(); appendLine()
                if (stack != null) { appendLine("--- Stack trace ---"); appendLine(SecretRedactor.redact(stack).take(20_000)); appendLine() }
                else appendLine("(No stack trace: the app was stopped from outside, or before it could write one.)").also { appendLine() }
                appendLine(trail(CRUMBS_BEFORE)); appendLine()
                if (!errors.isNullOrBlank()) { appendLine("--- Recent errors ZED survived ---"); appendLine(errors.take(8_000)) }
            }
            runCatching { fatal.delete() }
            File(dir, CRASH_FILE).writeText(full)
            saveToDownloads(full)
        }
    }

    /** Also keeps a copy in Download/ZED, so a crash can be looked at without opening the app. */
    private fun saveToDownloads(text: String, prefix: String = "zed-crash-") {
        if (Build.VERSION.SDK_INT < 29 || !::app.isInitialized) return
        val name = prefix + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
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

    /**
     * Everything useful for tracking down a problem, in one text: the device, what ZED was doing in this
     * run and the one before, the last crash and the errors it survived. Also saved to Download/ZED.
     */
    fun problemReport(): String {
        val text = buildString {
            appendLine("ZED problem report")
            appendLine("Time: ${timestamp()}")
            appendLine("App: ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine(trail(CRUMBS).ifEmpty { "(nothing noted in this run yet)" }); appendLine()
            trail(CRUMBS_BEFORE).takeIf { it.isNotEmpty() }?.let { appendLine("Before the last restart:"); appendLine(it); appendLine() }
            lastCrash()?.let { appendLine("--- Last crash ---"); appendLine(it.take(20_000)); appendLine() }
            errors()?.let { appendLine("--- Recent errors ZED survived ---"); appendLine(it.takeLast(10_000)) }
        }
        runCatching { saveToDownloads(text, "zed-report-") }
        return text
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
