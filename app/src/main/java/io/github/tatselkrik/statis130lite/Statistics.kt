package io.github.tatselkrik.statis130lite

import org.apache.commons.statistics.descriptive.Mean
import org.apache.commons.statistics.descriptive.Variance
import org.apache.commons.statistics.distribution.ChiSquaredDistribution
import org.apache.commons.statistics.distribution.FDistribution
import org.apache.commons.statistics.distribution.TDistribution
import org.apache.commons.statistics.inference.DataDispersion
import org.apache.commons.statistics.inference.MannWhitneyUTest
import org.apache.commons.statistics.inference.OneWayAnova
import org.apache.commons.statistics.inference.PValueMethod
import org.apache.commons.statistics.inference.TTest
import org.apache.commons.statistics.inference.WilcoxonSignedRankTest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class Estimate(
    val label: String,
    val value: Double,
    val lower95: Double? = null,
    val upper95: Double? = null,
)

data class SampleSummary(
    val label: String,
    val size: Int,
    val mean: Double,
    val standardDeviation: Double?,
    val median: Double,
    val minimum: Double,
    val maximum: Double,
)

data class FrequencyTable(
    val rowLabels: List<String>,
    val columnLabels: List<String>,
    val observed: List<List<Long>>,
    val expected: List<List<Double>>,
)

data class TestResult(val label: String, val statistic: Double, val pValue: Double)
data class AnalysisTable(val title: String, val headers: List<String>, val rows: List<List<String>>,
    val notes: List<String> = emptyList(), val rowLabelColumns: Int = 1)

data class AnalysisResult(
    val method: AnalysisMethod,
    val calculation: String,
    val test: TestResult?,
    val includedCases: Long,
    val excludedRows: Int,
    val degreesOfFreedom: List<Double> = emptyList(),
    val estimates: List<Estimate> = emptyList(),
    val samples: List<SampleSummary> = emptyList(),
    val frequencies: FrequencyTable? = null,
    val notes: List<String> = emptyList(),
    val descriptions: List<DescriptiveSummary> = emptyList(),
    val assumptionChecks: List<AssumptionCheck> = emptyList(),
    val followUp: FollowUpResult? = null,
    val plots: AnalysisPlots? = null,
    val tables: List<AnalysisTable> = emptyList(),
    val correlations: CorrelationMatrixResult? = null,
    val associations: AssociationSummary? = null,
    val friedman: FriedmanSummary? = null,
) {
    constructor(
        method: AnalysisMethod, calculation: String, statisticLabel: String, statistic: Double,
        pValue: Double, includedCases: Long, excludedRows: Int,
        degreesOfFreedom: List<Double> = emptyList(), estimates: List<Estimate> = emptyList(),
        samples: List<SampleSummary> = emptyList(), frequencies: FrequencyTable? = null,
        notes: List<String> = emptyList(),
    ) : this(method, calculation, TestResult(statisticLabel, statistic, pValue), includedCases,
        excludedRows, degreesOfFreedom, estimates, samples, frequencies, notes)

    init {
        require((test == null) == (method in setOf(AnalysisMethod.DESCRIPTIVES, AnalysisMethod.RELIABILITY, AnalysisMethod.CORRELATION_MATRIX, AnalysisMethod.EFA)))
        require((method == AnalysisMethod.DESCRIPTIVES) == descriptions.isNotEmpty())
        require((method == AnalysisMethod.CORRELATION_MATRIX) == (correlations != null))
    }

    val descriptives: DescriptiveSummary? = descriptions.firstOrNull()
    val statisticLabel: String get() = requireNotNull(test).label
    val statistic: Double get() = requireNotNull(test).statistic
    val pValue: Double get() = requireNotNull(test).pValue
}

/** Stateless calculations. Only columns selected in the request take part in row exclusion. */
object Statistics {
    fun goodnessCategoryOrder(data: CourseData, request: AnalysisRequest): List<String> =
        data.orderedCategories(request.first, frequencyRecords(data, request).map { it.first })

    internal fun contingencyCategoryOrder(data: CourseData, request: AnalysisRequest): Pair<List<String>, List<String>> {
        val records = frequencyRecords(data, request).filter { it.count > 0 }
        return data.orderedCategories(request.first, records.map { it.first }) to
            data.orderedCategories(request.second, records.map { it.second })
    }

