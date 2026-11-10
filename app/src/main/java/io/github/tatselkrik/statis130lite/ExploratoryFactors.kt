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
enum class EigenvalueBasis(val label: String) {
    COMMON_FACTOR("Common-factor eigenvalues"), CORRELATION("Correlation / PCA eigenvalues"),
}
enum class FactorParallelMethod(val label: String) {
    COMMON_FACTOR("Common factors · 20 Gaussian simulations"), PCA_WISHART("PCA · 200 Wishart simulations"),
}
enum class FactorExtraction(val label: String) { MINRES("Minimum residual (MINRES)"), PRINCIPAL_AXIS("Principal axis factoring") }
enum class MinresConvergence(val label: String) {
    REFERENCE("Reference optimizer stopping"), TIGHT("Tight spectral least squares"),
}
enum class PrincipalAxisConvergence(val label: String) {
    REFERENCE("Total communality change ≤ 0.001"), TIGHT("Tight individual communalities"),
}
enum class FactorRotation(val label: String) { OBLIMIN("Oblimin · correlated factors"), VARIMAX("Varimax · orthogonal"), PROMAX("Promax · correlated factors"), NONE("None") }
enum class VarimaxConvergence(val label: String) {
    REFERENCE("Polar iteration · relative change 0.00001"), TIGHT("Tight pairwise plane rotations"),
}
data class ScreePlot(val observed: List<Double>, val parallel95: List<Double>, val basis: EigenvalueBasis = EigenvalueBasis.CORRELATION,
    val replications: Int = if (parallel95.isEmpty()) 0 else 200, val seed: Int? = null)
internal data class EfaFit(val n: Int, val excluded: Int, val correlation: Array<DoubleArray>, val eigenvalues: DoubleArray,
    val parallel95: DoubleArray?, val suggested: Int, val count: Int, val pattern: Array<DoubleArray>?,
    val structure: Array<DoubleArray>?, val phi: Array<DoubleArray>?, val communalities: DoubleArray?,
    val unrotatedSS: List<Double>, val kmo: Double?, val itemKmo: List<Double?>, val bartlett: Double, val bartlettP: Double,
    val rmsr: Double?, val notes: List<String>, val commonEigenvalues: DoubleArray? = null)
internal data class CommonSpectrum(val values: DoubleArray, val note: String? = null)
internal data class ReferenceParallel(val thresholds: DoubleArray, val provisionalReplicates: Int)

/** Correlation-based common-factor models; never substitutes PCA loadings for factor extraction. */
internal object ExploratoryFactors {
    const val PARALLEL_REPLICATIONS = 200
    const val REFERENCE_PARALLEL_REPLICATIONS = 20
    const val PARALLEL_SEED = 20261109L
    private const val UNIQUE_MIN = 0.005

    private fun inverse(matrix: Array<DoubleArray>): Array<DoubleArray> {
        val eigen = MatrixMath.eigen(matrix)
        require(eigen.values.last() > eigen.values.first() * 1e-10) { "The correlation or rotation matrix is singular or nearly singular. Remove redundant variables or reduce factors." }
        return Array(matrix.size) { i -> DoubleArray(matrix.size) { j -> MatrixMath.sum(DoubleArray(matrix.size) {
            eigen.vectors[i][it] * eigen.vectors[j][it] / eigen.values[it]
        }) } }
    }

    private fun loadings(matrix: Array<DoubleArray>, count: Int): Array<DoubleArray> = loadings(MatrixMath.eigen(matrix), count)
    private fun loadings(eigen: MatrixMath.Eigen, count: Int): Array<DoubleArray> {
        require(eigen.values[count - 1] > 1e-10) { "The reduced correlation matrix cannot support this many common factors. Reduce the factor count." }
        return Array(eigen.values.size) { i -> DoubleArray(count) { j -> eigen.vectors[i][j] * sqrt(eigen.values[j]) } }
    }

