package io.github.tatselkrik.statis130lite

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MeanDiagnosticsTest {
    private fun request(equal: Boolean = true) = AnalysisRequest(AnalysisMethod.ANOVA, first = 1, second = 0,
        equalVariance = equal, checkNormality = true, anovaNormality = NormalityBasis.GROUPS, checkVariances = true,
        meanLeveneCenter = LeveneCenter.MEDIAN, pairwiseComparisons = true)
    private fun fixture() = fictionalExample(AnalysisMethod.ANOVA)
    private fun references() = JSONObject(javaClass.classLoader!!.getResourceAsStream("mean-check-reference.json")!!
        .bufferedReader().use { it.readText() }).getJSONArray("cases")

    @Test fun diagnosticsAndBothPairwiseMethodsMatchIndependentScipyReferences() {
        val cases = references()
        for (caseIndex in 0 until cases.length()) {
            val case = cases.getJSONObject(caseIndex)
            val groups = case.getJSONArray("groups")
            val rows = buildList {
                for (index in 0 until groups.length()) {
                    val values = groups.getJSONArray(index)
                    for (row in 0 until values.length()) add(listOf(('A' + index).toString(), values.getDouble(row).toString()))
                }
            }
            val data = CourseData(listOf("Group", "Score"), rows)
            for (equal in listOf(true, false)) {
                val result = Statistics.run(data, request(equal))
                val plain = Statistics.run(data, request(equal).copy(checkNormality = false, checkVariances = false, pairwiseComparisons = false))
                assertEquals(plain.test, result.test)
                assertEquals(groups.length() + 1, result.assumptionChecks.size)
                val normality = case.getJSONArray("normality")
                for (i in 0 until groups.length()) {
                    assertEquals(normality.getJSONObject(i).getDouble("w"), result.assumptionChecks[i].test!!.statistic, 1e-7)
                    assertEquals(normality.getJSONObject(i).getDouble("p"), result.assumptionChecks[i].test!!.pValue, 2e-6)
                }
                val levene = result.assumptionChecks.last()
                assertEquals(case.getJSONObject("levene").getDouble("f"), levene.test!!.statistic, 1e-10)
                assertEquals(case.getJSONObject("levene").getDouble("p"), levene.test.pValue, 1e-10)
                val followUp = result.followUp!!
                assertNull(followUp.unavailable)
                assertEquals(if (equal) "Tukey–Kramer" else "Games–Howell", followUp.method)
                val pairs = case.getJSONArray(if (equal) "tukey" else "gamesHowell")
                assertEquals(pairs.length(), followUp.comparisons.size)
                followUp.comparisons.forEachIndexed { index, actual ->
                    val expected = pairs.getJSONObject(index)
                    assertEquals(expected.getString("first"), actual.first)
                    assertEquals(expected.getString("second"), actual.second)
                    assertEquals(expected.getDouble("difference"), actual.meanDifference, 1e-10)
                    assertEquals(expected.getDouble("se"), actual.standardError, 1e-10)
                    assertEquals(expected.getDouble("t"), actual.t, 1e-10)
                    assertEquals(expected.getDouble("df"), actual.degreesOfFreedom, 1e-9)
                    assertEquals(expected.getDouble("p"), actual.adjustedP, 3e-7)
                    assertEquals(expected.getDouble("lower95"), actual.lower95, 2e-5)
                    assertEquals(expected.getDouble("upper95"), actual.upper95, 2e-5)
                }
            }
        }
    }

    @Test fun optionsDefaultOffAndNeverChangeTheMainAnalysis() {
        val default = Statistics.run(fixture(), request().copy(checkNormality = false, checkVariances = false, pairwiseComparisons = false))
        assertTrue(default.assumptionChecks.isEmpty())
        assertNull(default.followUp)
        val extended = Statistics.run(fixture(), request())
        assertEquals(default.test, extended.test)
        assertEquals(default.samples, extended.samples)
        assertEquals(default.includedCases, extended.includedCases)
        assertTrue(extended.notes.any { it.contains("does not prove") })
        assertTrue(extended.notes.any { it.contains("study design") })
    }

    @Test fun pairedNormalityUsesCompletePairDifferences() {
        val data = parseDelimited("Before,After\n4,2\n6,5\n7,5\n9,8\nNA,9\n13,11\n14,12")
        val result = Statistics.run(data, AnalysisRequest(AnalysisMethod.PAIRED_T, first = 0, second = 1, checkNormality = true))
        val check = result.assumptionChecks.single()
        assertEquals("Paired differences", check.sample)
        assertEquals(6, check.size)
        val expected = ShapiroWilk.test(doubleArrayOf(2.0, 1.0, 2.0, 1.0, 2.0, 2.0))
        assertEquals(expected, check.test)
        assertEquals(1, result.excludedRows)
    }

    @Test fun oneSampleNormalityUsesTheIncludedSampleAndReportsSmallSamplesAsUnavailable() {
        val data = parseDelimited("Score\n4\n6\n7\n9\n10\n13\n14\nNA")
        val result = Statistics.run(data, AnalysisRequest(checkNormality = true))
        assertEquals(7, result.assumptionChecks.single().size)
        assertNotNull(result.assumptionChecks.single().test)
        val tiny = Statistics.run(parseDelimited("Score\n4\n8"), AnalysisRequest(checkNormality = true))
        assertNotNull(tiny.test)
        assertNull(tiny.assumptionChecks.single().test)
        assertTrue(tiny.assumptionChecks.single().note!!.contains("3 to 5,000"))
    }

    @Test fun unavailableDiagnosticsDoNotDiscardAValidMainResult() {
        val data = parseDelimited("Group,Score\nA,1\nA,2\nB,4\nB,8")
        val result = Statistics.run(data, AnalysisRequest(AnalysisMethod.INDEPENDENT_T, first = 1, second = 0,
            checkNormality = true, independentNormality = NormalityBasis.GROUPS, checkVariances = true))
        assertNotNull(result.test)
        assertEquals(3, result.assumptionChecks.size)
        assertTrue(result.assumptionChecks.all { it.test == null && it.note != null })
        assertTrue(result.asReport("Practice", true).contains("Unavailable"))
    }

    @Test fun constantGroupNormalityIsUnavailableWhileOtherChecksAndTukeyContinue() {
        val data = parseDelimited("Group,Score\nA,3\nA,3\nA,3\nB,2\nB,4\nB,6\nC,7\nC,8\nC,9")
        val result = Statistics.run(data, request())
        assertNull(result.assumptionChecks.first().test)
        assertTrue(result.assumptionChecks.first().note!!.contains("constant"))
        assertNotNull(result.assumptionChecks[1].test)
        assertNotNull(result.assumptionChecks.last().test)
        assertEquals(3, result.followUp!!.comparisons.size)
    }

    @Test fun diagnosticsAndComparisonsExcludeTheSameMissingRowsAsTheMainTest() {
        val data = parseDelimited("Group,Score\nA,1\nA,2\nA,4\nA,NA\nB,4\nB,6\nB,9\n,12\nC,8\nC,10\nC,15")
        val result = Statistics.run(data, request())
        assertEquals(9L, result.includedCases)
        assertEquals(2, result.excludedRows)
        assertEquals(listOf(3, 3, 3, 9), result.assumptionChecks.map { it.size })
        assertTrue(result.followUp!!.comparisons.all { it.degreesOfFreedom == 6.0 })
    }

    @Test fun twoGroupTukeyAndGamesHowellReduceToStudentAndWelchComparisons() {
        val data = parseDelimited("Group,Score\nA,1\nA,3\nA,5\nA,7\nB,2\nB,4\nB,8\nB,9\nB,12")
        for (equal in listOf(true, false)) {
            val pair = Statistics.run(data, request(equal)).followUp!!.comparisons.single()
            val t = Statistics.run(data, AnalysisRequest(AnalysisMethod.INDEPENDENT_T, first = 1, second = 0, equalVariance = equal))
            assertEquals(t.statistic, pair.t, 1e-12)
            assertEquals(t.pValue, pair.adjustedP, 1e-12)
            assertEquals(t.degreesOfFreedom.single(), pair.degreesOfFreedom, 1e-12)
        }
    }

    @Test fun followUpIntervalsAndPValuesAreStableUnderLargeAndTinyScaleChanges() {
        val original = fixture()
        for (equal in listOf(true, false)) {
            val expected = Statistics.run(original, request(equal)).followUp!!.comparisons
            for (scale in listOf(1e100, 1e-100)) {
                val changed = original.copy(rows = original.rows.map { listOf(it[0], (it[1].toDouble() * scale).toString()) })
                val result = Statistics.run(changed, request(equal))
                val pairs = result.followUp!!.comparisons
                assertEquals(expected.size, pairs.size)
                for (index in pairs.indices) {
                    assertEquals(expected[index].adjustedP, pairs[index].adjustedP, 1e-8)
                    assertEquals(expected[index].meanDifference, pairs[index].meanDifference / scale, 1e-10)
                    assertEquals(expected[index].lower95, pairs[index].lower95 / scale, 1e-5)
                    assertEquals(expected[index].upper95, pairs[index].upper95 / scale, 1e-5)
                }
            }
        }
    }

    @Test fun unsupportedDiagnosticOptionsAreRejectedClearly() {
        val data = fictionalExample(AnalysisMethod.PEARSON)
        assertThrows(IllegalArgumentException::class.java) {
            Statistics.run(data, AnalysisRequest(AnalysisMethod.PEARSON, checkNormality = true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Statistics.run(data, AnalysisRequest(checkVariances = true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Statistics.run(data, AnalysisRequest(pairwiseComparisons = true))
        }
    }

    @Test fun reportsIdentifyAdjustedProbabilitiesIntervalsAndUnavailableChecks() {
        val result = Statistics.run(fixture(), request())
        val report = result.asReport("Fictional source", true)
        for (text in listOf("Assumption checks", "Shapiro–Wilk", "Levene (median)", "Tukey–Kramer", "p (adjusted)",
            "95% interval", "family adjustment", "A − B", "B − C")) assertTrue(text, report.contains(text))
        assertEquals(3, result.followUp!!.comparisons.size)
    }

    @Test fun savedOptionsResultsAndPendingReportsRoundTripAndOldSessionsDefaultOff() {
        val data = fixture()
        val request = request(false)
        val result = Statistics.run(data, request)
        val draft = data.asCsv()
        val screen = AnalysisScreenState(page = AppPage.RESULTS, draft = draft, appliedDraft = draft, data = data,
            source = "Fictional", fictional = true, draftFictional = true, request = request, result = result)
        val encoded = SessionCodec.encode(SavedSession.capture(screen, ExportKind.REPORT))
        val decoded = SessionCodec.decode(encoded)
        assertEquals(ExportKind.REPORT, decoded.pendingExport)
        assertEquals(request, decoded.screen.request)
        assertEquals(result, decoded.restoreScreen().result)
        val old = JSONObject(String(encoded, Charsets.UTF_8))
        old.getJSONObject("request").apply { remove("checkNormality"); remove("checkVariances"); remove("pairwiseComparisons") }
        val restored = SessionCodec.decode(old.toString().toByteArray()).restoreScreen()
        assertFalse(restored.request.checkNormality)
        assertFalse(restored.request.checkVariances)
        assertFalse(restored.request.pairwiseComparisons)
        val restoredResult = requireNotNull(restored.result)
        assertTrue(restoredResult.assumptionChecks.isEmpty())
        assertNull(restoredResult.followUp)
        assertEquals(result.test, restoredResult.test)
        val bad = JSONObject(String(encoded, Charsets.UTF_8))
        bad.getJSONObject("request").put("checkNormality", "true")
        assertThrows(IllegalArgumentException::class.java) { SessionCodec.decode(bad.toString().toByteArray()) }
    }
}
