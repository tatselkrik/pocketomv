package io.github.tatselkrik.statis130lite

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FactorSessionReportTest {
    private val fields=listOf("factors","covariates","factorInteractions","sumsOfSquares","modelMarginalMeans","modelComparisons","modelAdjustment","checkSlopes",
        "factorCountMethod","factorCount","factorEigenvalue","factorExtraction","factorRotation","factorLoadingCutoff","factorSortLoadings","factorScreePlot","factorKmo","factorBartlett")
    private fun screen(method:AnalysisMethod):AnalysisScreenState {
        val data=fictionalExample(method)
        val request=AnalysisRequest.forData(method,data).let { if(method==AnalysisMethod.EFA) it.copy(factorCountMethod=FactorCountMethod.FIXED,
            factorCount="2",factorRotation=FactorRotation.PROMAX,factorLoadingCutoff="0.2",factorSortLoadings=true,factorScreePlot=true)
        else it.copy(sumsOfSquares=SumsOfSquares.II,modelComparisons=true,modelAdjustment=ModelAdjustment.BONFERRONI,
            checkNormality=true,checkVariances=true,checkSlopes=true,showQQPlot=true,showScatterplot=true) }
        return AnalysisScreenState(page=AppPage.RESULTS,data=data,draft=data.asCsv(),appliedDraft=data.asCsv(),request=request,
            result=Statistics.run(data,request),source="Fictional reference practice",fictional=true,draftFictional=true)
    }

    @Test fun allFactorOptionsResultsAndPendingExportsSurviveColdRecovery() {
        for(method in listOf(AnalysisMethod.FACTORIAL_ANOVA,AnalysisMethod.ANCOVA,AnalysisMethod.EFA)) {
            val state=screen(method)
            for(kind in ExportKind.entries) {
                val restored=SessionCodec.decode(SessionCodec.encode(SavedSession.capture(state,kind)))
                assertEquals(state.request,restored.screen.request);assertEquals(kind,restored.pendingExport)
                assertEquals(state,restored.restoreScreen())
            }
        }
    }

    @Test fun versionOneSessionsWithoutFactorFieldsKeepTheirOriginalMeaning() {
        val data=fictionalExample(AnalysisMethod.ONE_SAMPLE_T)
        val state=AnalysisScreenState(data=data,draft=data.asCsv(),appliedDraft=data.asCsv(),request=AnalysisRequest.forData(AnalysisMethod.ONE_SAMPLE_T,data).copy(modelAdjustment=ModelAdjustment.HOLM,factorEigenvalue="1",factorRotation=FactorRotation.VARIMAX))
        val json=JSONObject(String(SessionCodec.encode(SavedSession.capture(state,null))))
        fields.forEach { json.getJSONObject("request").remove(it) }
        assertEquals(state,SessionCodec.decode(json.toString().toByteArray()).restoreScreen())
    }

    @Test fun malformedFactorSelectionsOptionsAndExcessiveTextCannotEnterSavedWork() {
        val state=screen(AnalysisMethod.ANCOVA)
        fun invalid(field:String,value:Any) {
            val json=JSONObject(String(SessionCodec.encode(SavedSession.capture(state,null))))
            json.getJSONObject("request").put(field,value)
            assertThrows(Exception::class.java) { SessionCodec.decode(json.toString().toByteArray()) }
        }
        invalid("factors",JSONArray(listOf(1,1)));invalid("factors",JSONArray(listOf(0,1,2,3)))
        invalid("covariates",JSONArray(listOf(60)));invalid("covariates",JSONArray((0..6).toList()))
        invalid("factorInteractions","true");invalid("sumsOfSquares","TYPE_IV");invalid("modelAdjustment","unrecognized")
        invalid("factorCountMethod","sample");invalid("factorExtraction","PCA");invalid("factorRotation","unknown")
        invalid("factorCount","1".repeat(65));invalid("factorLoadingCutoff","0".repeat(65))
    }

    @Test fun columnDeletionRemapsFactorCovariateAndEfaListsWithoutReusingAnotherColumn() {
        val state=screen(AnalysisMethod.ANCOVA)
        val removed=state.withTableChange(DataEditing.apply(state.data!!,TableEdit.DeleteColumn(1)))
        assertEquals(listOf(1),removed.request.factors);assertEquals(listOf(2),removed.request.covariates)
        assertEquals(AnalysisMethod.ANCOVA,removed.request.method);assertNull(removed.result)
        val itemState=screen(AnalysisMethod.EFA)
        val itemRemoved=itemState.withTableChange(DataEditing.apply(itemState.data!!,TableEdit.DeleteColumn(2)))
        assertEquals(listOf(0,1,2,3,4),itemRemoved.request.items);assertNull(itemRemoved.result)
        assertEquals("Item 4",itemRemoved.data!!.columns[2])
    }

    @Test fun reportsRetainEveryTableCompositeIdentifierAndRequestedFigure() {
        for(method in listOf(AnalysisMethod.FACTORIAL_ANOVA,AnalysisMethod.ANCOVA,AnalysisMethod.EFA)) {
            val state=screen(method);val result=state.result!!
            val report=result.formattedReport(state.source,true,emptyList());val html=HtmlReport.render(report)
            val plain=result.asReport(state.source,true)
            result.tables.forEach { table ->
                assertTrue(report.blocks.contains(ReportBlock.Table(table)));assertTrue(plain.contains(table.title))
                table.rows.flatten().filter(String::isNotEmpty).forEach { assertTrue(html.contains(HtmlReport.escape(it))) }
            }
            result.assumptionChecks.forEach { assertTrue(html.contains(HtmlReport.escape(it.name))) }
            if(method==AnalysisMethod.EFA) {
                assertEquals(1,report.blocks.count { it is ReportBlock.Figure });assertTrue(html.contains("Scree plot"))
                assertTrue(html.contains("Factor structure correlations"));assertTrue(html.contains("Kaiser"))
            } else {
                assertEquals(2,report.blocks.count { it is ReportBlock.Figure })
                assertEquals(2,result.tables.single { it.title=="Estimated marginal means" }.rowLabelColumns)
                assertEquals(3,result.tables.single { it.title.startsWith("Marginal mean comparisons") }.rowLabelColumns)
            }
            assertFalse(html.contains("<script"));assertFalse(html.contains("https://"));assertTrue(html.contains("Fictional practice data"))
        }
    }

    @Test fun factorLevelAndItemNamesAreEscapedInTablesFiguresAndNotes() {
        val attack="<script>alert('x')</script>&\""
        val state=screen(AnalysisMethod.ANCOVA)
        val changed=state.data!!.copy(rows=state.data.rows.map { row -> row.mapIndexed { col,value -> if(col==1 && value=="Guided") attack else value } })
        val result=Statistics.run(changed,state.request)
        val html=HtmlReport.render(result.formattedReport(attack,true,listOf(attack)))
        assertFalse(html.contains("<script>"));assertTrue(html.contains(HtmlReport.escape(attack)))
        val efa=screen(AnalysisMethod.EFA)
        val renamed=efa.data!!.copy(columns=efa.data.columns.mapIndexed { index,name -> if(index==0) attack else name })
        val efaHtml=HtmlReport.render(Statistics.run(renamed,efa.request).formattedReport("Fictional",true,emptyList()))
        assertFalse(efaHtml.contains("<script>"));assertTrue(efaHtml.contains(HtmlReport.escape(attack)))
    }
}