    private data class PrincipalAxisFit(val loadings: Array<DoubleArray>, val spectrum: DoubleArray, val converged: Boolean)
    private fun principalAxis(r: Array<DoubleArray>, count: Int, initial: DoubleArray,
        convergence: PrincipalAxisConvergence, preliminary: Boolean = false): PrincipalAxisFit {
        var common = initial.copyOf()
        val reference = convergence == PrincipalAxisConvergence.REFERENCE
        val limit = if (reference) 50 else 1000
        var last: PrincipalAxisFit? = null
        repeat(limit) {
            val reduced = Array(r.size) { i -> DoubleArray(r.size) { j -> if (i == j) common[i] else r[i][j] } }
            val eigen = MatrixMath.eigen(reduced)
            val l = loadings(eigen, count)
            val next = DoubleArray(r.size) { MatrixMath.dot(l[it], l[it]) }
            require(next.all(Double::isFinite)) { "Principal axis communalities exceeded the supported range." }
            require(preliminary || next.all { it < 1.0 && it >= 0.0 }) { "Principal axis factoring reached a negative/zero uniqueness (Heywood) solution. Reduce factors or use constrained MINRES." }
            val converged = if (reference) abs(MatrixMath.sum(next) - MatrixMath.sum(common)) <= 0.001
                else common.indices.maxOf { abs(common[it] - next[it]) } < 1e-8
            last = PrincipalAxisFit(l, eigen.values, converged)
            if (converged) return last!!
            common = if (reference) next else DoubleArray(r.size) { (common[it] + next[it]) / 2 }
        }
        if (preliminary) return requireNotNull(last)
        throw IllegalArgumentException("Principal axis factoring did not converge within $limit iterations. Reduce factors, inspect the correlations or explicitly choose tight convergence.")
    }

    /** The reference EFA spectrum comes from a preliminary one-factor common fit.
     * Principal axis uses the last reduced matrix BEFORE updating its communalities.
     * MINRES uses the fitted one-factor communalities on the diagonal instead.
     */
    internal fun commonSpectrum(r: Array<DoubleArray>, request: AnalysisRequest): CommonSpectrum {
        if (r.indices.sumOf { i -> r.indices.filter { it != i }.sumOf { j -> r[i][j] * r[i][j] } } <= 1e-12)
            return CommonSpectrum(DoubleArray(r.size))
        val inv = inverse(r)
        val smc = DoubleArray(r.size) { (1 - 1 / inv[it][it]).coerceIn(0.0, 1.0) }
        if (request.factorExtraction == FactorExtraction.PRINCIPAL_AXIS) {
            val fit = principalAxis(r, 1, if (request.principalAxisConvergence == PrincipalAxisConvergence.TIGHT)
                DoubleArray(r.size) { smc[it].coerceIn(0.005, 0.995) } else smc, request.principalAxisConvergence, preliminary = true)
            val issue = !fit.converged || fit.loadings.any { MatrixMath.dot(it, it) >= 1 }
            return CommonSpectrum(fit.spectrum, if (issue) "The preliminary one-factor PA fit used for eigenvalues reached an iteration or uniqueness boundary. Its selection spectrum is provisional; it is not an accepted factor solution." else null)
        }
        val fit = if (request.minresConvergence == MinresConvergence.REFERENCE) minresReference(r, 1, smc, preliminary = true) else null
        val l = fit?.loadings ?: minresTight(r, 1, smc)
        val common = DoubleArray(r.size) { MatrixMath.dot(l[it], l[it]) }
        val values = MatrixMath.eigen(Array(r.size) { i -> DoubleArray(r.size) { j -> if (i == j) common[i] else r[i][j] } }).values
        return CommonSpectrum(values, if (common.any { it >= 1 } || fit?.converged == false)
            "The preliminary one-factor MINRES fit used for eigenvalues reached an optimizer or uniqueness boundary${if (fit?.converged == false) " (${fit.reason})" else ""}. Its selection spectrum is provisional; it is not an accepted factor solution." else null)
    }

