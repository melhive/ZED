package com.melhive.zed.security

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stores API keys and tokens encrypted with [KeystoreCipher]. Plaintext never
 * touches disk, logs or the database. Values are only decrypted when a request
 * is about to be sent.
 */
class SecretStore(context: Context, private val cipher: KeystoreCipher = KeystoreCipher()) {
    private val prefs: SharedPreferences = context.getSharedPreferences("zed_secrets", Context.MODE_PRIVATE)
    private val _configured = MutableStateFlow(configuredKeys())
    /** Names of secrets that are set (never the values). */
    val configured: StateFlow<Set<String>> = _configured.asStateFlow()

    fun put(name: String, value: String) {
        val v = value.trim()
        if (v.isEmpty()) { remove(name); return }
        prefs.edit().putString(name, cipher.encrypt(v)).apply()
        _configured.value = configuredKeys()
    }

    fun get(name: String): String? {
        val enc = prefs.getString(name, null) ?: return null
        return try { cipher.decrypt(enc) } catch (e: Exception) {
            // Key was invalidated (e.g. after a restore); the secret is unrecoverable.
            remove(name); null
        }
    }

    fun has(name: String): Boolean = prefs.contains(name)

    fun remove(name: String) {
        prefs.edit().remove(name).apply()
        _configured.value = configuredKeys()
    }

    private fun configuredKeys(): Set<String> = prefs.all.keys.toSet()

    /** ZED is Gemma-only: AI keys saved by older versions are deleted from the phone. */
    fun removeLegacyAiKeys() {
        val old = prefs.all.keys.filter { it.startsWith(LEGACY_AI_KEY_PREFIX) }
        if (old.isEmpty()) return
        prefs.edit().apply { old.forEach { remove(it) } }.apply()
        _configured.value = configuredKeys()
    }

    companion object {
        const val HOME_ASSISTANT_TOKEN = "home_assistant_token"
        /** Names AI-provider keys were stored under before ZED became Gemma-only (removed at start). */
        const val LEGACY_AI_KEY_PREFIX = "api_key_"
        const val HF_TOKEN = "huggingface_token"
    }
}
