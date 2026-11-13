package com.melhive.zed.tools

import com.melhive.zed.AppContainer
import com.melhive.zed.database.KnowledgeDocEntity
import com.melhive.zed.database.MemoryFactEntity
import com.melhive.zed.database.ReminderEntity
import com.melhive.zed.database.TodoEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ZED acts immediately, so every delete can be taken back: "undo" (or the Undo bar) restores the
 * most recent one within [WINDOW_MS]. Kept in memory only; when an entry expires its
 * [Entry.expire] runs (deleted files leave the trash for good).
 */
class UndoLog(private val scope: CoroutineScope, private val c: android.content.Context) {
    data class Entry(val id: Long, val label: String, val at: Long, val restore: suspend () -> String, val expire: suspend () -> Unit)

    private val mutex = Mutex()
    private val entries = ArrayDeque<Entry>()
    private var next = 1L
    private val _latest = MutableStateFlow<Entry?>(null)
    /** The newest thing that can be undone, for the Undo bar. */
    val latest: StateFlow<Entry?> = _latest.asStateFlow()

    fun record(label: String, expire: suspend () -> Unit = {}, restore: suspend () -> String) {
        scope.launch {
            mutex.withLock {
                prune()
                val e = Entry(next++, label, System.currentTimeMillis(), restore, expire)
                entries.addLast(e)
                runCatching { com.melhive.zed.assist.ZedWidgetProvider.refresh(c) }
                while (entries.size > MAX) runCatching { entries.removeFirst().expire() }
                _latest.value = e
            }
        }
    }

    /** Restores the most recent delete; null when there is nothing to undo. */
    suspend fun undo(): String? = mutex.withLock {
        prune()
        val e = entries.removeLastOrNull() ?: return@withLock null
        _latest.value = entries.lastOrNull()?.takeIf { System.currentTimeMillis() - it.at < BAR_MS }
        runCatching { e.restore() }.getOrElse { "I couldn't bring back ${e.label}: ${it.message}" }
            .also { runCatching { com.melhive.zed.assist.ZedWidgetProvider.refresh(c) } }
    }

    /** Hides the Undo bar without forgetting what can still be undone by voice. */
    fun dismissBar() { _latest.value = null }

    private suspend fun prune() {
        val now = System.currentTimeMillis()
        while (entries.isNotEmpty() && now - entries.first().at > WINDOW_MS) runCatching { entries.removeFirst().expire() }
    }

    companion object {
        const val WINDOW_MS = 30 * 60_000L
        const val BAR_MS = 8_000L
        private const val MAX = 20
    }
}

/**
 * Every way ZED deletes things (by voice, chat or the Memory screen) goes through here,
 * so each one is recorded for Undo.
 */
class Deleter(private val c: AppContainer) {
    private fun plural(n: Int, one: String, many: String = one + "s") = if (n == 1) "1 $one" else "$n $many"

    suspend fun todos(items: List<TodoEntity>) {
        if (items.isEmpty()) return
        items.forEach { c.todos.delete(it.id) }
        c.undo.record(if (items.size == 1) "“${items[0].title}”" else plural(items.size, "task")) {
            items.forEach { runCatching { c.db.todos().insert(it) } }
            if (items.size == 1) "Brought back “${items[0].title}”." else "Brought back ${plural(items.size, "task")}."
        }
    }

    suspend fun reminders(items: List<ReminderEntity>) {
        if (items.isEmpty()) return
        items.forEach { c.reminderScheduler.deleteReminder(it.id) }
        c.undo.record(if (items.size == 1) "the reminder “${items[0].text}”" else plural(items.size, "reminder")) {
            val now = System.currentTimeMillis()
            for (r in items) runCatching {
                c.db.reminders().insert(r)
                if (r.status == "scheduled" && r.triggerAt > now) c.reminderScheduler.schedule(r)
            }
            if (items.size == 1) "Brought back the reminder “${items[0].text}”." else "Brought back ${plural(items.size, "reminder")}."
        }
    }

