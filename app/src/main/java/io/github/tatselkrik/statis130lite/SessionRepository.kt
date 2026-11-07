package io.github.tatselkrik.statis130lite

import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal interface SessionStorage {
    fun read(): SavedSession?
    fun write(session: SavedSession)
    fun preserveUnreadable()
}

internal class AtomicSessionStorage(private val file: File) : SessionStorage {
    private val atomic = AtomicFile(file)

    override fun read(): SavedSession? {
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        val bytes = atomic.openRead().use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= SessionCodec.MAX_BYTES) { "The saved session is too large." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return SessionCodec.decode(bytes)
    }

    override fun write(session: SavedSession) {
        val bytes = SessionCodec.encode(session)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    override fun preserveUnreadable() {
        if (!file.exists()) return
        val copy = File.createTempFile("session-recovery-", ".json", file.parentFile)
        try {
            FileOutputStream(copy).use { output ->
                file.inputStream().use { it.copyTo(output) }
                output.fd.sync()
            }
        } catch (error: Exception) {
            copy.delete()
            throw error
        }
    }
}

data class SessionSaveStatus(
    val revision: Long = 0,
    val savedRevision: Long = 0,
    val hasSavedWork: Boolean = false,
    val failed: Boolean = false,
) {
    val saving: Boolean get() = revision > savedRevision && !failed
}

internal data class SessionRestore(val session: SavedSession?, val failed: Boolean)

/** One writer for the application lifetime, including after an activity or ViewModel closes. */
@OptIn(FlowPreview::class)
internal class SessionRepository(
    private val storage: SessionStorage,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private data class Save(val revision: Long, val session: SavedSession, val immediate: Boolean = false)
    private val changes = Channel<Save>(Channel.CONFLATED)
    private val mutableStatus = MutableStateFlow(SessionSaveStatus())
    val status = mutableStatus.asStateFlow()
    @Volatile private var latest: Save? = null
    private val initial = scope.async {
        val restored = try {
            SessionRestore(storage.read(), failed = false)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            SessionRestore(null, failed = true)
        }
        synchronized(this@SessionRepository) {
            if (latest == null && restored.session != null) {
                latest = Save(0, restored.session)
                mutableStatus.value = SessionSaveStatus(hasSavedWork = true)
            }
        }
        restored
    }

    init {
        scope.launch {
            var needsRecoveryCopy = initial.await().failed
            changes.receiveAsFlow().debounce { if (it.immediate) 0L else 200L }.collect { save ->
                if (save.revision <= mutableStatus.value.savedRevision) return@collect
                try {
                    if (needsRecoveryCopy) {
                        storage.preserveUnreadable()
                        needsRecoveryCopy = false
                    }
                    storage.write(save.session)
                    complete(save, failed = false)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    complete(save, failed = true)
                }
            }
        }
    }

    suspend fun restore(): SessionRestore {
        val restored = initial.await()
        return latest?.let { SessionRestore(it.session, failed = false) } ?: restored
    }

    @Synchronized
    fun save(session: SavedSession, immediate: Boolean = false): Long {
        val previous = latest
        if (previous?.session == session && !mutableStatus.value.failed) {
            if (immediate && mutableStatus.value.saving) changes.trySend(previous.copy(immediate = true))
            return previous.revision
        }
        val revision = (previous?.revision ?: 0) + 1
        val save = Save(revision, session, immediate)
        latest = save
        mutableStatus.value = mutableStatus.value.copy(revision = revision, failed = false)
        check(changes.trySend(save).isSuccess)
        return revision
    }

    suspend fun awaitSaved(revision: Long) {
        val completed = status.first { it.savedRevision >= revision || (it.revision >= revision && it.failed) }
        if (completed.savedRevision < revision) throw IOException("Autosave could not complete.")
    }

    @Synchronized
    private fun complete(save: Save, failed: Boolean) {
        val current = mutableStatus.value
        mutableStatus.value = current.copy(
            savedRevision = if (failed) current.savedRevision else maxOf(current.savedRevision, save.revision),
            hasSavedWork = current.hasSavedWork || !failed,
            failed = failed && current.revision == save.revision,
        )
    }
}
