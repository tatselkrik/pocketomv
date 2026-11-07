package io.github.tatselkrik.statis130lite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * All data below are fictional. Reference values were computed independently using SciPy 1.16.2.
 * Spearman uses SciPy's t approximation. Signed-rank references report W+ to match the engine.
 */
class StatisticsTest {
    private val x = listOf(4, 6, 7, 9, 10, 13, 14)
    private val y = listOf(2, 5, 5, 8, 9, 11, 12)
    private val groups = listOf(listOf(4, 7, 8, 10), listOf(5, 6, 10, 12, 14), listOf(9, 11, 13, 15))
    private val correlationX = (1..8).toList()
    private val correlationY = listOf(3, 1, 4, 3, 6, 5, 7, 8)

    @Test
    fun oneSampleMatchesReferenceAndUsesSampleStandardDeviation() {
        val result = Statistics.run(single(x), AnalysisRequest(reference = "8"))
        checkTest(result, 0.7245688373094719, 0.49598799375773706)
        assertEquals(listOf(6.0), result.degreesOfFreedom)
        assertEquals(7L, result.includedCases)
        assertTrue(result.estimates.first().lower95!! < result.estimates.first().value)
        val known = Statistics.run(single(listOf(3, 1, 5, 2, 4)), AnalysisRequest())
        assertEquals(kotlin.math.sqrt(2.5), known.samples.single().standardDeviation!!, 1e-12)
    }

    @Test
    fun continuousOneSidedProbabilitiesHaveTheRequestedDirection() {
        val data = single(x)
        val greater = Statistics.run(data, AnalysisRequest(reference = "8", tail = Tail.GREATER))
        val less = Statistics.run(data, AnalysisRequest(reference = "8", tail = Tail.LESS))
        assertEquals(0.49598799375773706 / 2, greater.pValue, 1e-12)
        assertEquals(1.0 - greater.pValue, less.pValue, 1e-12)
        assertEquals(greater.estimates.first().lower95!!, less.estimates.first().lower95!!, 1e-12)
    }

    @Test
    fun independentWelchAndStudentTestsMatchSeparateReferences() {
        val data = grouped(listOf(x, y.take(6)))
        val request = AnalysisRequest(method = AnalysisMethod.INDEPENDENT_T, first = 1, second = 0)
        val welch = Statistics.run(data, request)
        checkTest(welch, 1.2159145841143895, 0.2495468833647638)
        assertEquals(10.964797913950456, welch.degreesOfFreedom.single(), 1e-10)
        val student = Statistics.run(data, request.copy(equalVariance = true))
        checkTest(student, 1.2046384711413818, 0.25362153583992736)
        assertEquals(listOf(11.0), student.degreesOfFreedom)
    }

    @Test
    fun pairedTestMatchesReference() {
        val result = Statistics.run(paired(x, y), AnalysisRequest(method = AnalysisMethod.PAIRED_T))
        checkTest(result, 7.778174593052023, 0.00023774439248235396)
        assertEquals(listOf(6.0), result.degreesOfFreedom)
        assertEquals(3, result.samples.size)
    }

    @Test
    fun ordinaryAndWelchAnovaMatchReferences() {
        val data = grouped(groups)
        val request = AnalysisRequest(method = AnalysisMethod.ANOVA, first = 1, second = 0, equalVariance = true)
        val ordinary = Statistics.run(data, request)
        checkTest(ordinary, 2.3114226253583072, 0.14956865213900788)
        assertEquals(listOf(2.0, 10.0), ordinary.degreesOfFreedom)
        checkTest(Statistics.run(data, request.copy(equalVariance = false)), 3.178473019174312, 0.10732867851427214)
    }

    @Test
    fun ordinaryAnovaAllowsASingleObservationInOneGroup() {
        val result = Statistics.run(
            grouped(listOf(listOf(2), listOf(3, 4, 5))),
            AnalysisRequest(method = AnalysisMethod.ANOVA, first = 1, second = 0, equalVariance = true),
        )
        assertEquals(listOf(1.0, 2.0), result.degreesOfFreedom)
        assertTrue(result.samples.first().standardDeviation == null)
        assertEquals(3.0, result.statistic, 1e-12)
    }

    @Test
    fun mannWhitneyExactAndTiedCasesMatchReferences() {
        val request = AnalysisRequest(method = AnalysisMethod.MANN_WHITNEY, first = 1, second = 0)
        val exact = Statistics.run(grouped(listOf(listOf(1, 4, 7, 10), listOf(2, 3, 5, 6, 8))), request)
        checkTest(exact, 11.0, 0.9047619047619049)
        assertTrue(exact.calculation.startsWith("Exact"))
        val tied = Statistics.run(grouped(listOf(listOf(1, 2, 2, 4, 6), listOf(2, 3, 3, 5))), request)
        checkTest(tied, 8.0, 0.7074180726307056)
        assertTrue(tied.calculation.contains("Normal approximation"))
    }

