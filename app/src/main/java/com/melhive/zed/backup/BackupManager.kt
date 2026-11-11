package com.melhive.zed.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import com.melhive.zed.core.backup.BackupCorruptException
import com.melhive.zed.core.backup.BackupCrypto
import com.melhive.zed.core.backup.BackupFormat
import com.melhive.zed.core.backup.BackupManifest
import com.melhive.zed.core.backup.BackupReader
import com.melhive.zed.core.backup.BackupWriter
import com.melhive.zed.core.backup.WrongPasswordException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

data class BackupOptions(val models: Boolean, val voices: Boolean, val password: String)

/** Bytes each part of a backup would take. */
data class BackupSizes(val data: Long, val models: Long, val voices: Long, val modelCount: Int, val voicePackCount: Int)

sealed interface BackupState {
    data object Idle : BackupState
    data class Working(val restoring: Boolean, val label: String, val done: Long, val total: Long) : BackupState
    data class Finished(val restoring: Boolean, val message: String) : BackupState
    data class Failed(val restoring: Boolean, val message: String) : BackupState
}

/**
 * Backup and restore of everything ZED stores, as one `.zedbackup` file the
 * user saves wherever they like (Downloads, a USB drive, cloud storage) and
 * opens on another phone.
 *
 * A backup holds the database (memories, tasks, reminders, conversations,
 * document index), all settings and routines, and optionally the downloaded AI
 * models, offline voices and — encrypted with a password — the API keys.
 *
 * Restore never touches live data while reading the file: everything is first
 * unpacked and checksum-verified into a staging folder. Only a complete,
 * verified backup is marked ready; it is then swapped in at the next app
 * start, before the database or settings are opened. A damaged file, a wrong
 * password or a full disk therefore leaves ZED exactly as it was.
 */
class BackupManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val dbVersion: Int,
    private val appVersion: String,
    /** Runs the block while no one else can write to the database. */
    private val lockDatabase: suspend (block: () -> Unit) -> Unit,
    private val readSecrets: () -> Map<String, String>,
    private val writeSecret: (String, String) -> Unit,
    private val counts: suspend () -> Map<String, Int>,
) {
    private val paths = Paths(context)
    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state.asStateFlow()

    /** Shown once after a restore has been applied. */
    val restoreNotice = MutableStateFlow<String?>(null)

    val busy: Boolean get() = _state.value is BackupState.Working

    fun dismiss() { if (!busy) _state.value = BackupState.Idle }

    fun secretNames(): Set<String> = runCatching { readSecrets().keys }.getOrDefault(emptySet())

    fun sizes(): BackupSizes {
        val models = modelFiles()
        val voices = voicePacks()
        val data = paths.db.length() + paths.wal.length() + paths.settings.length()
        return BackupSizes(data, models.sumOf { it.length() }, voices.sumOf { p -> packFiles(p).sumOf { it.second.length() } }, models.size, voices.size)
    }

    // ------------------------------------------------------------------ backup

    fun startBackup(uri: Uri, options: BackupOptions) {
        if (busy) return
        _state.value = BackupState.Working(false, "Preparing", 0, 0)
        scope.launch(Dispatchers.IO) {
            try {
                val out = context.contentResolver.openOutputStream(uri, "w") ?: throw java.io.IOException("Couldn't open the file to save to.")
                val manifest = BufferedOutputStream(out, 1 shl 16).use { backup(it, options) }
                val parts = buildList {
                    add("your data and settings")
                    if (manifest.models.isNotEmpty()) add("${manifest.models.size} AI model${if (manifest.models.size == 1) "" else "s"}")
                    if (manifest.voices.isNotEmpty()) add("${manifest.voices.size} voice pack${if (manifest.voices.size == 1) "" else "s"}")
                    if (manifest.keyNames.isNotEmpty()) add("${manifest.keyNames.size} API key${if (manifest.keyNames.size == 1) "" else "s"} (password-protected)")
                }
                _state.value = BackupState.Finished(false, "Backup saved (${formatBytes(manifest.totalBytes)}): ${parts.joinToString()}.")
            } catch (e: Throwable) {
                // Never leave a half-written file that looks like a backup.
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                if (e is kotlinx.coroutines.CancellationException) { _state.value = BackupState.Idle; throw e }
                _state.value = BackupState.Failed(false, "Backup failed: ${describe(e)}")
            }
        }
    }

    /** Writes a complete backup to [out] and returns its manifest. */
    suspend fun backup(out: OutputStream, options: BackupOptions): BackupManifest = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "backup_tmp").apply { deleteRecursively(); mkdirs() }
        try {
            // A consistent snapshot of the database: copied while writers are held off.
            val dbCopy = File(tmp, "zed.db"); val walCopy = File(tmp, "zed.db-wal")
            if (paths.db.exists()) lockDatabase {
                paths.db.copyTo(dbCopy, overwrite = true)
                if (paths.wal.exists() && paths.wal.length() > 0) paths.wal.copyTo(walCopy, overwrite = true)
            }
            val settings = if (paths.settings.exists()) paths.settings.readBytes() else null
            val secrets = if (options.password.isNotEmpty()) readSecrets() else emptyMap()
            val keys = if (secrets.isNotEmpty()) BackupCrypto.encrypt(BackupCrypto.encodeKeys(secrets), options.password.toCharArray()) else null
            val models = if (options.models) modelFiles() else emptyList()
            val packs = if (options.voices) voicePacks() else emptyList()
            val voiceFiles = packs.flatMap { p -> packFiles(p).map { (rel, f) -> "${BackupFormat.VOICES}${p.name}/$rel" to f } }

            val files = buildList {
                if (dbCopy.exists()) add(BackupFormat.DB to dbCopy)
                if (walCopy.exists()) add(BackupFormat.DB_WAL to walCopy)
                addAll(voiceFiles)
                models.forEach { add(BackupFormat.MODELS + it.name to it) }
            }
            val total = files.sumOf { it.second.length() } + (settings?.size ?: 0) + (keys?.size ?: 0)
            val manifest = BackupManifest(
                appVersion = appVersion, dbVersion = dbVersion, createdAt = System.currentTimeMillis(),
                device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(), keyNames = secrets.keys.sorted(),
                models = models.map { it.name }, voices = packs.map { it.name }, totalBytes = total,
                counts = runCatching { counts() }.getOrDefault(emptyMap()),
            )
            val progress = Progress(false, total)
            val writer = BackupWriter(out)
            writer.entry(BackupFormat.MANIFEST, manifest.encode())
            if (keys != null) { writer.entry(BackupFormat.KEYS, keys); progress.add(keys.size.toLong(), "Saving API keys") }
            if (settings != null) { writer.entry(BackupFormat.SETTINGS, settings); progress.add(settings.size.toLong(), "Saving settings") }
            for ((name, file) in files) {
                currentCoroutineContext().ensureActive()
                val label = when {
                    name.startsWith(BackupFormat.MODELS) -> "Saving model ${file.name}"
                    name.startsWith(BackupFormat.VOICES) -> "Saving voices"
                    else -> "Saving your data"
                }
                // The size is fixed up front; a file that changes underneath fails the backup instead of corrupting it.
                file.inputStream().use { input -> writer.entry(name, file.length(), input) { n -> progress.add(n, label) } }
            }
            writer.finish()
            out.flush()
            manifest
        } finally {
            tmp.deleteRecursively()
        }
    }

    // ----------------------------------------------------------------- restore

    /** Reads only the summary at the start of a backup file. */
    suspend fun inspect(uri: Uri): BackupManifest = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Couldn't open that file.")
        input.use { inspect(it) }
    }

    fun inspect(input: InputStream): BackupManifest = BackupReader(BufferedInputStream(input, 1 shl 16)).let { reader ->
        val first = reader.next() ?: throw BackupCorruptException("The backup is empty.")
        if (first.name != BackupFormat.MANIFEST) throw BackupCorruptException("The backup has no summary.")
        BackupManifest.decode(first.readBytes()).also { checkCompatible(it) }
    }

    private fun checkCompatible(m: BackupManifest) {
        if (m.format > BackupFormat.VERSION || m.dbVersion > dbVersion)
            throw BackupCorruptException("This backup was made by a newer ZED (${m.appVersion}). Update ZED on this phone first, then restore.")
    }

    /** Unpacks and verifies the backup, then restarts ZED to swap it in. */
    fun startRestore(uri: Uri, password: String, restart: () -> Unit) {
        if (busy) return
        _state.value = BackupState.Working(true, "Reading backup", 0, 0)
        scope.launch(Dispatchers.IO) {
            try {
                val input = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Couldn't open that file.")
                input.use { stageRestore(it, password) }
                _state.value = BackupState.Finished(true, "Backup verified. Restarting ZED to finish…")
                kotlinx.coroutines.delay(1200)
                withContext(Dispatchers.Main) { restart() }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) { _state.value = BackupState.Idle; throw e }
                _state.value = BackupState.Failed(true, when (e) {
                    is WrongPasswordException -> "That password doesn't open the API keys in this backup. Nothing was changed. Check the password, or clear it to restore everything except the keys."
                    else -> "Restore failed: ${describe(e)} Nothing was changed."
                })
            }
        }
    }

    /**
     * Unpacks [input] into the staging folder and marks it ready. Throws — and
     * leaves nothing behind — if the file is damaged, incomplete, too new, too
     * big for the free space, or the password is wrong.
     */
    suspend fun stageRestore(input: InputStream, password: String): BackupManifest = withContext(Dispatchers.IO) {
        val staging = paths.staging
        staging.deleteRecursively()
        try {
            val reader = BackupReader(BufferedInputStream(input, 1 shl 16))
            val first = reader.next() ?: throw BackupCorruptException("The backup is empty.")
            if (first.name != BackupFormat.MANIFEST) throw BackupCorruptException("The backup has no summary.")
            val manifest = BackupManifest.decode(first.readBytes())
            checkCompatible(manifest)
            val free = context.filesDir.usableSpace
            if (manifest.totalBytes + (64L shl 20) > free)
                throw java.io.IOException("Not enough free storage: the backup needs ${formatBytes(manifest.totalBytes)} and ${formatBytes(free)} is free.")
            staging.mkdirs()
            val progress = Progress(true, manifest.totalBytes)
            var keys: Map<String, String> = emptyMap()
            val buf = ByteArray(1 shl 16)
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = reader.next() ?: break
                val label = when {
                    entry.name.startsWith(BackupFormat.MODELS) -> "Restoring model ${entry.name.substringAfterLast('/')}"
                    entry.name.startsWith(BackupFormat.VOICES) -> "Restoring voices"
                    else -> "Restoring your data"
                }
                when {
                    entry.name == BackupFormat.KEYS -> {
                        val bytes = entry.readBytes()
                        // Checked before the large files are unpacked, so a typo is reported in seconds.
                        if (password.isNotEmpty()) keys = BackupCrypto.decodeKeys(BackupCrypto.decrypt(bytes, password.toCharArray()))
                        progress.add(bytes.size.toLong(), label)
                    }
                    entry.name == BackupFormat.SETTINGS || entry.name == BackupFormat.DB || entry.name == BackupFormat.DB_WAL ||
                        entry.name.startsWith(BackupFormat.MODELS) || entry.name.startsWith(BackupFormat.VOICES) -> {
                        val target = File(staging, entry.name)
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out ->
                            while (true) {
                                val n = entry.stream.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                progress.add(n.toLong(), label)
                            }
                        }
                    }
                    // Parts added by future versions are skipped (and still checksum-verified by the reader).
                    else -> Unit
                }
            }
            if (!File(staging, BackupFormat.DB).exists()) throw BackupCorruptException("The backup holds no ZED data.")
            keys.forEach { (name, value) -> writeSecret(name, value) }
            File(staging, NOTICE).writeText(summary(manifest, keys.size))
            // Written last: only a complete, verified backup is ever applied.
            File(staging, READY).writeText(manifest.appVersion)
            manifest
        } catch (e: Throwable) {
            staging.deleteRecursively()
            throw e
        }
    }

    private fun summary(m: BackupManifest, keysRestored: Int): String = buildString {
        val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(m.createdAt))
        append("Restored the backup from $date")
        if (m.device.isNotBlank()) append(" (${m.device})")
        append(".\n\n")
        val c = m.counts.filterValues { it > 0 }.map { "${it.value} ${it.key}" }
        append("• Your data: ").append(if (c.isEmpty()) "settings and routines" else c.joinToString() + ", settings and routines").append('\n')
        append("• AI models: ").append(if (m.models.isEmpty()) "not in this backup — download them again in Models & AI" else "${m.models.size} restored").append('\n')
        append("• Offline voices: ").append(if (m.voices.isEmpty()) "not in this backup — download them again in Voice" else "${m.voices.size} pack${if (m.voices.size == 1) "" else "s"} restored").append('\n')
        append("• API keys: ").append(when {
            keysRestored > 0 -> "$keysRestored restored"
            m.keyNames.isNotEmpty() -> "skipped (no password entered) — add them again in Models & AI"
            else -> "not in this backup — add them again in Models & AI"
        }).append('\n')
        append("\nOn a new phone, also allow ZED's permissions again (Settings → Allow everything) and re-grant folders in Files.")
    }

    /** After a restore was applied at startup: publishes the notice for the UI. Returns true if a restore just happened. */
    fun consumeNotice(): Boolean {
        val f = paths.notice
        if (!f.exists()) return false
        restoreNotice.value = runCatching { f.readText() }.getOrDefault("Restore complete.")
        f.delete()
        return true
    }

    // ----------------------------------------------------------------- helpers

    private fun modelFiles(): List<File> = paths.models.listFiles().orEmpty()
        .filter { it.isFile && it.length() > 0 && !it.name.endsWith(".part") && !it.name.endsWith(".import") && BackupFormat.isSafeName(BackupFormat.MODELS + it.name) }
        .sortedBy { it.name }

    /** Fully installed voice packs (the wake word model is one of them). */
    private fun voicePacks(): List<File> = paths.voices.listFiles().orEmpty()
        .filter { it.isDirectory && File(it, MARKER).exists() && BackupFormat.isSafeName(BackupFormat.VOICES + it.name + "/x") }
        .sortedBy { it.name }

    private fun packFiles(pack: File): List<Pair<String, File>> = pack.walkTopDown()
        .filter { it.isFile && it.name != MARKER }
        .map { it.relativeTo(pack).invariantSeparatorsPath to it }
        .filter { (rel, _) -> BackupFormat.isSafeName("${BackupFormat.VOICES}${pack.name}/$rel") }
        .sortedBy { it.first }.toList()

    private inner class Progress(val restoring: Boolean, val total: Long) {
        private var done = 0L
        private var shown = -1L
        fun add(n: Long, label: String) {
            done += n
            val current = _state.value as? BackupState.Working
            if (done - shown >= (1L shl 20) || done >= total || current?.label != label) {
                shown = done
                _state.value = BackupState.Working(restoring, label, done.coerceAtMost(total), total)
            }
        }
    }

    private fun describe(e: Throwable): String {
        val text = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        return when {
            text.contains("ENOSPC") || text.contains("No space left", ignoreCase = true) -> "There isn't enough free storage."
            text.endsWith(".") || text.endsWith("!") -> text
            else -> "$text."
        }
    }

    private class Paths(context: Context) {
        val db: File = context.getDatabasePath(DB_NAME)
        val wal = File(db.path + "-wal")
        val settings = File(context.filesDir, "datastore/$SETTINGS_FILE")
        val models = File(context.filesDir, "models")
        val voices = File(context.filesDir, "voices")
        val staging = File(context.filesDir, "restore_staging")
        val notice = File(context.filesDir, "restore_notice.txt")
    }

    companion object {
        const val DB_NAME = "zed.db"
        const val SETTINGS_FILE = "zed_settings.preferences_pb"
        const val MIME = "application/octet-stream"
        private const val READY = "READY"
        private const val NOTICE = "NOTICE"
        private const val MARKER = ".installed"

        fun suggestedName(now: java.time.LocalDate = java.time.LocalDate.now()) = "ZED-backup-$now.${BackupFormat.EXTENSION}"

        fun formatBytes(b: Long): String = when {
            b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
            b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
            else -> "%.0f KB".format((b / 1024.0).coerceAtLeast(1.0))
        }

        /**
         * Swaps a verified, staged restore into place. Must run at process
         * start before the database or settings are opened. Every step is a
         * rename inside the app's private storage and is safe to repeat, so
         * being killed half-way simply continues on the next start.
         */
        fun applyPendingRestore(context: Context): Boolean {
            val p = Paths(context)
            val staging = p.staging
            if (!staging.exists()) return false
            if (!File(staging, READY).exists()) { staging.deleteRecursively(); return false }

            File(staging, NOTICE).takeIf { it.exists() }?.let { move(it, p.notice) }

            val db = File(staging, BackupFormat.DB)
            if (db.exists()) {
                for (suffix in listOf("-wal", "-shm", "-journal")) File(p.db.path + suffix).delete()
                p.db.parentFile?.mkdirs()
                move(db, p.db)
            }
            File(staging, BackupFormat.DB_WAL).takeIf { it.exists() }?.let { move(it, p.wal) }
            File(staging, BackupFormat.SETTINGS).takeIf { it.exists() }?.let { p.settings.parentFile?.mkdirs(); move(it, p.settings) }

            File(staging, "voices").listFiles().orEmpty().filter { it.isDirectory }.forEach { pack ->
                File(pack, MARKER).writeText("restored")
                val target = File(p.voices, pack.name)
                target.deleteRecursively()
                p.voices.mkdirs()
                if (!pack.renameTo(target)) { pack.copyRecursively(target, overwrite = true); pack.deleteRecursively() }
            }
            File(staging, "models").listFiles().orEmpty().filter { it.isFile }.forEach { model ->
                p.models.mkdirs()
                move(model, File(p.models, model.name))
            }
            File(staging, READY).delete()
            staging.deleteRecursively()
            return true
        }

        private fun move(from: File, to: File) {
            to.delete()
            if (!from.renameTo(to)) { from.copyTo(to, overwrite = true); from.delete() }
        }

        /** Closes ZED and opens it again, so a staged restore is applied by a fresh process. */
        fun restartApp(context: Context) {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val component = launch?.component
            if (component != null) context.startActivity(Intent.makeRestartActivityTask(component))
            Runtime.getRuntime().exit(0)
        }
    }
}
