package com.melhive.zed.memory

import com.melhive.zed.core.intelligence.ChatTurn
import com.melhive.zed.core.intelligence.Role
import com.melhive.zed.database.ConversationEntity
import com.melhive.zed.database.ZedDatabase
import com.melhive.zed.database.MemoryFactEntity
import com.melhive.zed.database.MessageEntity
import com.melhive.zed.database.TodoEntity
import kotlinx.coroutines.flow.Flow

/** Escapes LIKE wildcards so user text is matched literally. */
internal fun likeEscape(s: String): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

class ConversationRepository(private val db: ZedDatabase) {
    val conversations: Flow<List<ConversationEntity>> = db.conversations().observeAll()
    fun messages(conversationId: Long): Flow<List<MessageEntity>> = db.messages().observe(conversationId)

    suspend fun create(title: String = "New conversation"): Long {
        val now = System.currentTimeMillis()
        return db.conversations().insert(ConversationEntity(title = title, createdAt = now, updatedAt = now))
    }

    suspend fun latestOrCreate(): Long = db.conversations().latest()?.id ?: create()
    suspend fun get(id: Long) = db.conversations().get(id)
    suspend fun rename(id: Long, title: String) = db.conversations().rename(id, title.take(80))
    suspend fun delete(id: Long) = db.conversations().delete(id)

    suspend fun addMessage(conversationId: Long, role: String, content: String, status: String = "complete", source: String? = null, toolName: String? = null, attachmentName: String? = null): Long {
        val now = System.currentTimeMillis()
        val id = db.messages().insert(MessageEntity(conversationId = conversationId, role = role, content = content, createdAt = now, status = status, source = source, toolName = toolName, attachmentName = attachmentName))
        db.conversations().touch(conversationId, now)
        val conv = db.conversations().get(conversationId)
        if (role == "user" && conv != null && conv.title == "New conversation") {
            db.conversations().rename(conversationId, content.lineSequence().first().take(48).ifBlank { "Conversation" })
        }
        return id
    }

    suspend fun updateMessage(id: Long, content: String, status: String) = db.messages().updateContent(id, content, status)
    suspend fun setTiming(id: Long, firstTokenMs: Long?, totalMs: Long, chars: Int) = db.messages().setTiming(id, firstTokenMs, totalMs, chars)
    suspend fun setTotalOnLastReply(conversationId: Long, totalMs: Long) = db.messages().setTotalOnLastReply(conversationId, totalMs)
    suspend fun allMessages(conversationId: Long) = db.messages().all(conversationId)
    suspend fun deleteMessage(id: Long) = db.messages().delete(id)

    /**
     * Recent turns for model context. Tool results are included; failed/cancelled partials are skipped.
     * With [sessionGapMs], only the current sitting counts: turns before a pause longer than that
     * (yesterday's chat in the same thread) are left out, so ZED stays on what is being talked about now.
     */
    suspend fun history(conversationId: Long, limit: Int = 40, sessionGapMs: Long? = null): List<ChatTurn> =
        db.messages().recent(conversationId, limit)
            .let { newestFirst -> if (sessionGapMs == null) newestFirst else currentSitting(newestFirst, sessionGapMs) }
            .asReversed()
            .filter { it.status == "complete" && it.content.isNotBlank() }
            .map {
                ChatTurn(
                    when (it.role) { "user" -> Role.USER; "tool" -> Role.TOOL; "system" -> Role.SYSTEM; else -> Role.ASSISTANT },
                    it.content,
                )
            }

    private fun currentSitting(newestFirst: List<MessageEntity>, gap: Long): List<MessageEntity> {
        val out = ArrayList<MessageEntity>()
        var later = System.currentTimeMillis()
        for (m in newestFirst) {
            if (later - m.createdAt > gap) break
            out += m; later = m.createdAt
        }
        return out
    }

    suspend fun search(query: String): List<MessageEntity> = db.messages().search(likeEscape(query.trim()), 20)
    suspend fun recoverInterrupted() = db.messages().markInterruptedStreams()

    /** Removes the last user message and everything after it; returns that message's text. */
    suspend fun popLastExchange(conversationId: Long): String? {
        val last = db.messages().lastUser(conversationId) ?: return null
        db.messages().deleteFrom(conversationId, last.id)
        return last.content
    }
}

class MemoryRepository(private val db: ZedDatabase) {
    val facts: Flow<List<MemoryFactEntity>> = db.memory().observeAll()

    /** What just happened in memory (saved, recalled) — the Live Brain listens to this. */
    val events = kotlinx.coroutines.flow.MutableSharedFlow<com.melhive.zed.core.brain.BrainEvent>(extraBufferCapacity = 64)

