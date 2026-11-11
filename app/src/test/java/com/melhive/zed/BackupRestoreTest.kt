package com.melhive.zed

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.melhive.zed.backup.BackupManager
import com.melhive.zed.backup.BackupOptions
import com.melhive.zed.core.backup.BackupCorruptException
import com.melhive.zed.core.backup.WrongPasswordException
import com.melhive.zed.database.ZedDatabase
import com.melhive.zed.memory.MemoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupRestoreTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val secrets = mutableMapOf<String, String>()
    private val modelBytes = ByteArray(200_000) { (it * 7 % 253).toByte() }
    private val voiceBytes = ByteArray(50_000) { (it * 3 % 251).toByte() }

    private fun manager(dbVersion: Int = ZedDatabase.VERSION) = BackupManager(
        ctx, CoroutineScope(Dispatchers.Unconfined), dbVersion, "0.1.20",
        lockDatabase = { it() }, readSecrets = { secrets.toMap() }, writeSecret = { k, v -> secrets[k] = v },
        counts = { mapOf("memories" to 1) },
    )

    private val settingsFile get() = File(ctx.filesDir, "datastore/${BackupManager.SETTINGS_FILE}")
    private val model get() = File(ctx.filesDir, "models/gemma.litertlm")
    private val pack get() = File(ctx.filesDir, "voices/kokoro")
    private val staging get() = File(ctx.filesDir, "restore_staging")

    private fun facts(): List<String> = runBlocking {
        val db = ZedDatabase.build(ctx)
        try { MemoryRepository(db).list().map { it.text } } finally { db.close() }
    }

    private fun remember(text: String) = runBlocking {
        val db = ZedDatabase.build(ctx)
        try { MemoryRepository(db).remember(text, "manual") } finally { db.close() }
    }

    @Before fun seed() {
        ctx.filesDir.deleteRecursively(); ctx.filesDir.mkdirs()
        ctx.deleteDatabase(BackupManager.DB_NAME)
        secrets.clear(); secrets["api_key_gemini"] = "AIza-secret"
        remember("My sister's name is Ana")
        settingsFile.apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(10, 20, 30)) }
        model.apply { parentFile!!.mkdirs(); writeBytes(modelBytes) }
        File(ctx.filesDir, "models/half.litertlm.part").writeBytes(ByteArray(10))
        pack.mkdirs(); File(pack, ".installed").writeText("sha"); File(pack, "model.onnx").writeBytes(voiceBytes)
        File(ctx.filesDir, "voices/unfinished.installing").apply { mkdirs(); File(this, "x.onnx").writeBytes(ByteArray(5)) }
    }

    private fun makeBackup(options: BackupOptions = BackupOptions(models = true, voices = true, password = "pw")): ByteArray = runBlocking {
        ByteArrayOutputStream().also { manager().backup(it, options) }.toByteArray()
    }

    /** What a new phone looks like: nothing but a different memory and other settings. */
    private fun becomeAnotherPhone() {
        ctx.deleteDatabase(BackupManager.DB_NAME)
        File(ctx.filesDir, "models").deleteRecursively(); File(ctx.filesDir, "voices").deleteRecursively()
        settingsFile.writeBytes(byteArrayOf(99))
        secrets.clear()
        remember("Something only the new phone knows")
    }

    @Test fun everythingComesBackOnAnotherPhone() = runBlocking<Unit> {
        val backup = makeBackup()
        val manifest = manager().inspect(ByteArrayInputStream(backup))
        assertEquals(listOf("gemma.litertlm"), manifest.models)
        assertEquals(listOf("kokoro"), manifest.voices)
        assertEquals(listOf("api_key_gemini"), manifest.keyNames)
        assertFalse(String(backup, Charsets.ISO_8859_1).contains("AIza-secret"), "keys are never stored in the clear")

        becomeAnotherPhone()
        val m = manager()
        m.stageRestore(ByteArrayInputStream(backup), "pw")
        // Staging alone changes nothing that is live.
        assertEquals(listOf("Something only the new phone knows"), facts())
        assertEquals("AIza-secret", secrets["api_key_gemini"])

        assertTrue(BackupManager.applyPendingRestore(ctx))
        assertEquals(listOf("My sister's name is Ana"), facts())
        assertContentEquals(byteArrayOf(10, 20, 30), settingsFile.readBytes())
        assertContentEquals(modelBytes, model.readBytes())
        assertContentEquals(voiceBytes, File(pack, "model.onnx").readBytes())
        assertTrue(File(pack, ".installed").exists(), "restored voice packs count as installed")
        assertFalse(File(ctx.filesDir, "models/half.litertlm.part").exists(), "unfinished downloads are not backed up")
        assertFalse(File(ctx.filesDir, "voices/unfinished.installing").exists())
        assertFalse(staging.exists())

        assertTrue(m.consumeNotice())
        assertTrue(m.restoreNotice.value!!.contains("1 restored"))
        assertFalse(m.consumeNotice())
        assertFalse(BackupManager.applyPendingRestore(ctx), "a restore is applied exactly once")
    }

    @Test fun dataOnlyBackupSkipsModelsVoicesAndKeys() = runBlocking<Unit> {
        val backup = makeBackup(BackupOptions(models = false, voices = false, password = ""))
        val manifest = manager().inspect(ByteArrayInputStream(backup))
        assertTrue(manifest.models.isEmpty() && manifest.voices.isEmpty() && manifest.keyNames.isEmpty())
        assertTrue(backup.size < modelBytes.size, "no model inside")
        becomeAnotherPhone()
        manager().stageRestore(ByteArrayInputStream(backup), "")
        assertTrue(BackupManager.applyPendingRestore(ctx))
        assertEquals(listOf("My sister's name is Ana"), facts())
        assertFalse(model.exists())
        assertTrue(secrets.isEmpty())
    }

    @Test fun wrongPasswordDamageAndTruncationChangeNothing() = runBlocking<Unit> {
        val backup = makeBackup()
        becomeAnotherPhone()
        val m = manager()
        assertFailsWith<WrongPasswordException> { m.stageRestore(ByteArrayInputStream(backup), "nope") }
        val damaged = backup.copyOf().also { it[it.size - 5000] = (it[it.size - 5000] + 1).toByte() }
        assertFailsWith<BackupCorruptException> { m.stageRestore(ByteArrayInputStream(damaged), "pw") }
        assertFailsWith<BackupCorruptException> { m.stageRestore(ByteArrayInputStream(backup.copyOf(backup.size / 2)), "pw") }
        assertFailsWith<BackupCorruptException> { m.stageRestore(ByteArrayInputStream("not a backup at all".toByteArray()), "pw") }

        assertFalse(staging.exists())
        assertFalse(BackupManager.applyPendingRestore(ctx))
        assertEquals(listOf("Something only the new phone knows"), facts())
        assertContentEquals(byteArrayOf(99), settingsFile.readBytes())
        assertTrue(secrets.isEmpty(), "keys are only written after the whole file is verified")
    }

    @Test fun passwordCanBeLeftEmptyToSkipTheKeys() = runBlocking<Unit> {
        val backup = makeBackup()
        becomeAnotherPhone()
        manager().stageRestore(ByteArrayInputStream(backup), "")
        assertTrue(secrets.isEmpty())
        assertTrue(BackupManager.applyPendingRestore(ctx))
        assertEquals(listOf("My sister's name is Ana"), facts())
    }

    @Test fun backupsFromANewerZedAreRefused() = runBlocking<Unit> {
        val backup = makeBackup()
        val older = manager(dbVersion = ZedDatabase.VERSION - 1)
        assertFailsWith<BackupCorruptException> { older.inspect(ByteArrayInputStream(backup)) }
        assertFailsWith<BackupCorruptException> { older.stageRestore(ByteArrayInputStream(backup), "pw") }
        assertFalse(staging.exists())
    }

    @Test fun anInterruptedStagingIsDiscardedAtStartup() {
        File(staging, "db").mkdirs(); File(staging, "db/zed.db").writeBytes(ByteArray(3))
        assertFalse(BackupManager.applyPendingRestore(ctx), "no READY marker: never applied")
        assertFalse(staging.exists())
        assertEquals(listOf("My sister's name is Ana"), facts())
    }

    @Test fun sizesAndNames() {
        val s = manager().sizes()
        assertEquals(1, s.modelCount); assertEquals(modelBytes.size.toLong(), s.models)
        assertEquals(1, s.voicePackCount); assertEquals(voiceBytes.size.toLong(), s.voices)
        assertTrue(s.data > 0)
        assertEquals("ZED-backup-2026-11-11.zedbackup", BackupManager.suggestedName(java.time.LocalDate.of(2026, 11, 11)))
        assertEquals("2.3 GB", BackupManager.formatBytes(2_469_606_195))
    }
}