    fun run(data: CourseData, request: AnalysisRequest): AnalysisResult {
        require(!request.checkNormality || request.method.supportsNormalityCheck) { "Normality checks are available for t tests and one-way ANOVA." }
        require(!request.checkVariances || request.method.supportsVarianceCheck) { "Levene's check requires independent groups." }
        require(!request.pairwiseComparisons || request.method == AnalysisMethod.ANOVA) { "Pairwise follow-ups are available for one-way ANOVA." }
        require(data.columns.isNotEmpty() && data.rows.isNotEmpty()) { "Add data before running an analysis." }
        require(data.rows.all { it.size == data.columns.size }) { "Every data row must have the same number of columns." }
        require(data.rows.size <= 20_000 && data.columns.size <= 60 &&
            data.rows.size.toLong() * data.columns.size <= 250_000
        ) { "Use at most 20,000 rows, 60 columns and 250,000 cells." }
        require(request.first in data.columns.indices) { "Select the first variable." }
        if (request.method.secondLabel != null) {
            require(request.second in data.columns.indices && request.second != request.first) {
                "Select two different columns."
            }
        }
        if (request.method.usesCounts && request.count != null) {
            require(request.count in data.columns.indices && request.count != request.first &&
                (request.method.secondLabel == null || request.count != request.second)
            ) { "Select a separate column for frequencies." }
        }

        val result = when (request.method) {
            AnalysisMethod.DESCRIPTIVES -> Descriptives.calculate(data, request)
            AnalysisMethod.ONE_SAMPLE_T -> oneSample(data, request)
            AnalysisMethod.INDEPENDENT_T -> independent(data, request)
            AnalysisMethod.PAIRED_T -> paired(data, request)
            AnalysisMethod.ANOVA -> anova(data, request)
            AnalysisMethod.MANN_WHITNEY -> mannWhitney(data, request)
            AnalysisMethod.WILCOXON -> wilcoxon(data, request)
            AnalysisMethod.KRUSKAL_WALLIS -> kruskalWallis(data, request)
            AnalysisMethod.CHI_GOODNESS -> goodness(data, request)
            AnalysisMethod.CHI_INDEPENDENCE -> independence(data, request)
            AnalysisMethod.FISHER_EXACT -> CategoricalAssociations.fisher(independence(data, request), request)
            AnalysisMethod.PEARSON, AnalysisMethod.SPEARMAN -> correlation(data, request)
            AnalysisMethod.CORRELATION_MATRIX -> CorrelationMatrices.run(data, request)
            AnalysisMethod.REGRESSION -> regression(data, request)
            AnalysisMethod.RELIABILITY -> ReliabilityAnalysis.run(data, request)
            AnalysisMethod.MULTIPLE_REGRESSION -> MultipleRegression.run(data, request)
            AnalysisMethod.REPEATED_ANOVA -> RepeatedAnova.run(data, request)
            AnalysisMethod.FRIEDMAN -> FriedmanAnalysis.run(data, request)
            AnalysisMethod.FACTORIAL_ANOVA, AnalysisMethod.ANCOVA -> FactorialAnova.run(data, request)
            AnalysisMethod.EFA -> ExploratoryFactors.run(data, request)
        }
        result.test?.let { test ->
            require(!test.statistic.isNaN() && test.pValue.isFinite() && test.pValue in 0.0..1.0) {
                "These values do not produce a defined test. Check the sample sizes and variation."
            }
        }
        val confidenceTail = if (request.method in setOf(AnalysisMethod.ONE_SAMPLE_T, AnalysisMethod.INDEPENDENT_T, AnalysisMethod.PAIRED_T)) request.tail else Tail.TWO_SIDED
        require(result.estimates.all { estimate ->
            estimate.value.isFinite() &&
                (estimate.lower95?.isFinite() != false || confidenceTail == Tail.LESS && estimate.lower95 == Double.NEGATIVE_INFINITY) &&
                (estimate.upper95?.isFinite() != false || confidenceTail == Tail.GREATER && estimate.upper95 == Double.POSITIVE_INFINITY)
        }) { "The numbers are outside the supported calculation range. Rescale the variables." }
        val missingNote = if (result.excludedRows > 0) {
            listOf("${result.excludedRows} data rows were excluded because selected values were missing.")
        } else emptyList()
        val categorical = if (request.method in setOf(AnalysisMethod.CHI_INDEPENDENCE, AnalysisMethod.FISHER_EXACT))
            CategoricalAssociations.attach(result, request) else result
        val extended = PlotCalculator.attach(data, request, MeanDiagnostics.attach(data, request, categorical))
        val countNote = if (request.method.usesCounts && request.count != null && request.countMissingPolicy == CountMissingPolicy.OMIT)
            listOf("Rows with missing frequency counts are omitted by the selected policy. Counts must otherwise be nonnegative whole numbers.") else emptyList()
        return extended.copy(notes = extended.notes + missingNote + countNote)
    }

