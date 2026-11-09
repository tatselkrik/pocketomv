package io.github.tatselkrik.statis130lite

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

class AdvancedAnalysisTest {
    private fun fixtures(area: String) = JSONObject(javaClass.classLoader!!.getResourceAsStream("advanced-reference.json")!!
        .bufferedReader().use { it.readText() }).getJSONArray(area)
    private fun data(json: JSONObject): CourseData {
        val rows = json.getJSONArray("data")
        return CourseData(List(rows.getJSONArray(0).length()) { "V${it + 1}" }, List(rows.length()) { i ->
            val row = rows.getJSONArray(i); List(row.length()) { row.getDouble(it).toString() }
        })
    }
    private fun matrix(data: CourseData) = NumericMatrix.read(data, data.columns.indices.toList(), 1, 60)
    private fun close(expected: Double, actual: Double, tolerance: Double = 2e-9) =
        assertEquals(expected, actual, tolerance * max(1.0, abs(expected)))
    private fun vector(expected: JSONArray, actual: List<Double?>, tolerance: Double = 2e-9) {
        assertEquals(expected.length(), actual.size)
        actual.forEachIndexed { i, value -> if (expected.isNull(i)) assertNull(value) else close(expected.getDouble(i), value!!, tolerance) }
    }

    @Test fun alphaItemDiagnosticsAndMlOmegaMatchIndependentScipyOptimization() {
        val cases = fixtures("reliability")
        for (i in 0 until cases.length()) {
            val ref = cases.getJSONObject(i); val fit = ReliabilityAnalysis.compute(matrix(data(ref)))
            close(ref.getDouble("alpha"), fit.alpha!!)
            close(ref.getDouble("standardizedAlpha"), fit.standardizedAlpha!!)
            vector(ref.getJSONArray("itemRest"), fit.itemRest)
            vector(ref.getJSONArray("alphaDropped"), fit.alphaDropped)
            if (ref.has("omega")) {
                assertNull(fit.omegaNote)
                close(ref.getDouble("omega"), fit.omega!!, 2e-6)
                close(ref.getDouble("standardizedOmega"), fit.standardizedOmega!!, 2e-6)
                vector(ref.getJSONArray("loadings"), fit.loadings!!, 1e-5)
            } else {
                assertNull(fit.omega)
                assertTrue(fit.omegaNote!!.contains("three items"))
            }
        }
    }

    @Test fun omegaPreservesNegativeKeyingAndAlphaIsNotClampedToZero() {
        val ref = fixtures("reliability").getJSONObject(2)
        val fit = ReliabilityAnalysis.compute(matrix(data(ref)))
        assertTrue(fit.loadings!![1] < 0)
        val negative = parseDelimited("A,B\n1,4\n2,1\n3,2\n4,1")
        assertTrue(ReliabilityAnalysis.compute(matrix(negative)).alpha!! < 0)
        val result = Statistics.run(negative, AnalysisRequest.forData(AnalysisMethod.RELIABILITY, negative))
        assertNull(result.test)
        assertTrue(result.notes.any { it.contains("Negative alpha") })
    }

    @Test fun reliabilityConstantSingularAndZeroTotalCasesExplainUnavailableValues() {
        val constant = parseDelimited("A,B,C\n1,1,5\n2,3,5\n3,2,5\n4,5,5")
        val fit = ReliabilityAnalysis.compute(matrix(constant))
        assertNotNull(fit.alpha)
        assertNull(fit.standardizedAlpha)
        assertNull(fit.omega)
        assertNull(fit.itemRest[2])
        assertTrue(fit.omegaNote!!.contains("no variation"))
        val singular = parseDelimited("A,B,C\n1,2,3\n2,4,6\n3,6,9\n4,8,12")
        val singularFit = ReliabilityAnalysis.compute(matrix(singular))
        assertNull(singularFit.omega)
        assertTrue(singularFit.omegaNote!!.contains("singular"))
        val zero = parseDelimited("A,B\n1,-1\n2,-2\n3,-3")
        assertNull(ReliabilityAnalysis.compute(matrix(zero)).alpha)
    }

    @Test fun reliabilityScalingAndCompleteCaseExclusionsAreConsistent() {
        val original = data(fixtures("reliability").getJSONObject(0))
        val fit = ReliabilityAnalysis.compute(matrix(original))
        val scaled = original.copy(rows = original.rows.map { row -> row.map { (it.toDouble()*1e140).toString() } })
        val large = ReliabilityAnalysis.compute(matrix(scaled))
        close(fit.alpha!!, large.alpha!!)
        close(fit.omega!!, large.omega!!, 1e-6)
        val extra = original.copy(columns = original.columns + "Ignored", rows = original.rows.map { it + "NA" } +
            listOf(listOf("NA", "2", "3", "4", "x")))
        val selected = NumericMatrix.read(extra, listOf(0,1,2,3), 2, 60)
        assertEquals(1, selected.excluded); assertEquals(original.rows.size, selected.n)
        close(fit.alpha, ReliabilityAnalysis.compute(selected).alpha!!)
    }