    suspend fun remember(text: String, source: String): MemoryFactEntity {
        val existing = db.memory().find(likeEscape(text.trim())).firstOrNull { it.text.equals(text.trim(), ignoreCase = true) }
        if (existing != null) return existing
        val f = MemoryFactEntity(text = text.trim(), createdAt = System.currentTimeMillis(), source = source)
        return f.copy(id = db.memory().insert(f)).also { events.tryEmit(com.melhive.zed.core.brain.BrainEvent.Saved("FACT:${it.id}")) }
    }

    /**
     * Saves something ZED learned by itself. It is marked as learned and less
     * certain than what the user stated, and is skipped if already known.
     */
    suspend fun learn(text: String, confidence: Float): MemoryFactEntity? {
        val t = text.trim()
        if (db.memory().find(likeEscape(t.trimEnd('.'))).any { it.text.trim().trimEnd('.').equals(t.trimEnd('.'), ignoreCase = true) }) return null
        val f = MemoryFactEntity(text = t, createdAt = System.currentTimeMillis(), source = "learned", confidence = confidence.coerceIn(0f, 1f))
        return f.copy(id = db.memory().insert(f)).also { events.tryEmit(com.melhive.zed.core.brain.BrainEvent.Saved("FACT:${it.id}")) }
    }

    /** Records that ZED used these memories: they grow stronger and the Live Brain lights them up. */
    suspend fun markUsed(ids: List<Long>) {
        if (ids.isEmpty()) return
        db.memory().markUsed(ids, System.currentTimeMillis())
        events.tryEmit(com.melhive.zed.core.brain.BrainEvent.Recalled(ids.map { "FACT:$it" }))
    }

    /**
     * The memories that share a meaningful word with [text] ("Ana", "dentist"),
     * best match first. Used to light up what a question touches.
     */
    suspend fun related(text: String, limit: Int = 6): List<MemoryFactEntity> {
        val words = com.melhive.zed.core.brain.BrainRecall.keys(text)
        if (words.isEmpty()) return emptyList()
        return db.memory().list(300)
            .map { it to com.melhive.zed.core.brain.BrainRecall.keys(it.text).count { w -> w in words } }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    suspend fun list(limit: Int = 100) = db.memory().list(limit)
    suspend fun find(query: String) = db.memory().find(likeEscape(query.trim()))
    suspend fun delete(ids: List<Long>) = db.memory().delete(ids)
    suspend fun update(f: MemoryFactEntity) = db.memory().update(f)
    suspend fun clear() = db.memory().clear()

    /** Replaces the stored name ("My name is …"). */
    suspend fun setUserName(name: String) {
        val existing = db.memory().find("my name is").filter { Regex("^my name is ", RegexOption.IGNORE_CASE).containsMatchIn(it.text.trim()) }
        if (existing.isNotEmpty()) db.memory().delete(existing.map { it.id })
        val clean = name.trim().split(Regex("\\s+")).joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        if (clean.isNotEmpty()) remember("My name is $clean", "manual")
    }

    /** The user's name if they told ZED ("My name is …"). */
    suspend fun userName(): String? = db.memory().find("my name is").firstNotNullOfOrNull {
        Regex("^my name is (.+)$", RegexOption.IGNORE_CASE).find(it.text.trim())?.groupValues?.get(1)?.trim()?.trimEnd('.')
    }
}

class TodoRepository(private val db: ZedDatabase) {
    val todos: Flow<List<TodoEntity>> = db.todos().observeAll()

    suspend fun add(title: String, dueAt: Long?): TodoEntity {
        val t = TodoEntity(title = title.trim(), dueAt = dueAt, createdAt = System.currentTimeMillis())
        return t.copy(id = db.todos().insert(t))
    }

    suspend fun list(includeDone: Boolean) = db.todos().list(includeDone)

    sealed interface CompleteResult {
        data class Done(val item: TodoEntity) : CompleteResult
        data class Ambiguous(val matches: List<TodoEntity>) : CompleteResult
        data object NotFound : CompleteResult
    }

    suspend fun complete(itemRef: String): CompleteResult {
        val byId = itemRef.trim().removePrefix("#").toLongOrNull()?.let { db.todos().get(it) }
        val matches = if (byId != null && !byId.done) listOf(byId) else db.todos().findOpenByTitle(likeEscape(itemRef.trim()))
        val exact = matches.filter { it.title.equals(itemRef.trim(), ignoreCase = true) }
        val target = when {
            exact.size == 1 -> exact[0]
            matches.size == 1 -> matches[0]
            matches.isEmpty() -> return CompleteResult.NotFound
            else -> return CompleteResult.Ambiguous(matches)
        }
        val done = target.copy(done = true, completedAt = System.currentTimeMillis())
        db.todos().update(done)
        return CompleteResult.Done(done)
    }

    suspend fun setDone(t: TodoEntity, done: Boolean) = db.todos().update(t.copy(done = done, completedAt = if (done) System.currentTimeMillis() else null))
    suspend fun delete(id: Long) = db.todos().delete(id)
}
