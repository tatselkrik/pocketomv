package io.github.tatselkrik.statis130lite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun AdvancedOptions(data: CourseData, request: AnalysisRequest, enabled: Boolean, onChange: (AnalysisRequest) -> Unit) {
    when (request.method) {
        AnalysisMethod.FACTORIAL_ANOVA, AnalysisMethod.ANCOVA -> FactorialOptions(data, request, enabled, onChange)
        AnalysisMethod.EFA -> FactorAnalysisOptions(data, request, enabled, onChange)
        AnalysisMethod.CHI_INDEPENDENCE, AnalysisMethod.FISHER_EXACT -> CategoricalOptions(data, request, enabled, onChange)
        AnalysisMethod.RELIABILITY -> {
            ColumnSelection("Items", data, request.items, data.columns.indices.toList(), 60,
                AnalysisRequest.forData(request.method, data).items, enabled) { onChange(request.copy(items = it)) }
            Text("Reverse-score negatively keyed items in Data first. Reliability uses complete participants across all selected items.", style = MaterialTheme.typography.bodySmall)
            CheckOption("Inter-item correlation table", request.showItemCorrelations, enabled) { onChange(request.copy(showItemCorrelations = it)) }
        }
        AnalysisMethod.MULTIPLE_REGRESSION -> {
            val suggested = AnalysisRequest.forData(request.method, data)
            ColumnSelection("Predictors", data, request.predictors, data.columns.indices.filter { it != request.first }, 12,
                (suggested.predictors + suggested.first).filter { it != request.first }.distinct().sorted().take(12), enabled) {
                onChange(request.copy(predictors = it))
            }
            Text("Enter numeric predictors together. Recode categorical predictors into indicator columns with a reference category first. Constant or redundant predictors cannot be fitted.", style = MaterialTheme.typography.bodySmall)
            CheckOption("Residual Shapiro–Wilk check", request.checkNormality, enabled) { onChange(request.copy(checkNormality = it)) }
        }
        AnalysisMethod.REPEATED_ANOVA -> {
            ColumnSelection("Conditions", data, request.items, data.columns.indices.toList(), 12,
                AnalysisRequest.forData(request.method, data).items, enabled) { onChange(request.copy(items = it)) }
            Text("One row per participant and one column per condition, in column order. Leave the participant ID unselected. Supports one within-subject factor.", style = MaterialTheme.typography.bodySmall)
            ChoiceField("Degrees-of-freedom correction", request.repeatedCorrection, RepeatedCorrection.entries.map { it to it.label }, enabled) {
                onChange(request.copy(repeatedCorrection = it))
            }
            CheckOption("Paired follow-up comparisons", request.repeatedComparisons, enabled) { onChange(request.copy(repeatedComparisons = it)) }
            Text("Follow-ups use Holm-adjusted p-values and Bonferroni family intervals, with the same complete participants as the main test.", style = MaterialTheme.typography.bodySmall)
        }
        AnalysisMethod.FRIEDMAN -> {
            ColumnSelection("Conditions", data, request.items, data.columns.indices.toList(), 12,
                AnalysisRequest.forData(request.method, data).items, enabled) { onChange(request.copy(items = it)) }
            Text("Select 3–12 numeric conditions, one column per condition and one row per participant. Leave ID unselected. Every result uses the same complete participants.", style = MaterialTheme.typography.bodySmall)
            CheckOption("Durbin–Conover follow-up comparisons", request.friedmanComparisons, enabled) {
                onChange(request.copy(friedmanComparisons = it))
            }
            if (request.friedmanComparisons) ChoiceField("Follow-up p-value adjustment", request.friedmanAdjustment,
                FriedmanAdjustment.entries.map { it to it.label }, enabled) { onChange(request.copy(friedmanAdjustment = it)) }
            Text("The omnibus test uses a tied-rank chi-square approximation. Follow-ups use shared rank residual variance; Holm adjusts the planned pairwise family by default.", style = MaterialTheme.typography.bodySmall)
        }
        else -> Unit
    }
}

@Composable
internal fun ColumnSelection(label: String, data: CourseData, selected: List<Int>, eligible: List<Int>, maximum: Int,
    suggested: List<Int>, enabled: Boolean, onChange: (List<Int>) -> Unit) {
    var search by rememberSaveable(label, data.columns) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("$label · ${selected.size} selected (up to $maximum)", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { onChange(suggested) }, enabled = enabled) { Text("Use suggested") }
            TextButton(onClick = { onChange(emptyList()) }, enabled = enabled) { Text("Clear selection") }
        }
        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), enabled = enabled,
            label = { Text("Find ${label.lowercase()}") }, singleLine = true)
        Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
            eligible.filter { data.columns[it].contains(search, ignoreCase = true) }.forEach { index ->
                CheckOption(data.columns[index], index in selected, enabled && (index in selected || selected.size < maximum)) {
                    onChange((if (it) selected + index else selected - index).distinct().sorted())
                }
            }
        }
    }
}