    @Test fun multipleRegressionMatchesIndependentSvdOlsReferences() {
        val cases = fixtures("regression")
        for (i in 0 until cases.length()) {
            val ref = cases.getJSONObject(i); val fit = MultipleRegression.compute(matrix(data(ref)))
            vector(ref.getJSONArray("b"), fit.coefficients.map { it.b })
            vector(ref.getJSONArray("se"), fit.coefficients.map { it.se })
            vector(ref.getJSONArray("beta"), fit.coefficients.drop(1).map { it.beta })
            vector(ref.getJSONArray("t"), fit.coefficients.map { it.t })
            vector(ref.getJSONArray("p"), fit.coefficients.map { it.p })
            vector(ref.getJSONArray("lower"), fit.coefficients.map { it.lower })
            vector(ref.getJSONArray("upper"), fit.coefficients.map { it.upper })
            close(ref.getDouble("r2"), fit.rSquared); close(ref.getDouble("adjustedR"), fit.adjustedR)
            close(ref.getDouble("f"), fit.f); close(ref.getDouble("pModel"), fit.p)
            close(ref.getDouble("rmse"), fit.rmse)
            vector(ref.getJSONArray("vif"), fit.vif)
            vector(ref.getJSONArray("fitted"), fit.fitted.toList()); vector(ref.getJSONArray("residuals"), fit.residuals.toList())
        }
    }

    @Test fun regressionRetainsRawUnitsInGraphsAndHandlesLargeScaleRatios() {
        val data = data(fixtures("regression").getJSONObject(0))
        val fit = MultipleRegression.compute(matrix(data))
        val factors = listOf(1e80, 1e-60, 1e100)
        val scaled = data.copy(rows = data.rows.map { row -> row.mapIndexed { i, s -> (s.toDouble()*factors[i]).toString() } })
        val big = MultipleRegression.compute(matrix(scaled))
        close(fit.f, big.f); close(fit.rSquared, big.rSquared)
        for (i in 1..2) close(fit.coefficients[i].b, big.coefficients[i].b*factors[i]/factors[0])
        val req = AnalysisRequest.forData(AnalysisMethod.MULTIPLE_REGRESSION, data).copy(showQQPlot=true, showScatterplot=true, checkNormality=true)
        val result = Statistics.run(data, req)
        assertEquals(data.rows.size, result.plots!!.scatter!!.points.size)
        assertEquals(fit.residuals.sorted(), result.plots.qq.single().points.map { it.y })
        assertEquals(1, result.assumptionChecks.size)
        assertTrue(result.plots.scatter.fittedLine == null)
    }

    @Test fun regressionRejectsConstantRedundantAndUnderdeterminedModels() {
        for (text in listOf("Y,A,B\n1,1,2\n2,2,4\n4,3,6\n7,4,8", "Y,A\n1,3\n2,3\n3,3", "Y,A,B\n1,2,3\n4,5,9\n5,6,8")) {
            assertThrows(IllegalArgumentException::class.java) { MultipleRegression.compute(matrix(parseDelimited(text))) }
        }
        val perfect = parseDelimited("Y,A\n3,1\n5,2\n7,3\n9,4")
        val fit = MultipleRegression.compute(matrix(perfect))
        assertTrue(fit.perfect); assertEquals(Double.POSITIVE_INFINITY, fit.f, 0.0)
        assertTrue(fit.coefficients.all { it.t == null && it.lower == null })
        assertTrue(fit.residuals.all { it == 0.0 })
        close(2.0, fit.coefficients[1].b); close(1.0, fit.coefficients[0].b)
    }

