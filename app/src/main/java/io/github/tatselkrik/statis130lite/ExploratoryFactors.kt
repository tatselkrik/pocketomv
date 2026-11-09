package io.github.tatselkrik.statis130lite

import java.util.Random
import org.apache.commons.statistics.distribution.ChiSquaredDistribution
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class FactorCountMethod(val label: String) {
    PARALLEL("Parallel analysis · 95th percentile"), EIGENVALUE("Eigenvalue threshold"), FIXED("Fixed number"),
}
enum class FactorExtraction(val label: String) { MINRES("Minimum residual (MINRES)"), PRINCIPAL_AXIS("Principal axis factoring") }
enum class FactorRotation(val label: String) { VARIMAX("Varimax · orthogonal"), PROMAX("Promax · correlated factors"), NONE("None") }
data class ScreePlot(val observed: List<Double>, val parallel95: List<Double>)
internal data class EfaFit(val n: Int, val excluded: Int, val correlation: Array<DoubleArray>, val eigenvalues: DoubleArray,
    val parallel95: DoubleArray?, val suggested: Int, val count: Int, val pattern: Array<DoubleArray>?,
    val structure: Array<DoubleArray>?, val phi: Array<DoubleArray>?, val communalities: DoubleArray?,
    val unrotatedSS: List<Double>, val kmo: Double?, val itemKmo: List<Double?>, val bartlett: Double, val bartlettP: Double,
    val rmsr: Double?, val notes: List<String>)

/** Correlation-based common-factor models; never substitutes PCA loadings for factor extraction. */
internal object ExploratoryFactors {
    const val PARALLEL_REPLICATIONS = 200
    const val PARALLEL_SEED = 20261109L
    private const val UNIQUE_MIN = 0.005

    private fun inverse(matrix: Array<DoubleArray>): Array<DoubleArray> {
        val eigen = MatrixMath.eigen(matrix)
        require(eigen.values.last() > eigen.values.first() * 1e-10) { "The correlation or rotation matrix is singular or nearly singular. Remove redundant variables or reduce factors." }
        return Array(matrix.size) { i -> DoubleArray(matrix.size) { j -> MatrixMath.sum(DoubleArray(matrix.size) {
            eigen.vectors[i][it] * eigen.vectors[j][it] / eigen.values[it]
        }) } }
    }

    private fun loadings(matrix: Array<DoubleArray>, count: Int): Array<DoubleArray> {
        val eigen = MatrixMath.eigen(matrix)
        require(eigen.values[count - 1] > 1e-10) { "The reduced correlation matrix cannot support this many common factors. Reduce the factor count." }
        return Array(matrix.size) { i -> DoubleArray(count) { j -> eigen.vectors[i][j] * sqrt(eigen.values[j]) } }
    }

    private fun principalAxis(r: Array<DoubleArray>, count: Int, initial: DoubleArray): Array<DoubleArray> {
        var common = initial.copyOf()
        repeat(1000) {
            val reduced = Array(r.size) { i -> DoubleArray(r.size) { j -> if (i == j) common[i] else r[i][j] } }
            val l = loadings(reduced, count)
            val next = DoubleArray(r.size) { MatrixMath.dot(l[it], l[it]) }
            require(next.all { it < 1.0 && it >= 0.0 }) { "Principal axis factoring reached a negative/zero uniqueness (Heywood) solution. Reduce factors or use constrained MINRES." }
            if (common.indices.maxOf { abs(common[it] - next[it]) } < 1e-8) return l
            common = DoubleArray(r.size) { (common[it] + next[it]) / 2 }
        }
        throw IllegalArgumentException("Principal axis factoring did not converge. Reduce factors or inspect the correlations.")
    }

