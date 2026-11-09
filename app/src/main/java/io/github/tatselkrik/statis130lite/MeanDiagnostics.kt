package io.github.tatselkrik.statis130lite

import org.apache.commons.statistics.descriptive.Mean
import org.apache.commons.statistics.descriptive.Variance
import org.apache.commons.statistics.inference.OneWayAnova
import kotlin.math.abs
import kotlin.math.sqrt

data class AssumptionCheck(
    val name: String,
    val sample: String,
    val size: Int,
    val test: TestResult?,
    val degreesOfFreedom: List<Double> = emptyList(),
    val note: String? = null,
)

data class PairwiseComparison(
    val first: String,
    val second: String,
    val meanDifference: Double,
    val standardError: Double,
    val t: Double,
    val degreesOfFreedom: Double,
    val adjustedP: Double,
    val lower95: Double,
    val upper95: Double,
)

data class FollowUpResult(
    val method: String,
    val comparisons: List<PairwiseComparison>,
    val note: String,
    val unavailable: String? = null,
)

internal object MeanDiagnostics {
    fun attach(data: CourseData, request: AnalysisRequest, result: AnalysisResult): AnalysisResult {
        if (request.method.usesResiduals) return result
        if (!request.checkNormality && !request.checkVariances && !request.pairwiseComparisons) return result
        val grouped = request.method == AnalysisMethod.INDEPENDENT_T || request.method == AnalysisMethod.ANOVA
        val groups = if (grouped) Statistics.readGroups(data, request) else emptyList()
        val checks = buildList {
            if (request.checkNormality) {
                when (request.method) {
                    AnalysisMethod.ONE_SAMPLE_T -> add(normality(data.columns[request.first], Statistics.readNumbers(data, request.first)))
                    AnalysisMethod.PAIRED_T -> add(normality("Paired differences", Statistics.readPairs(data, request).differences()))
                    AnalysisMethod.INDEPENDENT_T, AnalysisMethod.ANOVA -> groups.forEach { add(normality(it.label, it.values)) }
                    else -> error("Unsupported normality check")
                }
            }
            if (request.checkVariances) add(levene(groups))
        }
        val notes = mutableListOf<String>()
        if (checks.isNotEmpty()) {
            notes.add("Assumption checks use the same included observations as the main analysis. Independence of observations depends on study design and cannot be established from this table.")
            notes.add("Small diagnostic p-values suggest a departure from the tested assumption. A nonsignificant result does not prove normality or equal variances; small samples can miss important departures.")
        }
        if (request.checkNormality) notes.add(if (request.method == AnalysisMethod.PAIRED_T)
            "Shapiro–Wilk checks the paired differences, not the two measurements separately. P-values use the Royston AS R94 approximation (exact for N = 3); supported N is 3–5,000."
        else "Shapiro–Wilk checks each sample separately (within each group for grouped analyses). Its diagnostic p-values are unadjusted. P-values use the Royston AS R94 approximation (exact for N = 3); supported N is 3–5,000.")
        if (request.checkVariances) notes.add("Levene's check is centered on group medians (the Brown–Forsythe variant). It tests equal group variances and does not automatically change the main test.")
        val followUp = if (request.pairwiseComparisons) pairwise(groups, request.equalVariance) else null
        return result.copy(assumptionChecks = checks, followUp = followUp, notes = result.notes + notes)
    }

    internal fun normality(label: String, values: DoubleArray): AssumptionCheck = try {
        AssumptionCheck("Shapiro–Wilk", label, values.size, ShapiroWilk.test(values))
    } catch (error: IllegalArgumentException) {
        AssumptionCheck("Shapiro–Wilk", label, values.size, null, note = error.message)
    }

