package io.github.tatselkrik.statis130lite

import java.util.Locale
import kotlin.math.abs

fun formatStatistic(value: Double): String = when {
    value == Double.POSITIVE_INFINITY -> "∞"
    value == Double.NEGATIVE_INFINITY -> "−∞"
    !value.isFinite() -> "—"
    value == 0.0 -> "0"
    abs(value) >= 1e8 || abs(value) < 0.0001 -> String.format(Locale.US, "%.4g", value)
    else -> String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')
}

fun formatProbability(value: Double): String =
    if (!value.isFinite()) "—" else if (value < 0.001) "< .001" else String.format(Locale.US, "%.3f", value)

fun Estimate.confidenceInterval(): String {
    if (lower95 == null || upper95 == null) return "—"
    val left = if (lower95 == Double.NEGATIVE_INFINITY) "(" else "["
    val right = if (upper95 == Double.POSITIVE_INFINITY) ")" else "]"
    return "$left${formatStatistic(lower95)}, ${formatStatistic(upper95)}$right"
}

fun AnalysisResult.asReport(source: String, fictional: Boolean, preparationNotes: List<String> = emptyList()): String = buildString {
    appendLine("pocketomv")
    appendLine(method.title)
    appendLine(calculation)
    appendLine(source)
    if (fictional) appendLine("Fictional practice data")
    if (preparationNotes.isNotEmpty()) {
        appendLine()
        appendLine("Data preparation (saved values)")
        preparationNotes.forEach { appendLine(reportCell(it)) }
    }
    appendLine()
    test?.let {
        appendLine("${it.label} = ${formatStatistic(it.statistic)}")
        appendLine("p ${if (it.pValue < 0.001) formatProbability(it.pValue) else "= ${formatProbability(it.pValue)}"}")
        if (degreesOfFreedom.isNotEmpty()) appendLine("Degrees of freedom = ${degreesOfFreedom.joinToString { formatStatistic(it) }}")
        appendLine("Included cases = $includedCases")
        appendLine("Excluded rows = $excludedRows")
    }
    if (test == null) {
        appendLine("Included rows = $includedCases")
        appendLine("Excluded rows = $excludedRows")
    }
    descriptions.forEach { summary ->
        appendLine()
        appendLine("Variable: ${summary.variable}")
        summary.group?.let { appendLine("Group: ${reportCell(it)}") }
        appendLine("Measure\tValue")
        summary.summaryRows().forEach { row -> appendLine(row.joinToString("\t") { reportCell(it) }) }
        summary.numeric?.let { numeric ->
            if (numeric.percentiles.isNotEmpty()) {
                appendLine()
                appendLine("Percentiles (linear interpolation, R type 7)")
                appendLine("Percentile\tValue")
                numeric.percentiles.forEach { appendLine("${formatStatistic(it.percent)}\t${formatStatistic(it.value)}") }
            }
            appendLine()
            appendLine("Histogram intervals")
            appendLine("Interval\tCount")
            numeric.histogram.forEachIndexed { index, bin ->
                appendLine("${bin.intervalLabel(index == numeric.histogram.lastIndex)}\t${bin.count}")
            }
        }
        appendLine()
        appendLine("Value frequencies (non-missing observations)")
        val frequencyTable = summary.frequencyTable()
        appendLine(frequencyTable.headers.joinToString("\t"))
        frequencyTable.rows.forEach { appendLine(it.joinToString("\t", transform = ::reportCell)) }
        if (summary.showBarChart && summary.validCount > 0) appendLine("Bar chart: category counts, first 20 observed labels; the frequency table retains every observed category.")
        summary.notes.forEach { appendLine(it) }
    }
    if (estimates.isNotEmpty()) {
        appendLine()
        appendLine("Estimates")
        appendLine("Parameter\tEstimate\t95% CI")
        estimates.forEach { appendLine("${it.label}\t${formatStatistic(it.value)}\t${it.confidenceInterval()}") }
    }
    if (samples.isNotEmpty()) {
        appendLine()
        appendLine("Descriptive statistics")
        appendLine("Sample\tN\tMean\tSD\tMedian\tMin\tMax")
        samples.forEach { sample ->
            appendLine(listOf(
                sample.label, sample.size.toString(), formatStatistic(sample.mean),
                sample.standardDeviation?.let(::formatStatistic) ?: "—", formatStatistic(sample.median),
                formatStatistic(sample.minimum), formatStatistic(sample.maximum),
            ).joinToString("\t"))
        }
    }
    frequencies?.let { table ->
        appendLine()
        appendLine("Observed counts")
        appendLine((listOf("Category") + table.columnLabels).joinToString("\t"))
        table.rowLabels.indices.forEach { row ->
            appendLine((listOf(table.rowLabels[row]) + table.observed[row].map(Long::toString)).joinToString("\t"))
        }
        appendLine()
        appendLine("Expected counts")
        appendLine((listOf("Category") + table.columnLabels).joinToString("\t"))
        table.rowLabels.indices.forEach { row ->
            appendLine((listOf(table.rowLabels[row]) + table.expected[row].map(::formatStatistic)).joinToString("\t"))
        }
    }
    if (assumptionChecks.isNotEmpty()) {
        appendLine()
        appendLine("Assumption checks")
        appendLine("Check\tSample\tN\tStatistic\tdf\tp\tNote")
        assumptionChecks.forEach { check ->
            appendLine(listOf(check.name, check.sample, check.size.toString(),
                check.test?.let { "${it.label} = ${formatStatistic(it.statistic)}" } ?: "Unavailable",
                check.degreesOfFreedom.joinToString { formatStatistic(it) },
                check.test?.pValue?.let(::formatProbability) ?: "—", check.note ?: "",
            ).joinToString("\t") { reportCell(it) })
        }
    }
    followUp?.let {
        appendLine()
        appendLine("Pairwise comparisons · ${it.method}")
        if (it.unavailable != null) appendLine(it.unavailable) else {
            appendLine(pairwiseHeaders.joinToString("\t"))
            it.comparisons.forEach { row -> appendLine(row.displayRow().joinToString("\t") { value -> reportCell(value) }) }
        }
        appendLine(it.note)
    }
    plots?.let { plots ->
        plots.scree?.let { appendLine("Scree plot: observed correlation eigenvalues and any parallel 95th-percentile thresholds are retained in the eigenvalue table.") }
        appendLine()
        appendLine("Graphs (figures are shown in the app; this text report includes numerical summaries)")
        if (plots.boxes.isNotEmpty()) {
            appendLine("Boxplots: R type 7 quartiles; whiskers to the last observations within 1.5 IQR. Outliers remain in the analysis.")
            appendLine(boxplotHeaders.joinToString("\t"))
            plots.boxes.forEach { box -> appendLine(box.displayRow().joinToString("\t") { reportCell(it) }) }
        }
        if (plots.qq.isNotEmpty()) {
            val references = plots.qq.map { if (it.identityReference) "identity reference line (y = x)" else "reference line through R type 7 Q1 and Q3" }.distinct().joinToString("; ")
            appendLine("Normal Q–Q: R ppoints positions; $references. A graph alone does not establish normality.")
            appendLine("Sample\tN\tQ1\tQ3\tStatus")
            plots.qq.forEach { plot ->
                appendLine(listOf(plot.label, plot.size.toString(), formatStatistic(plot.q1),
                formatStatistic(plot.q3), plot.unavailable ?: if (plot.points.first().y == plot.points.last().y)
                    "Constant values; normality cannot be assessed" else "Available in app")
                    .joinToString("\t") { reportCell(it) })
                plot.detail?.let { appendLine(reportCell(it)) }
            }
        }
        plots.scatter?.let { scatter ->
            appendLine("Scatterplot: X = ${reportCell(scatter.xLabel)}; Y = ${reportCell(scatter.yLabel)}; ${scatter.points.size} complete pairs, original values.")
            if (scatter.fittedLine != null) appendLine("Fitted least-squares regression line shown in app.")
        }
    }
    tables.forEach { table ->
        appendLine()
        appendLine(table.title)
        appendLine(table.headers.joinToString("\t") { reportCell(it) })
        table.rows.forEach { row -> appendLine(row.joinToString("\t") { reportCell(it) }) }
        table.notes.forEach { appendLine(it) }
    }
    if (method == AnalysisMethod.RELIABILITY || method == AnalysisMethod.EFA) {
        appendLine("Complete participants = $includedCases")
        appendLine("Excluded rows = $excludedRows")
    }
    if (notes.isNotEmpty()) {
        appendLine()
        appendLine("Method notes")
        notes.forEach { appendLine(it) }
    }
}

