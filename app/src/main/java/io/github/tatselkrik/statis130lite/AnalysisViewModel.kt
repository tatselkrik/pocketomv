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
import kotlinx.coroutines.async
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
    val dataEditor: DataEditorView = DataEditorView.TEXT,
    val preparationNotes: List<String> = emptyList(),
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
    private var pendingWorkbook: ExcelWorkbook? = null
    internal var worksheetImport by mutableStateOf<WorksheetImportState?>(null)
        private set
    private var unreadableWorkUntouched = false
    internal var tableUndo by mutableStateOf<AnalysisScreenState?>(null)
        private set

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
                        if (pendingExport?.requiresResult == true) pendingExport = null
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
        tableUndo = null
        state = state.copy(draft = value, result = null, error = null)
        persist()
    }

    fun setHeaders(value: Boolean) {
        if (state.busy != null) return
        pendingExport = null
        tableUndo = null
        state = state.copy(firstRowHasNames = value, result = null, error = null)
        persist()
    }

    fun setFictional(value: Boolean) {
        if (state.busy != null) return
        pendingExport = null
        tableUndo = null
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

    internal fun selectQQSample(label: String) {
        if (state.busy != null || state.result?.plots?.qq?.none { it.label == label } != false) return
        state = state.copy(request = state.request.copy(qqSample = label))
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

    internal fun selectDataEditor(view: DataEditorView) {
        if (state.busy != null) return
        if (view == DataEditorView.CELLS && state.hasUnappliedChanges) {
            state = state.copy(error = "Use data to apply your text edits before opening cells. Your unfinished text is still here.")
            return
        }
        state = state.copy(dataEditor = view, error = null)
        persist()
    }

    internal fun clearTableError() {
        if (state.busy == null) state = state.copy(error = null)
    }

    internal fun setCellDataFictional(value: Boolean) {
        if (state.busy != null || state.hasUnappliedChanges) return
        state = state.copy(fictional = value, draftFictional = value)
        persist()
    }

    internal fun newTable() {
        if (state.busy != null || state.data != null || state.draft.isNotBlank()) return
        val data = CourseData(listOf("Column 1"), listOf(listOf("")))
        acceptData(data, data.asCsv(), true, "Entered data", fictional = false)
        state = state.copy(page = AppPage.DATA, dataEditor = DataEditorView.CELLS)
        persist()
    }

    internal suspend fun editTable(edit: TableEdit, base: CourseData? = state.data): Boolean =
        // A rotating or closed dialog must not cancel an accepted table update.
        viewModelScope.async { performTableEdit(edit, base) }.await()

    private suspend fun performTableEdit(edit: TableEdit, base: CourseData?): Boolean {
        val before = state
        if (before.busy != null) return false
        state = before.copy(busy = "Updating table…", error = null)
        return try {
            require(base != null && base == before.data) { "The table changed. Close this dialog and try again." }
            require(!before.hasUnappliedChanges) { "Use data to apply your text edits before editing cells or scores." }
            val change = withContext(Dispatchers.Default) { DataEditing.apply(base, edit) }
            if (change.data == base) {
                state = before.copy(error = null)
            } else {
                val updated = before.withTableChange(change)
                tableUndo = before
                pendingExport = null
                state = updated.copy(message = if (change.note != null) "Added ${change.data.columns.last()}"
                    else if (updated.preparationNotes.isNotEmpty()) "Table updated. Rescore saved score columns if their inputs changed."
                    else "Table updated")
                persist()
            }
            true
        } catch (error: Exception) {
            fail(error, "The table could not be changed. Your previous data is preserved.")
            false
        }
    }

    internal fun undoTableEdit() {
        if (state.busy != null || state.hasUnappliedChanges) return
        val previous = tableUndo ?: return
        pendingExport = null
        tableUndo = null
        state = previous.copy(page = AppPage.DATA, dataEditor = DataEditorView.CELLS,
            error = null, busy = null, message = "Undid the last table change")
        persist()
    }

    fun importData(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            ready.await()
            if (state.busy != null) return@launch
            val names = state.firstRowHasNames
            state = state.copy(busy = "Opening data…", error = null)
            try {
                val (opened, name) = withContext(Dispatchers.IO) {
                    val source = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: "Imported data"
                    val imported = resolver.openInputStream(uri)?.use { readDataImport(it, source, resolver.getType(uri), names) }
                        ?: error("The selected file could not be opened.")
                    imported to source
                }
                when (opened) {
                    is DataImport.Table -> acceptData(opened.data, opened.data.asCsv(), true, name, fictional = false)
                    is DataImport.Workbook -> {
                        pendingWorkbook = opened.workbook
                        worksheetImport = WorksheetImportState(name, opened.workbook.sheets,
                            opened.workbook.sheets.firstOrNull { !it.hidden }?.id ?: opened.workbook.sheets.first().id, names)
                        state = state.copy(busy = null)
                    }
                }
            } catch (error: Exception) {
                fail(error, "The file could not be opened. Choose a local Excel .xlsx, CSV or TSV file.")
            }
        }
    }

    internal fun chooseWorksheet(id: String) {
        if (state.busy != null) return
        worksheetImport?.let { selection ->
            if (selection.sheets.any { it.id == id }) worksheetImport = selection.copy(selectedId = id, error = null)
        }
    }

    internal fun setWorksheetHeaders(headers: Boolean) {
        if (state.busy == null) worksheetImport = worksheetImport?.copy(firstRowHasNames = headers, error = null)
    }

    internal fun cancelWorksheetImport() {
        if (state.busy != null) return
        worksheetImport = null
        pendingWorkbook = null
    }

    internal fun importWorksheet() {
        if (state.busy != null) return
        val selection = worksheetImport ?: return
        val workbook = pendingWorkbook ?: return
        viewModelScope.launch {
            state = state.copy(busy = "Reading worksheet…", error = null)
            worksheetImport = selection.copy(error = null)
            try {
                val (imported, text) = withContext(Dispatchers.Default) {
                    val table = workbook.readSheet(selection.selectedId, selection.firstRowHasNames)
                    val draft = table.data.asCsv()
                    require(draft.length <= 2_000_000) { "This worksheet is too large to edit. Import a smaller table." }
                    table to draft
                }
                val sheet = selection.sheets.single { it.id == selection.selectedId }
                acceptData(imported.data, text, true, "${selection.name} · ${sheet.name}", fictional = false)
                worksheetImport = null
                pendingWorkbook = null
                if (imported.formulaCount > 0) {
                    state = state.copy(message = "Imported saved formula values. Formulas were not recalculated.")
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                state = state.copy(busy = null)
                worksheetImport = selection.copy(error = if (error is IllegalArgumentException) error.message
                    else "This worksheet could not be read. Choose another worksheet or cancel.")
            }
        }
    }

    fun loadExample() {
        if (state.busy != null) return
        val method = state.request.method
        val data = fictionalExample(method, state.request.descriptiveNumeric)
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
            (kind.requiresResult && state.result == null)
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
                    val bytes = when (kind) {
                        ExportKind.DATA -> requireNotNull(snapshot.data) { "Open a table before exporting." }.asCsv().toByteArray(Charsets.UTF_8)
                        ExportKind.REPORT -> requireNotNull(snapshot.result) {
                            "Run the analysis before exporting its report."
                        }.asReport(snapshot.source, snapshot.fictional, snapshot.preparationNotes).toByteArray(Charsets.UTF_8)
                        ExportKind.HTML, ExportKind.PDF -> {
                            val result = requireNotNull(snapshot.result) { "Run the analysis before exporting its report." }
                            val report = result.formattedReport(snapshot.source, snapshot.fictional, snapshot.preparationNotes)
                            if (kind == ExportKind.HTML) HtmlReport.render(report).toByteArray(Charsets.UTF_8) else PdfReport.render(report)
                        }
                    }
                    require(bytes.size <= 32 * 1024 * 1024) { "The report exceeds 32 MB. Export a smaller dataset." }
                    val stream = resolver.openOutputStream(uri, "wt") ?: error("The selected file could not be opened.")
                    stream.use { it.write(bytes) }
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
        tableUndo = null
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
            preparationNotes = emptyList(),
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
