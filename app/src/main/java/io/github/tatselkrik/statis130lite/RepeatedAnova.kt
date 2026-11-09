package io.github.tatselkrik.statis130lite

import org.apache.commons.statistics.distribution.ChiSquaredDistribution
import org.apache.commons.statistics.distribution.FDistribution
import org.apache.commons.statistics.distribution.TDistribution
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

internal data class RepeatedFit(val f: Double, val pUncorrected: Double, val eta: Double, val ssCondition: Double?, val ssError: Double?,
    val gg: Double, val hf: Double?, val mauchlyW: Double?, val mauchlyChi: Double?, val mauchlyP: Double?, val mauchlyDf: Int, val mauchlyNote: String?)

internal object RepeatedAnova {
    fun compute(matrix: NumericMatrix): RepeatedFit {
        val n = matrix.n; val k = matrix.k; val d = k - 1
        require(k in 2..12 && n >= 3) { "Repeated-measures ANOVA needs 2–12 conditions and at least three complete participants." }
        val differences = Array(k) { j -> DoubleArray(n) { matrix.columns[j][it] - matrix.columns[0][it] } }
        val scale = differences.maxOf { column -> column.maxOf { abs(it) } }
        require(scale > 0) { "Add variation between the repeated measurements." }
        val values = Array(k) { j -> DoubleArray(n) { differences[j][it] / scale } }
        val means = DoubleArray(k) { MatrixMath.sum(values[it]) / n }
        val grand = MatrixMath.sum(means) / k
        val subject = DoubleArray(n) { row -> MatrixMath.sum(DoubleArray(k) { values[it][row] }) / k }
        val condition = n * MatrixMath.sum(DoubleArray(k) { (means[it] - grand) * (means[it] - grand) })
        val error = MatrixMath.sum(DoubleArray(n) { row -> MatrixMath.sum(DoubleArray(k) { j ->
            val residual = values[j][row] - subject[row] - means[j] + grand
            residual * residual
        }) })
        val negligible = error <= 1e-26 * (condition + error)
        val sse = if (negligible) 0.0 else error
        require(condition > 0.0 || sse > 0.0) { "These measurements do not produce a defined within-subject test." }
        val f = if (sse == 0.0) Double.POSITIVE_INFINITY else (condition / d) / (sse / ((n - 1) * d))
        val contrasts = List(d) { j -> DoubleArray(n) { row ->
            val denom = sqrt((j + 1.0) * (j + 2.0))
            (MatrixMath.sum(DoubleArray(j + 1) { values[it][row] }) - (j + 1) * values[j + 1][row]) / denom
        } }
        val centered = contrasts.map { column -> val mean = MatrixMath.sum(column) / n; DoubleArray(n) { column[it] - mean } }
        val covariance = Array(d) { i -> DoubleArray(d) { j -> MatrixMath.dot(centered[i], centered[j]) / (n - 1) } }
        val eigen = MatrixMath.eigen(covariance).values
        val trace = MatrixMath.sum(eigen)
        val gg = if (d == 1 || trace <= 0.0) 1.0 else (trace * trace / (d * MatrixMath.dot(eigen, eigen))).coerceIn(1.0 / d, 1.0)
        val hfDenominator = d * (n - 1.0 - d * gg)
        val hf = if (d == 1) 1.0 else if (hfDenominator > 0) ((n * d * gg - 2) / hfDenominator).coerceIn(gg, 1.0) else null
        var w: Double? = null; var chi: Double? = null; var probability: Double? = null
        val mauchlyDf = d * (d + 1) / 2 - 1
        val note = when {
            d == 1 -> "Sphericity is automatic with two conditions."
            n <= d -> "Too few participants to estimate the sphericity covariance matrix."
            trace <= 0 || eigen.last() <= eigen.first() * 1e-12 -> "Mauchly's test is unavailable because the contrast covariance is singular."
            else -> {
                val logW = MatrixMath.sum(DoubleArray(d) { ln(eigen[it]) }) - d * ln(trace / d)
                w = exp(logW.coerceAtMost(0.0))
                val residualDf = n - 1.0
                val correction = 1 - (2 * d * d + d + 2.0) / (6 * d * residualDf)
                chi = -residualDf * correction * logW.coerceAtMost(0.0)
                val secondOrder = (d + 2.0) * (d - 1) * (d - 2) * (2.0 * d * d * d + 6 * d * d + 3 * k + 2) /
                    (288 * residualDf * residualDf * d * d * correction * correction)
                val p1 = ChiSquaredDistribution.of(mauchlyDf.toDouble()).survivalProbability(chi)
                val p2 = ChiSquaredDistribution.of(mauchlyDf + 4.0).survivalProbability(chi)
                probability = (p1 + secondOrder * (p2 - p1)).coerceIn(0.0, 1.0)
                null
            }
        }
        fun originalSS(value: Double): Double? = (value * scale * scale).takeIf { it.isFinite() && (value == 0.0 || it > 0.0) }
        val p = if (f.isInfinite()) 0.0 else FDistribution.of(d.toDouble(), ((n - 1) * d).toDouble()).survivalProbability(f)
        return RepeatedFit(f, p, condition / (condition + sse), originalSS(condition), originalSS(sse), gg, hf,
            w, chi, probability, mauchlyDf, note)
    }

