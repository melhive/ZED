package com.melhive.zenith.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * Local database. Schema history is exported to app/schemas and every version
 * bump must ship a [Migration] in [MIGRATIONS]. Destructive fallback is never
 * enabled: user data must survive updates.
 */
@Database(
    entities = [
        ConversationEntity::class, MessageEntity::class, MemoryFactEntity::class, TodoEntity::class,
        ReminderEntity::class, TaskEntity::class, TaskLogEntity::class, InstalledModelEntity::class,
        GrantedFolderEntity::class, KnowledgeDocEntity::class, KnowledgeChunkEntity::class,
        KnowledgeChunkFts::class, AuditLogEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ZenithDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun memory(): MemoryDao
    abstract fun todos(): TodoDao
    abstract fun reminders(): ReminderDao
    abstract fun tasks(): TaskDao
    abstract fun models(): ModelDao
    abstract fun folders(): FolderDao
    abstract fun knowledge(): KnowledgeDao
    abstract fun audit(): AuditDao

    companion object {
        const val NAME = "zenith.db"

        /** Add migrations here when the schema version increases (1→2, 2→3, …). */
        val MIGRATIONS: Array<Migration> = emptyArray()

        fun build(context: Context): ZenithDatabase =
            Room.databaseBuilder(context.applicationContext, ZenithDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