    private fun oneSample(data: CourseData, request: AnalysisRequest): AnalysisResult {
        val values = readNumbers(data, request.first)
        require(values.size >= 2) { "A t test needs at least two valid observations." }
        val reference = request.reference.trim().toDoubleOrNull()
        require(reference != null && reference.isFinite() && abs(reference) <= MAX_MAGNITUDE) {
            "Enter a finite reference mean."
        }
        val variance = variance(values)
        require(variance > 0.0) { "A t test needs variation in the sample." }
        val test = TTest.withDefaults().with(request.tail.hypothesis).withMu(reference).test(values)
        val df = values.size - 1.0
        val mean = mean(values)
        return AnalysisResult(
            method = request.method,
            calculation = "Student's one-sample t test",
            statisticLabel = "t",
            statistic = test.statistic,
            pValue = test.pValue,
            includedCases = values.size.toLong(),
            excludedRows = data.rows.size - values.size,
            degreesOfFreedom = listOf(df),
            estimates = listOf(
                interval("Mean", mean, sqrt(variance / values.size), df, request.tail),
                interval("Mean difference", mean - reference, sqrt(variance / values.size), df, request.tail),
                Estimate("Reference mean", reference),
                Estimate("Cohen's d", (mean - reference) / sqrt(variance)),
            ),
            samples = listOf(summary(data.columns[request.first], values)),
            notes = listOf(tailNote(request.tail, "${data.columns[request.first]} mean − $reference"), confidenceNote(request.tail)),
        )
    }

    private fun independent(data: CourseData, request: AnalysisRequest): AnalysisResult {
        val groups = readGroups(data, request)
        require(groups.size == 2) { "Select a grouping column containing exactly two groups." }
        val first = groups[0].values
        val second = groups[1].values
        require(first.size >= 2 && second.size >= 2) { "Each group needs at least two observations." }
        val v1 = variance(first)
        val v2 = variance(second)
        require(v1 + v2 > 0.0) { "A t test needs variation within at least one group." }
        val test = TTest.withDefaults()
            .with(request.tail.hypothesis)
            .with(if (request.equalVariance) DataDispersion.HOMOSCEDASTIC else DataDispersion.HETEROSCEDASTIC)
            .test(first, second)
        val pooled = ((first.size - 1) * v1 + (second.size - 1) * v2) /
            (first.size + second.size - 2)
        val a = v1 / first.size
        val b = v2 / second.size
        val df = if (request.equalVariance) {
            first.size + second.size - 2.0
        } else {
            // Normalize before squaring to avoid overflow or underflow.
            val scale = max(a, b)
            val x = a / scale
            val y = b / scale
            (x + y) * (x + y) / (x * x / (first.size - 1) + y * y / (second.size - 1))
        }
        val se = if (request.equalVariance) {
            sqrt(pooled * (1.0 / first.size + 1.0 / second.size))
        } else sqrt(a + b)
        val difference = mean(first) - mean(second)
        val effectSD = if (request.equalVariance) sqrt(pooled) else sqrt(v1 / 2 + v2 / 2)
        return AnalysisResult(
            method = request.method,
            calculation = if (request.equalVariance) "Student's independent t test" else "Welch's independent t test",
            statisticLabel = "t",
            statistic = test.statistic,
            pValue = test.pValue,
            includedCases = (first.size + second.size).toLong(),
            excludedRows = data.rows.size - first.size - second.size,
            degreesOfFreedom = listOf(df),
            estimates = listOf(
                interval("Mean difference", difference, se, df, request.tail),
                Estimate(if (request.equalVariance) "Cohen's d (pooled SD)" else "Cohen's d (average variance SD)", difference / effectSD),
            ),
            samples = groups.map { summary(it.label, it.values) },
            notes = listOf(tailNote(request.tail, "${groups[0].label} − ${groups[1].label}"), confidenceNote(request.tail)),
        )
    }

    private fun paired(data: CourseData, request: AnalysisRequest): AnalysisResult {
        val pairs = readPairs(data, request)
        val differences = pairs.differences()
        require(differences.size >= 2) { "A paired t test needs at least two complete pairs." }
        val variance = variance(differences)
        require(variance > 0.0) { "The paired differences need variation for a t test." }
        val test = TTest.withDefaults().with(request.tail.hypothesis).test(differences)
        val df = differences.size - 1.0
        return AnalysisResult(
            method = request.method,
            calculation = "Student's paired t test",
            statisticLabel = "t",
            statistic = test.statistic,
            pValue = test.pValue,
            includedCases = differences.size.toLong(),
            excludedRows = data.rows.size - differences.size,
            degreesOfFreedom = listOf(df),
            estimates = listOf(
                interval("Mean paired difference", mean(differences), sqrt(variance / differences.size), df, request.tail),
                Estimate("Cohen's dz", mean(differences) / sqrt(variance)),
            ),
            samples = listOf(
                summary(data.columns[request.first], pairs.first),
                summary(data.columns[request.second], pairs.second),
                summary("Paired difference", differences),
            ),
            notes = listOf(
                tailNote(request.tail, "${data.columns[request.first]} − ${data.columns[request.second]}"),
                confidenceNote(request.tail),
            ),
        )
    }

