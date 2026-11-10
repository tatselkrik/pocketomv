package io.github.tatselkrik.statis130lite

import org.junit.Assert.*
import org.junit.Test

class ScreeChartTest {
    @Test fun negativeCommonEigenvaluesKeepTheirDistinctVerticalPositions() {
        val plot=ScreePlot(listOf(3.0,-.2,-.8),listOf(1.1,.1,-.1),EigenvalueBasis.COMMON_FACTOR,20,123)
        val scene=ReportCharts.scree(plot);val points=scene.marks.filterIsInstance<ChartMark.Circle>()
        assertEquals(3,points.size);assertEquals(42.0,points[0].y,1e-12)
        assertEquals(235.68421052631578,points[1].y,1e-10);assertEquals(272.0,points[2].y,1e-12)
        assertTrue(scene.marks.filterIsInstance<ChartMark.Text>().any { it.text=="Common-factor eigenvalue" })
    }
}
