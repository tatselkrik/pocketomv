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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun OmvImportDialog(model: AnalysisViewModel) {
    val (name, project) = model.projectImport ?: return
    var selected by remember(project) { mutableIntStateOf(0) }
    AlertDialog(
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.92f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = model::cancelProjectImport,
        title = { Text("Open jamovi project") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(name, style = MaterialTheme.typography.bodyMedium)
                ChoiceField("Analysis to load", selected, project.analyses.mapIndexed { index, request ->
                    index to request.projectLabel(project.data)
                } + (-1 to "Data only"), true) { selected = it }
                Text("pocketomv opens one analysis at a time.", style = MaterialTheme.typography.bodySmall)
                project.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { model.chooseProjectAnalysis(selected.takeIf { it >= 0 }) }) { Text("Open project") }
        },
        dismissButton = { TextButton(onClick = model::cancelProjectImport) { Text("Cancel") } },
    )
}

private fun AnalysisRequest.projectLabel(data: CourseData): String {
    val suffix = when (method) {
        AnalysisMethod.INDEPENDENT_T, AnalysisMethod.ANOVA -> if (equalVariance) " · Equal variances" else " · Welch"
        AnalysisMethod.CORRELATION_MATRIX -> " · ${correlationKind.label}"
        else -> ""
    }
    val variables = when {
        method == AnalysisMethod.CORRELATION_MATRIX -> correlationVariables
        method.usesItemSelection -> items
        method == AnalysisMethod.DESCRIPTIVES -> descriptiveVariables ?: listOf(first)
        method.secondLabel != null -> listOf(first, second)
        else -> listOf(first)
    }
    val names = variables.take(2).mapNotNull(data.columns::getOrNull).joinToString(", ")
    return method.title + suffix + if (names.isEmpty()) "" else " · $names"
}
