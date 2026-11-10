package io.github.tatselkrik.statis130lite

import java.io.ByteArrayInputStream
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UiWorkflowTest {
    @Test
    fun everyFictionalExampleRunsUsingTheScreensDefaultColumnSuggestions() {
        AnalysisMethod.entries.forEach { method ->
            val data = fictionalExample(method)
            val request = AnalysisRequest.forData(method, data)
                .let { if (method == AnalysisMethod.ONE_SAMPLE_T) it.copy(reference = "8") else it }
            val result = Statistics.run(data, request)
            assertEquals(method, result.method)
            if (method == AnalysisMethod.DESCRIPTIVES) {
                assertTrue(result.test == null && result.descriptives != null)
            } else if (method == AnalysisMethod.RELIABILITY || method == AnalysisMethod.EFA) {
                assertTrue(result.test == null && result.tables.isNotEmpty())
            } else if (method == AnalysisMethod.CORRELATION_MATRIX) {
                assertTrue(result.test == null && result.correlations != null && result.tables.isNotEmpty())
            } else assertTrue(method.title, result.pValue in 0.0..1.0)
            assertEquals(0, result.excludedRows)
        }
    }

    @Test
    fun copiedReportIncludesTheSelectedAnalysisNumbersAndPracticeLabel() {
        val data = fictionalExample(AnalysisMethod.PAIRED_T)
        val result = Statistics.run(data, AnalysisRequest.forData(AnalysisMethod.PAIRED_T, data))
        val text = result.asReport("Example source", fictional = true)
        assertTrue(text.contains("Fictional practice data"))
        assertTrue(text.contains("Example source"))
        assertTrue(text.contains("t = 7.7782"))
        assertTrue(text.contains("p < .001"))
        assertTrue(text.contains("Degrees of freedom = 6"))
        assertTrue(text.contains("Sample\tN\tMean\tSD"))
        assertTrue(text.contains("95% CI"))
        assertTrue(text.contains(result.notes.first()))
        assertFalse(result.asReport("Entered data", fictional = false).contains("Fictional practice data"))
    }

    @Test
    fun reportsAndScreensFormatSmallProbabilitiesAndUseDecimalPointsInEveryLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("< .001", formatProbability(0.0))
            assertEquals("< .001", formatProbability(0.000999))
            assertEquals("0.001", formatProbability(0.001))
            assertEquals("1.000", formatProbability(1.0))
            assertEquals("1.25", formatStatistic(1.25))
            assertEquals("−∞", formatStatistic(Double.NEGATIVE_INFINITY))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun displayedGoodnessCategoryOrderMatchesTheResultsAfterMissingFrequencyExclusions() {
        val data = parseDelimited("Category,Count\nC,0\nA,3\nB,NA")
        val request = AnalysisRequest.forData(AnalysisMethod.CHI_GOODNESS, data).copy(expectedWeights = "1,2", countMissingPolicy = CountMissingPolicy.OMIT)
        val order = Statistics.goodnessCategoryOrder(data, request)
        assertEquals(listOf("A", "C"), order)
        assertEquals(order, Statistics.run(data, request).frequencies!!.rowLabels)
    }

    @Test
    fun csvExportCanBeReimportedWithQuotesAndMultilineCategoryLabels() {
        val data = CourseData(listOf("Group", "Score"), listOf(
            listOf("A, east", "4"), listOf("B \"west\"\nteam", "7"),
        ))
        assertEquals(data, parseDelimited(data.asCsv()))
    }

    @Test
    fun utf16FilesAndTablesWithoutHeadersReachTheSameDataModel() {
        val text = "\uFEFFGroup\tScore\r\nA\t4\r\nB\t6"
        val bytes = text.toByteArray(Charsets.UTF_16LE)
        val imported = ByteArrayInputStream(bytes).use { readDelimited(it, firstRowHasNames = true) }
        assertEquals(listOf("Group", "Score"), imported.columns)
        assertEquals(listOf(listOf("A", "4"), listOf("B", "6")), imported.rows)
        val noHeader = parseDelimited("4\n6", firstRowHasNames = false)
        assertEquals(listOf("Column 1"), noHeader.columns)
        assertEquals(2, noHeader.rows.size)
    }

    @Test
    fun quotedSingleColumnHeadersDoNotLetCategoryPunctuationChangeTheDelimiter() {
        val data = parseDelimited("\"Category\"\nA;B\nC;D")
        assertEquals(listOf("Category"), data.columns)
        assertEquals(listOf(listOf("A;B"), listOf("C;D")), data.rows)
    }

    @Test
    fun importRejectsMoreThanTwentyThousandDataRowsWithOrWithoutHeaders() {
        val text = List(20_001) { "4" }.joinToString("\n")
        assertThrows(IllegalArgumentException::class.java) { parseDelimited(text, firstRowHasNames = false) }
        assertThrows(IllegalArgumentException::class.java) { parseDelimited("Score\n$text") }
    }

    @Test
    fun aCountColumnCanBeTheNumericOutcomeInAnIndependentGroupTest() {
        val data = parseDelimited("Group,Count\n1,4\n1,7\n1,9\n2,3\n2,5\n2,8")
        val request = AnalysisRequest.forData(AnalysisMethod.INDEPENDENT_T, data)
        assertEquals(1, request.first)
        assertEquals(0, request.second)
        assertEquals(6L, Statistics.run(data, request).includedCases)
    }
}
