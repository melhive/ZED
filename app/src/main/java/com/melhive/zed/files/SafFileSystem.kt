package com.melhive.zed.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.melhive.zed.core.security.PathPolicy
import com.melhive.zed.database.FolderDao
import com.melhive.zed.database.GrantedFolderEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class FileOpException(message: String) : Exception(message)

data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val mimeType: String?,
)

/**
 * File operations restricted to folders the user granted through the Storage
 * Access Framework (ACTION_OPEN_DOCUMENT_TREE). ZED has no broad storage
 * permission and cannot reach anything outside these trees.
 *
 * Path format: relative path inside a granted folder. If several folders are
 * granted, the first segment may name the folder ("Download/report.pdf");
 * otherwise the first granted folder is used.
 */
class SafFileSystem(private val context: Context, private val folders: FolderDao) {

    val grants: Flow<List<GrantedFolderEntity>> = folders.observeAll()

    suspend fun addGrant(treeUri: Uri): GrantedFolderEntity = withContext(Dispatchers.IO) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(treeUri, flags)
        val doc = DocumentFile.fromTreeUri(context, treeUri) ?: throw FileOpException("Folder is not accessible")
        val entity = GrantedFolderEntity(treeUri.toString(), doc.name ?: "Folder", System.currentTimeMillis())
        folders.insert(entity)
        entity
    }

    suspend fun removeGrant(uri: String) = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        folders.delete(uri)
    }

    /** Grants whose permission is still held (users can revoke in system settings). */
    suspend fun validGrants(): List<GrantedFolderEntity> {
        val held = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        return folders.all().filter { it.uri in held }
    }

    suspend fun hasAnyGrant(): Boolean = validGrants().isNotEmpty()

    data class Resolved(val grant: GrantedFolderEntity, val root: DocumentFile, val relative: String) {
        /** Stable key for conflict detection and indexing. */
        val resourceKey: String get() = "tree:${grant.uri.hashCode()}" + if (relative.isEmpty()) "" else "/$relative"
        val displayPath: String get() = if (relative.isEmpty()) grant.displayName else "${grant.displayName}/$relative"
    }

    suspend fun resolveRoot(path: String): Resolved = withContext(Dispatchers.IO) {
        val grants = validGrants()
        if (grants.isEmpty()) throw FileOpException("No folder access granted. Open Files and grant a folder first.")
        val first = path.substringBefore('/')
        val match = grants.firstOrNull { it.displayName.equals(first, ignoreCase = true) }
        val grant = match ?: grants.first()
        val relative = if (match != null) path.substringAfter('/', "") else path
        val root = DocumentFile.fromTreeUri(context, Uri.parse(grant.uri))
            ?: throw FileOpException("Folder ${grant.displayName} is no longer accessible")
        Resolved(grant, root, relative)
    }

    private fun DocumentFile.child(name: String): DocumentFile? =
        listFiles().firstOrNull { it.name == name } ?: listFiles().firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun walk(root: DocumentFile, relative: String): DocumentFile? {
        var cur = root
        if (relative.isEmpty()) return cur
        for (seg in relative.split('/')) {
            if (!cur.isDirectory) return null
            cur = cur.child(seg) ?: return null
        }
        return cur
    }

    suspend fun find(path: String): Pair<Resolved, DocumentFile> = withContext(Dispatchers.IO) {
        val r = resolveRoot(path)
        val f = walk(r.root, r.relative) ?: throw FileOpException("'${r.displayPath}' was not found")
        r to f
    }

    private fun DocumentFile.toItem(parentPath: String): FileItem = FileItem(
        name = name ?: "?",
        path = PathPolicy.join(parentPath, name ?: "?"),
        isDirectory = isDirectory,
        sizeBytes = if (isDirectory) 0 else length(),
        lastModified = lastModified(),
        mimeType = type,
    )

    suspend fun list(path: String): List<FileItem> = withContext(Dispatchers.IO) {
        val (r, dir) = find(path)
        if (!dir.isDirectory) throw FileOpException("'${r.displayPath}' is not a folder")
        val prefix = if (path.isEmpty()) "" else path
        dir.listFiles().map { it.toItem(prefix) }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    suspend fun readText(path: String, maxBytes: Int = 512 * 1024): String = withContext(Dispatchers.IO) {
        val (r, f) = find(path)
        if (f.isDirectory) throw FileOpException("'${r.displayPath}' is a folder")
        if (!PathPolicy.isTextFile(f.name ?: "")) throw FileOpException("'${f.name}' is not a supported text format")
        context.contentResolver.openInputStream(f.uri)?.use { input ->
            val buf = ByteArray(maxBytes)
            var total = 0
            while (total < maxBytes) {
                val n = input.read(buf, total, maxBytes - total)
                if (n < 0) break
                total += n
            }
            val truncated = input.read() >= 0
            String(buf, 0, total, Charsets.UTF_8) + if (truncated) "\n…[truncated at ${maxBytes / 1024} KB]" else ""
        } ?: throw FileOpException("Could not open '${r.displayPath}'")
    }

    suspend fun mkdirs(path: String): DocumentFile = withContext(Dispatchers.IO) {
        val r = resolveRoot(path)
        if (r.relative.isEmpty()) return@withContext r.root
        var cur = r.root
        for (seg in r.relative.split('/')) {
            val existing = cur.child(seg)
            cur = when {
                existing == null -> cur.createDirectory(seg) ?: throw FileOpException("Could not create folder '$seg'")
                existing.isDirectory -> existing
                else -> throw FileOpException("'$seg' exists and is not a folder")
            }
        }
        cur
    }

    private fun mimeFor(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    suspend fun createFile(path: String, content: String?): FileItem = withContext(Dispatchers.IO) {
        val r = resolveRoot(path)
        if (r.relative.isEmpty()) throw FileOpException("A file name is required")
        val name = PathPolicy.name(path)
        val parent = mkdirs(PathPolicy.parent(path))
        if (parent.child(name) != null) throw FileOpException("'$name' already exists")
        // createFile may append an extension based on MIME; using the exact MIME of the extension avoids that.
        val created = parent.createFile(mimeFor(name), name) ?: throw FileOpException("Could not create '$name'")
        if (!content.isNullOrEmpty()) {
            context.contentResolver.openOutputStream(created.uri, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                ?: throw FileOpException("Could not write to '$name'")
        }
        created.toItem(PathPolicy.parent(path))
    }

    suspend fun rename(path: String, newName: String): String = withContext(Dispatchers.IO) {
        val (r, f) = find(path)
        if (r.relative.isEmpty()) throw FileOpException("A granted root folder cannot be renamed")
        val parent = f.parentFile
        if (parent?.child(newName) != null) throw FileOpException("'$newName' already exists")
        if (!f.renameTo(newName)) throw FileOpException("Rename failed")
        newName
    }

    suspend fun copy(source: String, destinationFolder: String): String = withContext(Dispatchers.IO) {
        val (_, src) = find(source)
        if (src.isDirectory) throw FileOpException("Copying folders is not supported; copy files individually")
        val dest = mkdirs(destinationFolder)
        val name = src.name ?: throw FileOpException("Source has no name")
        val target = uniqueName(dest, name)
        val out = dest.createFile(src.type ?: mimeFor(name), target) ?: throw FileOpException("Could not create copy")
        try {
            context.contentResolver.openInputStream(src.uri).use { input ->
                context.contentResolver.openOutputStream(out.uri, "w").use { output ->
                    if (input == null || output == null) throw FileOpException("Could not open streams")
                    input.copyTo(output, 64 * 1024)
                }
            }
        } catch (e: Exception) {
            out.delete()
            throw if (e is FileOpException) e else FileOpException("Copy failed: ${e.message}")
        }
        target
    }

    /**
     * Moves within the same granted tree using DocumentsContract.moveDocument
     * (atomic on providers that support it), falling back to copy + delete
     * only after the copy is verified by size.
     */
    suspend fun move(source: String, destinationFolder: String): String = withContext(Dispatchers.IO) {
        val (_, src) = find(source)
        val parent = src.parentFile ?: throw FileOpException("A granted root folder cannot be moved")
        val dest = mkdirs(destinationFolder)
        val name = src.name ?: throw FileOpException("Source has no name")
        if (dest.child(name) != null) throw FileOpException("'$name' already exists in the destination")
        val moved = runCatching {
            DocumentsContract.moveDocument(context.contentResolver, src.uri, parent.uri, dest.uri)
        }.getOrNull()
        if (moved != null) return@withContext name
        if (src.isDirectory) throw FileOpException("This storage provider cannot move folders")
        val copiedName = copy(source, destinationFolder)
        val copied = dest.child(copiedName)
        if (copied == null || copied.length() != src.length()) throw FileOpException("Move verification failed; original kept")
        if (!src.delete()) throw FileOpException("Copied, but the original could not be deleted")
        copiedName
    }

    suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        val (r, f) = find(path)
        if (r.relative.isEmpty()) throw FileOpException("A granted root folder cannot be deleted; remove the grant instead")
        if (!f.delete()) throw FileOpException("Delete failed")
    }

    /** Moves a direct child of [folderPath] into a sub-folder, used by organize tasks. */
    suspend fun moveChild(folderPath: String, fileName: String, subFolder: String): String =
        move(PathPolicy.join(folderPath, fileName), PathPolicy.join(folderPath, subFolder))

    private fun uniqueName(dir: DocumentFile, name: String): String {
        if (dir.child(name) == null) return name
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        var i = 1
        while (true) {
            val candidate = "$base ($i)$ext"
            if (dir.child(candidate) == null) return candidate
            i++
        }
    }

    /** Recursively enumerates text files for indexing, up to [limit]. */
    suspend fun walkTextFiles(path: String, limit: Int = 2000): List<Pair<FileItem, DocumentFile>> = withContext(Dispatchers.IO) {
        val (_, dir) = find(path)
        val out = mutableListOf<Pair<FileItem, DocumentFile>>()
        fun recurse(d: DocumentFile, prefix: String, depth: Int) {
            if (depth > PathPolicy.MAX_DEPTH || out.size >= limit) return
            for (f in d.listFiles()) {
                if (out.size >= limit) return
                val name = f.name ?: continue
                if (name.startsWith(".")) continue
                if (f.isDirectory) recurse(f, PathPolicy.join(prefix, name), depth + 1)
                else if (PathPolicy.isTextFile(name)) out += f.toItem(prefix) to f
            }
        }
        recurse(dir, path, 0)
        out
    }

    fun openText(doc: DocumentFile, maxBytes: Int = 1024 * 1024): String? =
        context.contentResolver.openInputStream(doc.uri)?.use { input ->
            val bytes = input.readNBytesCompat(maxBytes)
            String(bytes, Charsets.UTF_8)
        }
}

internal fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    var total = 0
    while (total < max) {
        val n = read(buf, 0, minOf(buf.size, max - total))
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
    }
    return out.toByteArray()
}
