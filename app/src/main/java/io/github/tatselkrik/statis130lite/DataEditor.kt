package io.github.tatselkrik.statis130lite

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CellDataEditor(model: AnalysisViewModel) {
    val state = model.state
    val data = state.data ?: return
    val enabled = state.busy == null && !state.hasUnappliedChanges
    val scope = rememberCoroutineScope()
    val vertical = rememberLazyListState()
    val horizontal = rememberScrollState()
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var row by rememberSaveable { mutableStateOf(0) }
    var column by rememberSaveable { mutableStateOf(0) }
    var goTo by rememberSaveable { mutableStateOf("1") }
    var jumpError by remember { mutableStateOf<String?>(null) }
    fun open(kind: String) { model.clearTableError(); dialog = kind }
    fun close() { if (model.state.busy == null) { dialog = null; model.clearTableError() } }
    fun apply(edit: TableEdit) { scope.launch { if (model.editTable(edit, data)) close() } }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Edit cells", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        CheckOption("Fictional practice data", state.fictional, enabled, model::setCellDataFictional)
        Text("Tap a cell to edit, a column name to rename or delete it, or a row number for row actions. Changes save automatically.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { scope.launch {
                if (model.editTable(TableEdit.AddRow(data.rows.lastIndex), data)) vertical.scrollToItem(data.rows.size)
            } }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Add row") }
            OutlinedButton(onClick = { open("addColumn") }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Add column") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(goTo, { goTo = it; jumpError = null }, Modifier.weight(1f),
                label = { Text("Go to row (1–${data.rows.size})") }, singleLine = true, enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            TextButton(onClick = {
                val target = goTo.toIntOrNull()
                if (target == null || target !in 1..data.rows.size) jumpError = "Choose a row from 1 to ${data.rows.size}."
                else scope.launch { vertical.scrollToItem(target - 1) }
            }, enabled = enabled) { Text("Go") }
        }
        jumpError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val cellHeight = if (LocalDensity.current.fontScale >= 1.3f) 88.dp else 64.dp
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val total = maxOf(maxWidth, 60.dp + 148.dp * data.columns.size)
            Column(Modifier.horizontalScroll(horizontal).width(total).border(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.background(MaterialTheme.colorScheme.primaryContainer)) {
                    GridCell("Row", "Row numbers", 60, 64, false) {}
                    data.columns.forEachIndexed { i, name ->
                        GridCell(name, "Column $name actions", 148, 64, enabled) { column = i; open("column") }
                    }
                }
                LazyColumn(Modifier.width(total).height(288.dp), state = vertical) {
                    itemsIndexed(data.rows) { i, cells -> Row {
                        GridCell((i + 1).toString(), "Row ${i + 1} actions", 60, cellHeight.value.toInt(), enabled) { row = i; open("row") }
                        cells.forEachIndexed { j, value ->
                            GridCell(value.ifBlank { "(blank)" }, "Edit row ${i + 1}, column ${data.columns[j]}", 148,
                                cellHeight.value.toInt(), enabled) { row = i; column = j; open("cell") }
                        }
                    } }
                }
            }
        }
        Text("${data.rows.size} rows · ${data.columns.size} columns. Swipe the grid sideways and scroll its rows.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { open("transform") }, enabled = enabled && data.columns.size < 60, modifier = Modifier.fillMaxWidth()) { Text("Recode / score") }
        if (data.columns.size == 60) Text("The table has 60 columns. Remove a column before adding a score.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = model::undoTableEdit, enabled = enabled && model.tableUndo != null,
            modifier = Modifier.fillMaxWidth()) { Text("Undo last table change") }
        Text("Undo keeps one previous table during this app session. Cell edits clear old analysis results.", style = MaterialTheme.typography.bodySmall)
        if (state.preparationNotes.isNotEmpty()) {
            Text("Scored columns contain saved values. Recreate scores after changing their inputs; they do not update automatically.",
                style = MaterialTheme.typography.bodyMedium)
            Text("Preparation notes", style = MaterialTheme.typography.titleSmall)
            state.preparationNotes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Button(onClick = { model.navigate(AppPage.ANALYSIS) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Analyze this table") }
    }
    when (dialog) {
        "cell" -> if (row in data.rows.indices && column in data.columns.indices) {
            var value by rememberSaveable(row, column) { mutableStateOf(data.rows[row][column]) }
            EditorDialog("Edit cell", enabled, "Save cell", { apply(TableEdit.Cell(row, column, value)) }, ::close, state.error) {
                Text("Row ${row + 1} · ${data.columns[column]}")
                OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), label = { Text("Cell value") }, enabled = enabled,
                    minLines = 2, maxLines = 6, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                TextButton(onClick = { value = "" }, enabled = enabled) { Text("Clear value") }
                Text("Blank cells are missing. Save cell applies this value; Cancel keeps the previous value.", style = MaterialTheme.typography.bodySmall)
            }
        }
        "addColumn", "column" -> {
            val selected = column.takeIf { it in data.columns.indices }
            var name by rememberSaveable(dialog, column) { mutableStateOf(if (dialog == "column" && selected != null) data.columns[selected] else "") }
            EditorDialog(if (dialog == "column") "Column actions" else "Add column", enabled,
                if (dialog == "column") "Rename column" else "Add column",
                { apply(if (dialog == "column") TableEdit.RenameColumn(column, name) else TableEdit.AddColumn(name)) }, ::close, state.error) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Column name") }, singleLine = true, enabled = enabled)
                if (dialog == "column") {
                    Text("Deleting removes this column's cells. Undo is available until this app session ends.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { apply(TableEdit.DeleteColumn(column)) }, enabled = enabled && data.columns.size > 1) { Text("Delete column") }
                } else Text("The new column starts with blank cells.", style = MaterialTheme.typography.bodySmall)
            }
        }
        "row" -> EditorDialog("Row ${row + 1} actions", enabled, "Insert row after", { apply(TableEdit.AddRow(row)) }, ::close, state.error) {
            Text("Insert a blank row after this one, or delete this row. Undo restores the last table change.")
            TextButton(onClick = { apply(TableEdit.DeleteRow(row)) }, enabled = enabled && data.rows.size > 1) { Text("Delete row") }
        }
        "transform" -> TransformDialog(model, data, ::close)
    }
}

@Composable
private fun GridCell(value: String, description: String, width: Int, height: Int, enabled: Boolean, onClick: () -> Unit) {
    val display = if (value.codePointCount(0, value.length) > 80) value.substring(0, value.offsetByCodePoints(0, 80)) + "…" else value
    Box(Modifier.width(width.dp).height(height.dp).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }.padding(8.dp), contentAlignment = Alignment.CenterStart) {
        Text(display, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EditorDialog(title: String, enabled: Boolean, action: String, onAction: () -> Unit, onDismiss: () -> Unit,
    error: String?, content: @Composable ColumnScope.() -> Unit) {
    Dialog(properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        onDismissRequest = { if (enabled) onDismiss() }) {
        val focus = LocalFocusManager.current
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(16.dp),
            contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(max = maxHeight),
                shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        content()
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Cancel") }
                        TextButton(onClick = { focus.clearFocus(); onAction() }, enabled = enabled, modifier = Modifier.weight(2f)) { Text(action) }
                    }
                }
            }
        }
    }
}