    private fun minres(r: Array<DoubleArray>, count: Int, initial: DoubleArray): Array<DoubleArray> {
        fun uniqueness(z: Double) = UNIQUE_MIN + (1 - UNIQUE_MIN) / (1 + exp(-z.coerceIn(-35.0, 35.0)))
        fun objective(z: DoubleArray): Pair<Double, DoubleArray> {
            val unique = DoubleArray(r.size) { uniqueness(z[it]) }
            val reduced = Array(r.size) { i -> DoubleArray(r.size) { j -> if (i == j) 1 - unique[i] else r[i][j] } }
            val eigen = MatrixMath.eigen(reduced)
            val l = Array(r.size) { i -> DoubleArray(count) { j -> eigen.vectors[i][j] * sqrt(eigen.values[j].coerceAtLeast(0.0)) } }
            val residual = Array(r.size) { i -> DoubleArray(r.size) { j -> reduced[i][j] - MatrixMath.dot(l[i], l[j]) } }
            val value = residual.sumOf { MatrixMath.dot(it, it) }
            val gradient = DoubleArray(r.size) { i ->
                val fraction = ((unique[i] - UNIQUE_MIN) / (1 - UNIQUE_MIN)).coerceIn(0.0, 1.0)
                -2 * residual[i][i] * (1 - UNIQUE_MIN) * fraction * (1 - fraction)
            }
            return value to gradient
        }
        val starts = listOf(initial.map { (1 - it).coerceIn(0.02, 0.98) }.toDoubleArray(), DoubleArray(r.size) { 0.5 })
        val best = starts.map { start -> MatrixMath.minimize(DoubleArray(r.size) {
            val fraction = ((start[it] - UNIQUE_MIN) / (1 - UNIQUE_MIN)).coerceIn(1e-6, 1 - 1e-6)
            ln(fraction / (1 - fraction))
        }, ::objective) }.filter { it.converged }.minByOrNull { it.value }
            ?: throw IllegalArgumentException("MINRES did not converge. Reduce the factor count or inspect the selected items.")
        return loadings(Array(r.size) { i -> DoubleArray(r.size) { j -> if (i == j) 1 - uniqueness(best.x[i]) else r[i][j] } }, count)
    }

    /** Pairwise plane rotations maximize the Kaiser-normalized varimax criterion. */
    internal fun varimax(input: Array<DoubleArray>): Array<DoubleArray> {
        val p = input.size; val m = input.first().size
        if (m == 1) return Array(p) { input[it].copyOf() }
        val scales = DoubleArray(p) { sqrt(MatrixMath.dot(input[it], input[it])).coerceAtLeast(1e-12) }
        val l = Array(p) { i -> DoubleArray(m) { input[i][it] / scales[i] } }
        repeat(250) {
            var largest = 0.0
            for (a in 0 until m) for (b in a + 1 until m) {
                val u = DoubleArray(p) { l[it][a] * l[it][a] - l[it][b] * l[it][b] }
                val v = DoubleArray(p) { 2 * l[it][a] * l[it][b] }
                val su = MatrixMath.sum(u); val sv = MatrixMath.sum(v)
                val numerator = 2 * (MatrixMath.dot(u, v) - su * sv / p)
                val denominator = MatrixMath.dot(u, u) - MatrixMath.dot(v, v) - (su * su - sv * sv) / p
                val angle = 0.25 * atan2(numerator, denominator)
                largest = maxOf(largest, abs(angle))
                val c = cos(angle); val s = sin(angle)
                for (i in 0 until p) { val x = l[i][a]; val y = l[i][b]; l[i][a] = c * x + s * y; l[i][b] = -s * x + c * y }
            }
            if (largest < 1e-7) return Array(p) { i -> DoubleArray(m) { l[i][it] * scales[i] } }
        }
        throw IllegalArgumentException("Varimax rotation did not converge. Try unrotated factors or fewer factors.")
    }

