package io.github.tatselkrik.statis130lite

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DataEditingSessionTest {
    private fun roundTrip(state: AnalysisScreenState, export: ExportKind? = null): SavedSession =
        SessionCodec.decode(SessionCodec.encode(SavedSession.capture(state, export)))

    @Test fun editedCellsBlankRowsScoredValuesAndPreparationNotesRecoverExactly() {
        val data = parseDelimited("Q1,Q2\n1,5\n2,4\nNA,3")
        var state = AnalysisScreenState(data = data, draft = data.asCsv(), appliedDraft = data.asCsv(), fictional = true, draftFictional = true)
        val derived = DataEditing.reverse(data, 1, "Q2_R", "1", "5")
        state = state.withTableChange(derived.appendTo(data))
        state = state.withTableChange(DataEditing.apply(state.data!!, TableEdit.AddRow(0)))
        state = state.withTableChange(DataEditing.apply(state.data!!, TableEdit.Cell(2, 0, "2.5")))
        val restored = roundTrip(state, ExportKind.DATA)
        assertEquals(ExportKind.DATA, restored.pendingExport)
        assertEquals(state, restored.restoreScreen())
        assertEquals(listOf("", "", ""), restored.screen.data!!.rows[1])
        assertEquals("2.5", restored.screen.data.rows[2][0])
        assertFalse(restored.screen.hasUnappliedChanges)
    }

    @Test fun analysisOfAnAddedScoreRecalculatesAndExportsItsPreparationHistory() {
        val data = parseDelimited("Q1,Q2\n1,5\n2,4\n3,2\n5,1")
        var state = AnalysisScreenState(data = data, draft = data.asCsv(), appliedDraft = data.asCsv(), fictional = true, draftFictional = true)
        state = state.withTableChange(DataEditing.score(data, listOf(0, 1), "Mean", ItemScore.MEAN, MissingItems.COMPLETE, 1).appendTo(data))
        val request = AnalysisRequest(AnalysisMethod.DESCRIPTIVES, first = 2, showBoxplot = true)
        state = state.copy(page = AppPage.RESULTS, request = request, result = Statistics.run(state.data!!, request))
        val restored = roundTrip(state, ExportKind.REPORT)
        assertEquals(state, restored.restoreScreen())
        val report = restored.restoreScreen().result!!.asReport("Fictional scores", true, restored.screen.preparationNotes)
        assertTrue(report.contains("Data preparation (saved values)"))
        assertTrue(report.contains("Mean of Q1, Q2"))
        assertTrue(report.contains("rescore after changing inputs"))
        assertEquals(ExportKind.REPORT, restored.pendingExport)
        assertEquals(2.875, restored.restoreScreen().result!!.descriptives!!.numeric!!.mean, 1e-14)
    }

    @Test fun oldSessionsDefaultToTextWithoutInventingScoringNotes() {
        val data = fictionalExample(AnalysisMethod.REGRESSION)
        val state = AnalysisScreenState(data = data, draft = data.asCsv(), appliedDraft = data.asCsv())
        val json = JSONObject(SessionCodec.encode(SavedSession.capture(state, null)).toString(Charsets.UTF_8))
        json.remove("dataEditor"); json.remove("preparationNotes")
        assertEquals(state, SessionCodec.decode(json.toString().toByteArray()).restoreScreen())
    }

    @Test fun invalidEditorModesAndPreparationNoteTypesAreRejected() {
        val json = JSONObject(SessionCodec.encode(SavedSession.capture(AnalysisScreenState(), null)).toString(Charsets.UTF_8))
        json.put("dataEditor", "UNKNOWN")
        assertThrows(IllegalArgumentException::class.java) { SessionCodec.decode(json.toString().toByteArray()) }
        json.put("dataEditor", "CELLS").put("preparationNotes", "not an array")
        assertThrows(org.json.JSONException::class.java) { SessionCodec.decode(json.toString().toByteArray()) }
    }
}
