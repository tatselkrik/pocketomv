package io.github.tatselkrik.statis130lite

import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class RegressionQQTest {
    private val data = parseDelimited("Y,X\n0,-1\n2,-1\n2,1\n4,1")
    private val request = AnalysisRequest(method=AnalysisMethod.MULTIPLE_REGRESSION,first=0,predictors=listOf(1),
        showQQPlot=true,showScatterplot=true,checkNormality=true)

    @Test fun qqUsesSampleSdAndIdentityWithoutChangingInferenceOrRawScatter() {
        // The fitted line is Y = 2 + X, with residuals -1,+1,-1,+1.
        val result=Statistics.run(data,request)
        val plot=result.plots!!.qq.single()
        val expected=listOf(-sqrt(.75),-sqrt(.75),sqrt(.75),sqrt(.75))
        expected.indices.forEach { assertEquals(expected[it],plot.points[it].y,2e-15) }
        assertTrue(plot.identityReference);assertNull(plot.unavailable)
        val axis=PlotAxis(expected.first(),expected.last())
        assertEquals(axis.fraction(plot.points.first().x),plot.reference!!.left,2e-15)
        assertEquals(axis.fraction(plot.points.last().x),plot.reference.right,2e-15)
        val without=Statistics.run(data,request.copy(showQQPlot=false))
        assertEquals(without.test,result.test);assertEquals(without.tables,result.tables)
        assertEquals(without.assumptionChecks,result.assumptionChecks);assertEquals(without.notes,result.notes)
        assertEquals(without.plots!!.scatter,result.plots.scatter)
        listOf(-1.0,1.0,-1.0,1.0).forEachIndexed { i,y->assertEquals(y,result.plots.scatter!!.points[i].y,2e-15) }
    }

    @Test fun dimensionlessPlotSurvivesExtremeOutcomeUnits() {
        val expected=Statistics.run(data,request).plots!!.qq.single().points
        for(scale in listOf(1e-80,1e80,-11.0)) {
            val changed=data.copy(rows=data.rows.map { listOf((it[0].toDouble()*scale).toString(),it[1]) })
            val points=Statistics.run(changed,request).plots!!.qq.single().points
            expected.indices.forEach { assertEquals(expected[it].x,points[it].x,2e-15);assertEquals(expected[it].y,points[it].y,2e-15) }
        }
    }

    @Test fun perfectFitHasExplainedUnavailablePlotAndRetainsItsMainResult() {
        val perfect=parseDelimited("Y,X\n3,1\n5,2\n7,3\n9,4")
        val result=Statistics.run(perfect,request)
        val qq=result.plots!!.qq.single()
        assertEquals(Double.POSITIVE_INFINITY,result.test!!.statistic,0.0)
        assertEquals(4,qq.size);assertTrue(qq.points.isEmpty());assertTrue(qq.identityReference)
        assertTrue(qq.unavailable!!.contains("no usable variation"))
        assertEquals(4,result.plots.scatter!!.points.size)
    }

    @Test fun savedWorkAndReportKeepTheStandardizedPlotAndIdentityCaption() {
        val state=AnalysisScreenState(data=data,draft=data.asCsv(),appliedDraft=data.asCsv(),request=request,
            source="Fictional regression practice",fictional=true,draftFictional=true,result=Statistics.run(data,request))
        val restored=SessionCodec.decode(SessionCodec.encode(SavedSession.capture(state,null))).restoreScreen()
        assertEquals(state.request,restored.request)
        assertEquals(state.result!!.plots,restored.result!!.plots)
        val report=restored.result.formattedReport(restored.source,true,preparationNotes=emptyList())
        assertTrue(report.blocks.filterIsInstance<ReportBlock.Figure>().any { it.caption.contains("Identity reference line")&&it.caption.contains("sample standard deviation") })
        assertTrue(HtmlReport.render(report).contains("Sample-standardized model residuals"))
    }
}