internal fun DescriptiveSummary.frequencyTable(title: String = "Value frequencies", limit: Int = Int.MAX_VALUE): AnalysisTable {
    val grouped = frequencyPooledN != null
    return AnalysisTable(title, listOf("Value", "Count", if (grouped) "Percent (pooled)" else "Percent") +
        if (grouped) listOf("Percent within group") else emptyList(), frequencies.take(limit).map {
        listOf(it.value, it.count.toString(), formatStatistic(it.percent)) +
            if (grouped) listOf(it.withinGroupPercent?.let(::formatStatistic) ?: "—") else emptyList()
    })
}

internal fun DescriptiveSummary.summaryRows(modeLimit: Int = 100): List<List<String>> = buildList {
    add(listOf("Valid N", validCount.toString()))
    add(listOf("Missing", missingCount.toString()))
    numeric?.let { values ->
        add(listOf("Mean", formatStatistic(values.mean)))
        values.weightedMean?.let { add(listOf("Weighted mean", formatStatistic(it.value))) }
        add(listOf("Median", formatStatistic(values.median)))
    }
    add(listOf("Mode", mode ?: "— (no non-missing observations)"))
    if (modes.size > 1) add(listOf("Tied modes", modes.take(modeLimit).joinToString(", ") +
        if (modes.size > modeLimit) " (${modes.size - modeLimit} more; see full frequency table)" else ""))
    if (modes.isNotEmpty()) add(listOf("Mode frequency", modeCount.toString()))
    numeric?.let { values ->
        val basis = if (values.population) "population" else "sample"
        add(listOf("Variance ($basis)", values.variance?.let(::formatStatistic) ?: "—"))
        add(listOf("Standard deviation ($basis)", values.standardDeviation?.let(::formatStatistic) ?: "—"))
        add(listOf("Minimum", descriptiveValue(values.minimum)))
        add(listOf("Maximum", descriptiveValue(values.maximum)))
        add(listOf("Skewness (adjusted sample)", values.skewness?.let(::formatStatistic) ?: "—"))
        add(listOf("Excess kurtosis (bias-corrected sample)", values.excessKurtosis?.let(::formatStatistic) ?: "—"))
    }
}

internal fun HistogramBin.intervalLabel(last: Boolean): String =
    if (lower == upper) descriptiveValue(lower)
    else "[${descriptiveValue(lower)}, ${descriptiveValue(upper)}${if (last) "]" else ")"}"

private fun reportCell(value: String): String =
    value.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")