    private fun anova(data: CourseData, request: AnalysisRequest): AnalysisResult {
        val groups = readGroups(data, request)
        val n = groups.sumOf { it.values.size }
        require(groups.size >= 2 && n > groups.size) { "ANOVA needs two or more groups and positive residual degrees of freedom." }
        val df1 = groups.size - 1.0
        val statistic: Double
        val df2: Double
        val probability: Double
        if (request.equalVariance) {
            val within = groups.sumOf { group ->
                val m = mean(group.values)
                group.values.sumOf { (it - m) * (it - m) }
            }
            require(within > 0.0) { "ANOVA needs variation within at least one group." }
            val test = OneWayAnova.withDefaults().test(groups.map { it.values })
            statistic = test.statistic
            probability = test.pValue
            df2 = n - groups.size.toDouble()
        } else {
            require(groups.all { it.values.size >= 2 && variance(it.values) > 0.0 }) {
                "Welch ANOVA needs two or more observations and nonzero variance in every group."
            }
            val weights = groups.map { it.values.size / variance(it.values) }
            val total = weights.sum()
            require(total.isFinite() && total > 0.0) { "Rescale the outcome to calculate Welch ANOVA." }
            val weightedMean = groups.indices.sumOf { weights[it] / total * mean(groups[it].values) }
            val adjustment = groups.indices.sumOf {
                val fraction = 1.0 - weights[it] / total
                fraction * fraction / (groups[it].values.size - 1)
            }
            val numerator = groups.indices.sumOf {
                val difference = mean(groups[it].values) - weightedMean
                weights[it] * difference * difference
            } / df1
            statistic = numerator / (1.0 + 2.0 * (groups.size - 2) * adjustment /
                (groups.size.toDouble() * groups.size - 1.0))
            df2 = (groups.size.toDouble() * groups.size - 1.0) / (3.0 * adjustment)
            probability = FDistribution.of(df1, df2).survivalProbability(statistic)
        }
        return AnalysisResult(
            request.method, if (request.equalVariance) "One-way ANOVA" else "Welch's one-way ANOVA",
            "F", statistic, probability, n.toLong(), data.rows.size - n,
            degreesOfFreedom = listOf(df1, df2),
            samples = groups.map { summary(it.label, it.values) },
            notes = listOf("The omnibus test compares all group means. A significant result alone does not identify which groups differ."),
        )
    }

    private fun mannWhitney(data: CourseData, request: AnalysisRequest): AnalysisResult {
        val groups = readGroups(data, request)
        require(groups.size == 2) { "Mann–Whitney needs exactly two independent groups." }
        val first = groups[0].values
        val second = groups[1].values
        val pooled = first + second
        require(pooled.distinct().size > 1) { "The pooled observations need at least two distinct values." }
        val exact = first.size < 50 && second.size < 50 && !hasTies(pooled)
        val test = MannWhitneyUTest.withDefaults().with(request.tail.hypothesis)
            .with(if (exact) PValueMethod.EXACT else PValueMethod.ASYMPTOTIC).test(first, second)
        val firstU = test.statistic
        val secondU = first.size.toDouble() * second.size - firstU
        return AnalysisResult(
            request.method, rankCalculation(exact), "U", min(firstU, secondU), test.pValue,
            pooled.size.toLong(), data.rows.size - pooled.size,
            estimates = listOf(Estimate("U₁", firstU), Estimate("U₂", secondU)),
            samples = groups.map { summary(it.label, it.values) },
            notes = listOf(
                "U is the smaller of U₁ and U₂, as in jamovi. The selected alternative still follows the displayed group order.",
                "U₁ refers to group ${groups[0].label}. U₂ refers to group ${groups[1].label}.",
                tailNote(request.tail, "${groups[0].label} relative to ${groups[1].label}"),
                "The rank test compares distributions. A median interpretation assumes similarly shaped distributions.",
            ),
        )
    }

