package com.melhive.zenith.knowledge

import com.melhive.zenith.core.knowledge.Bm25
import com.melhive.zenith.core.knowledge.TextChunker
import com.melhive.zenith.core.knowledge.Tokenizer
import com.melhive.zenith.database.KnowledgeDao
import com.melhive.zenith.database.KnowledgeDocEntity
import com.melhive.zenith.files.SafFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class KnowledgeHit(val docName: String, val docPath: String, val text: String, val score: Double)

/**
 * On-device document index: text files from granted folders are chunked and
 * stored in SQLite FTS4; queries fetch FTS candidates and rank them with BM25.
 * Nothing leaves the device unless retrieved excerpts are sent as context to
 * an online provider the user selected.
 */
class KnowledgeRepository(private val dao: KnowledgeDao, private val files: SafFileSystem) {
    val documents: Flow<List<KnowledgeDocEntity>> = dao.observeDocs()

    data class IndexReport(val scanned: Int, val indexed: Int, val unchanged: Int, val failed: Int)

    /**
     * Indexes text files under [path]. [onProgress] receives actual counts
     * (processed, total) after each file.
     */
    suspend fun index(path: String, onProgress: suspend (Int, Int, String) -> Unit): IndexReport = withContext(Dispatchers.IO) {
        val root = files.resolveRoot(path)
        val candidates = files.walkTextFiles(path)
        var indexed = 0; var unchanged = 0; var failed = 0
        candidates.forEachIndexed { i, (item, doc) ->
            currentCoroutineContext().ensureActive()
            try {
                val existing = dao.findDoc(root.grant.uri, item.path)
                if (existing != null && existing.modifiedAt == item.lastModified && existing.sizeBytes == item.sizeBytes) {
                    unchanged++
                } else {
                    val text = files.openText(doc) ?: throw IllegalStateException("unreadable")
                    val chunks = TextChunker.chunk(text)
                    dao.replaceDoc(
                        existing?.id,
                        KnowledgeDocEntity(treeUri = root.grant.uri, path = item.path, name = item.name, sizeBytes = item.sizeBytes, modifiedAt = item.lastModified, indexedAt = System.currentTimeMillis(), chunkCount = chunks.size),
                        chunks,
                    )
                    indexed++
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failed++
            }
            onProgress(i + 1, candidates.size, item.name)
        }
        IndexReport(candidates.size, indexed, unchanged, failed)
    }

    suspend fun search(query: String, limit: Int = 5): List<KnowledgeHit> = withContext(Dispatchers.IO) {
        val terms = Tokenizer.tokens(query).distinct().take(12)
        if (terms.isEmpty()) return@withContext emptyList()
        // FTS4 query: OR of quoted prefix terms. Quotes are stripped from tokens so input cannot alter syntax.
        val match = terms.joinToString(" OR ") { "\"${it.replace("\"", "")}\"*" }
        val candidates = try { dao.searchChunks(match, 80) } catch (e: Exception) { emptyList() }
        if (candidates.isEmpty()) return@withContext emptyList()
        val ranked = Bm25().rank(query, candidates.map { Bm25.Doc(it.id, it.text) }, limit)
        val byId = candidates.associateBy { it.id }
        val docs = dao.docs(candidates.map { it.docId }.distinct()).associateBy { it.id }
        ranked.mapNotNull { hit ->
            val c = byId[hit.id] ?: return@mapNotNull null
            val d = docs[c.docId] ?: return@mapNotNull null
            KnowledgeHit(d.name, d.path, c.text, hit.score)
        }
    }

    suspend fun chunkCount() = dao.chunkCount()
    suspend fun deleteDoc(id: Long) = dao.deleteDoc(id)
    suspend fun clear() = dao.clear()
}
