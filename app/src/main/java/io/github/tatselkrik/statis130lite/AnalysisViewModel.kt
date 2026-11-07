package io.github.tatselkrik.statis130lite

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppPage(val label: String, val step: String) {
    DATA("Data", "1"),
    ANALYSIS("Analyze", "2"),
    RESULTS("Results", "3"),
}

data class AnalysisScreenState(
    val page: AppPage = AppPage.DATA,
    val draft: String = "",
    val firstRowHasNames: Boolean = true,
    val appliedDraft: String = "",
    val appliedNames: Boolean = true,
    val data: CourseData? = null,
    val source: String = "",
    val fictional: Boolean = false,
    val draftFictional: Boolean = false,
    val request: AnalysisRequest = AnalysisRequest(),
    val result: AnalysisResult? = null,
    val busy: String? = null,
    val error: String? = null,
    val message: String? = null,
) {
    val hasUnappliedChanges: Boolean get() =
        draft != appliedDraft || firstRowHasNames != appliedNames || draftFictional != fictional
}

class AnalysisViewModel(application: Application) : AndroidViewModel(application) {
    private val sessions = (application as Statis130Application).sessions
    val saveStatus = sessions.status
    private val ready = CompletableDeferred<Unit>()
    var state by mutableStateOf(AnalysisScreenState(busy = "Restoring saved work…"))
        private set
    private var pendingExport: ExportKind? = null
    private var unreadableWorkUntouched = false

    init {
        viewModelScope.launch {
            try {
                val restored = sessions.restore()
                val session = restored.session
                if (session != null) {
                    pendingExport = session.pendingExport
                    state = try {
                        withContext(Dispatchers.Default) { session.restoreScreen() }
                    } catch (_: IllegalArgumentException) {
                        if (pendingExport == ExportKind.REPORT) pendingExport = null
                        session.screen.copy(
                            page = AppPage.ANALYSIS,
                            error = "Your table and settings were restored. Run the analysis again to update the results.",
                        )
                    }
                } else if (restored.failed) {
                    unreadableWorkUntouched = true
                    state = AnalysisScreenState(
                        error = "Saved work could not be restored. Open a saved CSV or enter a new table to continue.",
                    )
                }
            } finally {
                state = state.copy(busy = null)
                ready.complete(Unit)
            }
        }
    }

    fun navigate(page: AppPage) {
        if (state.busy != null) return
        state = state.copy(page = page, error = null)
        persist()
    }

    fun editDraft(value: String) {
        if (state.busy != null) return
        if (value.length > 2_000_000) {
            state = state.copy(error = "Use a table smaller than 2 MB.")
            return
        }
        pendingExport = null
        state = state.copy(draft = value, result = null, error = null)
        persist()
    }

    fun setHeaders(value: Boolean) {
        if (state.busy != null) return
        pendingExport = null
        state = state.copy(firstRowHasNames = value, result = null, error = null)
        persist()
    }

    fun setFictional(value: Boolean) {
        if (state.busy != null) return
        pendingExport = null
        state = state.copy(draftFictional = value, result = null, error = null)
        persist()
    }

    fun selectMethod(method: AnalysisMethod) {
        if (state.busy != null) return
        pendingExport = null
        state = state.copy(
            request = AnalysisRequest.forData(method, state.data),
            result = null,
            error = null,
        )
        persist()
    }

    fun configure(request: AnalysisRequest) {
        if (state.busy != null) return
        pendingExport = null
        state = state.copy(request = request, result = null, error = null)
        persist()
    }

    fun applyDraft() {
        if (state.busy != null) return
        val text = state.draft
        val names = state.firstRowHasNames
        val fictional = state.draftFictional
        viewModelScope.launch {
            state = state.copy(busy = "Reading data…", error = null)
            try {
                val data = withContext(Dispatchers.Default) { parseDelimited(text, names) }
                acceptData(data, text, names, if (fictional) "Practice table" else "Entered data", fictional)
            } catch (error: Exception) {
                fail(error, "The table could not be read. Check its rows and separators.")
            }
        }
    }

