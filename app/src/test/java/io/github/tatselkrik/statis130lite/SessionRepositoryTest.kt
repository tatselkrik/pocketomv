package io.github.tatselkrik.statis130lite

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRepositoryTest {
    @Test
    fun newestEditsWinWhileAnOlderWriteIsStillRunning() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val storage = MemoryStorage()
        storage.beforeWrite = {
            if (it.screen.draft == "first") {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            val repo = SessionRepository(storage, scope)
            repo.restore()
            repo.save(session("first"), immediate = true)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repo.save(session("middle"), immediate = true)
            val latest = repo.save(session("latest"), immediate = true)
            release.countDown()
            withTimeout(5000) { repo.awaitSaved(latest) }
            assertEquals("latest", storage.saved.get()!!.screen.draft)
            assertEquals("latest", repo.restore().session!!.screen.draft)
            assertEquals(latest, repo.status.value.savedRevision)
            assertFalse(repo.status.value.failed)
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test
    fun anIoFailureKeepsThePreviousCommitAndRetryCanSaveTheCurrentWork() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val storage = MemoryStorage(session("previous"))
        try {
            val repo = SessionRepository(storage, scope)
            repo.restore()
            storage.beforeWrite = { throw IOException("Simulated full storage") }
            val revision = repo.save(session("current"), immediate = true)
            var failed = false
            try {
                withTimeout(5000) { repo.awaitSaved(revision) }
            } catch (_: IOException) {
                failed = true
            }
            assertTrue(failed)
            assertEquals("previous", storage.saved.get()!!.screen.draft)
            assertEquals("current", repo.restore().session!!.screen.draft)
            assertTrue(repo.status.value.failed)
            storage.beforeWrite = {}
            val retry = repo.save(session("current"), immediate = true)
            withTimeout(5000) { repo.awaitSaved(retry) }
            assertEquals("current", storage.saved.get()!!.screen.draft)
            assertFalse(repo.status.value.failed)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun unreadableStoredWorkIsPreservedBeforeNewWorkIsCommitted() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val storage = MemoryStorage()
        storage.readFailure = true
        try {
            val repo = SessionRepository(storage, scope)
            assertTrue(repo.restore().failed)
            val revision = repo.save(session("new work"), immediate = true)
            withTimeout(5000) { repo.awaitSaved(revision) }
            assertEquals(1, storage.recoveryCopies.get())
            assertEquals("new work", storage.saved.get()!!.screen.draft)
            val next = repo.save(session("newer work"), immediate = true)
            withTimeout(5000) { repo.awaitSaved(next) }
            assertEquals(1, storage.recoveryCopies.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun identicalSavedStateDoesNotCauseExtraDiskWrites() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val storage = MemoryStorage(session("existing"))
        try {
            val repo = SessionRepository(storage, scope)
            repo.restore()
            val revision = repo.save(session("existing"), immediate = true)
            withTimeout(5000) { repo.awaitSaved(revision) }
            assertEquals(0, storage.writes.size)
            assertTrue(repo.status.value.hasSavedWork)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun foregroundExitFlushesTheLatestDebouncedEdits() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val storage = MemoryStorage()
        try {
            val repo = SessionRepository(storage, scope)
            repo.restore()
            repo.save(session("typing"))
            repo.save(session("finished"))
            val flushed = repo.save(session("finished"), immediate = true)
            withTimeout(5000) { repo.awaitSaved(flushed) }
            assertEquals("finished", storage.saved.get()!!.screen.draft)
        } finally {
            scope.cancel()
        }
    }

    private fun session(text: String): SavedSession = SavedSession.capture(AnalysisScreenState(draft = text), null)

    private class MemoryStorage(initial: SavedSession? = null) : SessionStorage {
        val saved = AtomicReference(initial)
        val writes: MutableList<SavedSession> = Collections.synchronizedList(mutableListOf())
        val recoveryCopies = AtomicInteger()
        @Volatile var beforeWrite: (SavedSession) -> Unit = {}
        var readFailure = false

        override fun read(): SavedSession? {
            if (readFailure) throw IOException("Simulated unreadable session")
            return saved.get()
        }

        override fun write(session: SavedSession) {
            beforeWrite(session)
            writes.add(session)
            saved.set(session)
        }

        override fun preserveUnreadable() {
            recoveryCopies.incrementAndGet()
        }
    }
}
