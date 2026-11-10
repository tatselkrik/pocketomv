package io.github.tatselkrik.statis130lite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MannWhitneyDisplayTest {
    private val request = AnalysisRequest(method = AnalysisMethod.MANN_WHITNEY, first = 1, second = 0)

    // Fictional untied observations: the exact U distribution has 35 equally likely rank allocations.
    private fun data(swapped: Boolean = false): CourseData {
        val high = listOf(7, 8, 9)
        val low = listOf(1, 2, 3, 10)
        val (first, second) = if (swapped) low to high else high to low
        return CourseData(listOf("Group", "Score"),
            first.map { listOf("A", it.toString()) } + second.map { listOf("B", it.toString()) })
    }

    @Test fun headlineUsesSmallerUAndRetainsBothGroupOrderedValues() {
        for (swapped in listOf(false, true)) {
            val result = Statistics.run(data(swapped), request)
            assertEquals("U", result.statisticLabel)
            assertEquals(3.0, result.statistic, 0.0)
            assertEquals(0.4, result.pValue, 1e-14)
            assertEquals(if (swapped) 3.0 else 9.0, result.estimates.single { it.label == "U₁" }.value, 0.0)
            assertEquals(if (swapped) 9.0 else 3.0, result.estimates.single { it.label == "U₂" }.value, 0.0)
            assertEquals(listOf("A", "B"), result.samples.map { it.label })
        }
    }

    @Test fun directionalProbabilitiesStillFollowOriginalGroupOrder() {
        val greater = Statistics.run(data(), request.copy(tail = Tail.GREATER))
        val less = Statistics.run(data(), request.copy(tail = Tail.LESS))
        assertEquals(3.0, greater.statistic, 0.0)
        assertEquals(3.0, less.statistic, 0.0)
        assertEquals(7.0 / 35, greater.pValue, 1e-14)
        assertEquals(31.0 / 35, less.pValue, 1e-14)
        val reversed = Statistics.run(data(true), request.copy(tail = Tail.GREATER))
        assertEquals(less.pValue, reversed.pValue, 1e-14)
    }

    @Test fun reportsExplainTheHeadlineAndKeepBothUValues() {
        val result = Statistics.run(data(), request)
        val html = HtmlReport.render(result.formattedReport("Fictional rank example", true, emptyList()))
        assertTrue(html.contains("U₁"))
        assertTrue(html.contains("U₂"))
        assertTrue(html.contains("U is the smaller of U₁ and U₂"))
        assertTrue(html.contains("group A"))
        assertTrue(html.contains("group B"))
    }
}