    @Test
    fun wilcoxonExactAndZeroDifferenceConventionsMatchReferences() {
        val request = AnalysisRequest(method = AnalysisMethod.WILCOXON)
        val exact = Statistics.run(paired(listOf(1, -2, 3, 4, -5, 6), List(6) { 0 }), request)
        checkTest(exact, 14.0, 0.5625)
        assertTrue(exact.calculation.startsWith("Exact"))
        val tied = Statistics.run(paired(listOf(0, 2, -1, 3, 0, -2, 2), List(7) { 0 }), request)
        checkTest(tied, 11.0, 0.4098467293684632)
        assertEquals(7L, tied.includedCases)
        assertEquals(5.0, tied.estimates.first().value, 0.0)
        assertEquals(2.0, tied.estimates[1].value, 0.0)
    }

    @Test
    fun kruskalWallisAppliesTheTieCorrection() {
        val result = Statistics.run(
            grouped(groups),
            AnalysisRequest(method = AnalysisMethod.KRUSKAL_WALLIS, first = 1, second = 0),
        )
        checkTest(result, 3.8256198347107415, 0.1476648769943902)
        assertEquals(listOf(2.0), result.degreesOfFreedom)
    }

    @Test
    fun goodnessOfFitNormalizesWeightsAndAggregatesDuplicateCategories() {
        val data = CourseData(listOf("Category", "Count"), listOf(
            listOf("A", "10"), listOf("A", "8"), listOf("B", "27"), listOf("C", "15"),
        ))
        val request = AnalysisRequest(method = AnalysisMethod.CHI_GOODNESS, count = 1, expectedWeights = "2,5,3")
        val weighted = Statistics.run(data, request)
        checkTest(weighted, 3.8, 0.14956861922263506)
        assertEquals(60L, weighted.includedCases)
        assertEquals(listOf(listOf(18L), listOf(27L), listOf(15L)), weighted.frequencies!!.observed)
        checkTest(Statistics.run(data, request.copy(expectedWeights = "")), 3.9000000000000004, 0.14227407158651353)
    }

    @Test
    fun independenceMatchesReferenceForAggregatedAndIndividualRows() {
        val data = contingency()
        val request = AnalysisRequest(method = AnalysisMethod.CHI_INDEPENDENCE, count = 2)
        val counted = Statistics.run(data, request)
        checkTest(counted, 3.4734662743816047, 0.17609473931810207)
        assertEquals(65L, counted.includedCases)
        assertEquals(listOf(2.0), counted.degreesOfFreedom)
        assertEquals(8.76923076923077, counted.frequencies!!.expected[0][0], 1e-12)
        val individualRows = data.rows.flatMap { row -> List(row[2].toInt()) { row.take(2) } }
        val individual = Statistics.run(CourseData(data.columns.take(2), individualRows), request.copy(count = null))
        checkTest(individual, counted.statistic, counted.pValue)
        assertEquals(counted.frequencies, individual.frequencies)
    }

    @Test
    fun zeroCountCellsArePreservedAndSmallExpectedCountsAreReported() {
        val data = CourseData(listOf("Category", "Count"), listOf(listOf("A", "0"), listOf("B", "3")))
        val result = Statistics.run(data, AnalysisRequest(method = AnalysisMethod.CHI_GOODNESS, count = 1))
        assertEquals(listOf(listOf(0L), listOf(3L)), result.frequencies!!.observed)
        assertTrue(result.notes.any { it.contains("below 5") })
    }

    @Test
    fun pearsonAndSpearmanMatchReferences() {
        val data = paired(correlationX, correlationY)
        val pearson = Statistics.run(data, AnalysisRequest(method = AnalysisMethod.PEARSON))
        checkTest(pearson, 0.8900766070927784, 0.0030528163403330233)
        assertEquals(listOf(6.0), pearson.degreesOfFreedom)
        val spearman = Statistics.run(data, AnalysisRequest(method = AnalysisMethod.SPEARMAN))
        checkTest(spearman, 0.8982196964349441, 0.002438796796382353)
        assertTrue(spearman.notes.any { it.contains("t approximation") })
    }

