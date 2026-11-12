package com.melhive.zed.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    /** user | assistant | tool | system */
    val role: String,
    val content: String,
    val createdAt: Long,
    /** complete | streaming | error | cancelled */
    val status: String = "complete",
    /** Which intelligence layer produced it: native | local:<model> | online:<provider>/<model> */
    val source: String? = null,
    val toolName: String? = null,
    val attachmentName: String? = null,
    /** Milliseconds from the request to the first word of this reply (model replies only). */
    val firstTokenMs: Long? = null,
    /** Milliseconds from the request to the finished reply. */
    val totalMs: Long? = null,
    /** Length of the generated text, for words-per-second. */
    val outputChars: Int? = null,
)

@Entity(tableName = "memory_facts")
data class MemoryFactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAt: Long,
    /** user_command | model_request | manual | learned */
    val source: String,
    val pinned: Boolean = false,
    /** How many times ZED has used this memory to answer. */
    @ColumnInfo(defaultValue = "0") val uses: Int = 0,
    /** When ZED last used it (0 = never). */
    @ColumnInfo(defaultValue = "0") val lastUsedAt: Long = 0,
    /** 1.0 = the user said it; lower = ZED inferred it. */
    @ColumnInfo(defaultValue = "1.0") val confidence: Float = 1f,
)

/** A labelled link between two things ZED knows ("Ana" —sister of→ "me"). */
@Entity(tableName = "relations")
data class RelationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subject: String,
    val relation: String,
    val target: String,
    /** The memory this was read from, if any. */
    val factId: Long? = null,
    val createdAt: Long,
    /** learned | manual */
    val source: String,
)

/** A request that needs the internet, kept until the phone is back online. */
@Entity(tableName = "pending_requests")
data class PendingRequestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val conversationId: Long? = null,
    val createdAt: Long,
    /** waiting | done | failed | cancelled */
    val status: String,
    val result: String? = null,
)

@Entity(tableName = "todos")
data class TodoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val dueAt: Long? = null,
    val done: Boolean = false,
    val createdAt: Long,
    val completedAt: Long? = null,
)

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val triggerAt: Long,
    /** scheduled | fired | cancelled */
    val status: String = "scheduled",
    val createdAt: Long,
    val exact: Boolean = false,
)

@Entity(tableName = "tasks", indices = [Index("status")])
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** organize | index | model_download | workflow */
    val type: String,
    val title: String,
    val status: String,
    val inputJson: String,
    val planJson: String? = null,
    val planSummary: String? = null,
    val resultSummary: String? = null,
    val error: String? = null,
    /** Actual processed item count; null when unknown. Never estimated. */
    val progressCurrent: Long? = null,
    val progressTotal: Long? = null,
    val progressLabel: String? = null,
    val createdAt: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val attempt: Int = 1,
    /** Comma separated task ids this task depends on. */
    val dependsOn: String = "",
    /** Comma separated resource keys used for conflict detection. */
    val resources: String = "",
    val workId: String? = null,
)

@Entity(
    tableName = "task_logs",
    foreignKeys = [ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("taskId")],
)
data class TaskLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val timestamp: Long,
    /** info | warn | error */
    val level: String,
    val message: String,
)

@Entity(tableName = "installed_models")
data class InstalledModelEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String?,
    /** catalog | imported */
    val source: String,
    val installedAt: Long,
    val runtime: String = "litert-lm",
    val verified: Boolean = false,
)

@Entity(tableName = "granted_folders")
data class GrantedFolderEntity(
    @PrimaryKey val uri: String,
    val displayName: String,
    val grantedAt: Long,
)

@Entity(tableName = "knowledge_docs", indices = [Index(value = ["treeUri", "path"], unique = true)])
data class KnowledgeDocEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val treeUri: String,
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val indexedAt: Long,
    val chunkCount: Int,
)

@Entity(
    tableName = "knowledge_chunks",
    foreignKeys = [ForeignKey(entity = KnowledgeDocEntity::class, parentColumns = ["id"], childColumns = ["docId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("docId")],
)
data class KnowledgeChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val docId: Long,
    val ordinal: Int,
    val text: String,
)

@Fts4(contentEntity = KnowledgeChunkEntity::class)
@Entity(tableName = "knowledge_chunks_fts")
data class KnowledgeChunkFts(val text: String)

@Entity(tableName = "audit_log", indices = [Index("timestamp")])
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val tool: String,
    val origin: String,
    val argsSummary: String,
    /** executed | denied | invalid | failed | cancelled */
    val outcome: String,
    val detail: String,
)
