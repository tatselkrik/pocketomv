package io.github.tatselkrik.statis130lite

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun DescriptiveOptions(
    data: CourseData, request: AnalysisRequest, enabled: Boolean, onChange: (AnalysisRequest) -> Unit,
) {
    val numericColumns = remember(data) { data.numericColumns() }
    val selected = request.descriptiveVariables ?: listOf(request.first)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ColumnSelection("Variables", data, selected, data.columns.indices.filter { it != request.splitBy }, 20,
            if (request.descriptiveNumeric) AnalysisRequest.forData(AnalysisMethod.CORRELATION_MATRIX, data).correlationVariables.filter { it != request.splitBy }
            else data.columns.indices.filter { it != request.splitBy }.take(20), enabled) {
            onChange(request.copy(descriptiveVariables = it, first = it.firstOrNull() ?: request.first,
                weight = request.weight?.takeUnless { weight -> weight in it }, qqSample = ""))
        }
        val groups: List<Pair<Int?, String>> = listOf(null to "No grouping") +
            data.columns.indices.filter { it !in selected && it != request.weight }.map { it to data.columns[it] }
        ChoiceField("Split by (optional)", request.splitBy, groups, enabled) {
            onChange(request.copy(splitBy = it, qqSample = ""))
        }
        Text("Each selected variable has its own N within each group. Blank group labels are excluded.",
            style = MaterialTheme.typography.bodySmall)
        ChoiceField("Data type", request.descriptiveNumeric,
            listOf(true to "Numeric", false to "Categories / text"), enabled) {
            onChange(request.copy(descriptiveNumeric = it, weight = if (it) request.weight else null,
                showBoxplot = it && request.showBoxplot, showQQPlot = it && request.showQQPlot,
                showBarChart = !it && request.showBarChart))
        }
        Text("The data type applies to every selected variable. Use Categories / text to treat numeric codes as categories.", style = MaterialTheme.typography.bodySmall)
        if (request.descriptiveNumeric) {
            val weights: List<Pair<Int?, String>> = listOf(null to "No weights") +
                numericColumns.filter { it !in selected && it != request.splitBy }.map { it to data.columns[it] }
            ChoiceField("Weight column (optional)", request.weight, weights, enabled) {
                onChange(request.copy(weight = it))
            }
            Text("Weights affect only the weighted mean. Other summaries count each data row once.",
                style = MaterialTheme.typography.bodySmall)
            ChoiceField("Variance and SD", request.population,
                listOf(false to "Sample (n − 1)", true to "Population (N)"), enabled) {
                onChange(request.copy(population = it))
            }
            Text("Choose Population when these observations are the entire population you want to describe.",
                style = MaterialTheme.typography.bodySmall)
            Text("Skewness and excess kurtosis use bias-corrected sample estimates. Small or constant samples show — where undefined.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = request.percentiles,
                onValueChange = { onChange(request.copy(percentiles = it)) },
                label = { Text("Percentiles (0–100)") },
                supportingText = { Text("Separate values with commas, for example 10,25,50,75,90. Leave blank to omit.") },
                enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )
            OutlinedTextField(
                value = request.histogramBins,
                onValueChange = { onChange(request.copy(histogramBins = it)) },
                label = { Text("Histogram bins (optional)") },
                supportingText = { Text("Enter 1 to 50 intervals, or leave blank for automatic bins.") },
                enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        } else {
            Text("Category summaries show the mode, counts and percentages. Blank entries are missing.",
                style = MaterialTheme.typography.bodySmall)
            CheckOption("Bar chart (category counts)", request.showBarChart, enabled) { onChange(request.copy(showBarChart = it)) }
        }
    }
}

internal fun LazyListScope.descriptiveItems(summaries: List<DescriptiveSummary>) {
    if (summaries.isEmpty()) return
    item {
        var selection by rememberSaveable(summaries.map { it.label }) { mutableIntStateOf(0) }
        val index = selection.coerceIn(summaries.indices)
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (summaries.size > 1) ChoiceField("Detailed distribution", index, summaries.mapIndexed { i, summary -> i to summary.label }, true) { selection = it }
            DescriptiveDetail(summaries[index])
        }
    }
}