    internal fun levene(groups: List<Statistics.Group>): AssumptionCheck {
        val n = groups.sumOf { it.values.size }
        return try {
            require(groups.all { it.values.size >= 2 }) { "Levene's check needs at least two observations in every group." }
            val minimum = groups.minOf { it.values.min() }
            val range = groups.maxOf { it.values.max() } - minimum
            require(range > 0.0 && range.isFinite()) { "Levene's check is unavailable for constant data." }
            val deviations = groups.map { group ->
                val sorted = group.values.map { (it - minimum) / range }.sorted()
                if (sorted.size == 2) DoubleArray(2) { (sorted[1] - sorted[0]) / 2.0 } else {
                    val middle = sorted.size / 2
                    val median = if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
                    DoubleArray(sorted.size) { abs(sorted[it] - median) }
                }
            }
            val within = deviations.sumOf { Variance.of(*it).asDouble * (it.size - 1) }
            require(within > 0.0 && within.isFinite()) { "Levene's check needs variation in the absolute deviations within groups." }
            val test = OneWayAnova.withDefaults().test(deviations)
            require(test.statistic.isFinite() && test.pValue.isFinite()) { "Levene's check is undefined for these deviations." }
            AssumptionCheck("Levene (median)", "All groups", n, TestResult("F", test.statistic, test.pValue),
                listOf(groups.size - 1.0, n - groups.size.toDouble()))
        } catch (error: IllegalArgumentException) {
            AssumptionCheck("Levene (median)", "All groups", n, null, note = error.message)
        }
    }

    private fun pairwise(groups: List<Statistics.Group>, equalVariance: Boolean): FollowUpResult {
        val method = if (equalVariance) "Tukey–Kramer" else "Games–Howell"
        val note = "Mean differences are first group minus second group. Two-sided p-values are adjusted across all group pairs using the studentized range. The 95% intervals use the same family adjustment" +
            if (equalVariance) "; Tukey–Kramer assumes equal population variances." else "; Games–Howell uses pair-specific Welch degrees of freedom and is approximate."
        return try {
            val means = groups.map { Mean.of(*it.values).asDouble }
            val variances = groups.map { if (it.values.size >= 2) Variance.of(*it.values).asDouble else 0.0 }
            val df = groups.sumOf { it.values.size } - groups.size.toDouble()
            val mse = groups.indices.sumOf { variances[it] * (groups[it].values.size - 1) } / df
            if (equalVariance) require(mse > 0.0 && mse.isFinite()) { "Tukey–Kramer needs positive residual variance." }
            else require(groups.indices.all { groups[it].values.size >= 2 && variances[it] > 0.0 }) {
                "Games–Howell needs at least two observations and positive variance in every group."
            }
            val criticalValues = mutableMapOf<Double, Double>()
            val comparisons = buildList {
                for (i in groups.indices) for (j in i + 1 until groups.size) {
                    val first = groups[i]
                    val second = groups[j]
                    val standardError: Double
                    val pairDf: Double
                    if (equalVariance) {
                        standardError = sqrt(mse * (1.0 / first.values.size + 1.0 / second.values.size))
                        pairDf = df
                    } else {
                        val a = variances[i] / first.values.size
                        val b = variances[j] / second.values.size
                        standardError = sqrt(a + b)
                        val fraction = a / (a + b)
                        pairDf = (1.0 / (fraction * fraction / (first.values.size - 1) +
                            (1.0 - fraction) * (1.0 - fraction) / (second.values.size - 1))).coerceAtLeast(1.0)
                    }
                    require(standardError > 0.0 && standardError.isFinite()) { "Rescale the data to calculate the pairwise standard errors." }
                    val difference = means[i] - means[j]
                    val statistic = difference / standardError
                    val distribution = StudentizedRange(groups.size, pairDf)
                    val p = distribution.survival(abs(statistic) * sqrt(2.0))
                    val critical = criticalValues.getOrPut(pairDf) { distribution.critical95() }
                    val margin = critical / sqrt(2.0) * standardError
                    require(listOf(difference, statistic, margin, difference - margin, difference + margin).all(Double::isFinite)) {
                        "Rescale the data to calculate finite pairwise estimates and intervals."
                    }
                    add(PairwiseComparison(first.label, second.label, difference, standardError, statistic, pairDf,
                        p, difference - margin, difference + margin))
                }
            }
            FollowUpResult(method, comparisons, note)
        } catch (error: IllegalArgumentException) {
            FollowUpResult(method, emptyList(), note, error.message ?: "Follow-up comparisons could not be calculated.")
        }
    }
}