    fun run(data: CourseData, request: AnalysisRequest): AnalysisResult {
        val matrix = NumericMatrix.read(data, request.items, 2, 12)
        val fit = compute(matrix)
        val d1 = matrix.k - 1.0; val d2 = (matrix.n - 1) * d1
        val epsilon = when (request.repeatedCorrection) {
            RepeatedCorrection.NONE -> 1.0
            RepeatedCorrection.GREENHOUSE_GEISSER -> fit.gg
            RepeatedCorrection.HUYNH_FELDT -> requireNotNull(fit.hf) { "Huynh–Feldt is unavailable for this sample. Choose Greenhouse–Geisser." }
        }
        fun probability(eps: Double) = if (fit.f.isInfinite()) 0.0 else FDistribution.of(d1 * eps, d2 * eps).survivalProbability(fit.f)
        fun value(x: Double?) = x?.let(::formatStatistic) ?: "—"
        val correctionRows = listOf("Uncorrected" to 1.0, "Greenhouse–Geisser" to fit.gg, "Huynh–Feldt" to fit.hf).map { (name, eps) ->
            listOf(name, value(eps), eps?.let { value(d1 * it) } ?: "—", eps?.let { value(d2 * it) } ?: "—", eps?.let { formatProbability(probability(it)) } ?: "—")
        }
        val tables = mutableListOf(AnalysisTable("Within-subject effects", listOf("Source", "SS", "df", "MS", "F"), listOf(
            listOf("Conditions", value(fit.ssCondition), value(d1), value(fit.ssCondition?.div(d1)), value(fit.f)),
            listOf("Error", value(fit.ssError), value(d2), value(fit.ssError?.div(d2)), "—"),
        ), listOf("SS, MS and df above are uncorrected; the corrections table adjusts df and p without changing F.")),
            AnalysisTable("Sphericity corrections", listOf("Correction", "ε", "df1", "df2", "p"), correctionRows),
            AnalysisTable("Sphericity · Mauchly", listOf("W", "χ²", "df", "p"), listOf(listOf(value(fit.mauchlyW), value(fit.mauchlyChi),
                if (matrix.k == 2) "—" else fit.mauchlyDf.toString(), fit.mauchlyP?.let(::formatProbability) ?: "—")), listOfNotNull(fit.mauchlyNote)),
            AnalysisTable("Effect size", listOf("Measure", "Value"), listOf(listOf("Partial η²", value(fit.eta)))))
        if (request.repeatedComparisons) tables.add(comparisons(matrix))
        return AnalysisResult(request.method, "One within-subject factor · ${request.repeatedCorrection.label}",
            TestResult("F", fit.f, probability(epsilon)), matrix.n.toLong(), matrix.excluded, listOf(d1 * epsilon, d2 * epsilon),
            samples = matrix.labels.indices.map { Statistics.summary(matrix.labels[it], matrix.columns[it]) }, tables = tables,
            notes = buildList {
                add("Each row is one participant, with one numeric column per condition. Uses the same complete participants for the main analysis and every comparison.")
                add("Supports one within-subject factor; it does not fit between-subject factors, interactions or mixed-effects models.")
                add("Mauchly uses a chi-square approximation with a second-order correction. A nonsignificant result does not prove sphericity. The chosen df correction is never changed automatically.")
                add("The F test assumes independent participants and suitably distributed within-subject contrasts. Partial eta-squared describes the effect relative to within-subject error.")
                if (fit.ssCondition == null || fit.ssError == null) add("Some sums of squares are outside the displayable numeric range; F and effect size use normalized data. Rescale measurements to display SS.")
                if (fit.f.isInfinite()) add("There is no usable within-subject error variation; F is infinite. Interpret this degenerate result cautiously.")
            })
    }

    private fun comparisons(matrix: NumericMatrix): AnalysisTable {
        data class Row(val label: String, val difference: Double, val se: Double, val t: Double?, val p: Double?, val low: Double?, val high: Double?)
        val count = matrix.k * (matrix.k - 1) / 2
        val tDistribution = TDistribution.of(matrix.n - 1.0)
        val critical = tDistribution.inverseCumulativeProbability(1 - 0.05 / (2 * count))
        val rows = buildList {
            for (i in 0 until matrix.k) for (j in i + 1 until matrix.k) {
                val differences = DoubleArray(matrix.n) { matrix.columns[i][it] - matrix.columns[j][it] }
                val summary = MatrixMath.standardize(differences)
                val mean = summary.originalMean
                val se = summary.originalSD / sqrt(matrix.n.toDouble())
                val t = if (se > 0) mean / se else null
                val p = t?.let { (2 * tDistribution.survivalProbability(abs(it))).coerceIn(0.0, 1.0) }
                add(Row("${matrix.labels[i]} − ${matrix.labels[j]}", mean, se, t, p,
                    if (se > 0) mean - critical * se else null, if (se > 0) mean + critical * se else null))
            }
        }
        val adjusted = DoubleArray(rows.size)
        var previous = 0.0
        rows.indices.sortedBy { rows[it].p ?: 1.0 }.forEachIndexed { rank, index ->
            previous = maxOf(previous, ((rows.size - rank) * (rows[index].p ?: 1.0)).coerceAtMost(1.0)); adjusted[index] = previous
        }
        return AnalysisTable("Paired comparisons", listOf("Comparison", "Mean difference", "SE", "t", "df", "p (Holm)", "95% family CI (Bonferroni)"),
            rows.mapIndexed { index, row -> listOf(row.label, formatStatistic(row.difference), formatStatistic(row.se), row.t?.let(::formatStatistic) ?: "—",
                (matrix.n - 1).toString(), if (row.p == null) "—" else formatProbability(adjusted[index]),
                if (row.low == null) "—" else "[${formatStatistic(row.low)}, ${formatStatistic(row.high!!)}]") },
            listOf("Paired t tests use Holm-adjusted p-values. Intervals use Bonferroni family coverage; they use a different adjustment from the p-values. Tests and intervals are unavailable for zero-variation differences; these pairs remain in the planned comparison count."))
    }
}