@Composable
private fun DescriptiveDetail(summary: DescriptiveSummary) {
    TitledTable("Summary · ${summary.label}", listOf("Measure", "Value"), summary.summaryRows(modeLimit = 10))
    summary.numeric?.let { numeric ->
        if (numeric.percentiles.isNotEmpty()) {
            TitledTable("Percentiles", listOf("Percentile", "Value"),
                numeric.percentiles.map { listOf(formatStatistic(it.percent), formatStatistic(it.value)) })
        }
        Histogram(summary.label, numeric.histogram, summary.validCount)
        run {
            TitledTable("Histogram intervals", listOf("Interval", "Count"),
                numeric.histogram.mapIndexed { index, bin ->
                    listOf(bin.intervalLabel(index == numeric.histogram.lastIndex), bin.count.toString())
                })
        }
    }
    if (summary.showBarChart && summary.frequencies.isNotEmpty()) CategoryBarChart(summary)
    run {
        TitledTable("Value frequencies", listOf("Value", "Count", "Percent"),
            summary.frequencies.take(100).map { listOf(it.value, it.count.toString(), formatStatistic(it.percent)) })
        if (summary.frequencies.size > 100) {
            Text("Showing the first 100 of ${summary.frequencies.size} values. Copy or save the report for the full frequency table.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    summary.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun CategoryBarChart(summary: DescriptiveSummary) {
    val maximum = summary.frequencies.maxOf { it.count }.coerceAtLeast(1)
    val fill = MaterialTheme.colorScheme.primary
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Bar chart · ${summary.label}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Frequency · common scale 0–$maximum", style = MaterialTheme.typography.labelMedium)
            summary.frequencies.take(20).forEach { value ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(value.value, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Canvas(Modifier.weight(1f).height(18.dp).semantics {
                            contentDescription = "${value.value}: ${value.count} observations"
                        }) { drawRect(fill, size = Size(size.width * value.count / maximum, size.height)) }
                        Text(value.count.toString(), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (summary.frequencies.size > 20) Text("Chart shows the first 20 categories in label order. The frequency table and reports retain every category.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Histogram(variable: String, bins: List<HistogramBin>, validCount: Int) {
    val maximum = bins.maxOf { it.count }.coerceAtLeast(1)
    val fill = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outline
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Histogram · $variable", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Frequency", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth().height(220.dp)) {
                Column(Modifier.width(44.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                    Text(maximum.toString(), style = MaterialTheme.typography.labelSmall)
                    Text(formatStatistic(maximum / 2.0), style = MaterialTheme.typography.labelSmall)
                    Text("0", style = MaterialTheme.typography.labelSmall)
                }
                Canvas(Modifier.weight(1f).fillMaxHeight().semantics {
                    contentDescription = "Histogram for $variable, ${bins.size} bins and $validCount observations. Counts are listed in the histogram intervals table."
                }) {
                    val baseline = size.height - 2.dp.toPx()
                    val top = 2.dp.toPx()
                    listOf(0f, 0.5f, 1f).forEach { fraction ->
                        val y = baseline - (baseline - top) * fraction
                        drawLine(axis.copy(alpha = 0.4f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    }
                    val width = size.width / bins.size
                    bins.forEachIndexed { index, bin ->
                        val height = (baseline - top) * bin.count / maximum
                        if (height > 0f) drawRect(fill,
                            Offset(index * width + 1.dp.toPx(), baseline - height),
                            Size((width - 2.dp.toPx()).coerceAtLeast(1f), height))
                    }
                    drawLine(axis, Offset(0f, top), Offset(0f, baseline), 1.dp.toPx())
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 44.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(descriptiveValue(bins.first().lower), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                Text(descriptiveValue(bins.last().upper), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
            }
            Text(if (bins.first().lower == bins.last().upper) "All observations have the same value."
                else "${bins.size} equal-width intervals. See the counts below.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
