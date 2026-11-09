package io.github.tatselkrik.statis130lite

import org.apache.commons.statistics.distribution.ChiSquaredDistribution
import org.apache.commons.statistics.distribution.TDistribution
import kotlin.math.abs
import kotlin.math.sqrt

data class FriedmanComparison(val first: String, val second: String, val rankDifference: Double,
    val standardError: Double, val statistic: Double?, val df: Double, val pValue: Double?, val adjustedPValue: Double?)

data class FriedmanSummary(val meanRanks: List<Double>, val rankSums: List<Double>, val tieCorrection: Double,
    val comparisons: List<FriedmanComparison>)

internal object FriedmanAnalysis {
    fun run(data: CourseData, request: AnalysisRequest): AnalysisResult {
        val matrix = NumericMatrix.read(data, request.items, 3, 12)
        val n = matrix.n; val k = matrix.k
        require(n >= 2) { "Friedman needs at least two complete participants and three conditions." }
        var ties = 0.0
        val ranks = List(n) { row -> Statistics.ranks(DoubleArray(k) { matrix.columns[it][row] }).let {
            ties += it.tieSum; it.values
        } }
        val sums = List(k) { column -> ranks.sumOf { it[column] } }
        val means = sums.map { it/n }
        val correction = 1 - ties/(n.toDouble()*(k*k*k-k))
        require(correction > 0) { "No within-participant rank variation: every participant has tied values in all selected conditions." }
        val center = n*(k+1.0)/2
        val q = (12 * sums.sumOf { (it-center)*(it-center) } / (n.toDouble()*k*(k+1)) / correction)
            .coerceIn(0.0, n*(k-1.0))
        val df = k-1.0
        val p = ChiSquaredDistribution.of(df).survivalProbability(q)
        val residual = ranks.sumOf { row -> row.indices.sumOf { j -> (row[j]-means[j])*(row[j]-means[j]) } }
        val pairDf = (n-1.0)*(k-1)
        val se = sqrt(2*residual/(n*pairDf))
        val comparisons = if (request.friedmanComparisons) comparisons(matrix.labels, means, se, pairDf, request.friedmanAdjustment) else emptyList()
        val tables = buildList {
            add(AnalysisTable("Condition mean ranks", listOf("Condition", "N", "Mean rank", "Rank sum"), matrix.labels.indices.map {
                listOf(matrix.labels[it], n.toString(), formatStatistic(means[it]), formatStatistic(sums[it]))
            }))
            if (request.friedmanComparisons) add(AnalysisTable("Friedman follow-ups · Durbin–Conover", listOf("First condition", "Second condition", "Mean-rank difference", "SE", "t", "df", "Raw p", "${request.friedmanAdjustment.label} p"), comparisons.map {
                listOf(it.first, it.second, formatStatistic(it.rankDifference), formatStatistic(it.standardError),
                    it.statistic?.let(::formatStatistic) ?: "—", formatStatistic(it.df),
                    it.pValue?.let(::formatProbability) ?: "—", it.adjustedPValue?.let(::formatProbability) ?: "—")
            }, listOf("First condition minus second, using ranks within each participant and the shared rank residual variance. ${request.friedmanAdjustment.label} adjustment applies to all ${k*(k-1)/2} planned pairs." +
                if (se == 0.0) " No residual rank variation remains; follow-up t tests are unavailable." else ""), rowLabelColumns = 2))
        }
        return AnalysisResult(request.method, "Friedman · tied-rank chi-square approximation", TestResult("χ²", q, p),
            n.toLong(), matrix.excluded, listOf(df), estimates = listOf(Estimate("Kendall's W", q/(n*(k-1.0)))),
            samples = matrix.labels.indices.map { Statistics.summary(matrix.labels[it], matrix.columns[it]) }, tables = tables,
            friedman = FriedmanSummary(means, sums, correction, comparisons), notes = buildList {
                add("One row is one participant, with 3–12 repeated-condition columns. The same complete participants are used for the omnibus test, mean ranks, summaries, plots and every follow-up.")
                add("Values are ranked separately within each participant, with average ranks for ties. The statistic is corrected for ties; its p-value uses a chi-square approximation with k − 1 degrees of freedom. This approximation may be inaccurate with small samples or few conditions.")
                add("Assumes independent participants and measurements that can be meaningfully ranked within each participant. A median interpretation needs a suitable common-shape/shift model. Nonparametric does not mean assumption-free.")
                add("Kendall's W = chi-square/[N(k − 1)] describes rank concordance; it does not establish causation.")
                if (request.friedmanComparisons) add("Durbin–Conover follow-ups use the shared residual variance of within-participant ranks, with (N − 1)(k − 1) degrees of freedom. P-values are two-sided and use the explicitly selected ${request.friedmanAdjustment.label} family adjustment; comparisons are displayed independently of omnibus significance.")
            })
    }

    private fun comparisons(labels: List<String>, means: List<Double>, se: Double, df: Double,
        adjustment: FriedmanAdjustment): List<FriedmanComparison> {
        val distribution = TDistribution.of(df)
        val rows = buildList {
            for (i in labels.indices) for (j in i+1 until labels.size) {
                val difference = means[i]-means[j]
                val t = if (se > 0) difference/se else null
                val p = t?.let { (2*distribution.survivalProbability(abs(it))).coerceIn(0.0, 1.0) }
                add(FriedmanComparison(labels[i], labels[j], difference, se, t, df, p, null))
            }
        }
        val adjusted = arrayOfNulls<Double>(rows.size)
        var previous = 0.0
        rows.indices.filter { rows[it].pValue != null }.sortedBy { rows[it].pValue }.forEachIndexed { rank, index ->
            val p = requireNotNull(rows[index].pValue)
            val value = when (adjustment) {
                FriedmanAdjustment.NONE -> p
                FriedmanAdjustment.BONFERRONI -> (p*rows.size).coerceAtMost(1.0)
                FriedmanAdjustment.HOLM -> maxOf(previous, (p*(rows.size-rank)).coerceAtMost(1.0))
            }
            adjusted[index] = value; previous = value
        }
        return rows.mapIndexed { i, row -> row.copy(adjustedPValue = adjusted[i]) }
    }
}
