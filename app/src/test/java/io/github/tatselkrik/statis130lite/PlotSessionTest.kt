package io.github.tatselkrik.statis130lite

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlotSessionTest {
    @Test fun selectedGroupAndGraphsRecalculateAfterReopeningWithPendingReport() {
        val data = fictionalExample(AnalysisMethod.ANOVA)
        val request = AnalysisRequest.forData(AnalysisMethod.ANOVA, data)
            .copy(showBoxplot = true, showQQPlot = true, qqSample = "B")
        val state = AnalysisScreenState(page = AppPage.RESULTS, data = data, draft = data.asCsv(),
            appliedDraft = data.asCsv(), request = request, result = Statistics.run(data, request), fictional = true, draftFictional = true)
        val restored = SessionCodec.decode(SessionCodec.encode(SavedSession.capture(state, ExportKind.REPORT)))
        assertEquals(ExportKind.REPORT, restored.pendingExport)
        assertEquals(state, restored.restoreScreen())
    }

    @Test fun oldSavedSessionsRestoreWithGraphOptionsOff() {
        val data = fictionalExample(AnalysisMethod.ONE_SAMPLE_T)
        val state = AnalysisScreenState(data = data, draft = data.asCsv(), appliedDraft = data.asCsv(),
            result = Statistics.run(data, AnalysisRequest()))
        val json = JSONObject(SessionCodec.encode(SavedSession.capture(state, null)).toString(Charsets.UTF_8))
        val request = json.getJSONObject("request")
        listOf("showBoxplot", "showQQPlot", "showScatterplot", "qqSample").forEach(request::remove)
        assertEquals(state, SessionCodec.decode(json.toString().toByteArray()).restoreScreen())
    }

    @Test fun persistedGraphFlagsAreStrictlyTyped() {
        val json = JSONObject(SessionCodec.encode(SavedSession.capture(AnalysisScreenState(), null)).toString(Charsets.UTF_8))
        json.getJSONObject("request").put("showBoxplot", "true")
        assertThrows(IllegalArgumentException::class.java) { SessionCodec.decode(json.toString().toByteArray()) }
    }
}
