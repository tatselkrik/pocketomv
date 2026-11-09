package io.github.tatselkrik.statis130lite

import org.apache.commons.statistics.distribution.TDistribution
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin
import kotlin.math.sqrt

class DistributionChecksTest {
    private fun reference() = JSONObject(javaClass.classLoader!!.getResourceAsStream("assumption-reference.json")!!
        .bufferedReader().use { it.readText() })

    @Test fun shapiroMatchesIndependentScipyReferencesAcrossSampleSizesAndShapes() {
        val cases = reference().getJSONArray("shapiro")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val values = case.getJSONArray("values")
            val result = ShapiroWilk.test(DoubleArray(values.length()) { values.getDouble(it) })
            assertEquals("W case $index", case.getDouble("w"), result.statistic, 1e-7)
            assertEquals("p case $index", case.getDouble("p"), result.pValue, 2e-6)
        }
    }

    @Test fun shapiroSupportsTheUpperSampleBoundaryAndRejectsUndefinedChecks() {
        val result = ShapiroWilk.test(DoubleArray(5000) { sin(it * 1.7) + it / 100000.0 })
        val expected = reference().getJSONObject("large_shapiro")
        assertEquals(expected.getDouble("w"), result.statistic, 1e-7)
        assertEquals(expected.getDouble("p"), result.pValue, 1e-8)
        for (values in listOf(doubleArrayOf(1.0, 2.0), DoubleArray(5001) { it.toDouble() },
            doubleArrayOf(2.0, 2.0, 2.0), doubleArrayOf(1.0, 2.0, Double.NaN))) {
            assertThrows(IllegalArgumentException::class.java) { ShapiroWilk.test(values) }
        }
    }

    @Test fun shapiroIsStableUnderTranslationScalingAndExtremeFiniteScales() {
        val values = doubleArrayOf(1.0, 2.0, 4.0, 5.0, 10.0, 20.0)
        val expected = ShapiroWilk.test(values)
        for (changed in listOf(values.map { it * 1e-150 }.toDoubleArray(),
            values.map { it * 1e140 + 1e145 }.toDoubleArray(), values.reversedArray())) {
            val result = ShapiroWilk.test(changed)
            assertEquals(expected.statistic, result.statistic, 1e-9)
            assertEquals(expected.pValue, result.pValue, 1e-8)
        }
    }

    @Test fun studentizedRangeTailMatchesIndependentScipyIncludingUnequalDegreesOfFreedom() {
        val cases = reference().getJSONArray("studentized_range")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val actual = StudentizedRange(case.getInt("k"), case.getDouble("df")).survival(case.getDouble("q"))
            assertEquals("range case $case", case.getDouble("p"), actual, 3e-7)
        }
    }

    @Test fun studentizedRangeCriticalValuesMatchIndependentScipy() {
        val cases = reference().getJSONArray("critical_values")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val distribution = StudentizedRange(case.getInt("k"), case.getDouble("df"))
            val critical = distribution.critical95()
            assertEquals("critical case $case", case.getDouble("q95"), critical, 1e-5)
            assertEquals(0.05, distribution.survival(critical), 1e-8)
        }
    }

    @Test fun twoGroupRangeReducesExactlyToTheTwoSidedTDistribution() {
        for (df in listOf(1.0, 4.5, 20.0, 500.0)) {
            val distribution = StudentizedRange(2, df)
            assertEquals(2.0 * TDistribution.of(df).survivalProbability(2.0), distribution.survival(2.0 * sqrt(2.0)), 1e-14)
            assertEquals(sqrt(2.0) * TDistribution.of(df).inverseSurvivalProbability(0.025), distribution.critical95(), 0.0)
            assertEquals(1.0, distribution.survival(0.0), 0.0)
            assertEquals(0.0, distribution.survival(Double.POSITIVE_INFINITY), 0.0)
        }
    }
}
