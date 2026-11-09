package com.melhive.zenith.models

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.melhive.zenith.android.DeviceInfo
import com.melhive.zenith.android.DeviceSnapshot
import com.melhive.zenith.database.InstalledModelEntity
import com.melhive.zenith.database.ModelDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

enum class Compatibility { COMPATIBLE, MARGINAL, INCOMPATIBLE }

data class CompatibilityReport(val level: Compatibility, val reasons: List<String>)

/** Installed on-device models live in app-private storage: files/models/. */
class ModelRepository(private val context: Context, private val dao: ModelDao) {
    val installed: Flow<List<InstalledModelEntity>> = dao.observeAll()
    val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }

    fun fileFor(m: InstalledModelEntity) = File(modelsDir, m.fileName)
    fun partialFile(c: CatalogModel) = File(modelsDir, c.fileName + ".part")

    suspend fun get(id: String) = dao.get(id)

    /** Most recently installed model whose file is present. */
    suspend fun firstInstalled(): InstalledModelEntity? = dao.all().firstOrNull { fileFor(it).exists() }

    fun assess(c: CatalogModel, device: DeviceSnapshot = DeviceInfo.snapshot(context)): CompatibilityReport = assess(c.minRamBytes, c.sizeBytes, device)

    fun assess(minRam: Long, sizeBytes: Long, device: DeviceSnapshot = DeviceInfo.snapshot(context)): CompatibilityReport {
        val reasons = mutableListOf<String>()
        var level = Compatibility.COMPATIBLE
        if (!device.arm64 && "x86_64" !in device.abis) {
            reasons += "LiteRT-LM supports arm64-v8a and x86_64 only; this device reports ${device.abis.joinToString()}"
            level = Compatibility.INCOMPATIBLE
        }
        if (device.totalRamBytes < minRam * 3 / 4) {
            reasons += "Device RAM ${DeviceInfo.formatBytes(device.totalRamBytes)} is well below the recommended ${DeviceInfo.formatBytes(minRam)}"
            level = Compatibility.INCOMPATIBLE
        } else if (device.totalRamBytes < minRam) {
            reasons += "Device RAM ${DeviceInfo.formatBytes(device.totalRamBytes)} is below the recommended ${DeviceInfo.formatBytes(minRam)}; loading may fail or be slow"
            if (level == Compatibility.COMPATIBLE) level = Compatibility.MARGINAL
        }
        if (device.freeStorageBytes < sizeBytes + 200L * 1024 * 1024) {
            reasons += "Not enough free storage (${DeviceInfo.formatBytes(device.freeStorageBytes)} free, ${DeviceInfo.formatBytes(sizeBytes)} needed)"
            level = Compatibility.INCOMPATIBLE
        }
        if (reasons.isEmpty()) reasons += "Meets the RAM, storage and CPU architecture requirements (actual speed must be measured on the device)"
        return CompatibilityReport(level, reasons)
    }

    suspend fun registerDownloaded(c: CatalogModel, file: File) {
        dao.upsert(InstalledModelEntity(c.id, c.name, file.name, file.length(), c.sha256, "catalog", System.currentTimeMillis(), verified = true))
    }

    /**
     * Copies a user-selected `.litertlm` file into app storage. Imported models
     * are not checksum-verified (no reference hash) and are labelled as such.
     */
    suspend fun import(uri: Uri, onProgress: suspend (Long, Long?) -> Unit): InstalledModelEntity = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "imported.litertlm"
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        require(name.endsWith(".litertlm", ignoreCase = true)) { "Only .litertlm model files are supported by the LiteRT-LM runtime (got '$name')" }
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(modelsDir, safeName)
        val tmp = File(modelsDir, "$safeName.import")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            resolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var total = 0L
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); digest.update(buf, 0, n)
                        total += n
                        if (total - lastReport > 16L shl 20) { onProgress(total, size); lastReport = total }
                    }
                    onProgress(total, size)
                }
            } ?: throw IllegalStateException("Could not open the selected file")
            if (!tmp.renameTo(target)) throw IllegalStateException("Could not store the model")
        } catch (e: Throwable) {
            tmp.delete(); throw e
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val catalogMatch = ModelCatalog.models.firstOrNull { it.sha256 == hash }
        val entity = InstalledModelEntity(
            id = catalogMatch?.id ?: "imported-${safeName.removeSuffix(".litertlm").lowercase()}",
            displayName = catalogMatch?.name ?: safeName.removeSuffix(".litertlm"),
            fileName = safeName,
            sizeBytes = target.length(),
            sha256 = hash,
            source = "imported",
            installedAt = System.currentTimeMillis(),
            verified = catalogMatch != null,
        )
        dao.upsert(entity)
        entity
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        val m = dao.get(id) ?: return@withContext
        fileFor(m).delete()
        dao.delete(id)
    }

    fun usedBytes(): Long = modelsDir.listFiles()?.sumOf { it.length() } ?: 0L
}
