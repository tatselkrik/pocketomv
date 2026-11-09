package io.github.tatselkrik.statis130lite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun ExcelImportDialog(model: AnalysisViewModel) {
    val selection = model.worksheetImport ?: return
    val enabled = model.state.busy == null
    AlertDialog(
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.92f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = model::cancelWorksheetImport,
        title = { Text("Import Excel workbook") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(selection.name, style = MaterialTheme.typography.bodyMedium)
                ChoiceField("Worksheet", selection.selectedId,
                    selection.sheets.map { it.id to (it.name + if (it.hidden) " (hidden)" else "") },
                    enabled, model::chooseWorksheet)
                CheckOption("First row contains column names", selection.firstRowHasNames,
                    enabled, model::setWorksheetHeaders)
                Text("Import the selected worksheet's saved cell values. The original workbook stays unchanged.",
                    style = MaterialTheme.typography.bodySmall)
                selection.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                model.state.busy?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = model::importWorksheet, enabled = enabled) { Text("Import worksheet") }
        },
        dismissButton = {
            TextButton(onClick = model::cancelWorksheetImport, enabled = enabled) { Text("Cancel") }
        },
    )
}
