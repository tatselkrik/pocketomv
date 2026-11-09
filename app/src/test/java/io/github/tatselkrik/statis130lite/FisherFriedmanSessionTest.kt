package io.github.tatselkrik.statis130lite

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FisherFriedmanSessionTest {
    private fun screen(method: AnalysisMethod): AnalysisScreenState {
        val data=fictionalExample(method)
        val request=AnalysisRequest.forData(method,data).let { when (method) {
            AnalysisMethod.FISHER_EXACT -> it.copy(comparisonGroup="B",eventCategory="Other",tail=Tail.GREATER)
            AnalysisMethod.FRIEDMAN -> it.copy(friedmanComparisons=true,friedmanAdjustment=FriedmanAdjustment.BONFERRONI,showBoxplot=true)
            else -> it
        } }
        return AnalysisScreenState(page=AppPage.RESULTS,data=data,draft=data.asCsv(),appliedDraft=data.asCsv(),
            source="Fictional practice",fictional=true,draftFictional=true,request=request,result=Statistics.run(data,request))
    }

    @Test fun newOptionsResultsAndPendingReportsRoundTripThroughTheSavedSession() {
        for (method in listOf(AnalysisMethod.FISHER_EXACT,AnalysisMethod.FRIEDMAN)) for (kind in listOf(ExportKind.HTML,ExportKind.PDF)) {
            val state=screen(method)
            val saved=SessionCodec.decode(SessionCodec.encode(SavedSession.capture(state,kind)))
            assertEquals(kind,saved.pendingExport)
            assertEquals(state,saved.restoreScreen())
        }
    }

    @Test fun sessionsBeforeTheseFeaturesRetainTheirExistingMeanAndCorrelationSettings() {
        for (method in listOf(AnalysisMethod.DESCRIPTIVES,AnalysisMethod.CORRELATION_MATRIX,AnalysisMethod.REPEATED_ANOVA)) {
            val state=screen(method)
            val json=JSONObject(SessionCodec.encode(SavedSession.capture(state,null)).toString(Charsets.UTF_8))
            val options=json.getJSONObject("request")
            listOf("comparisonGroup","eventCategory","friedmanComparisons","friedmanAdjustment").forEach(options::remove)
            assertEquals(state,SessionCodec.decode(json.toString().toByteArray()).restoreScreen())
        }
    }

    @Test fun unfinishedComparisonChoicesPersistWithoutRunningAnInvalidAnalysis() {
        val state=AnalysisScreenState(request=AnalysisRequest(method=AnalysisMethod.FISHER_EXACT,
            comparisonGroup="not present yet",eventCategory="unfinished",friedmanComparisons=true))
        assertEquals(state,SessionCodec.decode(SessionCodec.encode(SavedSession.capture(state,null))).restoreScreen())
    }

    @Test fun malformedSavedOptionsAreRejectedWithoutCoercingTheirTypes() {
        val state=screen(AnalysisMethod.FISHER_EXACT)
        fun encoded(update: (JSONObject)->Unit): ByteArray {
            val json=JSONObject(SessionCodec.encode(SavedSession.capture(state,null)).toString(Charsets.UTF_8))
            update(json.getJSONObject("request"));return json.toString().toByteArray()
        }
        assertThrows(IllegalArgumentException::class.java) { SessionCodec.decode(encoded { it.put("comparisonGroup",5) }) }
        assertThrows(IllegalArgumentException::class.java) { SessionCodec.decode(encoded { it.put("eventCategory",true) }) }
        assertThrows(IllegalArgumentException::class.java) { SessionCodec.decode(encoded { it.put("friedmanComparisons","true") }) }
        assertThrows(IllegalArgumentException::class.java) { SessionCodec.decode(encoded { it.put("friedmanAdjustment","invalid") }) }
    }

    @Test fun deletingAnUnselectedIdRemapsFriedmanConditionsAndPreservesFollowUpChoices() {
        val state=screen(AnalysisMethod.FRIEDMAN)
        val edited=state.withTableChange(DataEditing.apply(state.data!!,TableEdit.DeleteColumn(0)))
        assertEquals(listOf(0,1,2),edited.request.items)
        assertEquals(FriedmanAdjustment.BONFERRONI,edited.request.friedmanAdjustment)
        assertTrue(edited.request.friedmanComparisons)
        assertNull(edited.result)
        val result=Statistics.run(edited.data!!,edited.request)
        assertEquals(state.result!!.test,result.test)
        assertEquals(state.result.friedman,result.friedman)
    }

    @Test fun deletingACategoricalInputResetsItsOrientationWithTheColumnSuggestions() {
        val state=screen(AnalysisMethod.FISHER_EXACT)
        val edited=state.withTableChange(DataEditing.apply(state.data!!,TableEdit.DeleteColumn(0)))
        assertEquals("",edited.request.comparisonGroup)
        assertEquals("",edited.request.eventCategory)
        assertNull(edited.result)
    }

    @Test fun bothReportFormatsIncludeOrientationIntervalsRankSummariesAndPairwiseAdjustments() {
        for (method in listOf(AnalysisMethod.FISHER_EXACT,AnalysisMethod.FRIEDMAN)) {
            val state=screen(method);val result=state.result!!
            val plain=result.asReport(state.source,true)
            val model=result.formattedReport(state.source,true,emptyList())
            val html=HtmlReport.render(model)
            for (table in result.tables) {
                assertTrue(plain.contains(table.title))
                assertTrue(model.blocks.contains(ReportBlock.Table(table)))
                table.rows.flatten().filter { it.isNotEmpty() }.forEach { assertTrue(html.contains(HtmlReport.escape(it))) }
            }
            if (method==AnalysisMethod.FISHER_EXACT) {
                assertTrue(html.contains("B versus A"));assertTrue(html.contains("event: Other"))
                assertTrue(plain.contains("95% CI"));assertTrue(html.contains("Log-Wald"))
            } else {
                assertTrue(html.contains("Condition mean ranks"));assertTrue(html.contains("Bonferroni p"))
                assertTrue(plain.contains("Kendall's W"))
                assertEquals(2,result.tables.last().rowLabelColumns)
            }
            assertFalse(html.contains("<script"));assertFalse(html.contains("https://"))
        }
    }

    @Test fun comparisonAndConditionLabelsRemainEscapedInHtml() {
        val attack="<script>alert(1)</script>&\""
        val original=fictionalExample(AnalysisMethod.FISHER_EXACT)
        val changed=original.copy(rows=original.rows.map { row -> row.mapIndexed { i,value -> if (i==0 && value=="B") attack else value } })
        val request=AnalysisRequest.forData(AnalysisMethod.FISHER_EXACT,changed).copy(comparisonGroup=attack)
        val html=HtmlReport.render(Statistics.run(changed,request).formattedReport("Fictional",true,emptyList()))
        assertFalse(html.contains("<script>"));assertTrue(html.contains(HtmlReport.escape(attack)))
    }
}
