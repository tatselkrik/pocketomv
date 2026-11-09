package io.github.tatselkrik.statis130lite

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class AdvancedSessionReportTest {
    private fun screen(method: AnalysisMethod): AnalysisScreenState {
        val data = fictionalExample(method)
        val base = AnalysisRequest.forData(method, data)
        val request = when (method) {
            AnalysisMethod.RELIABILITY -> base.copy(showItemCorrelations=true)
            AnalysisMethod.MULTIPLE_REGRESSION -> base.copy(checkNormality=true,showQQPlot=true,showScatterplot=true)
            AnalysisMethod.REPEATED_ANOVA -> base.copy(repeatedCorrection=RepeatedCorrection.HUYNH_FELDT,repeatedComparisons=true,showBoxplot=true)
            else -> base
        }
        return AnalysisScreenState(page=AppPage.RESULTS,data=data,draft=data.asCsv(),appliedDraft=data.asCsv(),
            request=request,result=Statistics.run(data,request),fictional=true,draftFictional=true,
            source="Fictional example",preparationNotes=listOf("Prepared example values"))
    }

    @Test fun allAdvancedOptionsAndEveryPendingExportSurviveProcessRecovery() {
        for (method in listOf(AnalysisMethod.RELIABILITY,AnalysisMethod.MULTIPLE_REGRESSION,AnalysisMethod.REPEATED_ANOVA)) {
            val state=screen(method)
            for (kind in ExportKind.entries) {
                val restored=SessionCodec.decode(SessionCodec.encode(SavedSession.capture(state,kind)))
                assertEquals(kind,restored.pendingExport)
                assertEquals(state,restored.restoreScreen())
            }
        }
    }

    @Test fun olderSessionsWithoutNewFieldsStillDecodeAndRecalculate() {
        val state=screen(AnalysisMethod.PAIRED_T)
        val json=JSONObject(String(SessionCodec.encode(SavedSession.capture(state,null)),Charsets.UTF_8))
        val request=json.getJSONObject("request")
        for (key in listOf("items","predictors","showItemCorrelations","repeatedCorrection","repeatedComparisons")) request.remove(key)
        val restored=SessionCodec.decode(json.toString().toByteArray()).restoreScreen()
        assertEquals(state,restored)
    }

    @Test fun invalidSelectionsAndOrphanedHtmlPdfExportsAreRejected() {
        for (values in listOf(listOf<Any>(0,0),listOf<Any>(0,60),listOf<Any>(0,1.5),listOf<Any>(0,"1"))) {
            val json=JSONObject(String(SessionCodec.encode(SavedSession.capture(screen(AnalysisMethod.RELIABILITY),null)),Charsets.UTF_8))
            json.getJSONObject("request").put("items",JSONArray(values))
            assertThrows(IllegalArgumentException::class.java) { SessionCodec.decode(json.toString().toByteArray()) }
        }
        for (kind in listOf(ExportKind.HTML,ExportKind.PDF)) {
            assertThrows(IllegalArgumentException::class.java) { SessionCodec.encode(SavedSession.capture(AnalysisScreenState(),kind)) }
        }
    }

    @Test fun deletingAnItemOrPredictorRemapsSelectionsAndClearsResults() {
        val state=screen(AnalysisMethod.RELIABILITY)
        val changed=state.withTableChange(DataEditing.apply(state.data!!,TableEdit.DeleteColumn(1)))
        assertEquals(listOf(0,1,2),changed.request.items)
        assertNull(changed.result)
        val regression=screen(AnalysisMethod.MULTIPLE_REGRESSION)
        val fewer=regression.withTableChange(DataEditing.apply(regression.data!!,TableEdit.DeleteColumn(1)))
        assertEquals(listOf(1),fewer.request.predictors)
        assertEquals(0,fewer.request.first)
        val repeated=screen(AnalysisMethod.REPEATED_ANOVA)
        val withoutId=repeated.withTableChange(DataEditing.apply(repeated.data!!,TableEdit.DeleteColumn(0)))
        assertEquals(listOf(0,1,2),withoutId.request.items)
        assertEquals(repeated.request.repeatedCorrection,withoutId.request.repeatedCorrection)
    }

    @Test fun allNewResultTablesNotesAndPreparationAppearInBothReportFormats() {
        for (method in listOf(AnalysisMethod.RELIABILITY,AnalysisMethod.MULTIPLE_REGRESSION,AnalysisMethod.REPEATED_ANOVA)) {
            val state=screen(method); val result=state.result!!
            val plain=result.asReport(state.source,true,state.preparationNotes)
            val formatted=result.formattedReport(state.source,true,state.preparationNotes)
            val html=HtmlReport.render(formatted)
            for (table in result.tables) {
                assertTrue(plain.contains(table.title)); assertTrue(html.contains(HtmlReport.escape(table.title)))
                assertTrue(formatted.blocks.contains(ReportBlock.Table(table)))
            }
            assertTrue(html.contains("Prepared example values")); assertTrue(html.contains("Fictional practice data"))
            result.notes.forEach { assertTrue(html.contains(HtmlReport.escape(it))) }
            assertFalse(html.contains("<script")); assertFalse(html.contains("https://"))
        }
    }

    @Test fun htmlEscapesUntrustedTextInEveryReportLocationAndSvg() {
        val malicious="<script>alert(\"x\")</script>&' <img src=https://invalid.test>"
        val scene=ChartScene(640,340,listOf(ChartMark.Text(20.0,20.0,malicious)))
        val report=FormattedReport(malicious,false,listOf(ReportBlock.Paragraph(malicious),
            ReportBlock.Table(AnalysisTable(malicious,listOf(malicious),listOf(listOf(malicious)),listOf(malicious))),
            ReportBlock.Figure(malicious,scene,malicious)))
        val html=HtmlReport.render(report)
        assertFalse(html.contains("<script>")); assertFalse(html.contains("<img src="))
        assertTrue(html.contains("&lt;script&gt;")); assertTrue(html.contains("&quot;x&quot;")); assertTrue(html.contains("&#39;"))
        assertTrue(html.contains("default-src 'none'"))
        val svg=ReportCharts.svg(scene,malicious)
        val dom=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(svg.byteInputStream())
        assertEquals(malicious,dom.getElementsByTagName("text").item(0).textContent)
    }

    @Test fun formattedReportsIncludeAllFrequencyRowsAndEveryRequestedGraphSample() {
        val data=CourseData(listOf("Value"),List(130) { listOf("Category $it") })
        val req=AnalysisRequest.forData(AnalysisMethod.DESCRIPTIVES,data).copy(descriptiveNumeric=false)
        val result=Statistics.run(data,req)
        val formatted=result.formattedReport("Fictional categories",true,emptyList())
        assertEquals(130,formatted.blocks.filterIsInstance<ReportBlock.Table>().single { it.value.title=="Value frequencies" }.value.rows.size)
        assertTrue(HtmlReport.render(formatted).contains("Category 129"))
        val grouped=fictionalExample(AnalysisMethod.ANOVA)
        val graphs=Statistics.run(grouped,AnalysisRequest.forData(AnalysisMethod.ANOVA,grouped).copy(showBoxplot=true,showQQPlot=true))
        val report=graphs.formattedReport("Fictional groups",true,emptyList())
        val figures=report.blocks.filterIsInstance<ReportBlock.Figure>()
        assertEquals(graphs.plots!!.qq.size+1,figures.size)
        assertEquals(graphs.plots.qq.sumOf { it.points.size },figures.filter { it.title.startsWith("Normal Q") }.sumOf { it.scene.marks.count { mark -> mark is ChartMark.Circle } })
    }

    @Test fun regressionReportFiguresContainAllPointsInOriginalUnits() {
        val state=screen(AnalysisMethod.MULTIPLE_REGRESSION)
        val figures=state.result!!.formattedReport(state.source,true,emptyList()).blocks.filterIsInstance<ReportBlock.Figure>()
        assertEquals(2,figures.size)
        assertEquals(listOf(12,12),figures.map { it.scene.marks.count { mark -> mark is ChartMark.Circle } })
        assertTrue(figures.any { it.title=="Residuals vs fitted" })
        figures.forEach { figure ->
            figure.scene.marks.filterIsInstance<ChartMark.Circle>().forEach { assertTrue(it.x.isFinite() && it.y.isFinite()) }
        }
    }

    @Test fun histogramReportContainsBothVectorFigureAndCompleteIntervals() {
        val data=parseDelimited("Value\n1\n2\n2\n3\n4\n5\n7")
        val result=Statistics.run(data,AnalysisRequest.forData(AnalysisMethod.DESCRIPTIVES,data).copy(histogramBins="3"))
        val report=result.formattedReport("Fictional values",true,emptyList())
        val intervals=report.blocks.filterIsInstance<ReportBlock.Table>().single { it.value.title=="Histogram intervals" }
        assertEquals(3,intervals.value.rows.size)
        val figure=report.blocks.filterIsInstance<ReportBlock.Figure>().single()
        assertEquals(3,figure.scene.marks.count { it is ChartMark.Rectangle })
        assertTrue(HtmlReport.render(report).contains("<svg"))
    }

    @Test fun longAxisLabelsStayBoundedAndFullNamesRemainInTheCaption() {
        val x="Predictor "+"W".repeat(110)
        val y="Outcome "+"漢".repeat(110)
        val data=CourseData(listOf(x,y),listOf(listOf("1","3"),listOf("2","4"),listOf("3","7"),listOf("4","8")))
        val result=Statistics.run(data,AnalysisRequest.forData(AnalysisMethod.REGRESSION,data).copy(showScatterplot=true))
        val report=result.formattedReport("Fictional long labels",true,emptyList())
        val figure=report.blocks.filterIsInstance<ReportBlock.Figure>().single()
        assertTrue(figure.scene.marks.filterIsInstance<ChartMark.Text>().all { it.text.codePointCount(0,it.text.length)<=45 })
        assertTrue(figure.caption.contains(x) && figure.caption.contains(y))
        assertTrue(HtmlReport.render(report).contains(HtmlReport.escape(x)))
    }
}
