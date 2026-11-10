package io.github.tatselkrik.statis130lite

import org.junit.Assert.assertEquals
import org.junit.Test

class NumericalStabilityTest {
    private val rows = listOf(
        listOf(1.0, 2.0, 1.0, 3.0), listOf(2.0, 1.0, 3.0, 2.0),
        listOf(3.0, 3.0, 4.0, 4.0), listOf(4.0, 4.0, 3.0, 5.0),
        listOf(2.0, 3.0, 2.0, 3.0), listOf(5.0, 4.0, 5.0, 4.0),
        listOf(3.0, 2.0, 3.0, 4.0), listOf(4.0, 5.0, 4.0, 5.0),
    )

    private fun matrix(offset: Boolean) = NumericMatrix(
        (1..4).map { "Item $it" },
        (0..3).map { j -> DoubleArray(rows.size) { rows[it][j] + if (offset) 1e14 * (j + 1) else 0.0 } }, 0,
    )

    @Test fun standardScoresPreserveExactlyRepresentableTranslation() {
        val original = matrix(false); val translated = matrix(true)
        for (j in original.columns.indices) {
            val a = MatrixMath.standardize(original.columns[j])
            val b = MatrixMath.standardize(translated.columns[j])
            assertEquals(a.originalSD, b.originalSD, 2e-13)
            for (i in a.z.indices) assertEquals(a.z[i], b.z[i], 2e-13)
        }
    }

    @Test fun reliabilityMatchesJamoviAfterExactlyRepresentableItemTranslations() {
        // jmv 2.8.0 on the same fictional integer table, both before and after translation.
        val expectedAlpha = 0.901623686723973
        assertEquals(expectedAlpha, ReliabilityAnalysis.compute(matrix(false)).alpha!!, 2e-13)
        assertEquals(expectedAlpha, ReliabilityAnalysis.compute(matrix(true)).alpha!!, 2e-13)
    }

    @Test fun regressionSlopesAndFitPreserveExactlyRepresentableTranslations() {
        val a = MultipleRegression.compute(matrix(false))
        val b = MultipleRegression.compute(matrix(true))
        assertEquals(a.rSquared, b.rSquared, 2e-13)
        assertEquals(a.f, b.f, 2e-12)
        assertEquals(a.p, b.p, 2e-13)
        for (i in 1 until a.coefficients.size) {
            assertEquals(a.coefficients[i].b, b.coefficients[i].b, 2e-13)
            assertEquals(a.coefficients[i].se!!, b.coefficients[i].se!!, 2e-13)
        }
    }
}