    private data class MinresFit(val loadings: Array<DoubleArray>, val converged: Boolean, val reason: String)
    private fun minresReference(r: Array<DoubleArray>, count: Int, initial: DoubleArray, preliminary: Boolean = false): MinresFit {
        fun load(x: DoubleArray): Array<DoubleArray> {
            val eigen = MatrixMath.eigen(Array(r.size) { i -> DoubleArray(r.size) { j -> if (i == j) 1 - 0.01 * x[i] else r[i][j] } })
            // Trial reduced matrices can have fewer positive eigenvalues than the final fit.
            // Their nonpositive components contribute zero rather than aborting optimization.
            return Array(r.size) { i -> DoubleArray(count) { j -> eigen.vectors[i][j] * sqrt(eigen.values[j].coerceAtLeast(0.0)) } }
        }
        // Uniqueness parameters are scaled by 0.01. The diagonal fixed-point residual is
        // the reference MINRES updating direction, rather than an exact derivative of
        // the off-diagonal objective. Keep this convention explicit.
        val fit = BoundedQuasiNewton.minimize(DoubleArray(r.size) { 100 * (1 - initial[it]) },
            DoubleArray(r.size) { 0.5 }, DoubleArray(r.size) { 100.0 }, { x ->
                val l = load(x)
                val residuals = DoubleArray(r.size * (r.size - 1) / 2)
                var pair = 0
                for (i in r.indices) for (j in 0 until i) {
                    val error = MatrixMath.dot(l[i], l[j]) - r[i][j]
                    residuals[pair++] = error * error
                }
                BoundedQuasiNewton.Evaluation(MatrixMath.sum(residuals), DoubleArray(r.size) {
                    0.01 * (MatrixMath.dot(l[it], l[it]) + 0.01 * x[it] - 1)
                })
            })
        require(preliminary || fit.converged) { "MINRES did not converge within the reference optimizer limits (${fit.reason}). Inspect the correlations or explicitly select tight spectral least squares." }
        val final = load(fit.x)
        require(preliminary || (0 until count).all { j -> final.sumOf { it[j] * it[j] } > 1e-10 }) {
            "The converged reduced correlation matrix supports fewer positive common factors than requested. Reduce the factor count."
        }
        return MinresFit(final, fit.converged, fit.reason)
    }

