package io.github.tatselkrik.statis130lite

import org.apache.commons.statistics.descriptive.Mean
import org.apache.commons.statistics.distribution.NormalDistribution
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Royston's AS R94 approximation for complete samples, 3 <= n <= 5000.
 * Coefficients/algorithm adapted from SciPy's BSD-licensed swilk implementation.
 * See assets/third-party/scipy-LICENSE.txt and docs/statistical-methods.md.
 */
internal object ShapiroWilk {
    fun test(values: DoubleArray): TestResult {
        require(values.size in 3..5000) { "Shapiro–Wilk requires 3 to 5,000 observations." }
        require(values.all(Double::isFinite)) { "Shapiro–Wilk requires finite observations." }
        val sorted = values.sortedArray()
        val range = sorted.last() - sorted.first()
        require(range > 0.0 && range.isFinite()) { "Shapiro–Wilk is unavailable for constant data or values outside the numeric range." }
        val x = DoubleArray(sorted.size) { (sorted[it] - sorted.first()) / range }
        val n = x.size
        val a = DoubleArray(n / 2)
        if (n == 3) a[0] = sqrt(0.5) else {
            val normal = NormalDistribution.of(0.0, 1.0)
            for (i in a.indices) a[i] = normal.inverseCumulativeProbability((i + 1.0 - 0.375) / (n + 0.25))
            val squared = 2.0 * a.sumOf { it * it }
            val inverseRoot = 1.0 / sqrt(n.toDouble())
            val first = polynomial(doubleArrayOf(0.0, 0.221157, -0.147981, -2.07119, 4.434685, -2.706056), inverseRoot) - a[0] / sqrt(squared)
            val begin: Int
            val factor: Double
            if (n > 5) {
                val second = polynomial(doubleArrayOf(0.0, 0.042981, -0.293762, -1.752461, 5.682633, -3.582633), inverseRoot) - a[1] / sqrt(squared)
                factor = sqrt((squared - 2.0 * a[0] * a[0] - 2.0 * a[1] * a[1]) /
                    (1.0 - 2.0 * first * first - 2.0 * second * second))
                a[1] = second
                begin = 2
            } else {
                factor = sqrt((squared - 2.0 * a[0] * a[0]) / (1.0 - 2.0 * first * first))
                begin = 1
            }
            a[0] = first
            for (i in begin..a.lastIndex) a[i] = -a[i] / factor
        }
        val mean = Mean.of(*x).asDouble
        val sumSquares = x.sumOf { (it - mean) * (it - mean) }
        val projection = a.indices.sumOf { a[it] * (x[n - 1 - it] - x[it]) }
        // The coefficient norm avoids mistaking tiny normalization error for non-normality.
        val coefficientNorm = 2.0 * a.sumOf { it * it }
        val denominator = sqrt(coefficientNorm * sumSquares)
        val complement = ((denominator - projection) * (denominator + projection) /
            (coefficientNorm * sumSquares)).coerceIn(0.0, 1.0)
        val w = (1.0 - complement).coerceIn(0.0, 1.0)
        val p = if (n == 3) {
            if (w <= 0.75) 0.0 else (1.0 - 6.0 / PI * acos(sqrt(w))).coerceIn(0.0, 1.0)
        } else if (complement == 0.0) 1.0 else {
            var y = ln(complement)
            val center: Double
            val spread: Double
            if (n <= 11) {
                val gamma = -2.273 + 0.459 * n
                if (y >= gamma) return TestResult("W", w, 1e-19)
                y = -ln(gamma - y)
                center = polynomial(doubleArrayOf(0.5440, -0.39978, 0.025054, -0.0006714), n.toDouble())
                spread = exp(polynomial(doubleArrayOf(1.3822, -0.77857, 0.062767, -0.0020322), n.toDouble()))
            } else {
                val logN = ln(n.toDouble())
                center = polynomial(doubleArrayOf(-1.5861, -0.31082, -0.083751, 0.0038915), logN)
                spread = exp(polynomial(doubleArrayOf(-0.4803, -0.082676, 0.0030302), logN))
            }
            NormalDistribution.of(0.0, 1.0).survivalProbability((y - center) / spread)
        }
        require(w.isFinite() && p.isFinite()) { "Shapiro–Wilk could not be calculated at this numeric scale." }
        return TestResult("W", w, p)
    }

    private fun polynomial(coefficients: DoubleArray, x: Double): Double =
        coefficients.reversedArray().fold(0.0) { value, coefficient -> value * x + coefficient }
}
