package com.melhive.zenith

import android.app.Application
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.melhive.zenith.database.ZenithDatabase
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
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ZenithDatabase::class.java)

    @Test fun committedSchemaMatchesEntities() {
        helper.createDatabase(TEST_DB, ZenithDatabase.VERSION).close()
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext<Application>(), ZenithDatabase::class.java, TEST_DB)
            .addMigrations(*ZenithDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        db.openHelper.writableDatabase // Room validates the identity hash here
        db.close()
    }

    @Test fun everyVersionStepHasAMigration() {
        val steps = ZenithDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }.toSet()
        for (v in 1 until ZenithDatabase.VERSION) assertEquals(true, (v to v + 1) in steps, "Missing migration $v → ${v + 1}")
    }

    companion object { const val TEST_DB = "migration-test.db" }
}
