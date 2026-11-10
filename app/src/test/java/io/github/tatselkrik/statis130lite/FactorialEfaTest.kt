package io.github.tatselkrik.statis130lite

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class FactorialEfaTest {
    private val reference = JSONObject(javaClass.getResource("/factorial-efa-reference.json")!!.readText())
    private fun JSONArray.indices() = 0 until length()
    private fun data(case: JSONObject): CourseData = CourseData(case.getJSONArray("columns").let { a -> a.indices().map { a.getString(it) } },
        case.getJSONArray("rows").let { a -> a.indices().map { row -> a.getJSONArray(row).let { r -> r.indices().map { r.get(it).toString() } } } })
    private fun ints(a: JSONArray) = a.indices().map { a.getInt(it) }
    private fun request(case: JSONObject) = AnalysisRequest(method = if (case.getJSONArray("covariates").length() == 0) AnalysisMethod.FACTORIAL_ANOVA else AnalysisMethod.ANCOVA,
        first = 0, factors = ints(case.getJSONArray("factors")), covariates = ints(case.getJSONArray("covariates")),
        factorInteractions = case.getBoolean("interactions"), modelComparisons = true, modelAdjustment = ModelAdjustment.HOLM,
        modelComparisonFamily = ModelComparisonFamily.ALL_FACTORS, checkSlopes = true)
    private fun close(expected: Double, actual: Double, tolerance: Double = 2e-8) = assertEquals(expected, actual, tolerance * (1 + abs(expected)))
    private fun termLabel(label: String) = label.replace(Regex("C\\(([^,]+), Sum\\)"), "$1").replace(":", " × ")

    @Test fun factorialAndAncovaTermsMatchIndependentStatsmodelsForAllThreeSsTypes() {
        val cases = reference.getJSONArray("models")
        for (index in cases.indices()) {
            val case = cases.getJSONObject(index)
            for (type in SumsOfSquares.entries) {
                val actual = FactorialAnova.compute(data(case), request(case).copy(sumsOfSquares = type))
                val table = case.getJSONObject("anovas").getJSONObject(when (type) { SumsOfSquares.I -> "1"; SumsOfSquares.II -> "2"; SumsOfSquares.III -> "3" })
                actual.terms.forEach { term ->
                    val key = table.keys().asSequence().single { termLabel(it) == term.label }
                    val expected = table.getJSONObject(key)
                    assertEquals("${case.getString("name")} ${type.name} ${term.label}", expected.getDouble("ss"), term.ss, 2e-7 * (1 + abs(expected.getDouble("ss"))))
                    assertEquals(expected.getInt("df"), term.df)
                    close(expected.getDouble("f"), term.f, 2e-7); close(expected.getDouble("p"), term.p, 2e-7)
                    close(term.ss / (term.ss + actual.sse), term.partialEta)
                }
                close(case.getDouble("sse"), actual.sse);assertEquals(case.getInt("df"),actual.df)
                close(case.getDouble("f"), actual.f);close(case.getDouble("p"), actual.p)
                close(case.getDouble("r2"), actual.r2);close(case.getDouble("adjustedR2"), actual.adjustedR2)
            }
        }
    }

    @Test fun equalWeightedMarginalMeansContrastsAndHolmFamilyMatchIndependentModelPredictions() {
        val cases = reference.getJSONArray("models")
        for (index in cases.indices()) {
            val case = cases.getJSONObject(index); val actual = FactorialAnova.compute(data(case), request(case))
            val expectedMeans = case.getJSONArray("means")
            assertEquals(expectedMeans.length(), actual.means.size)
            actual.means.forEach { mean ->
                val expected = expectedMeans.indices().map { expectedMeans.getJSONObject(it) }.single { it.getString("factor") == mean.factor && it.getString("level") == mean.level }
                close(expected.getDouble("mean"), mean.mean); close(expected.getDouble("se"), mean.se)
                close(expected.getDouble("lower"), mean.lower); close(expected.getDouble("upper"), mean.upper)
            }
            val expectedPairs = case.getJSONArray("pairs")
            assertEquals(expectedPairs.length(), actual.comparisons.size)
            actual.comparisons.forEach { pair ->
                val expected = expectedPairs.indices().map { expectedPairs.getJSONObject(it) }.single { it.getString("factor") == pair.factor && it.getString("first") == pair.first && it.getString("second") == pair.second }
                close(expected.getDouble("difference"),pair.difference);close(expected.getDouble("se"),pair.se)
                close(expected.getDouble("t"),pair.t);close(expected.getDouble("p"),pair.p);close(expected.getDouble("adjustedP"),pair.adjustedP)
            }
        }
    }

    @Test fun ancovaSlopeCheckMatchesIndependentExpandedModelComparison() {
        val cases = reference.getJSONArray("models")
        for (index in cases.indices()) {
            val case = cases.getJSONObject(index)
            if (case.isNull("slope")) continue
            val actual = FactorialAnova.compute(data(case), request(case)).checks.single { it.name == "Homogeneity of regression slopes" }
            val expected = case.getJSONObject("slope")
            close(expected.getDouble("f"),actual.test!!.statistic); close(expected.getDouble("p"),actual.test.pValue)
            assertEquals(listOf(expected.getDouble("df1"),expected.getDouble("df2")),actual.degreesOfFreedom)
        }
    }

    @Test fun efaEigenvaluesAdequacyAndAllMinresRotationsMatchIndependentReferences() {
        val cases=reference.getJSONArray("efa")
        for(index in cases.indices()) {
            val case=cases.getJSONObject(index);val table=data(case)
            for(rotation in listOf(FactorRotation.NONE, FactorRotation.VARIMAX, FactorRotation.PROMAX)) {
                val request=AnalysisRequest(method=AnalysisMethod.EFA,items=table.columns.indices.toList(),factorCountMethod=FactorCountMethod.FIXED,
                    factorCount=case.getInt("count").toString(),factorRotation=rotation,minresConvergence=MinresConvergence.TIGHT,
                    varimaxConvergence=VarimaxConvergence.TIGHT)
                val actual=ExploratoryFactors.compute(NumericMatrix.read(table,request.items,3,20),request)
                val expected=case.getJSONObject("rotations").getJSONObject(rotation.name.lowercase())
                close(case.getDouble("kmo"),actual.kmo!!);close(case.getDouble("bartlett"),actual.bartlett);close(case.getDouble("bartlettP"),actual.bartlettP)
                actual.eigenvalues.indices.forEach { close(case.getJSONArray("eigen").getDouble(it),actual.eigenvalues[it]);close(case.getJSONArray("itemKmo").getDouble(it),actual.itemKmo[it]!!) }
                actual.pattern!!.indices.forEach { i -> actual.pattern[i].indices.forEach { j ->
                    close(expected.getJSONArray("pattern").getJSONArray(i).getDouble(j),actual.pattern[i][j],4e-4)
                    close(expected.getJSONArray("structure").getJSONArray(i).getDouble(j),actual.structure!![i][j],4e-4)
                };close(expected.getJSONArray("common").getDouble(i),actual.communalities!![i],3e-5) }
                actual.phi!!.indices.forEach { i -> actual.phi[i].indices.forEach { j -> close(expected.getJSONArray("phi").getJSONArray(i).getDouble(j),actual.phi[i][j],4e-4) } }
                close(expected.getDouble("rmsr"),actual.rmsr!!,3e-5)
            }
        }
    }

    @Test fun principalAxisCommunalitiesMatchIndependentScipyFixedPointSolution() {
        val cases=reference.getJSONArray("efa")
        for(index in cases.indices()) {
            val case=cases.getJSONObject(index);val table=data(case)
            val request=AnalysisRequest(method=AnalysisMethod.EFA,items=table.columns.indices.toList(),factorCountMethod=FactorCountMethod.FIXED,
                factorCount=case.getInt("count").toString(),factorExtraction=FactorExtraction.PRINCIPAL_AXIS,principalAxisConvergence=PrincipalAxisConvergence.TIGHT)
            val actual=ExploratoryFactors.compute(NumericMatrix.read(table,request.items,3,20),request)
            actual.communalities!!.indices.forEach { close(case.getJSONArray("principalAxisCommon").getDouble(it),actual.communalities[it],3e-6) }
        }
    }

    @Test fun parallelAnalysisIsReproducibleAndAgreesWithIndependentWishartDistribution() {
        val cases=reference.getJSONArray("efa")
        for(index in cases.indices()) {
            val case=cases.getJSONObject(index);val actual=ExploratoryFactors.parallel(case.getInt("n"),case.getInt("p"))
            assertArrayEquals(actual,ExploratoryFactors.parallel(case.getInt("n"),case.getInt("p")),0.0)
            actual.indices.forEach { assertEquals(case.getJSONArray("parallel95").getDouble(it),actual[it],0.045) }
            assertTrue(actual.toList().zipWithNext().all { (a,b) -> a>=b })
        }
    }

    @Test fun completeCaseExclusionIncludesOnlySelectedVariables() {
        val data=fictionalExample(AnalysisMethod.ANCOVA).let { d -> d.copy(columns=d.columns+"Unused",rows=d.rows.map { it+"text" }+listOf(listOf("", "Guided", "Short", "4", "unused"),listOf("12", "", "Short", "4", "unused"))) }
        val request=AnalysisRequest.forData(AnalysisMethod.ANCOVA,data).copy(factors=listOf(1,2),covariates=listOf(3),checkNormality=true,checkVariances=true,checkSlopes=true)
        val result=Statistics.run(data,request)
        assertEquals(32L,result.includedCases);assertEquals(2,result.excludedRows)
        assertEquals(3,result.assumptionChecks.size)
        assertTrue(result.assumptionChecks.all { it.size==32 })
        val missingCov=data.copy(rows=data.rows+listOf(listOf("12", "Guided", "Short", "NA", "unused")))
        assertEquals(3,Statistics.run(missingCov,request).excludedRows)
    }

    @Test fun rankDeficientModelsAndUndefinedResidualVariationExplainTheProblem() {
        val data=fictionalExample(AnalysisMethod.FACTORIAL_ANOVA);val base=AnalysisRequest.forData(AnalysisMethod.FACTORIAL_ANOVA,data)
        val emptyCell=data.copy(rows=data.rows.filterNot { it[1]=="Guided" && it[2]=="Extended" })
        val error=assertThrows(IllegalArgumentException::class.java) { Statistics.run(emptyCell,base) }
        assertTrue(error.message!!.contains("redundant"))
        val duplicated=data.copy(columns=data.columns+"Duplicate",rows=data.rows.map { it+it[1] })
        assertThrows(IllegalArgumentException::class.java) { Statistics.run(duplicated,base.copy(factors=listOf(1,4))) }
        val perfect=data.copy(rows=data.rows.map { row -> row.toMutableList().also { it[0]=if(row[1]=="Guided") "2" else "1" } })
        assertTrue(assertThrows(IllegalArgumentException::class.java) { Statistics.run(perfect,base) }.message!!.contains("residual variation"))
        assertThrows(IllegalArgumentException::class.java) { Statistics.run(data,base.copy(factors=listOf(1,1))) }
        assertThrows(IllegalArgumentException::class.java) { Statistics.run(data,base.copy(factors=listOf(1,8))) }
    }

    @Test fun zeroFactorSuggestionIsNotSilentlyReplacedAndInvalidCountsAreRejected() {
        val data=fictionalExample(AnalysisMethod.EFA);val base=AnalysisRequest.forData(AnalysisMethod.EFA,data).copy(factorScreePlot=true)
        val zero=Statistics.run(data,base.copy(factorCountMethod=FactorCountMethod.EIGENVALUE,factorEigenvalue="100"))
        assertTrue(zero.notes.any { it.contains("zero factors") });assertFalse(zero.tables.any { it.title=="Factor pattern loadings" })
        assertNotNull(zero.plots!!.scree)
        for(count in listOf("0","-1","999999999999","x","2.5","4")) assertThrows(IllegalArgumentException::class.java) {
            Statistics.run(data,base.copy(factorCountMethod=FactorCountMethod.FIXED,factorCount=count))
        }
        for(cutoff in listOf("NaN","Infinity","-1","1.1","")) assertThrows(IllegalArgumentException::class.java) {
            Statistics.run(data,base.copy(factorLoadingCutoff=cutoff))
        }
    }

    @Test fun singularConstantAndTooSmallEfaInputsAreRejected() {
        val data=fictionalExample(AnalysisMethod.EFA);val base=AnalysisRequest.forData(AnalysisMethod.EFA,data)
        assertThrows(IllegalArgumentException::class.java) { Statistics.run(data.copy(rows=data.rows.take(6)),base) }
        val constant=data.copy(rows=data.rows.map { row -> row.toMutableList().also { it[2]="4" } })
        assertThrows(IllegalArgumentException::class.java) { Statistics.run(constant,base) }
        val redundant=data.copy(rows=data.rows.map { row -> row.toMutableList().also { it[2]=it[1] } })
        assertTrue(assertThrows(IllegalArgumentException::class.java) { Statistics.run(redundant,base) }.message!!.contains("singular"))
    }
}