    private fun wilcoxon(data: CourseData, request: AnalysisRequest): AnalysisResult {
        val pairs = readPairs(data, request)
        val differences = pairs.differences()
        val nonzero = differences.filter { it != 0.0 }.toDoubleArray()
        require(nonzero.isNotEmpty()) { "At least one complete pair must have a nonzero difference." }
        val zeros = differences.size - nonzero.size
        val exact = differences.size < 50 && zeros == 0 && !hasTies(nonzero.map(::abs).toDoubleArray())
        // R/jamovi's Wilcoxon convention removes zero differences before assigning ranks.
        val test = WilcoxonSignedRankTest.withDefaults().with(request.tail.hypothesis)
            .with(if (exact) PValueMethod.EXACT else PValueMethod.ASYMPTOTIC).test(nonzero)
        return AnalysisResult(
            request.method, rankCalculation(exact), "W⁺", test.statistic, test.pValue,
            differences.size.toLong(), data.rows.size - differences.size,
            estimates = listOf(
                Estimate("Nonzero pairs", nonzero.size.toDouble()),
                Estimate("Zero differences omitted from ranks", zeros.toDouble()),
            ),
            samples = listOf(
                summary(data.columns[request.first], pairs.first),
                summary(data.columns[request.second], pairs.second),
                summary("Paired difference", differences),
            ),
            notes = listOf(
                tailNote(request.tail, "${data.columns[request.first]} − ${data.columns[request.second]}"),
                "Zero differences are removed before ranking. The signed-rank test assumes a symmetric distribution of paired differences.",
            ),
        )
    }

    private fun kruskalWallis(data: CourseData, request: AnalysisRequest): AnalysisResult {
        val groups = readGroups(data, request)
        require(groups.size >= 2) { "Kruskal–Wallis needs at least two independent groups." }
        val values = groups.flatMap { it.values.asList() }.toDoubleArray()
        val ranking = ranks(values)
        val n = values.size.toDouble()
        val correction = 1.0 - ranking.tieSum / (n * n * n - n)
        require(correction > 0.0) { "Kruskal–Wallis needs at least two distinct outcome values." }
        var offset = 0
        val rankSumSquares = groups.sumOf { group ->
            var sum = 0.0
            repeat(group.values.size) { sum += ranking.values[offset++] }
            sum * sum / group.values.size
        }
        val h = max(0.0, (12.0 * rankSumSquares / (n * (n + 1.0)) - 3.0 * (n + 1.0)) / correction)
        val df = groups.size - 1.0
        return AnalysisResult(
            request.method, "Tie-corrected chi-square approximation", "H", h,
            ChiSquaredDistribution.of(df).survivalProbability(h), values.size.toLong(),
            data.rows.size - values.size,
            degreesOfFreedom = listOf(df),
            samples = groups.map { summary(it.label, it.values) },
            notes = listOf("Average ranks are used for ties. This omnibus test does not identify which groups differ."),
        )
    }

    private fun goodness(data: CourseData, request: AnalysisRequest): AnalysisResult {
        val records = frequencyRecords(data, request)
        val labels = data.orderedCategories(request.first, records.map { it.first })
        require(labels.size in 2..40) { "A goodness-of-fit test needs 2 to 40 categories." }
        val observed = labels.map { label -> records.filter { it.first == label }.sumOf { it.count } }
        val total = checkedTotal(observed)
        val weights = if (request.expectedWeights.isBlank()) {
            List(labels.size) { 1.0 }
        } else {
            request.expectedWeights.trim().split(Regex("[,;\\s]+")).map { it.toDoubleOrNull() ?: Double.NaN }
        }
        require(weights.size == labels.size && weights.all { it.isFinite() && it > 0.0 }) {
            "Enter one positive expected weight for each category, in this order: ${labels.joinToString()}."
        }
        val largest = weights.max()
        val normalized = weights.map { it / largest }
        val denominator = normalized.sum()
        val expected = normalized.map { it / denominator * total }
        require(expected.all { it > 0.0 }) { "The expected weights span too large a numeric range." }
        val chi = observed.indices.sumOf {
            val difference = observed[it] - expected[it]
            difference * difference / expected[it]
        }
        val df = labels.size - 1.0
        return AnalysisResult(
            request.method, "Pearson chi-square approximation", "χ²", chi,
            ChiSquaredDistribution.of(df).survivalProbability(chi), total, data.rows.size - records.size,
            degreesOfFreedom = listOf(df),
            frequencies = FrequencyTable(labels, listOf("Frequency"), observed.map { listOf(it) }, expected.map { listOf(it) }),
            notes = listOf(
                if (request.expectedWeights.isBlank()) "The null hypothesis assigns equal proportions to all categories."
                else "Expected weights are normalized in category order: ${labels.joinToString()}.",
            ) + expectedNotes(expected),
        )
    }

