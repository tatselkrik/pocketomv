package io.github.tatselkrik.statis130lite

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GroupedCorrelationTest {
    private val reference = JSONObject(javaClass.getResource("/grouped-correlation-reference.json")!!.readText())
    private val grouped = parseDelimited("Group,X,Y,W\nA,1,10,1\nA,2,,2\nA,,30,1\nA,4,40,0\nB,5,50,1\nB,8,,0\nB,,70,1\nB,9,80,2\nC,,,1\nNA,11,90,1\n,99,99,1")
    private val description = AnalysisRequest(method = AnalysisMethod.DESCRIPTIVES, first = 1,
        descriptiveVariables = listOf(1, 2), splitBy = 0, weight = 3)

    @Test fun shapeStatisticsMatchIndependentScipyFixtures() {
        val cases = reference.getJSONArray("shapes")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val data = case.getJSONArray("data")
            val table = CourseData(listOf("X"), List(data.length()) { listOf(data.getDouble(it).toString()) })
            val result = Statistics.run(table, AnalysisRequest(method = AnalysisMethod.DESCRIPTIVES)).descriptives!!.numeric!!
            close(case, "skew", result.skewness)
            close(case, "kurtosis", result.excessKurtosis)
        }
    }

    @Test fun shapeStatisticsRetainTheirValuesUnderOffsetsAndExtremeScaleChanges() {
        val base = doubleArrayOf(-4.0, -2.0, 0.0, 2.0, 10.0, 12.0)
        fun shape(values: DoubleArray): NumericDescription {
            val data = CourseData(listOf("X"), values.map { listOf(it.toString()) })
            return Statistics.run(data, AnalysisRequest(method = AnalysisMethod.DESCRIPTIVES)).descriptives!!.numeric!!
        }
        val expected = shape(base)
        for (values in listOf(DoubleArray(base.size) { base[it] + 281474976710656.0 },
            DoubleArray(base.size) { base[it] * 1e300 }, DoubleArray(base.size) { base[it] * 1e-310 })) {
            val actual = shape(values)
            assertEquals(expected.skewness!!, actual.skewness!!, 2e-11)
            assertEquals(expected.excessKurtosis!!, actual.excessKurtosis!!, 2e-11)
        }
        val extremes = shape(doubleArrayOf(-1e308, -1e308, 1e308, 1e308))
        assertEquals(0.0, extremes.skewness!!, 1e-12)
        assertEquals(-6.0, extremes.excessKurtosis!!, 1e-12)
    }

    @Test fun groupedVariablesUseTheirOwnObservationsAndRetainEmptyGroups() {
        val result = Statistics.run(grouped, description)
        assertEquals(8, result.descriptions.size)
        assertEquals(9L, result.includedCases)
        assertEquals(2, result.excludedRows)
        fun summary(variable: String, group: String) = result.descriptions.single { it.variable == variable && it.group == group }
        val x = summary("X", "A")
        assertEquals(3, x.validCount); assertEquals(1, x.missingCount)
        assertEquals(7.0 / 3, x.numeric!!.mean, 1e-12)
        assertEquals(5.0 / 3, x.numeric.weightedMean!!.value, 1e-12)
        val y = summary("Y", "A")
        assertEquals(80.0 / 3, y.numeric!!.mean, 1e-12)
        assertEquals(20.0, y.numeric.weightedMean!!.value, 1e-12)
        assertEquals(0, summary("X", "C").validCount)
        assertEquals(1, summary("Y", "C").missingCount)
        assertNull(summary("X", "C").numeric)
        assertEquals(1, summary("X", "NA").validCount)
        assertEquals(8, result.tables.single().rows.size)
        result.descriptions.forEach { assertEquals(it.validCount, it.frequencies.sumOf { value -> value.count }) }
    }

    @Test fun groupedGraphsUseExactlyEachVariablesValidUnweightedValues() {
        val result = Statistics.run(grouped, description.copy(showBoxplot = true, showQQPlot = true))
        val plots = result.plots!!
        assertEquals(6, plots.boxes.size)
        assertEquals(6, plots.qq.size)
        val box = plots.boxes.single { it.label == "X · A" }
        assertEquals(3, box.size); assertEquals(4.0, box.maximum, 0.0)
        assertEquals(2.0, box.median, 0.0)
        assertNotNull(plots.qq.single { it.label == "X · NA" }.unavailable)
    }

    @Test fun groupsWithoutPositiveWeightsDoNotDiscardOtherValidSummaries() {
        val data = parseDelimited("G,X,W\nA,1,1\nA,2,2\nB,3,0\nB,4,NA")
        val result = Statistics.run(data, description.copy(descriptiveVariables = listOf(1), weight = 2))
        val b = result.descriptions.single { it.group == "B" }
        assertEquals(3.5, b.numeric!!.mean, 0.0)
        assertNull(b.numeric.weightedMean)
        assertTrue(b.notes.any { "no complete row has a positive weight" in it })
        val onlyB = data.copy(rows = data.rows.filter { it[0] == "B" })
        val oneGroup = Statistics.run(onlyB, description.copy(descriptiveVariables = listOf(1), weight = 2))
        assertEquals(3.5, oneGroup.descriptives!!.numeric!!.mean, 0.0)
        assertNull(oneGroup.descriptives.numeric.weightedMean)
    }

    @Test fun categoricalGroupsKeepLiteralMissingMarkersAsLabelsAndExportBarCharts() {
        val data = parseDelimited("G,Color,Code\nA,Red,1\nA,NA,2\nB,Blue,NA\nB,,1\nC,,")
        val result = Statistics.run(data, description.copy(descriptiveNumeric = false, showBarChart = true, weight = null))
        val a = result.descriptions.single { it.variable == "Color" && it.group == "A" }
        assertTrue(a.frequencies.any { it.value == "NA" })
        assertTrue(result.descriptions.all { it.numeric == null })
        val formatted = result.formattedReport("Fictional", true, emptyList())
        assertEquals(4, formatted.blocks.filterIsInstance<ReportBlock.Figure>().size)
        val html = HtmlReport.render(formatted)
        assertTrue(html.contains("Bar chart")); assertTrue(html.contains("Color · A"))
        assertTrue(result.asReport("Fictional", true).contains("Group: C"))
    }

    @Test fun categoricalBarChartsAreBoundedWithoutTruncatingTheirFrequencyTables() {
        val data = CourseData(listOf("Category"), List(25) { listOf("Category $it") })
        val result = Statistics.run(data, AnalysisRequest(method = AnalysisMethod.DESCRIPTIVES,
            descriptiveNumeric = false, showBarChart = true))
        val formatted = result.formattedReport("Fictional", true, emptyList())
        assertEquals(2, formatted.blocks.filterIsInstance<ReportBlock.Figure>().size)
        assertEquals(25, formatted.blocks.filterIsInstance<ReportBlock.Table>().single { it.value.title == "Value frequencies" }.value.rows.size)
        assertTrue(HtmlReport.render(formatted).contains("first 20 categories"))
    }

    @Test fun undefinedShapeMeasuresDoNotInventZeros() {
        for (values in listOf("1\n2", "5\n5\n5\n5")) {
            val summary = Statistics.run(parseDelimited("X\n$values"), AnalysisRequest(method = AnalysisMethod.DESCRIPTIVES)).descriptives!!.numeric!!
            assertNull(summary.skewness); assertNull(summary.excessKurtosis)
        }
        val three = Statistics.run(parseDelimited("X\n1\n2\n7"), AnalysisRequest(method = AnalysisMethod.DESCRIPTIVES)).descriptives!!.numeric!!
        assertNotNull(three.skewness); assertNull(three.excessKurtosis)
    }

    @Test fun invalidVariableGroupAndWeightSelectionsAreRejected() {
        val invalid = listOf(description.copy(descriptiveVariables = emptyList()), description.copy(descriptiveVariables = listOf(1, 1)),
            description.copy(descriptiveVariables = listOf(8)), description.copy(splitBy = 1), description.copy(splitBy = 3),
            description.copy(weight = 2), description.copy(splitBy = 9))
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { Statistics.run(grouped, it) } }
        val groups = CourseData(listOf("G", "X"), List(41) { listOf("G$it", "1") })
        assertThrows(IllegalArgumentException::class.java) { Statistics.run(groups, description.copy(descriptiveVariables = listOf(1), weight = null)) }
    }

    @Test fun groupingPreservesTheOriginalRowNumberInInputErrors() {
        val data = parseDelimited("G,X\nB,1\nA,2\nB,3\nA,4\nB,bad")
        val error = assertThrows(IllegalArgumentException::class.java) { Statistics.run(data,
            description.copy(descriptiveVariables = listOf(1), weight = null)) }
        assertTrue(error.message!!.contains("data row 5"))
    }

    @Test fun matricesMatchIndependentScipyReferencesAcrossPoliciesAndCorrelationMethods() {
        val matrices = reference.getJSONArray("matrices")
        for (index in 0 until matrices.length()) {
            val fixture = matrices.getJSONObject(index)
            val rows = fixture.getJSONArray("rows")
            val k = rows.getJSONArray(0).length()
            val data = CourseData(List(k) { "Variable ${it + 1}" }, List(rows.length()) { row ->
                List(k) { column -> rows.getJSONArray(row).getString(column) }
            })
            val cases = fixture.getJSONArray("cases")
            for (c in 0 until cases.length()) {
                val case = cases.getJSONObject(c)
                val result = Statistics.run(data, AnalysisRequest(method = AnalysisMethod.CORRELATION_MATRIX,
                    correlationVariables = (0 until k).toList(), correlationKind = CorrelationKind.valueOf(case.getString("kind")),
                    correlationMissing = CorrelationMissing.valueOf(case.getString("deletion")), correlationConfidence = true))
                val matrix = result.correlations!!
                assertNull(result.test)
                for (i in 0 until k) for (j in 0 until k) {
                    val expected = case.getJSONArray("cells").getJSONArray(i).getJSONObject(j)
                    val actual = matrix.cells[i][j]
                    assertEquals(expected.getInt("n"), actual.n)
                    close(expected, "coefficient", actual.coefficient)
                    close(expected, "p", actual.pValue)
                    close(expected, "lower", actual.lower95)
                    close(expected, "upper", actual.upper95)
                    assertEquals(actual, matrix.cells[j][i])
                }
            }
        }
    }

    @Test fun matrixValuesAgreeWithExistingPairAnalysesWithTheSameIncludedRows() {
        val data = fictionalExample(AnalysisMethod.CORRELATION_MATRIX)
        for (kind in CorrelationKind.entries) {
            val request = AnalysisRequest.forData(AnalysisMethod.CORRELATION_MATRIX, data).copy(correlationKind = kind)
            val matrix = Statistics.run(data, request).correlations!!
            for (i in data.columns.indices) for (j in i + 1 until data.columns.size) {
                val pair = Statistics.run(data, AnalysisRequest(method = if (kind == CorrelationKind.PEARSON)
                    AnalysisMethod.PEARSON else AnalysisMethod.SPEARMAN, first = i, second = j))
                assertEquals(pair.statistic, matrix.cells[i][j].coefficient!!, 0.0)
                assertEquals(pair.pValue, matrix.cells[i][j].pValue!!, 0.0)
            }
        }
    }

    @Test fun matrixConstantsAndSmallSamplesRetainOtherDefinedPairs() {
        val data = parseDelimited("A,B,Constant\n1,2,4\n2,4,4\n3,6,4")
        val result = Statistics.run(data, AnalysisRequest.forData(AnalysisMethod.CORRELATION_MATRIX, data).copy(correlationConfidence = true))
        val cells = result.correlations!!.cells
        assertEquals(1.0, cells[0][1].coefficient!!, 1e-12)
        assertEquals(0.0, cells[0][1].pValue!!, 0.0)
        assertNull(cells[0][1].lower95)
        assertNull(cells[0][2].coefficient)
        assertTrue(cells[0][2].note!!.contains("constant"))
        assertNull(cells[2][2].coefficient)
    }

    @Test fun matrixMissingnessCountsAndListwiseInclusionAreExplicit() {
        val data = parseDelimited("A,B,C\n1,2,5\n2,,4\n,3,3\n4,4,2\n5,6,").let {
            it.copy(rows = it.rows + listOf(listOf("", "", "")))
        }
        val request = AnalysisRequest.forData(AnalysisMethod.CORRELATION_MATRIX, data)
        val pairwise = Statistics.run(data, request)
        assertEquals(5L, pairwise.includedCases)
        assertEquals(1, pairwise.excludedRows)
        assertEquals(3, pairwise.correlations!!.cells[0][1].n)
        assertEquals(4, pairwise.correlations.cells[0][0].n)
        val listwise = Statistics.run(data, request.copy(correlationMissing = CorrelationMissing.LISTWISE))
        assertEquals(2L, listwise.includedCases)
        assertTrue(listwise.correlations!!.cells.flatten().all { it.n == 2 })
        assertTrue(listwise.correlations.cells.flatten().all { it.pValue == null })
    }

    @Test fun invalidMatrixVariablesAreRejectedButUnselectedTextIsIgnored() {
        val data = parseDelimited("X,Y,Comment\n1,4,hello\n2,1,world\n3,5,hello\n4,3,world")
        val request = AnalysisRequest(method = AnalysisMethod.CORRELATION_MATRIX, correlationVariables = listOf(0, 1))
        assertNotNull(Statistics.run(data, request).correlations)
        for (selected in listOf(emptyList(), listOf(0), listOf(0, 0), listOf(0, 9), listOf(0, 2)))
            assertThrows(IllegalArgumentException::class.java) { Statistics.run(data, request.copy(correlationVariables = selected)) }
    }

    private fun close(expected: JSONObject, key: String, actual: Double?) {
        if (expected.isNull(key)) assertNull(key, actual) else {
            assertNotNull(key, actual)
            assertEquals(key, expected.getDouble(key), actual!!, 2e-9)
        }
    }
}