    private fun rotate(input: Array<DoubleArray>, kind: FactorRotation): Pair<Array<DoubleArray>, Array<DoubleArray>> {
        val p = input.size; val m = input.first().size
        val unit = Array(m) { i -> DoubleArray(m) { j -> if (i == j) 1.0 else 0.0 } }
        if (kind == FactorRotation.NONE || m == 1) return input to unit
        val orthogonal = varimax(input)
        if (kind == FactorRotation.VARIMAX) return orthogonal to unit
        val scale = DoubleArray(p) { sqrt(MatrixMath.dot(orthogonal[it], orthogonal[it])).coerceAtLeast(1e-12) }
        val b = Array(p) { i -> DoubleArray(m) { orthogonal[i][it] / scale[i] } }
        val qr = MatrixMath.qr((0 until m).map { j -> DoubleArray(p) { b[it][j] } })
        val targetColumns = (0 until m).map { j -> DoubleArray(p) { b[it][j] * abs(b[it][j]).pow(3) } }
        val transformColumns = targetColumns.map(qr::solve)
        val gram = Array(m) { i -> DoubleArray(m) { j -> MatrixMath.dot(transformColumns[i], transformColumns[j]) } }
        val phi0 = inverse(gram)
        val factorScale = DoubleArray(m) { sqrt(phi0[it][it]) }
        val pattern = Array(p) { i -> DoubleArray(m) { j -> MatrixMath.dot(orthogonal[i], transformColumns[j]) * factorScale[j] } }
        val phi = Array(m) { i -> DoubleArray(m) { j -> phi0[i][j] / (factorScale[i] * factorScale[j]) } }
        return pattern to phi
    }

    // Exact Wishart sampling avoids an N-by-P allocation in every parallel-analysis replication.
    // The centered Gaussian sample covariance has N-1 degrees of freedom.
    private fun gamma(random: Random, shape: Double): Double {
        if (shape < 1) return gamma(random, shape + 1) * random.nextDouble().coerceAtLeast(1e-300).pow(1 / shape)
        val d = shape - 1.0 / 3; val c = 1 / sqrt(9 * d)
        repeat(10000) {
            val z = random.nextGaussian(); val root = 1 + c * z
            if (root > 0) {
                val v = root * root * root; val u = random.nextDouble().coerceAtLeast(1e-300)
                if (u < 1 - 0.0331 * z.pow(4) || ln(u) < z * z / 2 + d * (1 - v + ln(v))) return d * v
            }
        }
        throw IllegalArgumentException("Parallel-analysis random sampling did not converge. Try a fixed factor count.")
    }

    internal fun parallel(n: Int, p: Int): DoubleArray {
        require(p in 3..20 && n > p)
        val random = Random(PARALLEL_SEED)
        val values = Array(p) { DoubleArray(PARALLEL_REPLICATIONS) }
        repeat(PARALLEL_REPLICATIONS) { run ->
            val a = Array(p) { i -> DoubleArray(p) { j -> when { j < i -> random.nextGaussian(); j == i -> sqrt(2 * gamma(random, (n - 1 - i) / 2.0)); else -> 0.0 } } }
            val ss = DoubleArray(p) { MatrixMath.dot(a[it], a[it]) }
            val r = Array(p) { i -> DoubleArray(p) { j -> if (i == j) 1.0 else MatrixMath.dot(a[i], a[j]) / sqrt(ss[i] * ss[j]) } }
            val eigen = MatrixMath.eigen(r).values
            for (j in 0 until p) values[j][run] = eigen[j]
        }
        return DoubleArray(p) { Descriptives.percentile(values[it].sortedArray(), 95.0) }
    }

    internal fun maximumFactors(p: Int): Int = minOf(6, ((2 * p + 1 - sqrt(8.0 * p + 1)) / 2).toInt())

