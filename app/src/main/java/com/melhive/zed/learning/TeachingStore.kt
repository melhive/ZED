package com.melhive.zed.learning

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * What the user taught ZED that isn't a memory: how to pronounce words and their own
 * shortcuts. (Rules about how to answer are memories with source "preference", so they
 * reach the model with everything else ZED knows.) Stored on this device only.
 */
class TeachingStore(context: Context, private val settings: com.melhive.zed.settings.SettingsRepository? = null, private val scope: kotlinx.coroutines.CoroutineScope? = null) {
    data class State(val pronunciations: Map<String, String> = emptyMap(), val aliases: Map<String, String> = emptyMap())

    private val prefs = context.getSharedPreferences("zed_teaching", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        spoken = _state.value.pronunciations
        // After a restore from backup, lessons come back through settings.
        if (settings != null && scope != null) scope.launch {
            runCatching {
                val s = settings.current()
                if (_state.value.pronunciations.isEmpty() && _state.value.aliases.isEmpty()) {
                    val restored = State(parse(s.taughtPronunciations), parse(s.taughtAliases))
                    if (restored.pronunciations.isNotEmpty() || restored.aliases.isNotEmpty()) edit { restored }
                } else if (s.taughtPronunciations == "{}" && s.taughtAliases == "{}") {
                    // Lessons from before backups included them: copy them over once.
                    settings.setTaught(JSONObject(_state.value.pronunciations as Map<*, *>).toString(), JSONObject(_state.value.aliases as Map<*, *>).toString())
                }
            }
        }
    }

    private fun parse(json: String): Map<String, String> = runCatching { val o = JSONObject(json); o.keys().asSequence().associateWith { o.getString(it) } }.getOrDefault(emptyMap())

    val pronunciations: Map<String, String> get() = _state.value.pronunciations
    val aliases: Map<String, String> get() = _state.value.aliases

    fun pronounce(word: String, say: String) = edit { it.copy(pronunciations = it.pronunciations.filterKeys { k -> !k.equals(word, true) } + (word.trim() to say.trim())) }
    fun alias(phrase: String, meaning: String) = edit { it.copy(aliases = it.aliases.filterKeys { k -> !k.equals(phrase, true) } + (phrase.trim() to meaning.trim())) }
    fun forgetPronunciation(word: String) = edit { it.copy(pronunciations = it.pronunciations.filterKeys { k -> !k.equals(word, true) }) }
    fun forgetAlias(phrase: String) = edit { it.copy(aliases = it.aliases.filterKeys { k -> !k.equals(phrase, true) }) }
    fun clear() = edit { State() }

    @Synchronized
    private fun edit(change: (State) -> State) {
        val next = change(_state.value)
        _state.value = next
        spoken = next.pronunciations
        val p = JSONObject(next.pronunciations as Map<*, *>).toString()
        val a = JSONObject(next.aliases as Map<*, *>).toString()
        prefs.edit().putString(KEY_PRONOUNCE, p).putString(KEY_ALIAS, a).apply()
        if (settings != null && scope != null) scope.launch { runCatching { settings.setTaught(p, a) } }
    }

    private fun load(): State = State(read(KEY_PRONOUNCE), read(KEY_ALIAS))

    private fun read(key: String): Map<String, String> = runCatching {
        val o = JSONObject(prefs.getString(key, "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    companion object {
        private const val KEY_PRONOUNCE = "pronunciations"
        private const val KEY_ALIAS = "aliases"

        /** The taught pronunciations, for the voice (read on every sentence, so kept in memory). */
        @Volatile var spoken: Map<String, String> = emptyMap()
            private set
    }
}
