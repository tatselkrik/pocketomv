package io.github.tatselkrik.statis130lite

import kotlin.math.abs
import kotlin.math.atanh
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.tanh

internal data class ReliabilityFit(
    val alpha: Double?, val standardizedAlpha: Double?, val omega: Double?, val standardizedOmega: Double?,
    val itemRest: List<Double?>, val alphaDropped: List<Double?>, val loadings: List<Double>?,
    val correlation: Array<DoubleArray>, val omegaNote: String?,
)

internal object ReliabilityAnalysis {
    fun compute(matrix: NumericMatrix): ReliabilityFit {
        require(matrix.n >= 3) { "Reliability needs at least three complete participants." }
        require(matrix.k in 2..60) { "Select 2–60 items." }
        val standard = matrix.columns.map(MatrixMath::standardize)
        val varying = standard.all { it.sd > 0 }
        val r = MatrixMath.correlation(standard)
        val all = standard.indices.toList()
        fun weights(indices: List<Int>, standardized: Boolean = false): DoubleArray {
            if (standardized) return DoubleArray(indices.size) { 1.0 }
            val scale = indices.filter { standard[it].sd > 0.0 }.maxOfOrNull { standard[it].maximum } ?: 1.0
            return DoubleArray(indices.size) { j -> standard[indices[j]].sd * (standard[indices[j]].maximum / scale) }
        }
        fun total(indices: List<Int>, w: DoubleArray): DoubleArray = DoubleArray(matrix.n) { row ->
            MatrixMath.sum(DoubleArray(indices.size) { j -> standard[indices[j]].z[row] * w[j] })
        }
        fun alpha(indices: List<Int>, standardized: Boolean = false): Double? {
            if (indices.size < 2 || (standardized && indices.any { standard[it].sd == 0.0 })) return null
            val w = weights(indices, standardized)
            val sums = total(indices, w)
            val variance = MatrixMath.dot(sums, sums) / (matrix.n - 1)
            if (variance <= 0.0) return null
            return (indices.size.toDouble() / (indices.size - 1) * (1 - MatrixMath.dot(w, w) / variance))
                .takeIf(Double::isFinite)?.coerceAtMost(1.0)
        }
        val rest = all.map { i ->
            val others = all.filter { it != i }
            val sums = total(others, weights(others))
            val norm = sqrt(MatrixMath.dot(sums, sums) * (matrix.n - 1))
            if (standard[i].sd == 0.0 || norm == 0.0) null else
                (MatrixMath.dot(standard[i].z, sums) / norm).coerceIn(-1.0, 1.0)
        }
        var note: String? = null
        val loading = when {
            matrix.k < 3 -> { note = "One-factor omega needs at least three items."; null }
            !varying -> { note = "Omega is unavailable because an item has no variation."; null }
            else -> {
                val eigen = MatrixMath.eigen(r)
                if (eigen.values.last() <= eigen.values.first() * 1e-10) {
                    note = "Omega is unavailable because the item correlation matrix is singular or nearly singular."; null
                } else fitOneFactor(r, eigen).also {
                    if (it == null) note = "One-factor omega could not be estimated reliably (nonconvergence, weak identification or a boundary solution)."
                }
            }
        }
        fun omega(standardized: Boolean): Double? {
            loading ?: return null
            val w = weights(all, standardized)
            val common = MatrixMath.sum(DoubleArray(matrix.k) { loading[it] * w[it] })
            val uniqueness = MatrixMath.sum(DoubleArray(matrix.k) { (1 - loading[it] * loading[it]) * w[it] * w[it] })
            return (common * common / (common * common + uniqueness)).takeIf(Double::isFinite)?.coerceIn(0.0, 1.0)
        }
        return ReliabilityFit(alpha(all), alpha(all, true), omega(false), omega(true), rest,
            all.map { i -> alpha(all.filter { it != i }) }, loading?.toList(), r, note)
    }

