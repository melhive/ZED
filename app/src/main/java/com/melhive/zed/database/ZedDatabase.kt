package com.melhive.zed.database

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
        KnowledgeChunkFts::class, AuditLogEntity::class, RelationEntity::class, PendingRequestEntity::class,
    ],
    version = ZedDatabase.VERSION,
    exportSchema = true,
)
abstract class ZedDatabase : RoomDatabase() {
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
    abstract fun pendingRequests(): PendingRequestDao

    companion object {
        const val NAME = "zed.db"
        const val VERSION = 2

        /**
         * v2 (0.1.21): memory strength, reply timings, and the tables later
         * features build on (labelled relations, the offline request queue) —
         * one upgrade instead of several.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `memory_facts` ADD COLUMN `uses` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `memory_facts` ADD COLUMN `lastUsedAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `memory_facts` ADD COLUMN `confidence` REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `firstTokenMs` INTEGER")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `totalMs` INTEGER")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `outputChars` INTEGER")
                db.execSQL("CREATE TABLE IF NOT EXISTS `relations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `subject` TEXT NOT NULL, `relation` TEXT NOT NULL, `target` TEXT NOT NULL, `factId` INTEGER, `createdAt` INTEGER NOT NULL, `source` TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `pending_requests` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT NOT NULL, `conversationId` INTEGER, `createdAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `result` TEXT)")
            }
        }

        /** Add migrations here when the schema version increases (1→2, 2→3, …). */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        fun build(context: Context): ZedDatabase =
            Room.databaseBuilder(context.applicationContext, ZedDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
