package io.github.tatselkrik.statis130lite

import org.junit.Assert.*
import org.junit.Test

class RankProbabilityTest {
    @Test fun perfectRanksIncludeTheirSingleObservedPermutationInEachDirectionalTail() {
        for(n in 2..6) {
            val d=CourseData(listOf("X","Y"),List(n) { listOf((it+1).toString(),(it+1).toString()) })
            val factorial=(2..n).fold(1.0) { product,value -> product*value }
            val req=AnalysisRequest(method=AnalysisMethod.SPEARMAN)
            assertEquals(2.0/factorial,Statistics.run(d,req).pValue,1e-14)
            assertEquals(1.0/factorial,Statistics.run(d,req.copy(tail=Tail.GREATER)).pValue,1e-14)
            assertEquals(1.0,Statistics.run(d,req.copy(tail=Tail.LESS)).pValue,1e-14)
        }
    }
}
