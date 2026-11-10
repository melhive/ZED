package com.melhive.zenith.models

import com.melhive.zenith.intelligence.providers.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Downloads a catalog model with HTTP Range resume into a `.part` file, then
 * verifies size and SHA-256 before moving it into place. Progress callbacks
 * report real byte counts.
 */
class ModelDownloader(private val repo: ModelRepository, private val hfToken: () -> String?) {

    suspend fun download(c: CatalogModel, onProgress: suspend (downloaded: Long, total: Long, phase: String) -> Unit): File = withContext(Dispatchers.IO) {
        val part = repo.partialFile(c)
        val target = File(repo.modelsDir, c.fileName)
        if (target.exists() && target.length() == c.sizeBytes) {
            onProgress(c.sizeBytes, c.sizeBytes, "Verifying")
            if (sha256(target) { done -> onProgress(done, c.sizeBytes, "Verifying") } == c.sha256) return@withContext target
            target.delete()
        }
        VerifiedDownload.fetch(c.downloadUrl, part, c.sizeBytes, c.sha256, hfToken(), onProgress)
        if (!part.renameTo(target)) throw IOException("Could not move the model into place")
        repo.registerDownloaded(c, target)
        target
    }

    private suspend fun sha256(file: File, onProgress: suspend (Long) -> Unit) = VerifiedDownload.sha256(file, onProgress)
}

/** Resumable HTTP download into a `.part` file with size and SHA-256 verification. */
object VerifiedDownload {
    suspend fun fetch(
        url: String, part: File, sizeBytes: Long, expectedSha256: String, bearer: String? = null,
        onProgress: suspend (downloaded: Long, total: Long, phase: String) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        var existing = if (part.exists()) part.length() else 0L
        if (existing > sizeBytes) { part.delete(); existing = 0 }
        if (existing < sizeBytes) {
            val req = Request.Builder().url(url)
                .apply { if (existing > 0) header("Range", "bytes=$existing-") }
                .apply { bearer?.let { header("Authorization", "Bearer $it") } }
                .build()
            Http.client.newCall(req).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) throw IOException("Access denied by the server (HTTP ${resp.code}). The file may require accepting its license or a token.")
                if (!resp.isSuccessful) throw IOException("Download failed: HTTP ${resp.code}")
                val resumed = resp.code == 206
                if (!resumed && existing > 0) existing = 0 // server ignored Range; start over
                val body = resp.body ?: throw IOException("Empty response body")
                FileOutputStream(part, resumed).use { out ->
                    val input = body.byteStream()
                    val buf = ByteArray(256 * 1024)
                    var downloaded = existing
                    var lastReport = 0L
                    onProgress(downloaded, sizeBytes, "Downloading")
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        if (downloaded - lastReport >= 4L shl 20) { onProgress(downloaded, sizeBytes, "Downloading"); lastReport = downloaded }
                    }
                    onProgress(downloaded, sizeBytes, "Downloading")
                }
            }
        }
        if (part.length() != sizeBytes) throw IOException("Incomplete download (${part.length()} of $sizeBytes bytes). Retry to resume.")
        val hash = sha256(part) { done -> onProgress(done, sizeBytes, "Verifying") }
        if (hash != expectedSha256) {
            part.delete()
            throw IOException("Checksum mismatch — the file was corrupted and has been deleted. Retry the download.")
        }
    }

    suspend fun sha256(file: File, onProgress: suspend (Long) -> Unit): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            var done = 0L
            var last = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                done += n
                if (done - last >= 64L shl 20) { onProgress(done); last = done }
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