    @Test
    fun regressionMatchesReferenceIncludingSlopeConfidenceInterval() {
        val result = Statistics.run(paired(correlationX, correlationY), AnalysisRequest(method = AnalysisMethod.REGRESSION))
        checkTest(result, 4.783196761128821, 0.0030528163403330108)
        val estimates = result.estimates.associateBy { it.label }
        assertEquals(0.8452380952380952, estimates.getValue("Slope").value, 1e-12)
        assertEquals(0.8214285714285716, estimates.getValue("Intercept").value, 1e-12)
        assertEquals(0.17670987363660565, estimates.getValue("Slope standard error").value, 1e-12)
        assertEquals(0.7922363664937924, estimates.getValue("R²").value, 1e-12)
        assertEquals(0.41284461122235483, estimates.getValue("Slope").lower95!!, 1e-12)
        assertEquals(1.2776315792538355, estimates.getValue("Slope").upper95!!, 1e-12)
    }

    @Test
    fun correlationIsStableAfterChangesInUnitsAndOrigin() {
        val rows = correlationX.indices.map {
            listOf((correlationX[it] + 1e12).toString(), (correlationY[it] * 1e-120).toString())
        }
        val result = Statistics.run(CourseData(listOf("X", "Y"), rows), AnalysisRequest(method = AnalysisMethod.PEARSON))
        checkTest(result, 0.8900766070927784, 0.0030528163403330233)
    }

    @Test
    fun perfectPositiveAndNegativeCorrelationsProduceDefinedTailProbabilities() {
        val data = paired((1..6).toList(), (1..6).map { -it })
        val result = Statistics.run(data, AnalysisRequest(method = AnalysisMethod.PEARSON, tail = Tail.LESS))
        assertEquals(-1.0, result.statistic, 1e-12)
        assertTrue(result.pValue < 1e-20)
        val greater = Statistics.run(data, AnalysisRequest(method = AnalysisMethod.PEARSON, tail = Tail.GREATER))
        assertEquals(1.0, greater.pValue, 1e-12)
    }

    @Test
    fun missingValuesAreExcludedOnlyFromSelectedColumns() {
        val data = CourseData(listOf("Value", "Unused"), x.map { listOf(it.toString(), "text") } +
            listOf(listOf("", "text"), listOf("NA", "text")))
        val result = Statistics.run(data, AnalysisRequest(reference = "8"))
        checkTest(result, 0.7245688373094719, 0.49598799375773706)
        assertEquals(2, result.excludedRows)
        assertTrue(result.notes.any { it.contains("2 data rows") })
    }

    @Test
    fun pairedMissingValuesRetainTheOriginalMatchingRows() {
        val complete = paired(x, y)
        val data = complete.copy(rows = complete.rows + listOf(listOf("", "123"), listOf("999", "NA")))
        val result = Statistics.run(data, AnalysisRequest(method = AnalysisMethod.PAIRED_T))
        checkTest(result, 7.778174593052023, 0.00023774439248235396)
        assertEquals(2, result.excludedRows)
    }

    @Test
    fun numericGroupCodesHaveAnExplicitNumericOrder() {
        val original = grouped(listOf(x, y.take(6)))
        val data = original.copy(rows = original.rows.map { listOf(if (it[0] == "A") "2" else "10", it[1]) })
        val result = Statistics.run(data, AnalysisRequest(method = AnalysisMethod.INDEPENDENT_T, first = 1, second = 0))
        checkTest(result, 1.2159145841143895, 0.2495468833647638)
        assertEquals(listOf("2", "10"), result.samples.map { it.label })
    }

    @Test
    fun defaultColumnSuggestionsReachTheEngineFromDelimitedInput() {
        val text = "Ss,Group,Outcome\n1,A,4\n2,A,6\n3,A,8\n4,B,3\n5,B,5\n6,B,7"
        val data = parseDelimited(text)
        val request = AnalysisRequest.forData(AnalysisMethod.INDEPENDENT_T, data)
        assertEquals(2, request.first)
        assertEquals(1, request.second)
        val result = Statistics.run(data, request)
        assertEquals(6L, result.includedCases)
        assertTrue(result.statistic > 0)
        val counted = parseDelimited("Row,Column,Count\nA,X,12\nA,Y,8\nA,Z,10\nB,X,7\nB,Y,15\nB,Z,13")
        val suggested = AnalysisRequest.forData(AnalysisMethod.CHI_INDEPENDENCE, counted)
        assertEquals(2, suggested.count)
        checkTest(Statistics.run(counted, suggested), 3.4734662743816047, 0.17609473931810207)
    }