    private fun minresTight(r: Array<DoubleArray>, count: Int, initial: DoubleArray): Array<DoubleArray> {
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

    private fun rotate(input: Array<DoubleArray>, kind: FactorRotation, convergence: VarimaxConvergence): Pair<Array<DoubleArray>, Array<DoubleArray>> {
        val p = input.size; val m = input.first().size
        val unit = Array(m) { i -> DoubleArray(m) { j -> if (i == j) 1.0 else 0.0 } }
        if (kind == FactorRotation.NONE || m == 1) return input to unit
        if (kind == FactorRotation.OBLIMIN) {
            val fit = ReferenceOblimin.rotate(input)
            require(fit.converged) { "Oblimin rotation did not converge (${fit.reason}). Inspect the correlations, reduce factors or explicitly select another rotation." }
            return fit.pattern to fit.phi
        }
        val orthogonal = if (convergence == VarimaxConvergence.REFERENCE)
            ReferenceVarimax.rotate(input) else varimax(input)
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

    internal fun parallel(n: Int, p: Int, seed: Long = PARALLEL_SEED): DoubleArray {
        require(p in 3..20 && n > p)
        val random = Random(seed)
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

    internal fun parallelReference(n: Int, p: Int, request: AnalysisRequest, seed: Int): ReferenceParallel {
        require(p in 3..20 && n >= maxOf(10, p + 1) && n <= 20_000) { "Reference parallel analysis needs 3–20 variables and max(10, variables + 1) to 20,000 complete observations." }
        require(n.toLong() * p * p * REFERENCE_PARALLEL_REPLICATIONS <= 60_000_000L) {
            "Reference parallel analysis exceeds the on-device work limit. Use fewer variables, a fixed factor count or the PCA Wishart alternative."
        }
        val random = ReferenceRandom(seed)
        val values = Array(p) { DoubleArray(REFERENCE_PARALLEL_REPLICATIONS) }
        var provisional = 0
        repeat(REFERENCE_PARALLEL_REPLICATIONS) { run ->
            // R's matrix(rnorm(N * P), nrow = N) fills each column before the next.
            val columns = List(p) { DoubleArray(n) { random.normal() } }
            val correlation = MatrixMath.correlation(columns.map(MatrixMath::standardize))
            val spectrum = try { commonSpectrum(correlation, request) } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("Reference parallel simulation ${run + 1} could not produce a usable common-factor spectrum: ${error.message} Choose another seed or an explicit factor count.")
            }
            if (spectrum.note != null) provisional++
            for (j in 0 until p) values[j][run] = spectrum.values[j]
        }
        return ReferenceParallel(DoubleArray(p) { Descriptives.percentile(values[it].sortedArray(), 95.0) }, provisional)
    }

    private fun parallelSeed(request: AnalysisRequest): Int = request.factorParallelSeed.trim().toIntOrNull()?.also {
        require(it != Int.MIN_VALUE) { "Use an integer seed from −2,147,483,647 to 2,147,483,647." }
    } ?: throw IllegalArgumentException("Enter a whole-number parallel-analysis seed from −2,147,483,647 to 2,147,483,647.")

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
        val notes = mutableListOf<String>()
        val selectionSpectrum = try { commonSpectrum(r, request).also { it.note?.let(notes::add) } }
            catch (e: IllegalArgumentException) { notes.add("Common-factor eigenvalues are unavailable: ${e.message}"); null }
        val simulated = if (request.factorCountMethod == FactorCountMethod.PARALLEL) {
            val seed = parallelSeed(request)
            if (request.factorParallelMethod == FactorParallelMethod.COMMON_FACTOR) parallelReference(matrix.n, matrix.k, request, seed).also {
                if (it.provisionalReplicates > 0) notes.add("${it.provisionalReplicates} of $REFERENCE_PARALLEL_REPLICATIONS null preliminary fits reached an iteration or uniqueness boundary. Their spectra follow the selected reference protocol and can be numerically sensitive; interpret retention as a heuristic.")
            }.thresholds else parallel(matrix.n, matrix.k, seed.toLong())
        } else null
        val maximum = maximumFactors(matrix.k)
        val suggested = when (request.factorCountMethod) {
            FactorCountMethod.FIXED -> request.factorCount.trim().toIntOrNull()?.also { require(it in 1..maximum) { "With ${matrix.k} variables, select 1–$maximum identifiable factors." } }
                ?: throw IllegalArgumentException("Enter a whole number of factors.")
            FactorCountMethod.EIGENVALUE -> {
                val threshold = request.factorEigenvalue.trim().toDoubleOrNull()
                require(threshold != null && threshold.isFinite() && threshold >= 0) { "Enter a nonnegative finite eigenvalue threshold." }
                val spectrum = if (request.factorEigenvalueBasis == EigenvalueBasis.COMMON_FACTOR)
                    requireNotNull(selectionSpectrum) { "Common-factor eigenvalues are unavailable. Inspect the correlations or explicitly choose correlation eigenvalues." }.values else eigen.values
                spectrum.count { it > threshold }
            }
            FactorCountMethod.PARALLEL -> {
                val reference = request.factorParallelMethod == FactorParallelMethod.COMMON_FACTOR
                val spectrum = if (reference) requireNotNull(selectionSpectrum) { "Common-factor eigenvalues are unavailable for reference parallel analysis. Inspect the data or choose another retention method." }.values else eigen.values
                val retained = spectrum.indices.takeWhile { spectrum[it] > simulated!![it] }.size
                if (reference) retained.coerceAtLeast(1) else retained
            }
        }
        val count = if (suggested in 1..maximum) suggested else 0
        if (suggested == 0) notes.add("The selected retention rule suggests zero factors. No common-factor solution is forced. Inspect the eigenvalues and theory, or explicitly choose a fixed count.")
        if (suggested > maximum) notes.add("The retention rule suggests $suggested factors, above the supported identifiable maximum of $maximum for ${matrix.k} variables. Inspect the eigenvalues and explicitly choose a smaller fixed count; no count is silently truncated.")
        if (count == 0) return EfaFit(matrix.n, matrix.excluded, r, eigen.values, simulated, suggested, count, null, null, null, null,
            emptyList(), kmo, itemKmo, chi, chiP, null, notes, selectionSpectrum?.values)
        require(rSquares.sum() > 1e-12) { "There are no usable inter-variable correlations for common-factor extraction. Inspect the items or use the retention results without forcing a factor." }
        val smc = DoubleArray(matrix.k) { (1 - 1 / inv[it][it]).coerceIn(0.0, 1.0) }
        val initial = when (request.factorExtraction) {
            FactorExtraction.MINRES -> if (request.minresConvergence == MinresConvergence.REFERENCE) minresReference(r, count, smc).loadings else minresTight(r, count, smc)
            FactorExtraction.PRINCIPAL_AXIS -> principalAxis(r, count,
                if (request.principalAxisConvergence == PrincipalAxisConvergence.TIGHT) DoubleArray(matrix.k) { smc[it].coerceIn(0.005, 0.995) } else smc,
                request.principalAxisConvergence).loadings
        }
        val ss = (0 until count).map { j -> initial.sumOf { it[j] * it[j] } }
        val (rotated, correlation) = rotate(initial, request.factorRotation, request.varimaxConvergence)
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
            common, ss, kmo, itemKmo, chi, chiP, sqrt(residualSS / (matrix.k * (matrix.k - 1) / 2)), notes, selectionSpectrum?.values)
    }

