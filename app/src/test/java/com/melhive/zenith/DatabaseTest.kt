package com.melhive.zenith

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.melhive.zenith.database.KnowledgeDocEntity
import com.melhive.zenith.database.ZenithDatabase
import com.melhive.zenith.knowledge.KnowledgeRepository
import com.melhive.zenith.memory.ConversationRepository
import com.melhive.zenith.memory.MemoryRepository
import com.melhive.zenith.memory.TodoRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DatabaseTest {
    private lateinit var db: ZenithDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ZenithDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun tearDown() = db.close()

    @Test fun conversationsPersistAndCascade() = runBlocking<Unit> {
        val repo = ConversationRepository(db)
        val id = repo.create()
        repo.addMessage(id, "user", "Plan my trip to Kyoto")
        repo.addMessage(id, "assistant", "Sure — when are you going?")
        repo.addMessage(id, "assistant", "partial", status = "streaming")
        assertEquals("Plan my trip to Kyoto", db.conversations().get(id)!!.title)
        val history = repo.history(id)
        assertEquals(2, history.size) // streaming partial excluded
        assertEquals(1, repo.search("kyoto").size)
        assertEquals(0, repo.search("100%").size) // wildcard escaped
        repo.recoverInterrupted()
        assertEquals("cancelled", repo.messages(id).first().last().status)
        repo.delete(id)
        assertEquals(0, db.messages().count())
    }

    @Test fun memoryIsDeduplicatedAndForgettable() = runBlocking<Unit> {
        val repo = MemoryRepository(db)
        repo.remember("My sister is Ana", "user_command")
        repo.remember("my sister is ana", "user_command")
        assertEquals(1, repo.list().size)
        val found = repo.find("sister")
        repo.delete(found.map { it.id })
        assertTrue(repo.list().isEmpty())
    }

    @Test fun todoCompletionHandlesAmbiguity() = runBlocking<Unit> {
        val repo = TodoRepository(db)
        repo.add("buy milk", null)
        repo.add("buy milk chocolate", null)
        assertIs<TodoRepository.CompleteResult.Done>(repo.complete("buy milk")) // exact title wins
        assertIs<TodoRepository.CompleteResult.Done>(repo.complete("chocolate"))
        assertIs<TodoRepository.CompleteResult.NotFound>(repo.complete("bread"))
        repo.add("call Ana", null); repo.add("call Ben", null)
        assertIs<TodoRepository.CompleteResult.Ambiguous>(repo.complete("call"))
    }

    @Test fun knowledgeFtsSearch() = runBlocking<Unit> {
        val dao = db.knowledge()
        dao.replaceDoc(null, KnowledgeDocEntity(treeUri = "t", path = "notes/invoices.txt", name = "invoices.txt", sizeBytes = 10, modifiedAt = 1, indexedAt = 1, chunkCount = 2),
            listOf("Invoice 42 from the electricity company was paid in March.", "Unrelated grocery list: apples, bread."))
        dao.replaceDoc(null, KnowledgeDocEntity(treeUri = "t", path = "recipes.md", name = "recipes.md", sizeBytes = 10, modifiedAt = 1, indexedAt = 1, chunkCount = 1),
            listOf("Banana bread recipe with walnuts."))
        assertEquals(3, dao.chunkCount())
        val hits = dao.searchChunks("\"invoice\"*")
        assertEquals(1, hits.size)
        // Repository ranking path (files are not needed for search).
        val repo = KnowledgeRepository(dao, com.melhive.zenith.files.SafFileSystem(ApplicationProvider.getApplicationContext(), db.folders()))
        val ranked = repo.search("electricity invoice")
        assertEquals("notes/invoices.txt", ranked.first().docPath)
        // Re-indexing a doc replaces its chunks.
        val existing = dao.findDoc("t", "recipes.md")!!
        dao.replaceDoc(existing.id, existing.copy(id = 0, chunkCount = 1), listOf("Pancakes."))
        assertTrue(repo.search("banana").isEmpty())
    }
}