private val recodeRuleSaver = listSaver<List<RecodeRule>, String>(
    save = { rules -> rules.flatMap { listOf(it.from, it.to, it.upper) } },
    restore = { it.chunked(3).map { fields -> RecodeRule(fields[0], fields[1], fields[2]) } },
)

@Composable
private fun TransformDialog(model: AnalysisViewModel, data: CourseData, onDismiss: () -> Unit) {
    var kind by rememberSaveable { mutableStateOf("Reverse score") }
    var source by rememberSaveable { mutableStateOf(data.numericColumns().firstOrNull() ?: 0) }
    var name by rememberSaveable { mutableStateOf(reverseName(data.columns[source])) }
    var minimum by rememberSaveable { mutableStateOf("1") }
    var maximum by rememberSaveable { mutableStateOf("5") }
    var match by rememberSaveable { mutableStateOf(RecodeMatch.EXACT) }
    var rules by rememberSaveable(stateSaver = recodeRuleSaver) { mutableStateOf(listOf(RecodeRule())) }
    var keepUnmatched by rememberSaveable { mutableStateOf(true) }
    var items by rememberSaveable { mutableStateOf(emptyList<Int>()) }
    var score by rememberSaveable { mutableStateOf(ItemScore.SUM) }
    var missing by rememberSaveable { mutableStateOf(MissingItems.COMPLETE) }
    var minimumAnswered by rememberSaveable { mutableStateOf("1") }
    var prepared by remember { mutableStateOf<PreparedColumn?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var calculating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val enabled = model.state.busy == null && !calculating
    fun changed() { prepared = null; error = null; model.clearTableError() }
    fun previewOrApply() { scope.launch {
        val result = prepared
        if (result != null) {
            if (model.editTable(TableEdit.Derived(result), data)) onDismiss()
        } else {
            calculating = true
            error = null
            try {
                prepared = withContext(Dispatchers.Default) {
                    when (kind) {
                        "Reverse score" -> DataEditing.reverse(data, source, name, minimum, maximum)
                        "Recode" -> DataEditing.recode(data, source, name, match, rules, keepUnmatched)
                        else -> DataEditing.score(data, items, name, score, missing,
                            if (missing == MissingItems.COMPLETE) 1 else minimumAnswered.toIntOrNull()
                                ?: throw IllegalArgumentException("Enter a whole minimum answered count."))
                    }
                }
            } catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                error = if (problem is IllegalArgumentException) problem.message else "This column could not be calculated. Review its values."
            } finally { calculating = false }
        }
    } }
    EditorDialog("Recode / score", enabled, if (prepared == null) "Preview column" else "Add scored column", ::previewOrApply,
        onDismiss, error ?: model.state.error) {
        if (prepared == null) {
        ChoiceField("Operation", kind, listOf("Reverse score", "Recode", "Item score").map { it to it }, enabled) {
            kind = it; name = when (it) { "Reverse score" -> reverseName(data.columns[source]); "Recode" -> "Recoded"; else -> "Total" }; changed()
        }
        OutlinedTextField(name, { name = it; changed() }, Modifier.fillMaxWidth(), label = { Text("New column name") }, singleLine = true, enabled = enabled)
        if (kind != "Item score") ChoiceField("Source column", source, data.columns.mapIndexed { i, label -> i to label }, enabled) {
            if (kind == "Reverse score" && name == reverseName(data.columns[source])) name = reverseName(data.columns[it])
            source = it; changed()
        }
        when (kind) {
            "Reverse score" -> {
                Text("For a 1–5 scale, 1 becomes 5, 2 becomes 4, and 3 stays 3. Use the scale's stated bounds, not the observed minimum and maximum.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(minimum, { minimum = it; changed() }, Modifier.fillMaxWidth(), label = { Text("Scale minimum") }, singleLine = true, enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false))
                OutlinedTextField(maximum, { maximum = it; changed() }, Modifier.fillMaxWidth(), label = { Text("Scale maximum") }, singleLine = true, enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false))
            }
            "Recode" -> {
                ChoiceField("Match values", match, RecodeMatch.entries.map { it to it.label }, enabled) { match = it; changed() }
                Text(if (match == RecodeMatch.EXACT) "Exact text is case sensitive: 1 and 1.0 are different. Blank cells remain missing; literal NA can be recoded as text."
                    else "Bounds include both endpoints. Leave one bound blank for an open-ended range. First matching rule wins; numeric NA values stay missing.", style = MaterialTheme.typography.bodySmall)
                rules.forEachIndexed { i, rule ->
                    Text("Rule ${i + 1}", style = MaterialTheme.typography.titleSmall)
                    fun replace(next: RecodeRule) { rules = rules.mapIndexed { j, old -> if (i == j) next else old }; changed() }
                    OutlinedTextField(rule.from, { replace(rule.copy(from = it)) }, Modifier.fillMaxWidth(), label = { Text(if (match == RecodeMatch.EXACT) "Match text ${i + 1}" else "Lower bound ${i + 1}") }, enabled = enabled)
                    if (match == RecodeMatch.RANGE) OutlinedTextField(rule.upper, { replace(rule.copy(upper = it)) }, Modifier.fillMaxWidth(), label = { Text("Upper bound ${i + 1}") }, enabled = enabled)
                    OutlinedTextField(rule.to, { replace(rule.copy(to = it)) }, Modifier.fillMaxWidth(), label = { Text("New value ${i + 1} (blank = missing)") }, enabled = enabled)
                    if (rules.size > 1) TextButton(onClick = { rules = rules.filterIndexed { j, _ -> i != j }; changed() }, enabled = enabled) { Text("Remove rule ${i + 1}") }
                }
                OutlinedButton(onClick = { rules = rules + RecodeRule(); changed() }, enabled = enabled && rules.size < 50) { Text("Add rule") }
                ChoiceField("Unmatched values", keepUnmatched, listOf(true to "Keep original values", false to "Set to missing"), enabled) { keepUnmatched = it; changed() }
            }
            else -> {
                ChoiceField("Score calculation", score, ItemScore.entries.map { it to it.label }, enabled) { score = it; changed() }
                Text("Select item columns. Reverse-score negatively keyed items first, then select their reversed columns instead of the originals.", style = MaterialTheme.typography.bodySmall)
                data.columns.forEachIndexed { i, label -> CheckOption(label, i in items, enabled) {
                    items = if (it) items + i else items - i; changed()
                } }
                ChoiceField("Missing answers", missing, MissingItems.entries.map { it to it.label }, enabled) { missing = it; changed() }
                if (missing == MissingItems.AVAILABLE) OutlinedTextField(minimumAnswered, { minimumAnswered = it; changed() }, Modifier.fillMaxWidth(),
                    label = { Text("Minimum answered items") }, enabled = enabled, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text(if (score == ItemScore.SUM && missing == MissingItems.AVAILABLE) "Partial sums are not prorated; use them only when the scoring instructions permit it."
                    else "Use the missing-answer rule specified by your questionnaire or assignment.", style = MaterialTheme.typography.bodySmall)
            }
        }
        }
        Text("Adds a separate column of saved values. Source columns stay intact. Editing inputs later does not update scores; recreate them to rescore.", style = MaterialTheme.typography.bodySmall)
        if (calculating) Text("Calculating preview…")
        prepared?.let { result ->
            Text("Preview · ${result.name}", style = MaterialTheme.typography.titleMedium)
            Text(result.explanation)
            Text("${result.values.size - result.missingCount} nonblank values · ${result.missingCount} missing")
            Text(result.details, style = MaterialTheme.typography.bodySmall)
            result.values.indices.take(8).forEach { i ->
                Text("Row ${i + 1}: ${result.inputs[i]} → ${result.values[i].ifBlank { "(missing)" }}", style = MaterialTheme.typography.bodySmall)
            }
            if (result.values.size > 8) Text("Showing the first 8 rows of ${result.values.size}.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { prepared = null; error = null; model.clearTableError() }, enabled = enabled) { Text("Edit options") }
        }
    }
}

private fun reverseName(source: String): String =
    (if (source.codePointCount(0, source.length) <= 48) source else source.substring(0, source.offsetByCodePoints(0, 48))) + "_R"