    private fun independence(data: CourseData, request: AnalysisRequest): AnalysisResult {
        val records = frequencyRecords(data, request)
        // Zero-only categories do not contribute a row or column to the contingency table.
        val positive = records.filter { it.count > 0 }
        val rowLabels = data.orderedCategories(request.first, positive.map { it.first })
        val columnLabels = data.orderedCategories(request.second, positive.map { it.second })
        require(rowLabels.size >= 2 && columnLabels.size >= 2) {
            "Independence needs at least two nonempty categories in each variable."
        }
        require(rowLabels.size <= 40 && columnLabels.size <= 40) { "Use at most 40 categories per variable." }
        val rowIndex = rowLabels.withIndex().associate { it.value to it.index }
        val columnIndex = columnLabels.withIndex().associate { it.value to it.index }
        val observed = Array(rowLabels.size) { LongArray(columnLabels.size) }
        checkedTotal(records.map { it.count })
        records.filter { it.count > 0 }.forEach {
            observed[rowIndex.getValue(it.first)][columnIndex.getValue(it.second)] += it.count
        }
        val rowTotals = observed.map { it.sum() }
        val columnTotals = columnLabels.indices.map { column -> observed.sumOf { it[column] } }
        val total = checkedTotal(rowTotals)
        val expected = rowLabels.indices.map { row ->
            columnLabels.indices.map { column -> rowTotals[row].toDouble() / total * columnTotals[column] }
        }
        val chi = rowLabels.indices.sumOf { row ->
            columnLabels.indices.sumOf { column ->
                val difference = observed[row][column] - expected[row][column]
                difference * difference / expected[row][column]
            }
        }
        val df = (rowLabels.size - 1.0) * (columnLabels.size - 1.0)
        return AnalysisResult(
            request.method, "Pearson chi-square approximation", "χ²", chi,
            ChiSquaredDistribution.of(df).survivalProbability(chi), total, data.rows.size - records.size,
            degreesOfFreedom = listOf(df),
            estimates = listOf(Estimate("Cramér's V", sqrt(chi / total / min(rowLabels.size - 1, columnLabels.size - 1)))),
            frequencies = FrequencyTable(rowLabels, columnLabels, observed.map { it.toList() }, expected),
            notes = listOf("Uses the Pearson statistic without Yates continuity correction.") + expectedNotes(expected.flatten()),
        )
    }

    private fun correlation(data: CourseData, request: AnalysisRequest): AnalysisResult {
        val pairs = readPairs(data, request)
        val ranked = request.method == AnalysisMethod.SPEARMAN
        require(pairs.first.size >= if (ranked) 2 else 3) {
            if (ranked) "Spearman inference needs at least two complete pairs." else "Pearson inference needs at least three complete pairs."
        }
        val r = correlationCoefficient(pairs.first, pairs.second, ranked)
        val rankProbability = if (ranked) SpearmanProbability.calculate(pairs.first, pairs.second, r, request.tail) else null
        val df = pairs.first.size - 2.0
        val t = if (abs(r) == 1.0) {
            if (r > 0) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
        } else r * sqrt(df / ((1.0 - r) * (1.0 + r)))
        return AnalysisResult(
            request.method,
            if (ranked) "Average-rank Spearman correlation · ${rankProbability!!.method.description}" else "Pearson correlation with t test",
            if (ranked) "ρ" else "r", r, rankProbability?.p ?: tProbability(t, df, request.tail),
            pairs.first.size.toLong(), data.rows.size - pairs.first.size,
            degreesOfFreedom = listOf(df),
            samples = listOf(summary(data.columns[request.first], pairs.first), summary(data.columns[request.second], pairs.second)),
            notes = listOf(tailNote(request.tail, "correlation")) +
                if (ranked) listOf("${rankProbability!!.method.description}. Tied values receive average ranks. Exact probabilities include the observed rank arrangement; untied samples of 10–1,290 pairs use AS 89. The displayed N − 2 degrees of freedom apply to the t approximation only.")
                else listOf("The p-value tests zero population correlation under the usual Pearson assumptions."),
        )
    }

    internal fun correlationCoefficient(first: DoubleArray, second: DoubleArray, ranked: Boolean): Double {
        val x = if (ranked) ranks(first).values else first
        val y = if (ranked) ranks(second).values else second
        val fit = standardizedFit(x, y)
        return (fit.xy / sqrt(fit.xx * fit.yy)).coerceIn(-1.0, 1.0)
    }