    /** One congeneric factor: covariance = lambda lambda' + diagonal uniqueness. */
    private fun fitOneFactor(r: Array<DoubleArray>, eigen: MatrixMath.Eigen): DoubleArray? {
        val k = r.size
        val cap = 0.9999
        fun objective(z: DoubleArray): Pair<Double, DoubleArray> {
            val l = DoubleArray(k) { cap * tanh(z[it]) }
            val inverseUnique = DoubleArray(k) { 1 / (1 - l[it] * l[it]) }
            val v = DoubleArray(k) { inverseUnique[it] * l[it] }
            val a = 1 + MatrixMath.dot(l, v)
            val rv = DoubleArray(k) { MatrixMath.dot(r[it], v) }
            val vrv = MatrixMath.dot(v, rv)
            val value = -MatrixMath.sum(DoubleArray(k) { ln(inverseUnique[it]) }) + ln(a) +
                MatrixMath.sum(inverseUnique) - vrv / a
            val gradient = DoubleArray(k) { i ->
                val s = inverseUnique[i]
                val mii = s - v[i] * v[i] / a -
                    (s * s - 2 * s * v[i] * rv[i] / a + v[i] * v[i] * vrv / (a * a))
                val ml = v[i] / a - s * rv[i] / a + v[i] * vrv / (a * a)
                2 * (ml - l[i] * mii) * cap * (1 - tanh(z[i]) * tanh(z[i]))
            }
            return value to gradient
        }
        val starts = listOf(0.25, 0.6, 0.85).map { amplitude ->
            DoubleArray(k) { i -> atanh((eigen.vectors[i][0] * sqrt(eigen.values[0].coerceAtLeast(0.1)) * amplitude).coerceIn(-0.85, 0.85) / cap) }
        }
        val best = starts.map { MatrixMath.minimize(it, ::objective) }.filter { it.converged }.minByOrNull { it.value } ?: return null
        val loadings = DoubleArray(k) { cap * tanh(best.x[it]) }
        if (loadings.any { it * it >= 0.995 } || loadings.count { abs(it) > 1e-4 } < 3) return null
        if (MatrixMath.sum(loadings) < 0) for (i in loadings.indices) loadings[i] = -loadings[i]
        return loadings
    }

    fun run(data: CourseData, request: AnalysisRequest): AnalysisResult {
        val matrix = NumericMatrix.read(data, request.items, 2, 60)
        val fit = compute(matrix)
        fun value(x: Double?) = x?.let(::formatStatistic) ?: "—"
        val standard = matrix.columns.map(MatrixMath::standardize)
        val tables = mutableListOf(AnalysisTable("Scale reliability", listOf("Measure", "Value"), listOf(
            listOf("Complete participants", matrix.n.toString()), listOf("Items", matrix.k.toString()),
            listOf("Cronbach's α (raw items)", value(fit.alpha)), listOf("Cronbach's α (standardized)", value(fit.standardizedAlpha)),
            listOf("McDonald's ω (raw items)", value(fit.omega)), listOf("McDonald's ω (standardized)", value(fit.standardizedOmega)),
        )), AnalysisTable("Item diagnostics", listOf("Item", "Mean", "SD", "Item-rest r", "α if removed"), matrix.labels.indices.map { i ->
            listOf(matrix.labels[i], formatStatistic(standard[i].originalMean), formatStatistic(standard[i].originalSD), value(fit.itemRest[i]), value(fit.alphaDropped[i]))
        }, listOf("Item-rest correlations and alpha if removed use raw items and the same complete-case sample.")))
        fit.loadings?.let { l -> tables.add(AnalysisTable("One-factor model", listOf("Item", "Standardized loading", "Uniqueness"),
            l.indices.map { listOf(matrix.labels[it], formatStatistic(l[it]), formatStatistic(1 - l[it] * l[it])) })) }
        if (request.showItemCorrelations) tables.add(AnalysisTable("Inter-item correlations", listOf("Item") + matrix.labels,
            matrix.labels.indices.map { i -> listOf(matrix.labels[i]) + matrix.labels.indices.map { j ->
                if (standard[i].sd == 0.0 || standard[j].sd == 0.0) "—" else formatStatistic(fit.correlation[i][j])
            } }))
        return AnalysisResult(request.method, "Internal consistency · complete cases", null, matrix.n.toLong(), matrix.excluded,
            tables = tables, notes = buildList {
                add("Alpha and omega describe internal consistency; they do not establish validity or unidimensionality. Interpret them with the questionnaire and study design.")
                add("Omega uses a single congeneric factor fitted by maximum likelihood. Standardized coefficients give each item unit variance; raw coefficients refer to the unweighted sum on the original scales.")
                add("Reverse-score negatively keyed items in Data first. No item is automatically reversed or removed, and no pass/fail cutoff is imposed.")
                if (fit.alpha == null) add("Raw alpha is unavailable because the total score has no usable variation.")
                if (fit.alpha != null && fit.alpha < 0) add("Negative alpha indicates inconsistent covariance; check item direction and scoring before interpreting the scale.")
                fit.omegaNote?.let(::add)
            })
    }
}
