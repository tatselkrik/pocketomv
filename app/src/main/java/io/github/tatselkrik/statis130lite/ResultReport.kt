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
    if (value < 0.001) "< .001" else String.format(Locale.US, "%.3f", value)

fun Estimate.confidenceInterval(): String =
    if (lower95 != null && upper95 != null) "[${formatStatistic(lower95)}, ${formatStatistic(upper95)}]" else "—"

fun AnalysisResult.asReport(source: String, fictional: Boolean): String = buildString {
    appendLine("pocketomv")
    appendLine(method.title)
    appendLine(calculation)
    appendLine(source)
    if (fictional) appendLine("Fictional practice data")
    appendLine()
    appendLine("$statisticLabel = ${formatStatistic(statistic)}")
    appendLine("p ${if (pValue < 0.001) formatProbability(pValue) else "= ${formatProbability(pValue)}"}")
    if (degreesOfFreedom.isNotEmpty()) appendLine("Degrees of freedom = ${degreesOfFreedom.joinToString { formatStatistic(it) }}")
    appendLine("Included cases = $includedCases")
    appendLine("Excluded rows = $excludedRows")
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
    if (notes.isNotEmpty()) {
        appendLine()
        appendLine("Method notes")
        notes.forEach { appendLine(it) }
    }
}
