package io.github.tatselkrik.statis130lite

import org.apache.commons.statistics.descriptive.Mean
import org.apache.commons.statistics.descriptive.Variance
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

data class ValueFrequency(val value: String, val count: Int, val percent: Double)
data class PercentileValue(val percent: Double, val value: Double)
data class HistogramBin(val lower: Double, val upper: Double, val count: Int)
data class WeightedMean(val value: Double, val positiveCases: Int, val missingWeights: Int, val zeroWeights: Int)

data class NumericDescription(
    val mean: Double,
    val median: Double,
    val minimum: Double,
    val maximum: Double,
    val variance: Double?,
    val standardDeviation: Double?,
    val population: Boolean,
    val weightedMean: WeightedMean?,
    val percentiles: List<PercentileValue>,
    val histogram: List<HistogramBin>,
)

data class DescriptiveSummary(
    val variable: String,
    val validCount: Int,
    val missingCount: Int,
    val modes: List<String>,
    val modeCount: Int,
    val frequencies: List<ValueFrequency>,
    val numeric: NumericDescription?,
)

internal object Descriptives {
    fun calculate(data: CourseData, request: AnalysisRequest): AnalysisResult {
        require(request.method == AnalysisMethod.DESCRIPTIVES)
        if (!request.descriptiveNumeric) {
            require(request.weight == null) { "Weighted mean requires a numeric variable." }
            val values = data.rows.map { it[request.first].trim() }.filter(String::isNotBlank)
            require(values.isNotEmpty()) { "Select a variable with at least one non-missing value." }
            val counts = values.groupingBy { it }.eachCount().toSortedMap()
            val frequencies = counts.map { (value, count) -> ValueFrequency(value, count, count * 100.0 / values.size) }
            return result(data, request, frequencies, values.size, numeric = null,
                notes = listOf("Category summaries count non-blank values. Text such as NA is kept as a category.",
                    "Percentages use non-missing observations. Each data row is counted once."))
        }

        request.weight?.let { weight ->
            require(weight in data.columns.indices && weight != request.first) { "Choose a separate numeric weight column." }
        }
        val percentiles = parsePercentiles(request.percentiles)
        val requestedBins = request.histogramBins.trim().let { text ->
            if (text.isEmpty()) null else text.toIntOrNull().also {
                require(it != null && it in 1..50) { "Enter 1 to 50 histogram bins, or leave the field blank for automatic bins." }
            }
        }
        val values = ArrayList<Double>()
        val weightedValues = ArrayList<Double>()
        val weights = ArrayList<Double>()
        var missingWeights = 0
        var zeroWeights = 0
        data.rows.forEachIndexed { index, row ->
            val text = row[request.first].trim()
            if (!text.isMissingNumber()) {
                val value = finiteNumber(text, index, "value").let { if (it == 0.0) 0.0 else it }
                values.add(value)
                request.weight?.let { column ->
                    val rawWeight = row[column].trim()
                    if (rawWeight.isMissingNumber()) {
                        missingWeights++
                    } else {
                        val weight = finiteNumber(rawWeight, index, "weight")
                        require(weight >= 0.0) { "Weights must be nonnegative. Check data row ${index + 1}." }
                        if (weight == 0.0) zeroWeights++ else {
                            weightedValues.add(value)
                            weights.add(weight)
                        }
                    }
                }
            }
        }
        require(values.isNotEmpty()) { "Select a variable with at least one valid numeric value." }
        val sorted = values.sorted().toDoubleArray()
        val numbers = values.toDoubleArray()
        val notes = mutableListOf("Percentages and unweighted summaries count each non-missing data row once.",
            "Percentiles use linear interpolation between ordered values (R type 7).")
        val mean = Mean.of(*numbers).asDouble
        require(mean.isFinite()) { "Rescale the values to calculate their mean." }
        val (variance, standardDeviation) = dispersion(numbers, request.population, notes)
        val weightedMean = request.weight?.let { column ->
            require(weights.isNotEmpty()) { "At least one complete row must have a positive weight." }
            val weightScale = weights.max()
            val valueScale = weightedValues.maxOf { abs(it) }
            val scaledWeights = weights.map { it / weightScale }
            val denominator = compensatedSum(scaledWeights)
            val weighted = if (valueScale == 0.0) 0.0 else {
                val normalized = weightedValues.map { it / valueScale }
                val ratio = compensatedSum(normalized.indices.map { normalized[it] * scaledWeights[it] }) / denominator
                ratio.coerceIn(normalized.min(), normalized.max()) * valueScale
            }
            require(weighted.isFinite()) { "Rescale the selected values or weights." }
            notes.add("Only the weighted mean uses ${data.columns[column]}. All other summaries, frequencies and histogram use the unweighted rows.")
            notes.add("Weighted mean uses ${weights.size} positive-weight rows; $missingWeights missing weights and $zeroWeights zero weights are omitted from that mean.")
            WeightedMean(weighted, weights.size, missingWeights, zeroWeights)
        }
        val counts = sorted.toList().groupingBy { it }.eachCount().toSortedMap()
        val frequencies = counts.map { (value, count) ->
            ValueFrequency(descriptiveValue(value), count, count * 100.0 / values.size)
        }
        val bins = histogram(sorted, requestedBins, notes)
        val numeric = NumericDescription(
            mean, percentile(sorted, 50.0), sorted.first(), sorted.last(), variance, standardDeviation,
            request.population, weightedMean, percentiles.map { PercentileValue(it, percentile(sorted, it)) }, bins,
        )
        return result(data, request, frequencies, values.size, numeric, notes)
    }

