package com.melhive.zed.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.CancellationSignal
import android.util.Base64
import com.melhive.zed.android.CrashReporter
import java.io.File

/**
 * Private Connections: notes that live apart from everything else ZED knows.
 * They are kept in one file encrypted with a key in the Android Keystore and
 * are only read after the owner has unlocked them with a fingerprint or the
 * phone's PIN. They are never in ZED's memory, search, answers or what the AI
 * model sees, and they are not part of backups (the key cannot leave this phone).
 */
class PrivateStore(context: Context) {
    data class Note(val id: Long, val text: String, val createdAt: Long)

    private val file = File(context.filesDir, "private-connections.bin")
    private val prefs = context.getSharedPreferences("zed_private", Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher("zed_private_v1")

    /** How many notes there are (shown on the locked group in the Live Brain; says nothing about what they are). */
    val count: Int get() = prefs.getInt("count", 0)

    /** Reads the notes. Call only after [DeviceAuth.confirm] succeeded. */
    fun load(): List<Note> {
        if (!file.exists()) return emptyList()
        return runCatching {
            cipher.decrypt(file.readText()).lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3) null else Note(parts[0].toLong(), String(Base64.decode(parts[2], Base64.NO_WRAP), Charsets.UTF_8), parts[1].toLong())
            }.toList()
        }.onFailure { CrashReporter.recordNonFatal("private notes", it) }.getOrDefault(emptyList())
    }

    fun save(notes: List<Note>) {
        runCatching {
            val plain = notes.joinToString("\n") { "${it.id}\t${it.createdAt}\t" + Base64.encodeToString(it.text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(cipher.encrypt(plain))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            prefs.edit().putInt("count", notes.size).apply()
        }.onFailure { CrashReporter.recordNonFatal("private notes save", it) }
    }
}

/** Asks the phone's owner to prove it is them: fingerprint (or face) or the phone's PIN, pattern or password. */
object DeviceAuth {
    /** False when the phone has no screen lock at all: then nothing can be protected. */
    fun available(context: Context): Boolean = runCatching { context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true }.getOrDefault(false)

    fun confirm(activity: Activity, title: String, subtitle: String, onResult: (Boolean) -> Unit) {
        if (!available(activity) || Build.VERSION.SDK_INT < 28) { onResult(false); return }
        runCatching {
            val builder = android.hardware.biometrics.BiometricPrompt.Builder(activity).setTitle(title).setSubtitle(subtitle)
            if (Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(
                android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            else @Suppress("DEPRECATION") builder.setDeviceCredentialAllowed(true)
            builder.build().authenticate(CancellationSignal(), activity.mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) { onResult(true) }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { onResult(false) }
            })
        }.onFailure { CrashReporter.recordNonFatal("device auth", it); onResult(false) }
    }
}
