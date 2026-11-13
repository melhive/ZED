package com.melhive.zed.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latest(): ConversationEntity?

    @Insert suspend fun insert(c: ConversationEntity): Long
    @Update suspend fun update(c: ConversationEntity)

    @Query("UPDATE conversations SET updatedAt = :ts WHERE id = :id")
    suspend fun touch(id: Long, ts: Long)

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM conversations")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun count(): Int
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id ASC")
    fun observe(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id DESC LIMIT :limit")
    suspend fun recent(conversationId: Long, limit: Int): List<MessageEntity>

    @Insert suspend fun insert(m: MessageEntity): Long

    @Query("UPDATE messages SET content = :content, status = :status WHERE id = :id")
    suspend fun updateContent(id: Long, content: String, status: String)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND role = 'user' ORDER BY id DESC LIMIT 1")
    suspend fun lastUser(conversationId: Long): MessageEntity?

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND id >= :fromId")
    suspend fun deleteFrom(conversationId: Long, fromId: Long)

    @Query("UPDATE messages SET firstTokenMs = :first, totalMs = :total, outputChars = :chars WHERE id = :id")
    suspend fun setTiming(id: Long, first: Long?, total: Long, chars: Int)

    /** Replies that didn't come from a model (instant native answers) only have a total time. */
    @Query("UPDATE messages SET totalMs = :total WHERE totalMs IS NULL AND id = (SELECT id FROM messages WHERE conversationId = :conversationId AND role = 'assistant' ORDER BY id DESC LIMIT 1)")
    suspend fun setTotalOnLastReply(conversationId: Long, total: Long)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id ASC")
    suspend fun all(conversationId: Long): List<MessageEntity>

    /** What the user said since [since] (epoch millis), oldest first (insights read these). */
    @Query("SELECT * FROM messages WHERE role = 'user' AND createdAt > :since ORDER BY id ASC LIMIT :limit")
    suspend fun userMessagesSince(since: Long, limit: Int = 600): List<MessageEntity>

    /** What the user said after message [after], oldest first (automatic learning reads these). */
    @Query("SELECT * FROM messages WHERE role = 'user' AND id > :after ORDER BY id ASC LIMIT :limit")
    suspend fun userMessagesAfter(after: Long, limit: Int): List<MessageEntity>

    @Query("UPDATE messages SET status = 'cancelled' WHERE status = 'streaming'")
    suspend fun markInterruptedStreams()

    @Query(
        """SELECT m.* FROM messages m WHERE m.role IN ('user','assistant') AND m.content LIKE '%' || :query || '%' ESCAPE '\'
           ORDER BY m.createdAt DESC LIMIT :limit"""
    )
    suspend fun search(query: String, limit: Int = 20): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory_facts ORDER BY pinned DESC, createdAt DESC")
    fun observeAll(): Flow<List<MemoryFactEntity>>

    @Query("SELECT * FROM memory_facts ORDER BY pinned DESC, createdAt DESC LIMIT :limit")
    suspend fun list(limit: Int = 100): List<MemoryFactEntity>

    @Query("SELECT * FROM memory_facts WHERE text LIKE '%' || :query || '%' ESCAPE '\\'")
    suspend fun find(query: String): List<MemoryFactEntity>

    @Insert suspend fun insert(f: MemoryFactEntity): Long
    @Update suspend fun update(f: MemoryFactEntity)

    @Query("DELETE FROM memory_facts WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("UPDATE memory_facts SET uses = uses + 1, lastUsedAt = :now WHERE id IN (:ids)")
    suspend fun markUsed(ids: List<Long>, now: Long)

    @Query("DELETE FROM memory_facts")
    suspend fun clear()
}

@Dao
interface TodoDao {
    @Query("SELECT * FROM todos ORDER BY done ASC, CASE WHEN dueAt IS NULL THEN 1 ELSE 0 END, dueAt ASC, createdAt DESC")
    fun observeAll(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE done = 0 OR :includeDone ORDER BY done ASC, createdAt DESC")
    suspend fun list(includeDone: Boolean): List<TodoEntity>

    @Query("SELECT * FROM todos WHERE id = :id")
    suspend fun get(id: Long): TodoEntity?

    @Query("SELECT * FROM todos WHERE done = 0 AND title LIKE '%' || :title || '%' ESCAPE '\\' ORDER BY createdAt DESC")
    suspend fun findOpenByTitle(title: String): List<TodoEntity>

    @Insert suspend fun insert(t: TodoEntity): Long
    @Update suspend fun update(t: TodoEntity)

    @Query("DELETE FROM todos WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY triggerAt ASC")
    fun observeAll(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE status = 'scheduled' ORDER BY triggerAt ASC")
    suspend fun scheduled(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun get(id: Long): ReminderEntity?

    @Insert suspend fun insert(r: ReminderEntity): Long

    @Query("UPDATE reminders SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("SELECT COUNT(*) FROM reminders")
    suspend fun count(): Int

    @Query("UPDATE reminders SET exact = :exact WHERE id = :id")
    suspend fun setExact(id: Long, exact: Boolean)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY id DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observe(id: Long): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: Long): TaskEntity?

    @Query("SELECT * FROM tasks WHERE status NOT IN ('SUCCEEDED','FAILED','CANCELLED')")
    suspend fun active(): List<TaskEntity>

    @Query("SELECT * FROM tasks")
    suspend fun all(): List<TaskEntity>

    @Insert suspend fun insert(t: TaskEntity): Long
    @Update suspend fun update(t: TaskEntity)

    @Query("UPDATE tasks SET progressCurrent = :current, progressTotal = :total, progressLabel = :label WHERE id = :id")
    suspend fun setProgress(id: Long, current: Long?, total: Long?, label: String?)

    @Query("UPDATE tasks SET workId = :workId WHERE id = :id")
    suspend fun setWorkId(id: Long, workId: String)

    @Query("DELETE FROM tasks WHERE status IN ('SUCCEEDED','FAILED','CANCELLED')")
    suspend fun clearFinished()

    @Insert suspend fun insertLog(l: TaskLogEntity): Long

    @Query("SELECT * FROM task_logs WHERE taskId = :taskId ORDER BY id ASC")
    fun observeLogs(taskId: Long): Flow<List<TaskLogEntity>>
}

@Dao
interface ModelDao {
    @Query("SELECT * FROM installed_models ORDER BY installedAt DESC")
    fun observeAll(): Flow<List<InstalledModelEntity>>

    @Query("SELECT * FROM installed_models WHERE id = :id")
    suspend fun get(id: String): InstalledModelEntity?

    @Query("SELECT * FROM installed_models ORDER BY installedAt DESC")
    suspend fun all(): List<InstalledModelEntity>

    @Upsert suspend fun upsert(m: InstalledModelEntity)

    @Query("DELETE FROM installed_models WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM granted_folders ORDER BY grantedAt ASC")
    fun observeAll(): Flow<List<GrantedFolderEntity>>

    @Query("SELECT * FROM granted_folders ORDER BY grantedAt ASC")
    suspend fun all(): List<GrantedFolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(f: GrantedFolderEntity)

    @Query("DELETE FROM granted_folders WHERE uri = :uri")
    suspend fun delete(uri: String)
}

@Dao
interface KnowledgeDao {
    @Query("SELECT * FROM knowledge_docs ORDER BY indexedAt DESC")
    fun observeDocs(): Flow<List<KnowledgeDocEntity>>

    @Query("SELECT * FROM knowledge_docs WHERE treeUri = :treeUri AND path = :path")
    suspend fun findDoc(treeUri: String, path: String): KnowledgeDocEntity?

    @Query("SELECT * FROM knowledge_docs WHERE id IN (:ids)")
    suspend fun docs(ids: List<Long>): List<KnowledgeDocEntity>

    @Insert suspend fun insertDoc(d: KnowledgeDocEntity): Long
    @Insert suspend fun insertChunks(chunks: List<KnowledgeChunkEntity>)

    @Query("SELECT * FROM knowledge_chunks WHERE docId = :docId ORDER BY ordinal")
    suspend fun chunks(docId: Long): List<KnowledgeChunkEntity>

    @Query("DELETE FROM knowledge_docs WHERE id = :id")
    suspend fun deleteDoc(id: Long)

    @Query("DELETE FROM knowledge_docs")
    suspend fun clear()

    @Transaction
    suspend fun replaceDoc(existingId: Long?, doc: KnowledgeDocEntity, texts: List<String>) {
        if (existingId != null) deleteDoc(existingId)
        val id = insertDoc(doc)
        insertChunks(texts.mapIndexed { i, t -> KnowledgeChunkEntity(docId = id, ordinal = i, text = t) })
    }

    @Query(
        """SELECT knowledge_chunks.* FROM knowledge_chunks
           JOIN knowledge_chunks_fts ON knowledge_chunks.rowid = knowledge_chunks_fts.rowid
           WHERE knowledge_chunks_fts MATCH :match LIMIT :limit"""
    )
    suspend fun searchChunks(match: String, limit: Int = 50): List<KnowledgeChunkEntity>

    @Query("SELECT COUNT(*) FROM knowledge_docs")
    suspend fun docCount(): Int

    @Query("SELECT COUNT(*) FROM knowledge_chunks")
    suspend fun chunkCount(): Int
}

@Dao
interface AuditDao {
    @Insert suspend fun insert(a: AuditLogEntity): Long

    @Query("SELECT * FROM audit_log WHERE timestamp BETWEEN :from AND :to ORDER BY id ASC")
    suspend fun between(from: Long, to: Long): List<AuditLogEntity>

    @Query("SELECT * FROM audit_log ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<AuditLogEntity>>

    @Query("DELETE FROM audit_log WHERE timestamp < :before")
    suspend fun prune(before: Long)
}

@Dao
interface PendingRequestDao {
    @Insert suspend fun insert(r: PendingRequestEntity): Long

    @Query("SELECT * FROM pending_requests WHERE status = 'waiting' ORDER BY id ASC")
    suspend fun waiting(): List<PendingRequestEntity>

    @Query("SELECT COUNT(*) FROM pending_requests WHERE status = 'waiting'")
    fun observeWaiting(): Flow<Int>

    @Query("UPDATE pending_requests SET status = :status, result = :result WHERE id = :id")
    suspend fun finish(id: Long, status: String, result: String?)
}

@Dao
interface RelationDao {
    @Query("SELECT * FROM relations ORDER BY id DESC LIMIT 200")
    fun observeAll(): Flow<List<RelationEntity>>

    @Query("SELECT * FROM relations")
    suspend fun all(): List<RelationEntity>

    @Insert suspend fun insert(r: RelationEntity): Long

    /** Relationships read from a memory that has since been forgotten. */
    @Query("DELETE FROM relations WHERE factId IS NOT NULL AND factId NOT IN (SELECT id FROM memory_facts)")
    suspend fun pruneOrphans()
}
