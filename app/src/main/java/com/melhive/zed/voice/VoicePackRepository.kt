package com.melhive.zed.voice

import android.content.Context
import com.melhive.zed.core.voice.VoiceCatalog
import com.melhive.zed.core.voice.VoicePack
import com.melhive.zed.models.VerifiedDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Offline voice packs live in app-private storage: files/voices/<packId>/.
 * A pack is installed when its marker file exists; the marker is written only
 * after every required file has been extracted, so a half-finished install is
 * never used.
 */
class VoicePackRepository(context: Context) {
    val root: File = File(context.filesDir, "voices").apply { mkdirs() }
    private val _installed = MutableStateFlow(scan())
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    fun dir(pack: VoicePack) = File(root, pack.id)
    fun isInstalled(pack: VoicePack) = File(dir(pack), MARKER).exists()
    fun usedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Bytes needed during install: the archive plus its extracted files. */
    fun requiredBytes(pack: VoicePack): Long = pack.sizeBytes * 2 + 50L * 1024 * 1024

    private fun scan(): Set<String> = (VoiceCatalog.packs + VoiceCatalog.WAKE_WORD).filter { File(File(root, it.id), MARKER).exists() }.map { it.id }.toSet()

    suspend fun install(pack: VoicePack, onProgress: suspend (done: Long, total: Long, phase: String) -> Unit) = withContext(Dispatchers.IO) {
        if (isInstalled(pack)) return@withContext
        val archive = File(root, "${pack.id}.tar.bz2.part")
        VerifiedDownload.fetch(pack.archiveUrl, archive, pack.sizeBytes, pack.sha256, null, onProgress)

        val tmp = File(root, "${pack.id}.installing")
        tmp.deleteRecursively(); tmp.mkdirs()
        val prefix = pack.archiveRoot.trimEnd('/') + "/"
        val extracted = mutableSetOf<String>()
        onProgress(0, pack.sizeBytes, "Installing")
        val counting = CountingInputStream(BufferedInputStream(FileInputStream(archive), 1 shl 16))
        TarArchiveInputStream(BZip2CompressorInputStream(counting)).use { tar ->
            var lastReport = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = tar.nextEntry ?: break
                if (counting.count - lastReport >= 4L shl 20) { onProgress(counting.count, pack.sizeBytes, "Installing"); lastReport = counting.count }
                if (entry.isDirectory || !entry.name.startsWith(prefix)) continue
                // Only the listed plain file names are written, so archive paths can never escape the folder.
                val rel = entry.name.removePrefix(prefix)
                if (rel !in pack.keepFiles) continue
                File(tmp, rel).outputStream().use { out -> tar.copyTo(out, 1 shl 16) }
                extracted += rel
            }
        }
        val missing = pack.keepFiles - "LICENSE" - extracted
        if (missing.isNotEmpty()) {
            tmp.deleteRecursively()
            throw IOException("The voice pack is missing ${missing.joinToString()}; it was not installed.")
        }
        File(tmp, MARKER).writeText(pack.sha256)
        val target = dir(pack)
        target.deleteRecursively()
        if (!tmp.renameTo(target)) { tmp.deleteRecursively(); throw IOException("Could not move the voice pack into place") }
        archive.delete()
        onProgress(pack.sizeBytes, pack.sizeBytes, "Installed")
        _installed.value = scan()
    }

    fun remove(pack: VoicePack) {
        dir(pack).deleteRecursively()
        File(root, "${pack.id}.tar.bz2.part").delete()
        File(root, "${pack.id}.installing").deleteRecursively()
        _installed.value = scan()
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
            private set
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }

    companion object { private const val MARKER = ".installed" }
}
