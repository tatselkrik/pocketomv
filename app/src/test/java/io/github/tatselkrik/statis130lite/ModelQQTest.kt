package io.github.tatselkrik.statis130lite

import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class ModelQQTest {
    @Test fun axisLabelsRoundMachineNoiseWithoutErasingTinyScaleValues() {
        val ordinary=PlotAxis(-1.549193338482967,1.549193338482966)
        assertEquals("0",ordinary.label(ordinary.midpoint))
        val tiny=PlotAxis(-1e-20,2e-20)
        assertNotEquals("0",tiny.label(tiny.midpoint))
        assertNotEquals("0",ordinary.label(1e-10))
    }
    @Test fun unequalCellLeveragesStandardizeResidualsWithoutChangingRawInference() {
        val cells=listOf(listOf(2),listOf(3,5),listOf(4,6,8),listOf(7,8,10,11))
        val data=CourseData(listOf("Outcome","A","B"),cells.flatMapIndexed { group,values->values.map { listOf(it.toString(),"A${group/2}","B${group%2}") } })
        val request=AnalysisRequest(method=AnalysisMethod.FACTORIAL_ANOVA,first=0,factors=listOf(1,2),
            showQQPlot=true,showScatterplot=true,checkNormality=true)
        val fit=FactorialAnova.compute(data,request)
        val expected=listOf(null,-sqrt(.6),sqrt(.6),-sqrt(1.8),0.0,sqrt(1.8),-sqrt(1.6),-sqrt(.4),sqrt(.4),sqrt(1.6))
        assertEquals(expected.size,fit.standardizedResiduals.size)
        expected.indices.forEach { i->if(expected[i]==null)assertNull(fit.standardizedResiduals[i])else assertEquals(expected[i]!!,fit.standardizedResiduals[i]!!,2e-12) }
        val standard=Statistics.run(data,request);val raw=Statistics.run(data,request.copy(modelQQBasis=ModelQQBasis.RAW))
        assertEquals(raw.test,standard.test);assertEquals(raw.assumptionChecks,standard.assumptionChecks)
        assertEquals(raw.plots!!.scatter,standard.plots!!.scatter)
        assertEquals(9,standard.plots.qq.single().points.size);assertEquals(10,raw.plots.qq.single().points.size)
        assertTrue(standard.plots.qq.single().identityReference);assertFalse(raw.plots.qq.single().identityReference)
        assertTrue(standard.plots.qq.single().detail!!.contains("1 undefined"))
        val report=standard.formattedReport("Fictional cells",true,preparationNotes=emptyList())
        assertTrue(report.blocks.filterIsInstance<ReportBlock.Figure>().any { it.caption.contains("Identity reference line") })
    }
    @Test fun identityQQCanShowTwoFiniteResidualsAndKeepsTheActualUnitLine() {
        val plot=PlotCalculator.qq("Standardized residuals",doubleArrayOf(-1.0,1.0),identityReference=true)
        assertEquals(2,plot.points.size);assertNull(plot.unavailable)
        assertEquals((plot.points.first().x+1)/2,plot.reference!!.left,1e-15)
        assertEquals((plot.points.last().x+1)/2,plot.reference.right,1e-15)
    }
}
