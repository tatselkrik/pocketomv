package io.github.tatselkrik.statis130lite

import org.apache.commons.numbers.rootfinder.BrentSolver
import org.apache.commons.statistics.distribution.ChiSquaredDistribution
import org.apache.commons.statistics.distribution.NormalDistribution
import org.apache.commons.statistics.distribution.TDistribution
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** Q = range of k independent standard normals / sqrt(chi-square(df)/df).
 * Integrates the conditional normal-range CDF over the chi-square scale.
 * This is numerical integration of the defining distribution, not an unadjusted t approximation.
 */
internal class StudentizedRange(private val groups: Int, private val df: Double) {
    private val normal = NormalDistribution.of(0.0, 1.0)
    private val chi = ChiSquaredDistribution.of(df)
    private val lower = sqrt(chi.inverseCumulativeProbability(5e-13) / df)
    private val upper = sqrt(chi.inverseSurvivalProbability(5e-13) / df)

    init {
        require(groups in 2..40 && df.isFinite() && df >= 1.0) { "The studentized range needs 2 to 40 groups and at least one degree of freedom." }
    }

    fun survival(q: Double): Double {
        require(!q.isNaN() && q >= 0.0)
        if (q == 0.0) return 1.0
        if (q == Double.POSITIVE_INFINITY) return 0.0
        if (groups == 2) return 2.0 * TDistribution.of(df).survivalProbability(q / sqrt(2.0))
        // Beyond range 16 the conditional tail is negligible, even for 40 groups.
        // Cropping here also resolves large-q tails without missing their narrow scale interval.
        val high = minOf(upper, 16.0 / q)
        if (high <= lower) return 0.0
        var previous = integrate(q, high, 32)
        for (order in listOf(64, 128, 256)) {
            val current = integrate(q, high, order)
            if (abs(current - previous) < 2e-9) return current.coerceIn(0.0, 1.0)
            if (order == 256) require(abs(current - previous) < 2e-7) { "The adjusted probability did not reach the supported numerical accuracy." }
            previous = current
        }
        return previous.coerceIn(0.0, 1.0)
    }

    fun critical95(): Double {
        if (groups == 2) return sqrt(2.0) * TDistribution.of(df).inverseSurvivalProbability(0.025)
        var high = 4.0
        while (survival(high) > 0.05) high *= 2.0
        return BrentSolver(1e-9, 1e-8, 2e-10).findRoot({ survival(it) - 0.05 }, 0.0, high)
    }

    private fun integrate(q: Double, high: Double, order: Int): Double {
        val quadrature = GaussLegendre.rule(order)
        val half = (high - lower) / 2.0
        val middle = (high + lower) / 2.0
        var result = 0.0
        for (i in quadrature.nodes.indices) {
            val scale = middle + half * quadrature.nodes[i]
            val density = exp(chi.logDensity(df * scale * scale) + ln(2.0 * df * scale))
            result += quadrature.weights[i] * density * normalRangeSurvival(q * scale)
        }
        return result * half
    }

    private fun normalRangeSurvival(range: Double): Double {
        if (range >= 16.0) return 0.0
        if (range <= 0.0) return 1.0
        val rule = GaussLegendre.rule(if (groups > 8) 128 else 64)
        var integral = 0.0
        for (i in rule.nodes.indices) {
            val minimum = 9.0 * rule.nodes[i]
            val interval = normal.probability(minimum, minimum + range)
            integral += rule.weights[i] * normal.density(minimum) * interval.pow(groups - 1)
        }
        return (1.0 - groups * 9.0 * integral).coerceIn(0.0, 1.0)
    }
}

private data class GaussianRule(val nodes: DoubleArray, val weights: DoubleArray)

private object GaussLegendre {
    private val rules = listOf(32, 64, 128, 256).associateWith(::create)
    fun rule(order: Int): GaussianRule = rules.getValue(order)

    private fun create(order: Int): GaussianRule {
        val nodes = DoubleArray(order)
        val weights = DoubleArray(order)
        for (i in 0 until (order + 1) / 2) {
            var root = cos(PI * (i + 0.75) / (order + 0.5))
            var derivative = 0.0
            for (iteration in 0..20) {
                var polynomial = 1.0
                var previous = 0.0
                for (degree in 1..order) {
                    val older = previous
                    previous = polynomial
                    polynomial = ((2.0 * degree - 1.0) * root * previous - (degree - 1.0) * older) / degree
                }
                derivative = order * (root * polynomial - previous) / (root * root - 1.0)
                val change = polynomial / derivative
                root -= change
                if (abs(change) < 2e-15) break
            }
            val weight = 2.0 / ((1.0 - root * root) * derivative * derivative)
            nodes[i] = -root
            nodes[order - i - 1] = root
            weights[i] = weight
            weights[order - i - 1] = weight
        }
        return GaussianRule(nodes, weights)
    }
}
