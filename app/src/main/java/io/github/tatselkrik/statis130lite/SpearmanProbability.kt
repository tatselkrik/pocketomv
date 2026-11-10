package io.github.tatselkrik.statis130lite

import org.apache.commons.statistics.distribution.NormalDistribution
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.round
import kotlin.math.sqrt

internal enum class SpearmanProbabilityMethod(val description: String) {
    EXACT("Exact permutation probability for untied ranks"),
    EDGEWORTH("AS 89 Edgeworth approximation for untied ranks"),
    T("Student's t approximation for tied ranks or more than 1,290 pairs"),
}

internal data class SpearmanProbability(val p: Double, val method: SpearmanProbabilityMethod) {
    companion object {
        private val normal = NormalDistribution.of(0.0, 1.0)
        private data class ExactDistribution(val cumulative: IntArray, val total: Int)
        private val exact = List(10) { n -> lazy { enumerate(n) } }

        /** Each untied permutation is equally likely under the permutation null hypothesis. */
        private fun enumerate(n: Int): ExactDistribution {
            val counts = IntArray(n * (n * n - 1) / 3 + 1)
            fun visit(position: Int, used: Int, distance: Int) {
                if (position == n) { counts[distance]++; return }
                for (rank in 0 until n) {
                    val bit = 1 shl rank
                    if (used and bit == 0) {
                        val difference = position - rank
                        visit(position + 1, used or bit, distance + difference * difference)
                    }
                }
            }
            visit(0, 0, 0)
            for (i in 1 until counts.size) counts[i] += counts[i - 1]
            return ExactDistribution(counts, counts.last())
        }

        private fun tied(values: DoubleArray): Boolean {
            val seen = HashSet<Double>(values.size)
            return values.any { !seen.add(if (it == 0.0) 0.0 else it) }
        }

        /** Best & Roberts (1975), AS 89, DOI: 10.2307/2347111.
         * The mathematical Edgeworth polynomial is grouped by powers of 1/N.
         * Lower and upper probabilities are evaluated separately to retain tail precision.
         */
        private fun edgeworth(n: Int, distance: Double, lower: Boolean): Double {
            val size = n.toDouble()
            val center = size * (size * size - 1) / 6
            // Rank distances are even integers; the lower-tail threshold includes the observation.
            val threshold = distance + if (lower) 2 else 0
            if (threshold <= 0) return if (lower) 0.0 else 1.0
            if (threshold > 2 * center) return if (lower) 1.0 else 0.0
            val z = ((threshold - 1) / center - 1) * sqrt(size - 1)
            val z2 = z * z; val z4 = z2 * z2; val z6 = z4 * z2
            val first = 0.2274 - 0.0758 * z2
            val second = 0.2531 + 0.1033 * z2 - 0.0879 * z4 + 0.0072 * z6
            val third = 0.1745 + 0.3932 * z2 - 0.0151 * z4 - 0.0831 * z6 +
                0.0131 * z6 * z2 - 0.00046 * z6 * z4
            val correction = z * (first + second / size + third / (size * size)) / size * exp(-z2 / 2)
            return (if (lower) normal.cumulativeProbability(z) - correction
                else normal.survivalProbability(z) + correction).coerceIn(0.0, 1.0)
        }

        fun calculate(x: DoubleArray, y: DoubleArray, rho: Double, tail: Tail): SpearmanProbability {
            val n = x.size
            require(n >= 2 && y.size == n && rho.isFinite() && abs(rho) <= 1)
            if (n > 1290 || tied(x) || tied(y)) {
                val statistic = if (abs(rho) == 1.0) {
                    if (rho > 0) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
                } else rho * sqrt((n - 2.0) / ((1 - rho) * (1 + rho)))
                return SpearmanProbability(Statistics.tProbability(statistic, n - 2.0, tail), SpearmanProbabilityMethod.T)
            }
            val size = n.toDouble()
            val center = size * (size * size - 1) / 6
            val distance = round(center * (1 - rho))
            val lower: Double
            val upper: Double
            val method: SpearmanProbabilityMethod
            if (n <= 9) {
                val distribution = exact[n].value
                val at = distance.toInt().coerceIn(distribution.cumulative.indices)
                lower = distribution.cumulative[at].toDouble() / distribution.total
                upper = (distribution.total - if (at == 0) 0 else distribution.cumulative[at - 1]).toDouble() / distribution.total
                method = SpearmanProbabilityMethod.EXACT
            } else {
                lower = edgeworth(n, distance, true); upper = edgeworth(n, distance, false)
                method = SpearmanProbabilityMethod.EDGEWORTH
            }
            val probability = when (tail) {
                Tail.GREATER -> lower
                Tail.LESS -> upper
                Tail.TWO_SIDED -> (2 * if (distance > center) upper else lower).coerceAtMost(1.0)
            }
            return SpearmanProbability(probability, method)
        }
    }
}