    private fun regression(data: CourseData, request: AnalysisRequest): AnalysisResult {
        val pairs = readPairs(data, request)
        require(pairs.first.size >= 3) { "Regression needs at least three complete pairs." }
        val fit = standardizedFit(pairs.first, pairs.second)
        val beta = fit.xy / fit.xx
        val slope = beta * fit.scaleY / fit.scaleX
        val intercept = fit.meanY - slope * fit.meanX
        val df = pairs.first.size - 2.0
        val residual = fit.x.indices.sumOf {
            val error = fit.y[it] - beta * fit.x[it]
            error * error
        }
        val seScaled = sqrt(residual / df / fit.xx)
        val seSlope = seScaled * fit.scaleY / fit.scaleX
        val t = if (seScaled == 0.0) {
            if (beta > 0.0) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
        } else beta / seScaled
        val rSquared = (1.0 - residual / fit.yy).coerceIn(0.0, 1.0)
        return AnalysisResult(
            request.method, "Ordinary least squares with an intercept", "t (slope)", t,
            tProbability(t, df, Tail.TWO_SIDED), pairs.first.size.toLong(), data.rows.size - pairs.first.size,
            degreesOfFreedom = listOf(df),
            estimates = listOf(
                Estimate("Intercept", intercept),
                interval("Slope", slope, seSlope, df),
                Estimate("Slope standard error", seSlope),
                Estimate("R²", rSquared),
                Estimate("Adjusted R²", 1.0 - (1.0 - rSquared) * (pairs.first.size - 1) / df),
                Estimate("RMSE", sqrt(residual / pairs.first.size) * fit.scaleY),
                Estimate("Residual standard deviation", sqrt(residual / df) * fit.scaleY),
            ),
            samples = listOf(summary(data.columns[request.first], pairs.first), summary(data.columns[request.second], pairs.second)),
            notes = listOf("Predicts ${data.columns[request.second]} from ${data.columns[request.first]}. The p-value tests a zero slope.", CI_NOTE) +
                if (residual == 0.0) listOf("The fitted line has zero residual variance.") else emptyList(),
        )
    }

    internal fun number(data: CourseData, row: Int, column: Int): Double {
        val value = data.rows[row][column].trim().toDoubleOrNull()
        require(value != null && value.isFinite() && abs(value) <= MAX_MAGNITUDE) {
            "Enter a finite number in ${data.columns[column]} at data row ${row + 1}. Values must have magnitude at most 1e150."
        }
        return if (value == 0.0) 0.0 else value
    }

    internal fun readNumbers(data: CourseData, column: Int): DoubleArray =
        data.rows.indices.filterNot { data.rows[it][column].isMissingNumber() }
            .map { number(data, it, column) }.toDoubleArray()

    internal data class Pairs(val first: DoubleArray, val second: DoubleArray) {
        fun differences(): DoubleArray = DoubleArray(first.size) { first[it] - second[it] }
    }

    internal fun readPairs(data: CourseData, request: AnalysisRequest): Pairs {
        val rows = data.rows.indices.filterNot {
            data.rows[it][request.first].isMissingNumber() || data.rows[it][request.second].isMissingNumber()
        }
        require(rows.isNotEmpty()) { "Add complete pairs in the selected numeric columns." }
        return Pairs(
            rows.map { number(data, it, request.first) }.toDoubleArray(),
            rows.map { number(data, it, request.second) }.toDoubleArray(),
        )
    }

    internal data class Group(val label: String, val values: DoubleArray)

    internal fun readGroups(data: CourseData, request: AnalysisRequest): List<Group> {
        val groups = linkedMapOf<String, MutableList<Double>>()
        data.rows.indices.forEach { row ->
            val label = data.rows[row][request.second].trim()
            if (label.isNotEmpty() && !data.rows[row][request.first].isMissingNumber()) {
                groups.getOrPut(label) { mutableListOf() }.add(number(data, row, request.first))
            }
        }
        require(groups.size in 2..40) { "Select a grouping column containing 2 to 40 nonempty groups." }
        return data.orderedCategories(request.second, groups.keys).map { Group(it, groups.getValue(it).toDoubleArray()) }
    }

    private data class FrequencyRecord(val first: String, val second: String, val count: Long)

    private fun frequencyRecords(data: CourseData, request: AnalysisRequest): List<FrequencyRecord> {
        if (request.countMissingPolicy == CountMissingPolicy.REJECT) request.count?.let { column ->
            require(data.rows.none { it[column].isMissingNumber() }) {
                "The frequency column contains missing counts. Complete those counts, or select Omit rows with missing counts."
            }
        }
        val records = data.rows.indices.mapNotNull { row ->
            val first = data.rows[row][request.first].trim()
            val second = if (request.method.secondLabel == null) "" else data.rows[row][request.second].trim()
            val countMissing = request.count?.let { data.rows[row][it].isMissingNumber() } == true
            if (first.isEmpty() || (request.method.secondLabel != null && second.isEmpty()) || countMissing) {
                return@mapNotNull null
            }
            val count = request.count?.let { column ->
                val value = number(data, row, column)
                require(value >= 0.0 && value <= 1_000_000_000_000.0 && value % 1.0 == 0.0) {
                    "Frequencies must be whole numbers from 0 to 1,000,000,000,000. Check data row ${row + 1}."
                }
                value.toLong()
            } ?: 1L
            FrequencyRecord(first, second, count)
        }
        require(records.isNotEmpty()) { "Add complete category values and frequencies." }
        checkedTotal(records.map { it.count })
        return records
    }

