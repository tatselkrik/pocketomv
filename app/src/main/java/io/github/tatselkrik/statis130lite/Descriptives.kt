package io.github.tatselkrik.statis130lite

import org.apache.commons.statistics.descriptive.Mean
import org.apache.commons.statistics.descriptive.Variance
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

data class ValueFrequency(val value: String, val count: Int, val percent: Double, val withinGroupPercent: Double? = null)
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
    val skewness: Double? = null,
    val excessKurtosis: Double? = null,
)

data class DescriptiveSummary(
    val variable: String,
    val validCount: Int,
    val missingCount: Int,
    val modes: List<String>,
    val modeCount: Int,
    val frequencies: List<ValueFrequency>,
    val numeric: NumericDescription?,
    val group: String? = null,
    val showBarChart: Boolean = false,
    val notes: List<String> = emptyList(),
    val numericData: Boolean = numeric != null,
    val frequencyPooledN: Int? = null,
) {
    val label: String get() = if (group == null) variable else "$variable · $group"
    val mode: String? get() = modes.firstOrNull()
}

internal object Descriptives {
    internal const val MAX_ZERO_FILLED_FREQUENCIES = 200_000
    fun calculate(data: CourseData, request: AnalysisRequest): AnalysisResult {
        require(request.method == AnalysisMethod.DESCRIPTIVES)
        val selected = request.descriptiveVariables ?: listOf(request.first)
        require(selected.size in 1..20 && selected.distinct().size == selected.size && selected.all { it in data.columns.indices }) {
            "Select 1–20 different variables for descriptives."
        }
        request.splitBy?.let { require(it in data.columns.indices && it !in selected && it != request.weight) {
            "Choose a separate column to split the descriptives by group."
        } }
        request.weight?.let { require(it in data.columns.indices && it !in selected) { "Choose a separate numeric weight column." } }
        require(!request.showBarChart || !request.descriptiveNumeric) { "Choose Categories / text for a frequency bar chart." }
        val groups: List<Pair<String?, List<Int>>> = request.splitBy?.let { column ->
            data.rows.indices.filter { data.rows[it][column].isNotBlank() }
                .groupBy { data.rows[it][column].trim() }.toSortedMap().map { it.key to it.value }
        } ?: listOf(null to data.rows.indices.toList())
        require(groups.isNotEmpty()) { "The group column needs at least one non-blank value." }
        require(groups.size <= 40 && groups.size * selected.size <= 200) {
            "Use at most 40 groups and 200 variable/group combinations. Choose fewer variables or groups."
        }
        val multiple = selected.size * groups.size > 1
        val descriptions = ArrayList<DescriptiveSummary>()
        val boxes = ArrayList<BoxPlotSummary>()
        val qq = ArrayList<NormalQQPlot>()
        val allNotes = linkedSetOf<String>()
        for ((group, rows) in groups) {
            val groupData = CourseData(data.columns, rows.map { data.rows[it] })
            for (column in selected) {
                val valid = groupData.rows.count { row -> if (request.descriptiveNumeric) !row[column].isMissingNumber() else row[column].isNotBlank() }
                val summary = if (valid == 0 && multiple) {
                    DescriptiveSummary(data.columns[column], 0, rows.size, emptyList(), 0, emptyList(), null,
                        notes = listOf("No non-missing observations in this variable/group."), numericData = request.descriptiveNumeric)
                } else {
                    val result = single(groupData, request.copy(first = column), rows, multiple || request.splitBy != null)
                    allNotes.addAll(result.notes)
                    result.descriptives!!.copy(notes = result.notes)
                }.copy(group = group, showBarChart = request.showBarChart)
                descriptions.add(summary)
                if (request.descriptiveNumeric && valid > 0 && (request.showBoxplot || request.showQQPlot)) {
                    val values = groupData.rows.mapNotNull { row -> row[column].takeUnless { it.isMissingNumber() }?.toDouble() }.toDoubleArray()
                    if (request.showBoxplot) boxes.add(PlotCalculator.box(summary.label, values))
                    if (request.showQQPlot) qq.add(PlotCalculator.qq(summary.label, values))
                }
            }
        }
        if (request.splitBy != null) {
            val byVariable = descriptions.indices.groupBy { descriptions[it].variable }
            val levels = selected.associate { column -> data.columns[column] to data.rows.mapNotNull { row ->
                val raw = row[column].trim()
                if (request.descriptiveNumeric) raw.takeUnless { it.isMissingNumber() }?.toDoubleOrNull()?.takeIf(Double::isFinite)?.let(::descriptiveValue)
                else raw.takeIf(String::isNotEmpty)
            }.distinct().let { labels -> if (request.descriptiveNumeric) labels.sortedBy(String::toDouble) else labels.sorted() } }
            val zeroFilledSize = byVariable.entries.sumOf { (variable, indices) -> indices.size.toLong() * levels.getValue(variable).size }
            val fillZeros = zeroFilledSize <= MAX_ZERO_FILLED_FREQUENCIES
            for ((variable, indices) in byVariable) {
                val pooledN = indices.sumOf { descriptions[it].validCount }
                for (i in indices) {
                    val summary = descriptions[i]
                    val observed = summary.frequencies.associateBy { it.value }
                    val labels = if (fillZeros) levels.getValue(variable) else summary.frequencies.map { it.value }
                    val frequencies = labels.map { value ->
                        val count = observed[value]?.count ?: 0
                        ValueFrequency(value, count, if (pooledN > 0) count * 100.0 / pooledN else Double.NaN,
                            if (summary.validCount > 0) count * 100.0 / summary.validCount else null)
                    }
                    descriptions[i] = summary.copy(frequencies = frequencies, frequencyPooledN = pooledN,
                        notes = summary.notes + "Frequency percentages use pooled valid N = $pooledN for this variable across groups, as in jamovi. The separate within-group column uses this group's N = ${summary.validCount}; percentages without a valid denominator are unavailable." +
                            if (!fillZeros) listOf("Zero-count category rows are omitted because the full grouped frequency grid exceeds $MAX_ZERO_FILLED_FREQUENCIES cells. Every observed category and its pooled percentage is retained.") else emptyList())
                }
            }
            allNotes.add("Grouped frequency percentages use each variable's pooled valid N across nonblank groups. Within-group percentages are shown separately; counts, means and graphs keep their group samples.")
            if (!fillZeros) allNotes.add("The grouped frequency grid exceeds $MAX_ZERO_FILLED_FREQUENCIES cells; zero-count rows are omitted, while all observed counts and percentages are retained.")
        }
        require(descriptions.any { it.validCount > 0 }) { "Select a variable with at least one non-missing value." }
        val included = groups.sumOf { (_, rows) -> rows.count { row -> selected.any { column ->
            if (request.descriptiveNumeric) !data.rows[row][column].isMissingNumber() else data.rows[row][column].isNotBlank()
        } } }
        if (multiple) allNotes.add("Each variable is summarized using its own non-missing observations within each group. Included rows have at least one selected value; see each variable's N.")
        request.splitBy?.let { column ->
            val missingGroups = data.rows.count { it[column].isBlank() }
            allNotes.add("Split by ${data.columns[column]}; groups are ordered alphabetically by their stored labels. $missingGroups rows with blank group labels are excluded. Literal NA labels remain groups.")
        }
        return AnalysisResult(AnalysisMethod.DESCRIPTIVES,
            if (multiple || request.splitBy != null) "Descriptive summaries by variable and group" else if (request.descriptiveNumeric) "Numeric summaries and distribution" else "Category frequencies and mode",
            test = null, includedCases = included.toLong(), excludedRows = data.rows.size - included,
            descriptions = descriptions, notes = allNotes.toList(),
            tables = if (multiple) listOf(comparisonTable(descriptions, request.descriptiveNumeric)) else emptyList(),
            plots = if (boxes.isNotEmpty() || qq.isNotEmpty()) AnalysisPlots(boxes = boxes, qq = qq) else null)
    }

