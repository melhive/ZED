package com.melhive.zed.learning

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.melhive.zed.AppContainer
import com.melhive.zed.android.CrashReporter
import com.melhive.zed.core.voice.HearingFix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * How ZED hears the user's own words. Speech recognition only writes words it was trained on, so what it
 * heard is corrected with:
 *  - what the user fixed before ("kay lin" → "Kaelyn"),
 *  - words the user taught ("learn the word XAUUSD"),
 *  - a vocabulary ZED gathers by itself: contacts' names, names and symbols in memories, typed chats,
 *    document names and people it has met.
 * Stored on this phone only.
 */
class HearingStore(private val context: Context, private val c: AppContainer) {
    private val file = File(context.filesDir, "hearing.json")
    private val learned = LinkedHashMap<String, String>()
    private val words = LinkedHashSet<String>()
    @Volatile private var gathered: List<String> = emptyList()
    @Volatile private var gatheredAt = 0L
    /** Changes whenever something is learned or forgotten. */
    val version = MutableStateFlow(0)

    /** A word just taught by voice: the next thing heard is how the recognizer writes it. */
    @Volatile private var awaiting: String? = null
    @Volatile private var awaitingUntil = 0L

    init {
        runCatching {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                o.optJSONObject("learned")?.let { l -> l.keys().forEach { k -> learned[k] = l.getString(k) } }
                o.optJSONArray("words")?.let { a -> for (i in 0 until a.length()) words += a.getString(i) }
            }
        }.onFailure { CrashReporter.recordNonFatal("hearing load", it) }
        c.appScope.launch { refresh() }
    }

    @Synchronized private fun save() {
        runCatching {
            file.writeText(JSONObject().put("learned", JSONObject(learned as Map<*, *>)).put("words", JSONArray(words.toList())).toString())
        }.onFailure { CrashReporter.recordNonFatal("hearing save", it) }
        version.value++
    }

    @Synchronized fun learnedPairs(): Map<String, String> = LinkedHashMap(learned)
    @Synchronized fun taughtWords(): List<String> = words.toList()

    /** Everything ZED may correct a heard word into: taught words first, then what it gathered. */
    fun vocabulary(): List<String> = synchronized(this) { words.toList() } + gathered

    /** What was heard, put right. Quick: the vocabulary is kept in memory and refreshed in the background. */
    fun fix(heard: String): String {
        if (System.currentTimeMillis() - gatheredAt > 10 * 60_000L) c.appScope.launch { refresh() }
        return runCatching { HearingFix.apply(heard, learnedPairs(), vocabulary()) }.getOrDefault(heard)
    }

    /** The user corrected what was heard: remember the pair. */
    @Synchronized fun learn(heard: String, meant: String) {
        val h = heard.trim().lowercase(); val m = meant.trim()
        if (h.isEmpty() || m.isEmpty() || h == m.lowercase() && m == m.lowercase()) return
        learned.remove(h); learned[h] = m
        while (learned.size > 400) learned.remove(learned.keys.first())
        HearingFix.vocabulary(m).forEach { words += it }
        save()
    }

    @Synchronized fun addWord(word: String) {
        val w = word.trim()
        if (w.length < 2) return
        words.removeAll { it.equals(w, ignoreCase = true) }
        words += w
        while (words.size > 600) words.remove(words.first())
        save()
    }

    fun addWords(list: Collection<String>) { if (list.isNotEmpty()) { synchronized(this) { list.forEach { w -> if (w.length >= 3 && words.none { it.equals(w, true) }) words += w }; while (words.size > 600) words.remove(words.first()) }; save() } }

    /** Forgets a taught word and every correction that leads to it. True when something was removed. */
    @Synchronized fun forget(word: String): Boolean {
        val w = word.trim()
        val a = words.removeAll { it.equals(w, ignoreCase = true) }
        val keys = learned.filter { it.value.equals(w, ignoreCase = true) || it.key.equals(w, ignoreCase = true) }.keys.toList()
        keys.forEach { learned.remove(it) }
        if (a || keys.isNotEmpty()) save()
        return a || keys.isNotEmpty()
    }

    @Synchronized fun clear() { learned.clear(); words.clear(); save() }

    /** "Learn the word X", said in a call: the next thing heard is taken as how X sounds. */
    fun awaitSaid(word: String) { awaiting = word; awaitingUntil = System.currentTimeMillis() + 40_000 }

    /** If a word is waiting to be heard, [raw] is how the recognizer writes it. Returns ZED's reply, or null. */
    fun heardWord(raw: String): String? {
        val word = awaiting ?: return null
        awaiting = null
        if (System.currentTimeMillis() > awaitingUntil) return null
        val said = raw.trim().trimEnd('.', '?', '!')
        if (said.isEmpty() || said.split(' ').size > 8) return null
        if (!said.equals(word, ignoreCase = true)) learn(said, word)
        return "Got it. When I hear “$said”, I'll take it as $word."
    }

    /** Gathers the user's own words from everything ZED knows. Runs in the background. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        runCatching {
            val out = LinkedHashSet<String>()
            // People ZED has met and the user's contacts: whole names and each part of them.
            c.people.names().forEach { out += it.second }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                context.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null)?.use { cur ->
                    var n = 0
                    while (cur.moveToNext() && n++ < 1500) cur.getString(0)?.split(Regex("[^\\p{L}'-]+"))?.filter { it.length >= 3 && it.first().isUpperCase() }?.forEach { out += it }
                }
            }
            // Names and symbols in what the user told ZED, what it found for them, and what they typed.
            c.memory.list(1000).forEach { out += HearingFix.vocabulary(it.text) }
            c.db.messages().userMessagesAfter(0, 400).filter { !it.content.startsWith("Search the web for") }.forEach { out += HearingFix.vocabulary(it.content.take(600)) }
            c.knowledge.documents.first().forEach { d -> out += HearingFix.vocabulary(d.name.substringBeforeLast('.').replace('_', ' ').replace('-', ' ')) }
            c.todos.list(true).forEach { out += HearingFix.vocabulary(it.title) }
            gathered = out.filter { it.length in 3..30 }.take(2500)
            gatheredAt = System.currentTimeMillis()
        }.onFailure { CrashReporter.recordNonFatal("hearing vocabulary", it) }
    }
}