    fun importData(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            ready.await()
            if (state.busy != null) return@launch
            val names = state.firstRowHasNames
            state = state.copy(busy = "Opening data…", error = null)
            try {
                val (data, name) = withContext(Dispatchers.IO) {
                    val source = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: "Imported data"
                    require(!source.endsWith(".xlsx", true) && !source.endsWith(".xls", true)) {
                        "Open a CSV or TSV file. Save Excel data as CSV before importing."
                    }
                    val table = resolver.openInputStream(uri)?.use { readDelimited(it, names) }
                        ?: error("The selected file could not be opened.")
                    table to source
                }
                acceptData(data, data.asCsv(), true, name, fictional = false)
            } catch (error: Exception) {
                fail(error, "The file could not be opened. Choose a local CSV or TSV.")
            }
        }
    }

    fun loadExample() {
        if (state.busy != null) return
        val method = state.request.method
        val data = fictionalExample(method)
        acceptData(data, data.asCsv(), true, "Example · ${method.title}", fictional = true)
        if (method == AnalysisMethod.ONE_SAMPLE_T) {
            state = state.copy(request = state.request.copy(reference = "8"))
            persist()
        }
    }

    fun calculate() {
        val data = state.data ?: return
        if (state.busy != null || state.hasUnappliedChanges) return
        val request = state.request
        viewModelScope.launch {
            state = state.copy(busy = "Calculating…", error = null)
            try {
                val result = withContext(Dispatchers.Default) { Statistics.run(data, request) }
                state = state.copy(busy = null, result = result, page = AppPage.RESULTS)
                persist()
            } catch (error: Exception) {
                fail(error, "The calculation could not be completed. Check the selected data and options.")
            }
        }
    }

    suspend fun prepareExport(kind: ExportKind): Boolean {
        ready.await()
        if (state.busy != null || state.data == null ||
            (kind == ExportKind.REPORT && state.result == null)
        ) return false
        pendingExport = kind
        state = state.copy(busy = "Preparing export…")
        val revision = persist(immediate = true)
        try {
            if (revision != null) sessions.awaitSaved(revision)
        } catch (_: java.io.IOException) {
            // Manual export remains available when the private autosave cannot be written.
        } finally {
            state = state.copy(busy = null)
        }
        return true
    }

    fun finishExport(resolver: ContentResolver, uri: Uri?) {
        viewModelScope.launch {
            ready.await()
            val kind = pendingExport
            pendingExport = null
            persist(immediate = true)
            if (uri != null && kind != null) saveExport(resolver, uri, kind)
        }
    }

    private fun saveExport(resolver: ContentResolver, uri: Uri, kind: ExportKind) {
        if (state.busy != null) return
        val snapshot = state
        viewModelScope.launch {
            state = state.copy(busy = "Saving…", error = null)
            try {
                withContext(Dispatchers.IO) {
                    val text = when (kind) {
                        ExportKind.DATA -> requireNotNull(snapshot.data) { "Open a table before exporting." }.asCsv()
                        ExportKind.REPORT -> requireNotNull(snapshot.result) {
                            "Run the analysis before exporting its report."
                        }.asReport(snapshot.source, snapshot.fictional)
                    }
                    val stream = resolver.openOutputStream(uri, "wt") ?: error("The selected file could not be opened.")
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                }
                state = state.copy(busy = null, message = "File saved")
            } catch (error: Exception) {
                fail(error, "The file could not be saved. Choose a location you can write to.")
            }
        }
    }

    fun clearMessage() {
        state = state.copy(message = null)
    }

    fun saveBeforeBackground() {
        persist(immediate = true)
    }

    fun retryAutosave() {
        persist(immediate = true)
    }

    private fun persist(immediate: Boolean = false): Long? {
        if (!ready.isCompleted) return null
        if (unreadableWorkUntouched && state.data == null && state.draft.isBlank()) return null
        unreadableWorkUntouched = false
        return sessions.save(SavedSession.capture(state, pendingExport), immediate)
    }

    private fun acceptData(data: CourseData, text: String, names: Boolean, source: String, fictional: Boolean) {
        pendingExport = null
        state = state.copy(
            data = data,
            draft = text,
            appliedDraft = text,
            firstRowHasNames = names,
            appliedNames = names,
            source = source,
            fictional = fictional,
            draftFictional = fictional,
            request = AnalysisRequest.forData(state.request.method, data),
            result = null,
            error = null,
            busy = null,
            page = AppPage.ANALYSIS,
        )
        persist()
    }

    private fun fail(error: Exception, fallback: String) {
        if (error is CancellationException) throw error
        state = state.copy(
            busy = null,
            error = if (error is IllegalArgumentException) error.message ?: fallback else fallback,
        )
    }
}