    @Test fun repeatedMeasuresAndSphericityMatchIndependentProjectionReferences() {
        val cases = fixtures("repeated")
        for (i in 0 until cases.length()) {
            val ref = cases.getJSONObject(i); val data = data(ref); val fit = RepeatedAnova.compute(matrix(data))
            close(ref.getDouble("f"), fit.f); close(ref.getDouble("p"), fit.pUncorrected)
            close(ref.getDouble("ssCondition"), fit.ssCondition!!); close(ref.getDouble("ssError"), fit.ssError!!)
            close(ref.getDouble("eta"), fit.eta); close(ref.getDouble("gg"), fit.gg); close(ref.getDouble("hf"), fit.hf!!)
            if (ref.has("w")) {
                close(ref.getDouble("w"), fit.mauchlyW!!); close(ref.getDouble("chi"), fit.mauchlyChi!!)
                close(ref.getDouble("pMauchly"), fit.mauchlyP!!)
            } else assertTrue(fit.mauchlyNote!!.contains("automatic"))
            val req = AnalysisRequest.forData(AnalysisMethod.REPEATED_ANOVA, data).copy(repeatedComparisons=true, showBoxplot=true)
            for ((correction, key) in listOf(RepeatedCorrection.NONE to "p", RepeatedCorrection.GREENHOUSE_GEISSER to "pGG", RepeatedCorrection.HUYNH_FELDT to "pHF")) {
                val result = Statistics.run(data, req.copy(repeatedCorrection=correction))
                close(ref.getDouble(key), result.pValue)
                assertEquals(data.columns.size, result.plots!!.boxes.size)
                val pairs = ref.getJSONArray("pairs")
                val rows = result.tables.single { it.title == "Paired comparisons" }.rows
                for (j in rows.indices) {
                    val pair = pairs.getJSONObject(j); val row = rows[j]
                    assertEquals(formatStatistic(pair.getDouble("mean")), row[1])
                    assertEquals(formatStatistic(pair.getDouble("se")), row[2])
                    assertEquals(formatStatistic(pair.getDouble("t")), row[3])
                    assertEquals(formatProbability(pair.getDouble("holm")), row[5])
                    assertEquals("[${formatStatistic(pair.getDouble("lower"))}, ${formatStatistic(pair.getDouble("upper"))}]", row[6])
                }
            }
        }
    }

    @Test fun twoConditionRepeatedFEqualsPairedTSquaredAndPartialEta() {
        val data = data(fixtures("repeated").getJSONObject(0))
        val fit = RepeatedAnova.compute(matrix(data))
        val paired = Statistics.run(data, AnalysisRequest(AnalysisMethod.PAIRED_T, first=0, second=1))
        close(paired.statistic*paired.statistic, fit.f); close(paired.pValue, fit.pUncorrected)
        close(fit.f/(fit.f+data.rows.size-1), fit.eta)
        close(1.0, fit.gg); close(1.0, fit.hf!!)
    }

    @Test fun repeatedMeasuresUsesCommonCompleteCasesAndExplainsDegenerateChecks() {
        val data = parseDelimited("A,B,C,Other\n2,4,6,x\n3,5,7,NA\n5,7,9,x\nNA,9,10,x")
        val req = AnalysisRequest(AnalysisMethod.REPEATED_ANOVA, items=listOf(0,1,2), repeatedComparisons=true)
        val result = Statistics.run(data, req)
        assertEquals(3L, result.includedCases); assertEquals(1, result.excludedRows)
        assertTrue(result.test!!.statistic.isInfinite())
        assertTrue(result.tables.single { it.title == "Sphericity · Mauchly" }.notes.single().contains("singular"))
        assertTrue(result.tables.single { it.title == "Paired comparisons" }.rows.all { it[3] == "—" && it[5] == "—" && it[6] == "—" })
        assertThrows(IllegalArgumentException::class.java) { RepeatedAnova.compute(matrix(parseDelimited("A,B\n2,2\n3,3\n4,4"))) }
    }

    @Test fun numericSelectionsRejectDuplicatesBadColumnsAndNonNumericCompleteCells() {
        val data = parseDelimited("A,B,C\n1,2,3\n4,5,no\n7,8,9")
        assertThrows(IllegalArgumentException::class.java) { NumericMatrix.read(data, listOf(0,0), 2, 60) }
        assertThrows(IllegalArgumentException::class.java) { NumericMatrix.read(data, listOf(0,3), 2, 60) }
        assertThrows(IllegalArgumentException::class.java) { NumericMatrix.read(data, listOf(0,2), 2, 60) }
        assertEquals(3, NumericMatrix.read(data, listOf(0,1), 2, 60).n)
    }

    @Test fun eigenvectorsReconstructDenseCovarianceAndRemainOrthonormal() {
        val a = arrayOf(doubleArrayOf(4.0,1.0,2.0),doubleArrayOf(1.0,3.0,.5),doubleArrayOf(2.0,.5,5.0))
        val e = MatrixMath.eigen(a)
        for (i in a.indices) for (j in a.indices) {
            close(a[i][j], a.indices.sumOf { e.vectors[i][it]*e.values[it]*e.vectors[j][it] })
            close(if (i==j) 1.0 else 0.0, a.indices.sumOf { e.vectors[it][i]*e.vectors[it][j] })
        }
    }
}
