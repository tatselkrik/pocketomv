package io.github.tatselkrik.statis130lite

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class PlotsTest {
    private fun references() = JSONObject(javaClass.classLoader!!.getResourceAsStream("plot-reference.json")!!
        .bufferedReader().use { it.readText() })

    @Test fun quartilesWhiskersOutliersAndQQCoordinatesMatchIndependentReferences() {
        val cases = references().getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val expected = cases.getJSONObject(i)
            val input = expected.getJSONArray("values")
            val values = DoubleArray(input.length()) { input.getDouble(it) }
            val box = PlotCalculator.box("Example", values)
            fun compare(key: String, actual: Double) {
                val value = expected.getDouble(key)
                assertEquals("$i $key", value, actual, maxOf(abs(value) * 1e-13, 1e-133))
            }
            compare("q1", box.q1); compare("median", box.median); compare("q3", box.q3)
            compare("lower", box.lowerWhisker); compare("upper", box.upperWhisker)
            val outliers = expected.getJSONArray("outliers")
            assertEquals(List(outliers.length()) { outliers.getDouble(it) }, box.outliers)
            val qq = PlotCalculator.qq("Example", values)
            assertEquals(values.size, qq.points.size)
            assertEquals(0.0, PlotAxis(qq.points.first().x, qq.points.last().x).midpoint, 0.0)
            val x = expected.getJSONArray("qqX")
            val y = expected.getJSONArray("qqY")
            qq.points.forEachIndexed { index, point ->
                assertEquals(x.getDouble(index), point.x, 2e-13)
                assertEquals(y.getDouble(index), point.y, 0.0)
            }
            assertEquals(expected.getJSONArray("line").getDouble(0), qq.reference!!.left, 2e-13)
            assertEquals(expected.getJSONArray("line").getDouble(1), qq.reference.right, 2e-13)
        }
    }

    @Test fun scatterLineMatchesIndependentLeastSquaresAndRetainsRawValues() {
        val fixture = references().getJSONObject("scatter")
        val points = List(fixture.getJSONArray("x").length()) {
            PlotPoint(fixture.getJSONArray("x").getDouble(it), fixture.getJSONArray("y").getDouble(it))
        }
        val result = PlotCalculator.scatter("Predictor", "Outcome", points, true)
        assertEquals(points, result.points)
        assertEquals(fixture.getJSONArray("line").getDouble(0), result.fittedLine!!.left, 1e-13)
        assertEquals(fixture.getJSONArray("line").getDouble(1), result.fittedLine.right, 1e-13)
        assertNull(PlotCalculator.scatter("X", "Y", points, false).fittedLine)
    }

    @Test fun exactFenceIsIncludedAndZeroIQRKeepsOnlyEqualValuesInside() {
        assertTrue(PlotCalculator.box("Fence", doubleArrayOf(0.0, 1.0, 2.0, 3.0, 6.0)).outliers.isEmpty())
        assertEquals(listOf(7.0), PlotCalculator.box("Outside", doubleArrayOf(0.0, 1.0, 2.0, 3.0, 7.0)).outliers)
        val tied = PlotCalculator.box("Tied", doubleArrayOf(1.0, 1.0, 1.0, 1.0, 2.0))
        assertEquals(1.0, tied.upperWhisker, 0.0)
        assertEquals(listOf(2.0), tied.outliers)
    }

    @Test fun extremeRangesAndCloseLargeValuesHaveFiniteCoordinates() {
        val limit = Double.MAX_VALUE
        val wide = PlotAxis(-limit, limit)
        assertEquals(0.0, wide.fraction(-limit), 0.0)
        assertEquals(0.5, wide.fraction(0.0), 0.0)
        assertEquals(1.0, wide.fraction(limit), 0.0)
        val close = PlotAxis(1e300, Math.nextUp(1e300))
        assertEquals(0.0, close.fraction(close.minimum), 0.0)
        assertEquals(1.0, close.fraction(close.maximum), 0.0)
        val qq = PlotCalculator.qq("Extreme", doubleArrayOf(-limit, -limit / 2, 0.0, limit / 2, limit))
        assertTrue(qq.reference!!.left.isFinite() && qq.reference.right.isFinite())
        assertTrue(PlotCalculator.box("Extreme", qq.points.map { it.y }.toDoubleArray()).outliers.isEmpty())
        val disparate = PlotCalculator.box("Different scales", doubleArrayOf(0.0, 1e-100, 2e-100, 3e-100, 4e-100,
            5e-100, 6e-100, 7e-100, 100e-100, limit))
        assertEquals(listOf(100e-100, limit), disparate.outliers)
        val line = PlotCalculator.scatter("X", "Y", listOf(PlotPoint(-limit, limit), PlotPoint(0.0, 0.0), PlotPoint(limit, -limit)), true).fittedLine!!
        assertEquals(1.0, line.left, 1e-14)
        assertEquals(0.0, line.right, 1e-14)
    }

    @Test fun tooSmallAndConstantSamplesDoNotInventNormalityFindings() {
        for (n in 1..2) {
            val qq = PlotCalculator.qq("Small", DoubleArray(n) { it.toDouble() })
            assertNotNull(qq.unavailable)
            assertTrue(qq.points.isEmpty())
            assertNull(qq.reference)
        }
        val constant = PlotCalculator.qq("Constant", doubleArrayOf(5.0, 5.0, 5.0))
        assertEquals(3, constant.points.size)
        assertEquals(PlotLine(0.5, 0.5), constant.reference)
        assertEquals(0.5, PlotAxis(5.0, 5.0).fraction(5.0), 0.0)
    }

    @Test fun optionalPlotsPreserveEveryMainResultAndDefaultToOff() {
        for (method in AnalysisMethod.entries) {
            val data = fictionalExample(method)
            val request = AnalysisRequest.forData(method, data)
            val plain = Statistics.run(data, request)
            assertNull(plain.plots)
            val result = Statistics.run(data, request.copy(showBoxplot = method.supportsBoxplot,
                showQQPlot = method.supportsQQPlot, showScatterplot = method.supportsScatterplot))
            assertEquals(method.title, plain, result.copy(plots = null))
        }
    }

    @Test fun numericDescriptivesPlotsAreUnweightedAndOmitOnlyMissingOutcomes() {
        val data = parseDelimited("Score,Weight,Unused\n1,0,NA\n2,1,\n3,NA,\nNA,2,\n100,1,")
        val request = AnalysisRequest(AnalysisMethod.DESCRIPTIVES, weight = 1, showBoxplot = true, showQQPlot = true)
        val result = Statistics.run(data, request)
        assertEquals(4, result.plots!!.boxes.single().size)
        assertEquals(listOf(1.0, 2.0, 3.0, 100.0), result.plots.qq.single().points.map { it.y })
        assertEquals(4L, result.includedCases)
    }

    @Test fun independentGroupsUseMainExclusionsAndNumericLabelOrdering() {
        val data = parseDelimited("Score,Group,Unused\n1,10,NA\n3,10,\n4,2,\n6,2,\nNA,2,\n8,,")
        val result = Statistics.run(data, AnalysisRequest(AnalysisMethod.INDEPENDENT_T, showBoxplot = true, showQQPlot = true))
        assertEquals(listOf("2", "10"), result.plots!!.boxes.map { it.label })
        assertEquals(listOf(2, 2), result.plots.boxes.map { it.size })
        assertEquals(2, result.excludedRows)
        assertTrue(result.plots.qq.all { it.unavailable != null })
    }

    @Test fun pairedBoxesUseCompleteMeasurementsAndQQUsesTheirDifferences() {
        val data = parseDelimited("Before,After\n4,2\n6,5\n7,5\n9,8\nNA,9\n13,11\n14,12")
        val result = Statistics.run(data, AnalysisRequest(AnalysisMethod.PAIRED_T, showBoxplot = true, showQQPlot = true))
        assertEquals(listOf(6, 6), result.plots!!.boxes.map { it.size })
        assertEquals(listOf(1.0, 1.0, 2.0, 2.0, 2.0, 2.0), result.plots.qq.single().points.map { it.y })
        assertTrue(result.plots.qq.single().label.contains("differences"))
    }

    @Test fun correlationsPlotCompleteOriginalPairsAndRegressionUsesCorrectAxes() {
        val data = parseDelimited("X,Y,Unused\n1,3,NA\n2,4,\n4,4,\n5,7,\n7,8,\nNA,9,\n8,NA,")
        for (method in listOf(AnalysisMethod.PEARSON, AnalysisMethod.SPEARMAN, AnalysisMethod.REGRESSION)) {
            val result = Statistics.run(data, AnalysisRequest(method, showScatterplot = true))
            val plot = result.plots!!.scatter!!
            assertEquals("X", plot.xLabel); assertEquals("Y", plot.yLabel)
            assertEquals(listOf(3.0, 4.0, 4.0, 7.0, 8.0), plot.points.map { it.y })
            assertEquals(5, plot.points.size)
            assertEquals(2, result.excludedRows)
            assertEquals(method == AnalysisMethod.REGRESSION, plot.fittedLine != null)
        }
    }

    @Test fun allTwentyThousandObservationsArePlottedWithoutSampling() {
        val data = CourseData(listOf("X", "Y"), List(20_000) { listOf(it.toString(), (it % 7 + it / 3).toString()) })
        val qq = Statistics.run(data, AnalysisRequest(AnalysisMethod.DESCRIPTIVES, showQQPlot = true)).plots!!.qq.single()
        assertEquals(20_000, qq.points.size)
        val scatter = Statistics.run(data, AnalysisRequest(AnalysisMethod.PEARSON, showScatterplot = true)).plots!!.scatter!!
        assertEquals(20_000, scatter.points.size)
        assertEquals(19999.0, scatter.points.last().x, 0.0)
    }

    @Test fun graphSummariesEscapeLabelsAndDescribeTheTextOnlyExport() {
        val data = CourseData(listOf("Score\tvalue"), listOf(listOf("1"), listOf("2"), listOf("3"), listOf("100")))
        val request = AnalysisRequest(AnalysisMethod.DESCRIPTIVES, showBoxplot = true, showQQPlot = true)
        val report = Statistics.run(data, request).asReport("Example", true)
        assertTrue(report.contains("figures are shown in the app"))
        assertTrue(report.contains("Score\\tvalue\t4"))
        assertTrue(report.contains("whiskers"))
        assertTrue(report.contains("R ppoints"))
        assertTrue(report.contains("Fictional practice data"))
        assertFalse(report.contains("p ="))
    }

    @Test fun graphFlagsRejectIncompatibleDataAndMethods() {
        assertThrows(IllegalArgumentException::class.java) {
            Statistics.run(fictionalExample(AnalysisMethod.CHI_GOODNESS), AnalysisRequest(AnalysisMethod.CHI_GOODNESS, showScatterplot = true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Statistics.run(parseDelimited("Category\nA\nB"), AnalysisRequest(AnalysisMethod.DESCRIPTIVES, descriptiveNumeric = false, showBoxplot = true))
        }
    }
}