    private fun checkedTotal(counts: List<Long>): Long {
        val total = counts.sum()
        require(total in 1..9_007_199_254_740_991L) { "The total frequency must be positive and at most 2^53 − 1." }
        return total
    }

    private fun expectedNotes(expected: List<Double>): List<String> {
        val low = expected.count { it < 5.0 }
        return if (low > 0) {
            listOf("$low of ${expected.size} expected counts are below 5. The chi-square approximation may be unreliable.")
        } else emptyList()
    }

    private fun mean(values: DoubleArray): Double = Mean.of(*values).asDouble

    private fun variance(values: DoubleArray): Double = Variance.of(*values).asDouble

    internal fun summary(label: String, values: DoubleArray): SampleSummary {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        val median = if (sorted.size % 2 == 1) sorted[middle] else sorted[middle - 1] / 2.0 + sorted[middle] / 2.0
        return SampleSummary(
            label, values.size, mean(values), if (values.size > 1) sqrt(variance(values)) else null,
            median, sorted.first(), sorted.last(),
        )
    }

    internal data class Ranking(val values: DoubleArray, val tieSum: Double)

    internal fun ranks(values: DoubleArray): Ranking {
        val order = values.indices.sortedBy { values[it] }
        val ranked = DoubleArray(values.size)
        var start = 0
        var tieSum = 0.0
        while (start < order.size) {
            var end = start + 1
            while (end < order.size && values[order[start]] == values[order[end]]) end++
            val average = (start + 1.0 + end) / 2.0
            for (index in start until end) ranked[order[index]] = average
            val length = (end - start).toDouble()
            tieSum += length * length * length - length
            start = end
        }
        return Ranking(ranked, tieSum)
    }

    private fun hasTies(values: DoubleArray): Boolean = ranks(values).tieSum > 0.0

    private data class Fit(
        val x: DoubleArray,
        val y: DoubleArray,
        val meanX: Double,
        val meanY: Double,
        val scaleX: Double,
        val scaleY: Double,
        val xx: Double,
        val yy: Double,
        val xy: Double,
    )

    private fun standardizedFit(first: DoubleArray, second: DoubleArray): Fit {
        val meanX = mean(first)
        val meanY = mean(second)
        val scaleX = first.maxOf { abs(it - meanX) }
        val scaleY = second.maxOf { abs(it - meanY) }
        require(scaleX > 0.0 && scaleY > 0.0) { "Both numeric variables need at least two distinct values." }
        val x = DoubleArray(first.size) { (first[it] - meanX) / scaleX }
        val y = DoubleArray(second.size) { (second[it] - meanY) / scaleY }
        return Fit(
            x, y, meanX, meanY, scaleX, scaleY,
            x.sumOf { it * it }, y.sumOf { it * it }, x.indices.sumOf { x[it] * y[it] },
        )
    }

    private fun interval(label: String, value: Double, se: Double, df: Double, tail: Tail = Tail.TWO_SIDED): Estimate {
        val margin = TDistribution.of(df).inverseSurvivalProbability(if (tail == Tail.TWO_SIDED) 0.025 else 0.05) * se
        return Estimate(label, value, if (tail == Tail.LESS) Double.NEGATIVE_INFINITY else value - margin,
            if (tail == Tail.GREATER) Double.POSITIVE_INFINITY else value + margin)
    }

    private fun confidenceNote(tail: Tail): String = when (tail) {
        Tail.TWO_SIDED -> CI_NOTE
        Tail.GREATER -> "Reported confidence intervals are one-sided 95% intervals with a finite lower bound and an unbounded upper end."
        Tail.LESS -> "Reported confidence intervals are one-sided 95% intervals with an unbounded lower end and a finite upper bound."
    }

    internal fun tProbability(statistic: Double, df: Double, tail: Tail): Double {
        val distribution = TDistribution.of(df)
        return when (tail) {
            Tail.TWO_SIDED -> min(1.0, 2.0 * distribution.survivalProbability(abs(statistic)))
            Tail.GREATER -> distribution.survivalProbability(statistic)
            Tail.LESS -> distribution.cumulativeProbability(statistic)
        }
    }

    private fun tailNote(tail: Tail, contrast: String): String = when (tail) {
        Tail.TWO_SIDED -> "Two-sided alternative for $contrast."
        Tail.GREATER -> "One-sided alternative for $contrast greater than zero."
        Tail.LESS -> "One-sided alternative for $contrast less than zero."
    }

    private fun rankCalculation(exact: Boolean): String =
        if (exact) "Exact rank-test probability" else "Normal approximation with tie and continuity corrections"

    private const val MAX_MAGNITUDE = 1e150
    private const val CI_NOTE = "Reported confidence intervals are two-sided 95% intervals."
}