    fun compute(matrix: NumericMatrix, request: AnalysisRequest): EfaFit {
        require(matrix.k in 3..20 && matrix.n >= maxOf(10, matrix.k + 1)) { "EFA needs 3–20 numeric variables and at least max(10, variables + 1) complete observations." }
        val columns = matrix.columns.map(MatrixMath::standardize)
        require(columns.all { it.sd > 0.0 }) { "Each EFA variable needs variation. Remove constant variables." }
        val r = MatrixMath.correlation(columns)
        val eigen = MatrixMath.eigen(r)
        val inv = inverse(r)
        val partial = Array(matrix.k) { i -> DoubleArray(matrix.k) { j -> if (i == j) 0.0 else -inv[i][j] / sqrt(inv[i][i] * inv[j][j]) } }
        val rSquares = DoubleArray(matrix.k) { i -> r.indices.filter { it != i }.sumOf { r[i][it] * r[i][it] } }
        val pSquares = DoubleArray(matrix.k) { i -> MatrixMath.dot(partial[i], partial[i]) }
        val itemKmo = r.indices.map { i -> (rSquares[i] / (rSquares[i] + pSquares[i])).takeIf { rSquares[i] + pSquares[i] > 1e-24 } }
        val kmo = (rSquares.sum() / (rSquares.sum() + pSquares.sum())).takeIf { rSquares.sum() + pSquares.sum() > 1e-24 }
        val chi = (-(matrix.n - 1 - (2.0 * matrix.k + 5) / 6) * eigen.values.sumOf(::ln)).coerceAtLeast(0.0)
        val chiP = ChiSquaredDistribution.of(matrix.k * (matrix.k - 1) / 2.0).survivalProbability(chi)
        val simulated = if (request.factorCountMethod == FactorCountMethod.PARALLEL) parallel(matrix.n, matrix.k) else null
        val maximum = maximumFactors(matrix.k)
        val suggested = when (request.factorCountMethod) {
            FactorCountMethod.FIXED -> request.factorCount.trim().toIntOrNull()?.also { require(it in 1..maximum) { "With ${matrix.k} variables, select 1–$maximum identifiable factors." } }
                ?: throw IllegalArgumentException("Enter a whole number of factors.")
            FactorCountMethod.EIGENVALUE -> {
                val threshold = request.factorEigenvalue.trim().toDoubleOrNull()
                require(threshold != null && threshold.isFinite() && threshold > 0) { "Enter a positive finite eigenvalue threshold." }
                eigen.values.count { it > threshold }
            }
            FactorCountMethod.PARALLEL -> eigen.values.indices.takeWhile { eigen.values[it] > simulated!![it] }.size
        }
        val notes = mutableListOf<String>()
        val count = if (suggested in 1..maximum) suggested else 0
        if (suggested == 0) notes.add("The selected retention rule suggests zero factors. No common-factor solution is forced. Inspect the eigenvalues and theory, or explicitly choose a fixed count.")
        if (suggested > maximum) notes.add("The retention rule suggests $suggested factors, above the supported identifiable maximum of $maximum for ${matrix.k} variables. Inspect the eigenvalues and explicitly choose a smaller fixed count; no count is silently truncated.")
        if (count == 0) return EfaFit(matrix.n, matrix.excluded, r, eigen.values, simulated, suggested, count, null, null, null, null,
            emptyList(), kmo, itemKmo, chi, chiP, null, notes)
        require(rSquares.sum() > 1e-12) { "There are no usable inter-variable correlations for common-factor extraction. Inspect the items or use the retention results without forcing a factor." }
        val smc = DoubleArray(matrix.k) { (1 - 1 / inv[it][it]).coerceIn(0.005, 0.995) }
        val initial = when (request.factorExtraction) {
            FactorExtraction.MINRES -> minres(r, count, smc)
            FactorExtraction.PRINCIPAL_AXIS -> principalAxis(r, count, smc)
        }
        val ss = (0 until count).map { j -> initial.sumOf { it[j] * it[j] } }
        val (rotated, correlation) = rotate(initial, request.factorRotation)
        val order = (0 until count).sortedByDescending { j -> rotated.sumOf { it[j] * it[j] } }
        val signs = order.map { j -> if (rotated.maxBy { abs(it[j]) }[j] < 0) -1.0 else 1.0 }
        val pattern = Array(matrix.k) { i -> DoubleArray(count) { j -> signs[j] * rotated[i][order[j]] } }
        val phi = Array(count) { i -> DoubleArray(count) { j -> signs[i] * signs[j] * correlation[order[i]][order[j]] } }
        val structure = Array(matrix.k) { i -> DoubleArray(count) { j -> MatrixMath.dot(pattern[i], phi[j]) } }
        val common = DoubleArray(matrix.k) { MatrixMath.dot(pattern[it], structure[it]) }
        require(common.all { it.isFinite() && it >= -1e-8 && it <= 1 + 1e-7 } && pattern.all { it.all(Double::isFinite) }) {
            "The factor solution has invalid communalities (Heywood solution). Reduce factors or inspect the data."
        }
        if (common.any { 1 - it < UNIQUE_MIN + 1e-4 }) notes.add("At least one uniqueness is at or near the MINRES lower bound of 0.005. Treat this boundary solution cautiously; inspect redundant items and the factor count.")
        var residualSS = 0.0
        for (i in r.indices) for (j in i + 1 until matrix.k) { val residual = r[i][j] - MatrixMath.dot(pattern[i], structure[j]); residualSS += residual * residual }
        return EfaFit(matrix.n, matrix.excluded, r, eigen.values, simulated, suggested, count, pattern, structure, phi,
            common, ss, kmo, itemKmo, chi, chiP, sqrt(residualSS / (matrix.k * (matrix.k - 1) / 2)), notes)
    }