    private fun single(data: CourseData, request: AnalysisRequest, originalRows: List<Int>, allowUnavailableWeights: Boolean): AnalysisResult {
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
                val value = finiteNumber(text, originalRows[index], "value").let { if (it == 0.0) 0.0 else it }
                values.add(value)
                request.weight?.let { column ->
                    val rawWeight = row[column].trim()
                    if (rawWeight.isMissingNumber()) {
                        missingWeights++
                    } else {
                        val weight = finiteNumber(rawWeight, originalRows[index], "weight")
                        require(weight >= 0.0) { "Weights must be nonnegative. Check data row ${originalRows[index] + 1}." }
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
            if (weights.isEmpty() && allowUnavailableWeights) {
                notes.add("Weighted mean is unavailable for this variable/group because no complete row has a positive weight.")
                return@let null
            }
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
        val shape = shape(numbers)
        notes.add("Skewness is the adjusted Fisher–Pearson sample coefficient (N ≥ 3). Kurtosis is bias-corrected excess kurtosis (N ≥ 4; normal reference 0). Both are unweighted and need nonconstant data, independently of the variance/SD choice.")
        val numeric = NumericDescription(
            mean, percentile(sorted, 50.0), sorted.first(), sorted.last(), variance, standardDeviation,
            request.population, weightedMean, percentiles.map { PercentileValue(it, percentile(sorted, it)) }, bins,
            skewness = shape.first, excessKurtosis = shape.second,
        )
        return result(data, request, frequencies, values.size, numeric, notes)
    }

    private fun result(
        data: CourseData, request: AnalysisRequest, frequencies: List<ValueFrequency>, count: Int,
        numeric: NumericDescription?, notes: List<String>,
    ): AnalysisResult {
        val highest = frequencies.maxOf { it.count }
        val modes = frequencies.filter { it.count == highest }.map { it.value }
        return AnalysisResult(
            method = AnalysisMethod.DESCRIPTIVES,
            calculation = if (numeric == null) "Category frequencies and mode" else "Numeric summaries and distribution",
            test = null,
            includedCases = count.toLong(), excludedRows = data.rows.size - count,
            descriptions = listOf(DescriptiveSummary(data.columns[request.first], count, data.rows.size - count,
                modes, highest, frequencies, numeric)),
            notes = notes + if (modes.size > 1) listOf("Multiple values share the highest frequency. Mode shows the first in sorted order; tied values are listed separately. This includes all-distinct and uniformly frequent samples, matching jamovi's numeric mode convention.") else emptyList(),
        )
    }

    private fun shape(values: DoubleArray): Pair<Double?, Double?> {
        if (values.size < 3 || values.all { it == values[0] }) return null to null
        // Subtract an observed origin first so a large offset does not erase small differences.
        val differences = DoubleArray(values.size) { values[it] - values[0] }
        val centered = if (differences.all(Double::isFinite)) differences else {
            val scale = values.maxOf { abs(it) }
            DoubleArray(values.size) { values[it] / scale }
        }
        val scale = centered.maxOf { abs(it) }
        val scaled = DoubleArray(values.size) { centered[it] / scale }
        val mean = Mean.of(*scaled).asDouble
        val deviations = DoubleArray(values.size) { scaled[it] - mean }
        val spread = deviations.maxOf { abs(it) }
        if (spread == 0.0) return null to null
        val normalized = deviations.map { it / spread }
        val n = values.size.toDouble()
        val m2 = compensatedSum(normalized.map { it * it }) / n
        val m3 = compensatedSum(normalized.map { it * it * it }) / n
        val m4 = compensatedSum(normalized.map { it * it * it * it }) / n
        val skewness = sqrt(n * (n - 1)) / (n - 2) * (m3 / (m2 * sqrt(m2)))
        val kurtosis = if (values.size < 4) null else
            (n - 1) / ((n - 2) * (n - 3)) * ((n + 1) * (m4 / (m2 * m2) - 3) + 6)
        return skewness.takeIf { it.isFinite() } to kurtosis?.takeIf { it.isFinite() }
    }

    private fun comparisonTable(descriptions: List<DescriptiveSummary>, numeric: Boolean): AnalysisTable {
        val grouped = descriptions.any { it.group != null }
        val headers = listOf("Variable") + (if (grouped) listOf("Group") else emptyList()) +
            listOf("Valid N", "Missing") + if (numeric) listOf("Mean", "SD", "Median", "Skewness", "Excess kurtosis") else listOf("Mode")
        val rows = descriptions.map { summary ->
            listOf(summary.variable) + (if (grouped) listOf(summary.group ?: "") else emptyList()) +
                listOf(summary.validCount.toString(), summary.missingCount.toString()) + if (numeric) {
                    val values = summary.numeric
                    listOf(values?.mean, values?.standardDeviation, values?.median, values?.skewness, values?.excessKurtosis)
                        .map { it?.let(::formatStatistic) ?: "—" }
                } else listOf(summary.mode ?: "—")
        }
        return AnalysisTable("Descriptives by variable and group", headers, rows,
            listOf("N and missing counts are specific to each variable within its group. Detailed summaries and frequency tables follow. Undefined statistics are shown as —."),
            rowLabelColumns = if (grouped) 2 else 1)
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
