package io.github.tatselkrik.statis130lite

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DescriptiveSessionTest {
    @Test fun completedDescriptivesAndPendingReportRecalculateWithTheirOptions() {
        val data = fictionalExample(AnalysisMethod.DESCRIPTIVES)
        val request = AnalysisRequest.forData(AnalysisMethod.DESCRIPTIVES, data)
            .copy(weight = 1, population = true, percentiles = "0,10,50,90,100", histogramBins = "3")
        val state = AnalysisScreenState(page = AppPage.RESULTS, data = data, draft = data.asCsv(),
            appliedDraft = data.asCsv(), request = request, result = Statistics.run(data, request),
            source = "Fictional example", fictional = true, draftFictional = true)
        val restored = SessionCodec.decode(SessionCodec.encode(SavedSession.capture(state, ExportKind.REPORT)))
        assertEquals(ExportKind.REPORT, restored.pendingExport)
        assertEquals(state, restored.restoreScreen())
        assertNull(restored.restoreScreen().result!!.test)
    }

    @Test fun sessionsSavedBeforeDescriptivesHaveBackwardCompatibleDefaults() {
        val data = fictionalExample(AnalysisMethod.ONE_SAMPLE_T)
        val request = AnalysisRequest(reference = "8")
        val state = AnalysisScreenState(data = data, draft = data.asCsv(), appliedDraft = data.asCsv(),
            request = request, result = Statistics.run(data, request))
        val json = JSONObject(SessionCodec.encode(SavedSession.capture(state, null)).toString(Charsets.UTF_8))
        val options = json.getJSONObject("request")
        listOf("descriptiveNumeric", "weight", "population", "percentiles", "histogramBins").forEach(options::remove)
        assertEquals(state, SessionCodec.decode(json.toString().toByteArray()).restoreScreen())
    }

    @Test fun unfinishedDescriptiveOptionsSurviveWithoutTryingToCalculateThem() {
        val state = AnalysisScreenState(request = AnalysisRequest(method = AnalysisMethod.DESCRIPTIVES,
            population = true, weight = 1, percentiles = "-", histogramBins = "2."))
        val restored = SessionCodec.decode(SessionCodec.encode(SavedSession.capture(state, null))).restoreScreen()
        assertEquals(state, restored)
    }

    @Test fun invalidSavedTypesAndColumnIndicesAreNotCoerced() {
        val json = JSONObject(SessionCodec.encode(SavedSession.capture(AnalysisScreenState(), null)).toString(Charsets.UTF_8))
        json.getJSONObject("request").put("population", "true")
        assertThrows(IllegalArgumentException::class.java) { SessionCodec.decode(json.toString().toByteArray()) }
        json.getJSONObject("request").put("population", true).put("weight", -1)
        assertThrows(IllegalArgumentException::class.java) { SessionCodec.decode(json.toString().toByteArray()) }
    }
}