    fun run(data: CourseData, request: AnalysisRequest): AnalysisResult {
        val cutoff = request.factorLoadingCutoff.trim().toDoubleOrNull()
        require(cutoff != null && cutoff.isFinite() && cutoff in 0.0..1.0) { "Enter a loading display threshold between 0 and 1." }
        val matrix = NumericMatrix.read(data, request.items, 3, 20)
        val fit = compute(matrix, request)
        val plotBasis = if (request.factorCountMethod == FactorCountMethod.PARALLEL)
            if (request.factorParallelMethod == FactorParallelMethod.COMMON_FACTOR) EigenvalueBasis.COMMON_FACTOR else EigenvalueBasis.CORRELATION
            else request.factorEigenvalueBasis
        val plotValues = if (plotBasis == EigenvalueBasis.COMMON_FACTOR) fit.commonEigenvalues else fit.eigenvalues
        val factorNames = List(fit.count) { "Factor ${it + 1}" }
        fun value(x: Double?) = x?.let(::formatStatistic) ?: "—"
        val tables = buildList {
            add(AnalysisTable("Factor selection", listOf("Measure", "Value"), listOf(listOf("Complete observations", fit.n.toString()),
                listOf("Variables", matrix.k.toString()), listOf("Selection", request.factorCountMethod.label), listOf("Suggested factors", fit.suggested.toString()),
                listOf("Extracted factors", fit.count.toString()), listOf("Extraction", request.factorExtraction.label), listOf("Rotation", request.factorRotation.label))))
            add(AnalysisTable("Eigenvalues and scree values", listOf("Order", "Correlation / PCA eigenvalue", "Common-factor eigenvalue", "${if (request.factorParallelMethod == FactorParallelMethod.COMMON_FACTOR) "Common-factor" else "PCA"} parallel 95th percentile"), fit.eigenvalues.indices.map {
                listOf((it + 1).toString(), formatStatistic(fit.eigenvalues[it]), value(fit.commonEigenvalues?.get(it)), value(fit.parallel95?.get(it))) },
                listOf("Common-factor eigenvalues use a preliminary one-factor fit with the selected extraction and stopping rule. Correlation eigenvalues have a unit diagonal. The scree plot uses ${plotBasis.label.lowercase()}. Parallel thresholds use the same basis as their observed curve.",
                    "Rows beyond the supported maximum of ${maximumFactors(matrix.k)} factors are contextual eigenvalue summaries, not additional supported factor solutions.")))
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
                if (request.factorRotation in listOf(FactorRotation.PROMAX, FactorRotation.OBLIMIN) && fit.count > 1) add(loadingTable("Factor structure correlations", fit.structure!!))
                add(AnalysisTable("Factor correlations", listOf("Factor") + factorNames, factorNames.indices.map { i -> listOf(factorNames[i]) + fit.phi!![i].map(::formatStatistic) }))
                add(AnalysisTable("Unrotated extracted common variance", listOf("Extraction order", "SS loadings", "% of total standardized variance"), fit.unrotatedSS.mapIndexed { i, ss ->
                    listOf((i + 1).toString(), formatStatistic(ss), formatStatistic(100 * ss / matrix.k)) },
                    listOf("This table describes the orthogonal extraction before rotation; it is not a ranking of the displayed rotated factors. Correlated factor contributions cannot be interpreted as independent portions.")))
                add(AnalysisTable("Residual correlation fit", listOf("Measure", "Value"), listOf(listOf("RMSR (off-diagonal)", value(fit.rmsr))),
                    listOf("Root mean squared off-diagonal correlation residual. This is a descriptive fit statistic, not a significance test or a validity certificate.")))
            }
        }
        return AnalysisResult(request.method, "Common-factor analysis · Pearson correlations · complete cases", null, fit.n.toLong(), fit.excluded,
            tables = tables, plots = if (request.factorScreePlot && plotValues != null) AnalysisPlots(scree = ScreePlot(plotValues.toList(), fit.parallel95?.toList() ?: emptyList(), plotBasis,
                if (fit.parallel95 == null) 0 else if (request.factorParallelMethod == FactorParallelMethod.COMMON_FACTOR) REFERENCE_PARALLEL_REPLICATIONS else PARALLEL_REPLICATIONS,
                if (fit.parallel95 == null) null else parallelSeed(request))) else null,
            assumptionChecks = if (request.factorBartlett) listOf(AssumptionCheck("Bartlett's sphericity", "Selected variables", fit.n,
                TestResult("χ²", fit.bartlett, fit.bartlettP), listOf(matrix.k * (matrix.k - 1) / 2.0))) else emptyList(), notes = buildList {
                add("Uses 3–20 varying numeric variables and the same complete participants throughout, with N ≥ max(10, variables + 1). Pearson correlations treat items as quantitative; this is not polychoric/ordinal factor analysis. Data adequacy depends on the design and correlation structure, not this minimum alone.")
                add("${request.factorExtraction.label}; ${request.factorRotation.label}. MINRES constrains uniqueness to [0.005, 1]; principal axis factoring iterates squared-multiple-correlation communalities. PCA is used only for correlation eigenvalues and the retention rule, not as a substitute for common-factor extraction.")
                if (request.factorExtraction == FactorExtraction.MINRES) add(if (request.minresConvergence == MinresConvergence.REFERENCE)
                    "MINRES uses bounded uniqueness updates from squared multiple correlations, off-diagonal residual loss and reference optimizer stopping. Numerical stopping is part of the selected convention; it need not give the smallest attainable residual loss."
                    else "The tight spectral least-squares alternative uses logistic uniqueness bounds, multiple starts and a gradient stopping rule. It can give a more converged fit than the reference optimizer and different last digits.")
                if (request.factorExtraction == FactorExtraction.PRINCIPAL_AXIS) add(if (request.principalAxisConvergence == PrincipalAxisConvergence.REFERENCE)
                    "Principal axis uses undamped communality updates and stops when the absolute change in total communality is at most 0.001, following jamovi. The limit is 50 iterations; an unconverged or Heywood solution is unavailable."
                    else "Principal axis uses damped communality updates until every individual change is below 1e-8, with a limit of 1,000 iterations. This tighter rule can differ from jamovi's default stopping point.")
                if (request.factorCountMethod == FactorCountMethod.PARALLEL) add(if (request.factorParallelMethod == FactorParallelMethod.COMMON_FACTOR)
                    "Parallel analysis compares leading common-factor eigenvalues with 95th-percentile thresholds from $REFERENCE_PARALLEL_REPLICATIONS Gaussian null datasets of the same complete N and variable count, using the selected extraction and stopping rule. Seed ${request.factorParallelSeed.trim()} uses the R-compatible Mersenne-Twister/Inversion stream. Stop at the first failed comparison, retaining at least one suggested factor as in jamovi; this minimum does not establish a usable or valid factor model. Match the seed and RNG kind for reproducibility; an unseeded jamovi session can differ."
                    else "PCA parallel analysis compares ordered correlation eigenvalues with the 95th percentile of $PARALLEL_REPLICATIONS Gaussian null correlations (seed ${request.factorParallelSeed.trim()}). Exact Wishart sampling uses N−1 degrees of freedom without an N-by-items matrix. Stop at the first failed comparison and retain a zero-factor suggestion. This PCA-eigenvalue rule differs from common-factor parallel analysis.")
                if (request.factorCountMethod == FactorCountMethod.EIGENVALUE) add("The eigenvalue rule retains ${request.factorEigenvalueBasis.label.lowercase()} strictly above ${request.factorEigenvalue.trim()}; it is a heuristic, not a fit test. Common-factor spectra depend on a preliminary one-factor fit, following jamovi.")
                add("Varimax uses Kaiser normalization and orthogonal factors. Promax uses the Kaiser-normalized varimax solution and power 4; its pattern coefficients and structure correlations differ when factors correlate. Factor signs and order are arbitrary and are standardized only for readability.")
                if (request.factorRotation == FactorRotation.VARIMAX) add(if (request.varimaxConvergence == VarimaxConvergence.REFERENCE)
                    "Varimax uses polar iteration with relative singular-sum change 0.00001, up to 1,000 iterations. A stationary solution can fail to maximize the rotation criterion, especially with symmetric loadings; tight pairwise plane rotations are a separate option."
                    else "Varimax uses tight pairwise plane rotations with angle tolerance 0.0000001, up to 250 sweeps. This stopping convention can differ from the reference polar iteration.")
                if (request.factorRotation == FactorRotation.PROMAX) add("Promax starts from " +
                    if (request.varimaxConvergence == VarimaxConvergence.REFERENCE) "reference polar Varimax stopping (relative change 0.00001)." else "tight pairwise plane Varimax stopping (angle tolerance 0.0000001).")
                if (request.factorRotation == FactorRotation.OBLIMIN) add("Oblimin minimizes unnormalized quartimin (gamma 0), starting from the identity transformation. It stops when the projected-gradient norm is below 0.00001, with up to 1,000 iterations and no random starts. Factors may correlate; structure equals pattern times factor correlations. A stationary solution need not be a global minimum. Singular or unconverged rotations are unavailable; another rotation is never substituted automatically.")
                add("KMO summarizes correlations relative to partial correlations. Bartlett tests an identity correlation matrix using a chi-square approximation under multivariate normality. Neither proves an appropriate factor model, validity or reliability. No observations are imputed and no items are automatically reversed or removed.")
                add("Supports up to six factors and counts with nonnegative nominal model degrees of freedom. This count limit alone does not establish identification in a particular dataset. Singular correlations, nonconvergence and invalid communalities produce an explanation; they are not silently replaced by another estimator. The implementation's options and numerical conventions can differ from jamovi.")
                addAll(fit.notes)
            })
    }
}