    @Test
    fun invalidSelectedNumbersAreRejectedWithTheirRowAndColumn() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            Statistics.run(single(listOf("4", "invalid", "7")), AnalysisRequest())
        }
        assertTrue(error.message!!.contains("Value"))
        assertTrue(error.message!!.contains("data row 2"))
        assertThrows(IllegalArgumentException::class.java) {
            Statistics.run(single(listOf("1", "Infinity", "3")), AnalysisRequest())
        }
    }

    @Test
    fun malformedRequestsAndRaggedDataAreRejected() {
        val data = paired(x, y)
        assertThrows(IllegalArgumentException::class.java) {
            Statistics.run(data, AnalysisRequest(method = AnalysisMethod.PAIRED_T, second = 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Statistics.run(data, AnalysisRequest(first = 5))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Statistics.run(data.copy(rows = listOf(listOf("1"))), AnalysisRequest())
        }
        assertThrows(IllegalArgumentException::class.java) {
            Statistics.run(data, AnalysisRequest(reference = "not a mean"))
        }
    }

    @Test
    fun frequenciesAndExpectedWeightsMustBeValid() {
        for (count in listOf("-1", "1.5", "0", "1000000000001")) {
            val data = CourseData(listOf("Category", "Count"), listOf(listOf("A", count), listOf("B", count)))
            assertThrows("Count $count", IllegalArgumentException::class.java) {
                Statistics.run(data, AnalysisRequest(method = AnalysisMethod.CHI_GOODNESS, count = 1))
            }
        }
        val data = CourseData(listOf("Category", "Count"), listOf(listOf("A", "3"), listOf("B", "4")))
        for (weights in listOf("1", "1,0", "1,-1", "1,Infinity")) {
            assertThrows("Weights $weights", IllegalArgumentException::class.java) {
                Statistics.run(data, AnalysisRequest(method = AnalysisMethod.CHI_GOODNESS, count = 1, expectedWeights = weights))
            }
        }
    }

    @Test
    fun undefinedTestsFailWithAnExplanation() {
        assertThrows(IllegalArgumentException::class.java) {
            Statistics.run(single(listOf(5, 5, 5)), AnalysisRequest())
        }
        for (method in listOf(AnalysisMethod.PAIRED_T, AnalysisMethod.WILCOXON)) {
            assertThrows(method.title, IllegalArgumentException::class.java) {
                Statistics.run(paired(x, x), AnalysisRequest(method = method))
            }
        }
        for (method in listOf(AnalysisMethod.MANN_WHITNEY, AnalysisMethod.KRUSKAL_WALLIS, AnalysisMethod.ANOVA)) {
            assertThrows(method.title, IllegalArgumentException::class.java) {
                Statistics.run(grouped(listOf(listOf(2, 2), listOf(2, 2))),
                    AnalysisRequest(method = method, first = 1, second = 0))
            }
        }
        for (method in listOf(AnalysisMethod.PEARSON, AnalysisMethod.SPEARMAN, AnalysisMethod.REGRESSION)) {
            assertThrows(method.title, IllegalArgumentException::class.java) {
                Statistics.run(paired(listOf(2, 2, 2), listOf(1, 2, 3)), AnalysisRequest(method = method))
            }
        }
    }

    @Test
    fun testsRejectInsufficientObservationsAndExcessGroups() {
        assertThrows(IllegalArgumentException::class.java) {
            Statistics.run(single(listOf(3)), AnalysisRequest())
        }
        assertThrows(IllegalArgumentException::class.java) {
            Statistics.run(grouped(groups), AnalysisRequest(method = AnalysisMethod.INDEPENDENT_T, first = 1, second = 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Statistics.run(paired(listOf(1, 2), listOf(3, 4)), AnalysisRequest(method = AnalysisMethod.PEARSON))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Statistics.run(grouped(listOf(listOf(2), listOf(3, 4, 5))),
                AnalysisRequest(method = AnalysisMethod.ANOVA, first = 1, second = 0, equalVariance = false))
        }
    }

    private fun checkTest(result: AnalysisResult, statistic: Double, probability: Double) {
        assertEquals(result.method.title, statistic, result.statistic, maxOf(1e-11, abs(statistic) * 1e-11))
        assertEquals(result.method.title, probability, result.pValue, 1e-11)
        assertTrue(result.excludedRows >= 0)
        assertNotNull(result.calculation)
    }

    private fun single(values: List<*>): CourseData =
        CourseData(listOf("Value"), values.map { listOf(it.toString()) })

    private fun paired(first: List<Int>, second: List<Int>): CourseData =
        CourseData(listOf("First", "Second"), first.indices.map { listOf(first[it].toString(), second[it].toString()) })

    private fun grouped(values: List<List<Int>>): CourseData =
        CourseData(listOf("Group", "Outcome"), values.flatMapIndexed { index, group ->
            group.map { listOf(('A'.code + index).toChar().toString(), it.toString()) }
        })

    private fun contingency(): CourseData = CourseData(listOf("Row", "Column", "Count"), listOf(
        listOf("A", "X", "12"), listOf("A", "Y", "8"), listOf("A", "Z", "10"),
        listOf("B", "X", "7"), listOf("B", "Y", "15"), listOf("B", "Z", "13"),
    ))
}
