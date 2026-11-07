package io.github.tatselkrik.statis130lite

import java.nio.charset.CharacterCodingException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedSessionTest {
    @Test
    fun completedAnalysesAllRecalculateAfterEncodingAndRestoring() {
        AnalysisMethod.entries.forEach { method ->
            val data = fictionalExample(method)
            val request = AnalysisRequest.forData(method, data)
                .let { if (method == AnalysisMethod.ONE_SAMPLE_T) it.copy(reference = "8") else it }
            val result = Statistics.run(data, request)
            val state = AnalysisScreenState(
                page = AppPage.RESULTS, data = data, request = request, result = result,
                draft = data.asCsv(), appliedDraft = data.asCsv(), source = "Fictional example",
                fictional = true, draftFictional = true,
            )
            val saved = roundTrip(SavedSession.capture(state, null))
            assertEquals(method.title, state, saved.restoreScreen())
        }
    }

    @Test
    fun unappliedEditsAndTheirHeaderAndPracticeFlagsRemainSeparateFromTheAppliedTable() {
        val data = fictionalExample(AnalysisMethod.ONE_SAMPLE_T)
        val state = AnalysisScreenState(
            page = AppPage.DATA, data = data, appliedDraft = data.asCsv(), draft = "9\nnot finished",
            firstRowHasNames = false, appliedNames = true, fictional = true, draftFictional = false,
            request = AnalysisRequest(reference = "-"),
        )
        val restored = roundTrip(SavedSession.capture(state, null)).restoreScreen()
        assertEquals(state, restored)
        assertTrue(restored.hasUnappliedChanges)
        assertNull(restored.result)
    }

    @Test
    fun aNewUnappliedTableSurvivesEvenBeforeTheFirstAnalysis() {
        val state = AnalysisScreenState(draft = "Before,After\n1,")
        assertEquals(state, roundTrip(SavedSession.capture(state, null)).restoreScreen())
    }

    @Test
    fun textLargerThanSixtyFourKilobytesAndUnicodeRoundTripWithoutLoss() {
        val text = "Hiligaynon,🙂,\"quoted\"\n".repeat(4000)
        val state = AnalysisScreenState(draft = text, source = "Fictional data")
        assertEquals(text, roundTrip(SavedSession.capture(state, null)).screen.draft)
    }

    @Test
    fun workingIndicatorsAndErrorMessagesAreNotRestoredAsIfWorkWereStillRunning() {
        val state = AnalysisScreenState(draft = "Score\n4", busy = "Reading…", error = "Old error", message = "Old notice")
        val restored = roundTrip(SavedSession.capture(state, null)).restoreScreen()
        assertNull(restored.busy)
        assertNull(restored.error)
        assertNull(restored.message)
        assertEquals(state.draft, restored.draft)
    }

    @Test
    fun selectedColumnsAndUnfinishedStatisticalOptionsArePreservedExactly() {
        val data = fictionalExample(AnalysisMethod.CHI_INDEPENDENCE)
        val request = AnalysisRequest(
            method = AnalysisMethod.CHI_INDEPENDENCE, first = 1, second = 0, count = 2,
            reference = "-", tail = Tail.LESS, equalVariance = true, expectedWeights = "2,",
        )
        val state = AnalysisScreenState(data = data, request = request, draft = data.asCsv(), appliedDraft = data.asCsv())
        assertEquals(request, roundTrip(SavedSession.capture(state, null)).restoreScreen().request)
    }

    @Test
    fun pendingDataAndReportExportsRetainTheirRequiredDataAcrossProcessRecovery() {
        val data = fictionalExample(AnalysisMethod.PAIRED_T)
        val request = AnalysisRequest.forData(AnalysisMethod.PAIRED_T, data)
        val state = AnalysisScreenState(
            data = data, request = request, draft = data.asCsv(), appliedDraft = data.asCsv(),
            result = Statistics.run(data, request), source = "Fictional example", fictional = true, draftFictional = true,
        )
        ExportKind.entries.forEach { kind ->
            val decoded = roundTrip(SavedSession.capture(state, kind))
            assertEquals(kind, decoded.pendingExport)
            assertEquals(state, decoded.restoreScreen())
        }
    }

    @Test
    fun missingFrequencyCellsAndLiteralCategoryStringsArePreserved() {
        val data = CourseData(listOf("Category", "Count"), listOf(
            listOf(" A ", ""), listOf("B, \"quoted\"", "NA"), listOf("C\nline", "2"),
        ))
        val state = AnalysisScreenState(data = data)
        assertEquals(data, roundTrip(SavedSession.capture(state, null)).screen.data)
    }

    @Test
    fun malformedAndUnknownFormatsAreRejected() {
        assertThrows(Exception::class.java) { SessionCodec.decode("{\"version\":".toByteArray()) }
        for (version in listOf<Any>(2, "1", 1.5)) {
            val json = json().put("version", version)
            assertThrows("Version $version", IllegalArgumentException::class.java) { SessionCodec.decode(json.toString().toByteArray()) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionCodec.decode(json().put("page", "UNKNOWN").toString().toByteArray())
        }
        assertThrows(CharacterCodingException::class.java) {
            SessionCodec.decode(byteArrayOf(0xff.toByte()))
        }
    }

    @Test
    fun fractionalColumnSelectionsAndStringFlagsAreRejected() {
        val json = json()
        json.getJSONObject("request").put("first", 1.5)
        assertThrows(IllegalArgumentException::class.java) { SessionCodec.decode(json.toString().toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) {
            SessionCodec.decode(json().put("firstRowHasNames", "true").toString().toByteArray())
        }
    }

    @Test
    fun incompleteTablesAndOrphanedResultsCannotBecomeAValidSavedSession() {
        val data = JSONObject().put("columns", JSONArray(listOf("First", "Second")))
            .put("rows", JSONArray().put(JSONArray(listOf("1"))))
        assertThrows(IllegalArgumentException::class.java) {
            SessionCodec.decode(json().put("data", data).toString().toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionCodec.decode(json().put("hadResult", true).toString().toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionCodec.decode(json().put("pendingExport", "REPORT").toString().toByteArray())
        }
    }

    @Test
    fun unappliedDataCanNeverRestoreAStaleResult() {
        val data = fictionalExample(AnalysisMethod.ONE_SAMPLE_T)
        val state = AnalysisScreenState(
            data = data, draft = "changed", appliedDraft = data.asCsv(),
            result = Statistics.run(data, AnalysisRequest()),
        )
        assertThrows(IllegalArgumentException::class.java) { SessionCodec.encode(SavedSession.capture(state, null)) }
    }

    @Test
    fun emptyWorkspaceIsAValidSession() {
        val restored = roundTrip(SavedSession.capture(AnalysisScreenState(), null))
        assertEquals(AnalysisScreenState(), restored.restoreScreen())
        assertFalse(restored.hadResult)
        assertNull(restored.pendingExport)
    }

    private fun roundTrip(session: SavedSession): SavedSession = SessionCodec.decode(SessionCodec.encode(session))

    private fun json(): JSONObject =
        JSONObject(SessionCodec.encode(SavedSession.capture(AnalysisScreenState(), null)).toString(Charsets.UTF_8))
}
