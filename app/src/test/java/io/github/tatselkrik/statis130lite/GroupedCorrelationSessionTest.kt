package io.github.tatselkrik.statis130lite

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GroupedCorrelationSessionTest {
    private val data = parseDelimited("ID,Group,X,Y,Weight\n1,A,1,4,1\n2,A,2,3,2\n3,B,4,2,1\n4,B,5,7,3\n5,B,8,9,1")
    private fun screen(request: AnalysisRequest): AnalysisScreenState = AnalysisScreenState(
        page = AppPage.RESULTS, data = data, draft = data.asCsv(), appliedDraft = data.asCsv(), request = request,
        result = Statistics.run(data, request), source = "Fictional practice", fictional = true, draftFictional = true)

    @Test fun groupedSettingsAndAllDistributionsRecoverWithPendingFormattedExports() {
        val request = AnalysisRequest(method = AnalysisMethod.DESCRIPTIVES, first = 2,
            descriptiveVariables = listOf(2, 3), splitBy = 1, weight = 4, showBoxplot = true, showQQPlot = true,
            population = true, percentiles = "10,50,90", histogramBins = "3")
        val state = screen(request)
        for (export in listOf(ExportKind.HTML, ExportKind.PDF)) {
            val restored = SessionCodec.decode(SessionCodec.encode(SavedSession.capture(state, export)))
            assertEquals(export, restored.pendingExport)
            assertEquals(state, restored.restoreScreen())
            assertEquals(4, restored.restoreScreen().result!!.descriptions.size)
        }
        val categories = screen(request.copy(weight = null, descriptiveNumeric = false, showBoxplot = false,
            showQQPlot = false, showBarChart = true))
        assertEquals(categories, SessionCodec.decode(SessionCodec.encode(SavedSession.capture(categories, null))).restoreScreen())
    }

    @Test fun matrixSelectionsMissingnessAndConfidenceOptionsRecover() {
        for (kind in CorrelationKind.entries) for (missing in CorrelationMissing.entries) {
            val request = AnalysisRequest(method = AnalysisMethod.CORRELATION_MATRIX,
                correlationVariables = listOf(2, 3, 4), correlationKind = kind, correlationMissing = missing,
                correlationConfidence = true)
            val state = screen(request)
            val restored = SessionCodec.decode(SessionCodec.encode(SavedSession.capture(state, ExportKind.PDF)))
            assertEquals(state, restored.restoreScreen())
            assertEquals(ExportKind.PDF, restored.pendingExport)
        }
    }

    @Test fun earlierVersionOneSessionsKeepTheirOriginalUnivariateMeaning() {
        val state = screen(AnalysisRequest(method = AnalysisMethod.DESCRIPTIVES, first = 2, weight = 4))
        val json = JSONObject(SessionCodec.encode(SavedSession.capture(state, null)).toString(Charsets.UTF_8))
        val options = json.getJSONObject("request")
        listOf("descriptiveVariables", "splitBy", "showBarChart", "correlationVariables", "correlationKind",
            "correlationMissing", "correlationConfidence").forEach(options::remove)
        val restored = SessionCodec.decode(json.toString().toByteArray()).restoreScreen()
        assertEquals(state, restored)
        assertEquals(1, restored.result!!.descriptions.size)
        assertEquals("X", restored.result.descriptives!!.variable)
    }

    @Test fun clearingVariableSelectionsIsSavedWithoutSilentlySelectingAnotherColumn() {
        for (request in listOf(AnalysisRequest(method = AnalysisMethod.DESCRIPTIVES, descriptiveVariables = emptyList()),
            AnalysisRequest(method = AnalysisMethod.CORRELATION_MATRIX, correlationVariables = emptyList()))) {
            val state = AnalysisScreenState(request = request)
            assertEquals(state, SessionCodec.decode(SessionCodec.encode(SavedSession.capture(state, null))).restoreScreen())
            assertThrows(IllegalArgumentException::class.java) { Statistics.run(data, request) }
        }
    }

    @Test fun invalidSavedListsAndGroupingTypesAreRejected() {
        val state = screen(AnalysisRequest(method = AnalysisMethod.DESCRIPTIVES, first = 2, descriptiveVariables = listOf(2, 3)))
        fun encoded(change: (JSONObject) -> Unit): ByteArray {
            val json = JSONObject(SessionCodec.encode(SavedSession.capture(state, null)).toString(Charsets.UTF_8))
            change(json.getJSONObject("request"))
            return json.toString().toByteArray()
        }
        for (key in listOf("descriptiveVariables", "correlationVariables")) {
            for (value in listOf(JSONArray(listOf(1, 1)), JSONArray(listOf(-1)), JSONArray(listOf(60)),
                JSONArray(listOf("2")), JSONArray((0..20).toList()))) {
                assertThrows(IllegalArgumentException::class.java) { SessionCodec.decode(encoded { it.put(key, value) }) }
            }
        }
        assertThrows(IllegalArgumentException::class.java) { SessionCodec.decode(encoded { it.put("splitBy", "1") }) }
        assertThrows(IllegalArgumentException::class.java) { SessionCodec.decode(encoded { it.put("splitBy", 60) }) }
        assertThrows(IllegalArgumentException::class.java) { SessionCodec.decode(encoded { it.put("correlationConfidence", "true") }) }
    }

    @Test fun columnDeletionRemapsEverySelectedVariableGroupAndWeight() {
        val state = screen(AnalysisRequest(method = AnalysisMethod.DESCRIPTIVES, first = 2,
            descriptiveVariables = listOf(2, 3), splitBy = 1, weight = 4))
        val changed = state.withTableChange(DataEditing.apply(data, TableEdit.DeleteColumn(0)))
        assertEquals(listOf(1, 2), changed.request.descriptiveVariables)
        assertEquals(0, changed.request.splitBy); assertEquals(3, changed.request.weight)
        assertNull(changed.result)
        assertEquals(4, Statistics.run(changed.data!!, changed.request).descriptions.size)
        val withoutGroup = state.withTableChange(DataEditing.apply(data, TableEdit.DeleteColumn(1)))
        assertNull(withoutGroup.request.splitBy)
        assertEquals(listOf(1, 2), withoutGroup.request.descriptiveVariables)
        val withoutFirst = state.withTableChange(DataEditing.apply(data, TableEdit.DeleteColumn(2)))
        assertEquals(listOf(2), withoutFirst.request.descriptiveVariables)
        val matrix = screen(AnalysisRequest(method = AnalysisMethod.CORRELATION_MATRIX,
            correlationVariables = listOf(2, 3, 4), correlationMissing = CorrelationMissing.LISTWISE))
        val reduced = matrix.withTableChange(DataEditing.apply(data, TableEdit.DeleteColumn(2)))
        assertEquals(listOf(2, 3), reduced.request.correlationVariables)
        assertEquals(CorrelationMissing.LISTWISE, reduced.request.correlationMissing)
        assertEquals(2, Statistics.run(reduced.data!!, reduced.request).correlations!!.variables.size)
    }

    @Test fun groupedAndMatrixReportsIncludeEveryTableAndObservationCount() {
        for (request in listOf(AnalysisRequest(method = AnalysisMethod.DESCRIPTIVES, first = 2,
            descriptiveVariables = listOf(2, 3), splitBy = 1, showBoxplot = true, showQQPlot = true),
            AnalysisRequest(method = AnalysisMethod.CORRELATION_MATRIX, correlationVariables = listOf(2, 3, 4),
                correlationConfidence = true))) {
            val result = screen(request).result!!
            val text = result.asReport("Fictional practice", true)
            val formatted = result.formattedReport("Fictional practice", true, emptyList())
            val html = HtmlReport.render(formatted)
            assertTrue(html.contains("Fictional practice data"))
            for (table in result.tables) {
                assertTrue(text.contains(table.title))
                assertTrue(formatted.blocks.contains(ReportBlock.Table(table)))
                table.rows.flatten().filter { it.isNotEmpty() }.forEach { assertTrue(html.contains(HtmlReport.escape(it))) }
            }
            assertEquals(2, result.tables.single { it.title == if (request.method == AnalysisMethod.DESCRIPTIVES)
                "Descriptives by variable and group" else "Correlation pair details" }.rowLabelColumns)
            result.descriptions.forEach { summary ->
                assertTrue(html.contains(HtmlReport.escape("Summary · ${summary.label}")))
                assertTrue(text.contains("Group: ${summary.group}"))
            }
            assertFalse(html.contains("<script"))
        }
    }

    @Test fun matrixCaptionsAndGroupLabelsAreEscapedInFormattedReports() {
        val attack = "<script>&\"'"
        val table = data.copy(columns = listOf("ID", "Group", attack, "Y", "Weight"),
            rows = data.rows.map { it.toMutableList().apply { this[1] = attack } })
        for (request in listOf(AnalysisRequest(method = AnalysisMethod.DESCRIPTIVES, first = 2,
            descriptiveVariables = listOf(2, 3), splitBy = 1), AnalysisRequest(method = AnalysisMethod.CORRELATION_MATRIX,
            correlationVariables = listOf(2, 3)))) {
            val html = HtmlReport.render(Statistics.run(table, request).formattedReport("Fictional", true, emptyList()))
            assertFalse(html.contains("<script>")); assertTrue(html.contains(HtmlReport.escape(attack)))
        }
    }
}