    fun run(data: CourseData, request: AnalysisRequest): AnalysisResult {
        val cutoff = request.factorLoadingCutoff.trim().toDoubleOrNull()
        require(cutoff != null && cutoff.isFinite() && cutoff in 0.0..1.0) { "Enter a loading display threshold between 0 and 1." }
        val matrix = NumericMatrix.read(data, request.items, 3, 20)
        val fit = compute(matrix, request)
        val factorNames = List(fit.count) { "Factor ${it + 1}" }
        fun value(x: Double?) = x?.let(::formatStatistic) ?: "—"
        val tables = buildList {
            add(AnalysisTable("Factor selection", listOf("Measure", "Value"), listOf(listOf("Complete observations", fit.n.toString()),
                listOf("Variables", matrix.k.toString()), listOf("Selection", request.factorCountMethod.label), listOf("Suggested factors", fit.suggested.toString()),
                listOf("Extracted factors", fit.count.toString()), listOf("Extraction", request.factorExtraction.label), listOf("Rotation", request.factorRotation.label))))
            add(AnalysisTable("Correlation eigenvalues and scree values", listOf("Order", "Observed eigenvalue", "Parallel 95th percentile"), fit.eigenvalues.indices.map {
                listOf((it + 1).toString(), formatStatistic(fit.eigenvalues[it]), value(fit.parallel95?.get(it))) }))
            if (request.factorKmo) add(AnalysisTable("Kaiser–Meyer–Olkin adequacy", listOf("Variable", "MSA"),
                listOf(listOf("Overall KMO", value(fit.kmo))) + matrix.labels.indices.map { listOf(matrix.labels[it], value(fit.itemKmo[it])) },
                listOf("MSA is unavailable when both correlation and partial-correlation sums of squares are zero. No automatic pass/fail cutoff is imposed.")))
            fit.pattern?.let { pattern ->
                val order = if (request.factorSortLoadings) matrix.labels.indices.sortedWith(compareBy<Int> { i -> pattern[i].indices.maxBy { abs(pattern[i][it]) } }
                    .thenByDescending { i -> pattern[i].maxOf { abs(it) } }) else matrix.labels.indices.toList()
                fun loadingTable(title: String, loading: Array<DoubleArray>) = AnalysisTable(title, listOf("Variable") + factorNames + listOf("Communality", "Uniqueness"), order.map { i ->
                    listOf(matrix.labels[i]) + loading[i].map { if (abs(it) < cutoff) "" else formatStatistic(it) } +
                        listOf(formatStatistic(fit.communalities!![i]), formatStatistic(1 - fit.communalities[i])) },
                    listOf("Only the display hides absolute loadings below ${formatStatistic(cutoff)}; calculations retain all loadings. Communality uses pattern × factor correlations × pattern transpose."))
                add(loadingTable("Factor pattern loadings", pattern))
                if (request.factorRotation == FactorRotation.PROMAX && fit.count > 1) add(loadingTable("Factor structure correlations", fit.structure!!))
                add(AnalysisTable("Factor correlations", listOf("Factor") + factorNames, factorNames.indices.map { i -> listOf(factorNames[i]) + fit.phi!![i].map(::formatStatistic) }))
                add(AnalysisTable("Unrotated extracted common variance", listOf("Extraction order", "SS loadings", "% of total standardized variance"), fit.unrotatedSS.mapIndexed { i, ss ->
                    listOf((i + 1).toString(), formatStatistic(ss), formatStatistic(100 * ss / matrix.k)) },
                    listOf("This table describes the orthogonal extraction before rotation; it is not a ranking of the displayed rotated factors. Correlated factor contributions cannot be interpreted as independent portions.")))
                add(AnalysisTable("Residual correlation fit", listOf("Measure", "Value"), listOf(listOf("RMSR (off-diagonal)", value(fit.rmsr))),
                    listOf("Root mean squared off-diagonal correlation residual. This is a descriptive fit statistic, not a significance test or a validity certificate.")))
            }
        }
        return AnalysisResult(request.method, "Common-factor analysis · Pearson correlations · complete cases", null, fit.n.toLong(), fit.excluded,
            tables = tables, plots = if (request.factorScreePlot) AnalysisPlots(scree = ScreePlot(fit.eigenvalues.toList(), fit.parallel95?.toList() ?: emptyList())) else null,
            assumptionChecks = if (request.factorBartlett) listOf(AssumptionCheck("Bartlett's sphericity", "Selected variables", fit.n,
                TestResult("χ²", fit.bartlett, fit.bartlettP), listOf(matrix.k * (matrix.k - 1) / 2.0))) else emptyList(), notes = buildList {
                add("Uses 3–20 varying numeric variables and the same complete participants throughout, with N ≥ max(10, variables + 1). Pearson correlations treat items as quantitative; this is not polychoric/ordinal factor analysis. Data adequacy depends on the design and correlation structure, not this minimum alone.")
                add("${request.factorExtraction.label}; ${request.factorRotation.label}. MINRES constrains uniqueness to [0.005, 1]; principal axis factoring iterates squared-multiple-correlation communalities. PCA is used only for correlation eigenvalues and the retention rule, not as a substitute for common-factor extraction.")
                if (request.factorCountMethod == FactorCountMethod.PARALLEL) add("Parallel analysis compares ordered observed correlation eigenvalues with the 95th percentile of $PARALLEL_REPLICATIONS independent Gaussian null correlation matrices of the same N and variable count (seed $PARALLEL_SEED). Exact Wishart sampling uses N−1 degrees of freedom. Retain leading eigenvalues only until the first failed comparison. This is a PCA-eigenvalue retention rule; it can differ from jamovi's common-factor parallel analysis.")
                if (request.factorCountMethod == FactorCountMethod.EIGENVALUE) add("The eigenvalue rule retains observed correlation eigenvalues strictly above ${request.factorEigenvalue.trim()}; it is a heuristic, not a fit test.")
                add("Varimax uses Kaiser normalization and orthogonal factors. Promax uses the Kaiser-normalized varimax solution and power 4; its pattern coefficients and structure correlations differ when factors correlate. Factor signs and order are arbitrary and are standardized only for readability.")
                add("KMO summarizes correlations relative to partial correlations. Bartlett tests an identity correlation matrix using a chi-square approximation under multivariate normality. Neither proves an appropriate factor model, validity or reliability. No observations are imputed and no items are automatically reversed or removed.")
                add("Supports up to six factors and counts with nonnegative nominal model degrees of freedom. This count limit alone does not establish identification in a particular dataset. Singular correlations, nonconvergence and invalid communalities produce an explanation; they are not silently replaced by another estimator. The implementation's options and numerical conventions can differ from jamovi.")
                addAll(fit.notes)
            })
    }
}