    /** A cancelled reminder can be switched back on. */
    fun reminderCancelled(r: ReminderEntity) {
        c.undo.record("the reminder “${r.text}”") {
            if (r.triggerAt <= System.currentTimeMillis()) return@record "That reminder's time has already passed."
            c.db.reminders().setStatus(r.id, "scheduled")
            c.reminderScheduler.schedule(r)
            "Reminder “${r.text}” is back on."
        }
    }

    suspend fun facts(items: List<MemoryFactEntity>, label: String? = null) {
        if (items.isEmpty()) return
        c.memory.delete(items.map { it.id })
        c.undo.record(label ?: if (items.size == 1) "“${items[0].text.take(60)}”" else plural(items.size, "memory", "memories")) {
            items.forEach { runCatching { c.db.memory().insert(it) } }
            if (items.size == 1) "Brought back “${items[0].text.take(80)}”." else "Brought back ${plural(items.size, "memory", "memories")}."
        }
    }

    suspend fun docs(items: List<KnowledgeDocEntity>) {
        if (items.isEmpty()) return
        val dao = c.db.knowledge()
        val saved = items.map { it to dao.chunks(it.id).map { ch -> ch.text } }
        items.forEach { c.knowledge.deleteDoc(it.id) }
        c.undo.record(if (items.size == 1) "“${items[0].path}” in ZED's knowledge" else plural(items.size, "document")) {
            saved.forEach { (doc, texts) -> runCatching { dao.replaceDoc(null, doc.copy(id = 0), texts) } }
            if (items.size == 1) "“${items[0].path}” is back in ZED's knowledge." else "Brought back ${plural(items.size, "document")}."
        }
    }

    /** Deletes one calendar occurrence; false when the calendar refused. */
    fun calendar(item: com.melhive.zed.agents.CalendarReader.Item, title: String): Boolean {
        val restore = com.melhive.zed.agents.CalendarWriter(c.context).deleteUndoable(item) ?: return false
        c.undo.record("“$title” in your calendar") { if (restore()) "“$title” is back in your calendar." else "Your calendar didn't let me bring back “$title”." }
        return true
    }

    /** Moves a file to the trash; returns false if trashing isn't possible (then it is deleted for good). */
    suspend fun file(path: String): Boolean {
        val (trashed, parent) = runCatching { c.files.trash(path) }.getOrNull() ?: run { c.files.delete(path); return false }
        c.undo.record("“$path”", expire = { runCatching { c.files.delete(trashed.substringBeforeLast('/')) } }) {
            c.files.move(trashed, parent)
            runCatching { c.files.delete(trashed.substringBeforeLast('/')) }
            "Brought back “$path”."
        }
        return true
    }

    /** Conversations with all their messages. */
    suspend fun conversations(items: List<com.melhive.zed.database.ConversationEntity>) {
        if (items.isEmpty()) return
        val saved = items.map { it to c.db.messages().observe(it.id).first() }
        items.forEach { c.conversations.delete(it.id) }
        c.undo.record(if (items.size == 1) "the conversation “${items[0].title}”" else plural(items.size, "conversation")) {
            for ((conv, msgs) in saved) runCatching {
                c.db.conversations().insert(conv)
                msgs.forEach { m -> runCatching { c.db.messages().insert(m) } }
            }
            if (items.size == 1) "Brought back “${items[0].title}”." else "Brought back ${plural(items.size, "conversation")}."
        }
    }

    /** Lessons: answering rules, pronunciations and shortcuts. */
    suspend fun lessons(rules: List<MemoryFactEntity>, pronunciations: Map<String, String>, aliases: Map<String, String>) {
        c.memory.delete(rules.map { it.id })
        pronunciations.keys.forEach { c.teaching.forgetPronunciation(it) }
        aliases.keys.forEach { c.teaching.forgetAlias(it) }
        val n = rules.size + pronunciations.size + aliases.size
        if (n == 0) return
        c.undo.record(if (n == 1) "that lesson" else "$n lessons") {
            rules.forEach { runCatching { c.db.memory().insert(it) } }
            pronunciations.forEach { (w, s) -> c.teaching.pronounce(w, s) }
            aliases.forEach { (p, m) -> c.teaching.alias(p, m) }
            if (n == 1) "I remember that lesson again." else "I remember those $n lessons again."
        }
    }
}
