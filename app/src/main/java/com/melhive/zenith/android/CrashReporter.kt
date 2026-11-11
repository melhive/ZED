package com.melhive.zenith.android

import android.content.Context
import android.os.Build
import com.melhive.zenith.BuildConfig
import com.melhive.zenith.core.security.SecretRedactor
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

    fun install(context: Context) {
        dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { File(dir, CRASH_FILE).writeText(report("Crash on thread ${thread.name}", error)) }
            previous?.uncaughtException(thread, error)
        }
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