    private fun result(
        data: CourseData, request: AnalysisRequest, frequencies: List<ValueFrequency>, count: Int,
        numeric: NumericDescription?, notes: List<String>,
    ): AnalysisResult {
        val highest = frequencies.maxOf { it.count }
        val uniform = frequencies.size > 1 && frequencies.all { it.count == highest }
        val modes = if (highest == 1 || uniform) emptyList() else frequencies.filter { it.count == highest }.map { it.value }
        return AnalysisResult(
            method = AnalysisMethod.DESCRIPTIVES,
            calculation = if (numeric == null) "Category frequencies and mode" else "Numeric summaries and distribution",
            test = null,
            includedCases = count.toLong(), excludedRows = data.rows.size - count,
            descriptives = DescriptiveSummary(data.columns[request.first], count, data.rows.size - count,
                modes, highest, frequencies, numeric),
            notes = notes + when {
                highest == 1 -> listOf("Every value occurs once, so no repeated mode is reported.")
                uniform -> listOf("All distinct values have equal frequency, so no mode is reported under the introductory-course convention.")
                else -> emptyList()
            },
        )
    }

    private fun finiteNumber(text: String, row: Int, kind: String): Double {
        val value = text.toDoubleOrNull()
        require(value != null && value.isFinite()) { "Choose numeric data or correct the $kind in data row ${row + 1}." }
        return value
    }

    private fun parsePercentiles(text: String): List<Double> {
        if (text.isBlank()) return emptyList()
        val tokens = text.trim().split(Regex("[\\s,;]+"))
        require(tokens.size <= 20) { "Request at most 20 percentiles." }
        return tokens.map {
            val value = it.toDoubleOrNull()
            require(value != null && value.isFinite() && value in 0.0..100.0) { "Enter percentile values from 0 to 100, separated by commas." }
            if (value == 0.0) 0.0 else value
        }.distinct().sorted()
    }

    internal fun percentile(sorted: DoubleArray, percent: Double): Double {
        val index = (sorted.size - 1) * (percent / 100.0)
        val lower = index.toInt()
        val upper = minOf(lower + 1, sorted.lastIndex)
        return interpolate(sorted[lower], sorted[upper], index - lower)
    }

    private fun interpolate(lower: Double, upper: Double, fraction: Double): Double {
        if (fraction == 0.0 || lower == upper) return lower
        if (fraction == 1.0) return upper
        val scale = max(abs(lower), abs(upper))
        if (scale == 0.0) return 0.0
        val low = lower / scale
        val high = upper / scale
        return (low * (1.0 - fraction) + high * fraction).coerceIn(low, high) * scale
    }

    private fun dispersion(values: DoubleArray, population: Boolean, notes: MutableList<String>): Pair<Double?, Double?> {
        if (!population && values.size < 2) {
            notes.add("Sample variance and standard deviation need at least two observations.")
            return null to null
        }
        val variance = Variance.of(*values).setBiased(population).asDouble
        if (variance.isFinite() && (variance > 0.0 || values.all { it == values[0] })) {
            return variance to sqrt(variance)
        }
        val scale = values.maxOf { abs(it) }
        val normalized = DoubleArray(values.size) { values[it] / scale }
        val normalizedVariance = Variance.of(*normalized).setBiased(population).asDouble
        val deviation = sqrt(normalizedVariance) * scale
        notes.add("Variance is outside the numeric display range at this scale. Rescale the values to inspect it.")
        if (!deviation.isFinite()) notes.add("Standard deviation is also outside the numeric range.")
        return null to deviation.takeIf { it.isFinite() }
    }

    private fun histogram(sorted: DoubleArray, requested: Int?, notes: MutableList<String>): List<HistogramBin> {
        val minimum = sorted.first()
        val maximum = sorted.last()
        if (minimum == maximum) {
            notes.add("The histogram has one bar because every valid value is the same.")
            return listOf(HistogramBin(minimum, maximum, sorted.size))
        }
        val selected = requested ?: (ceil(ln(sorted.size.toDouble()) / ln(2.0)).toInt() + 1).coerceIn(1, 50)
        var count = selected
        var edges: DoubleArray
        while (true) {
            edges = DoubleArray(count + 1) { interpolate(minimum, maximum, it.toDouble() / count) }
            if ((1..count).all { edges[it] > edges[it - 1] }) break
            count--
        }
        if (count != selected) notes.add("Histogram bins were reduced to $count to keep their boundaries distinct at this numeric precision.")
        notes.add(if (requested == null) "Histogram uses $count equal-width bins selected automatically (Sturges rule)."
            else "Histogram uses $count equal-width bins. The last interval includes its upper boundary.")
        val frequencies = IntArray(count)
        sorted.forEach { value ->
            var low = 0
            var high = edges.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (edges[middle] <= value) low = middle + 1 else high = middle
            }
            frequencies[(low - 1).coerceIn(0, count - 1)]++
        }
        return List(count) { HistogramBin(edges[it], edges[it + 1], frequencies[it]) }
    }

    private fun compensatedSum(values: List<Double>): Double {
        var sum = 0.0
        var correction = 0.0
        values.forEach { value ->
            val next = sum + value
            correction += if (abs(sum) >= abs(value)) (sum - next) + value else (value - next) + sum
            sum = next
        }
        return sum + correction
    }
}

internal fun descriptiveValue(value: Double): String =
    if (value == 0.0) "0" else value.toString().removeSuffix(".0")
