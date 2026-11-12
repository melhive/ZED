package com.melhive.zed

import android.app.Application
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.melhive.zed.database.ZedDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Guards user data across updates:
 * - the committed schema for the current version must match the entities
 *   (catches entity changes without a version bump), and
 * - there must be a migration for every step from 1 to the current version.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ZedDatabase::class.java)

    @Test fun committedSchemaMatchesEntities() {
        helper.createDatabase(TEST_DB, ZedDatabase.VERSION).close()
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext<Application>(), ZedDatabase::class.java, TEST_DB)
            .addMigrations(*ZedDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        db.openHelper.writableDatabase // Room validates the identity hash here
        db.close()
    }

    @Test fun everyVersionStepHasAMigration() {
        val steps = ZedDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }.toSet()
        for (v in 1 until ZedDatabase.VERSION) assertEquals(true, (v to v + 1) in steps, "Missing migration $v → ${v + 1}")
    }

    /** 1 → 2 (v0.1.21): what was stored stays, and the result is exactly the schema the app expects. */
    @Test fun upgradeFrom1KeepsDataAndMatchesTheSchema() {
        val name = "$TEST_DB-1to2"
        helper.createDatabase(name, 1).use { db ->
            db.execSQL("INSERT INTO memory_facts (text, createdAt, source, pinned) VALUES ('My sister is Ana', 1, 'manual', 0)")
        }
        helper.runMigrationsAndValidate(name, 2, true, ZedDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT text, uses, lastUsedAt, confidence FROM memory_facts").use { c ->
                assertEquals(true, c.moveToFirst())
                assertEquals("My sister is Ana", c.getString(0))
                assertEquals(0, c.getInt(1))
                assertEquals(0L, c.getLong(2))
                assertEquals(1.0, c.getDouble(3))
            }
            db.query("SELECT COUNT(*) FROM relations").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
            db.query("SELECT COUNT(*) FROM pending_requests").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        }
    }

    companion object { const val TEST_DB = "migration-test.db" }
}
