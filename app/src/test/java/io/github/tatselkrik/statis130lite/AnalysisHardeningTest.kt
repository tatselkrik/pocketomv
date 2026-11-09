package io.github.tatselkrik.statis130lite

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class AnalysisHardeningTest {
    @Test fun symmetricTwoFactorStructureDoesNotStopAtANonmaximalRotation() {
        val data=fictionalExample(AnalysisMethod.EFA)
        val request=AnalysisRequest.forData(AnalysisMethod.EFA,data).copy(factorCountMethod=FactorCountMethod.FIXED,factorCount="2",factorRotation=FactorRotation.PROMAX)
        val result=ExploratoryFactors.compute(NumericMatrix.read(data,request.items,3,20),request)
        // Independently optimized Kaiser-varimax plane and power-4 Promax target.
        assertEquals(0.2989882873,result.phi!![0][1],5e-5)
        val expected=listOf(.64,.5625,.49,.7225,.5184,.6084)
        expected.indices.forEach { assertEquals(expected[it],result.communalities!![it],2e-6) }
        assertTrue(result.rmsr!!<1e-6)
    }
    @Test fun anovaAndAncovaAreInvariantToOrderAndSupportedChangesOfUnits() {
        val data=fictionalExample(AnalysisMethod.ANCOVA)
        for(method in listOf(AnalysisMethod.FACTORIAL_ANOVA,AnalysisMethod.ANCOVA)) {
            val request=AnalysisRequest.forData(method,data).copy(modelComparisons=true,checkSlopes=true)
            val initial=FactorialAnova.compute(data,request)
            val changed=data.copy(rows=data.rows.reversed().map { row -> row.toMutableList().also { it[0]=(row[0].toDouble()*2.5+130).toString();it[3]=(row[3].toDouble()*-7+20).toString() } })
            val final=FactorialAnova.compute(changed,request)
            assertEquals(initial.f,final.f,1e-8);assertEquals(initial.p,final.p,1e-10)
            initial.terms.indices.forEach { i -> assertEquals(initial.terms[i].ss*6.25,final.terms[i].ss,1e-7);assertEquals(initial.terms[i].f,final.terms[i].f,1e-8) }
            initial.means.indices.forEach { i -> assertEquals(initial.means[i].mean*2.5+130,final.means[i].mean,1e-8) }
        }
    }

    @Test fun efaCorrelationsAndCommunalitiesAreInvariantToUnitsAndRowOrder() {
        val data=fictionalExample(AnalysisMethod.EFA)
        val request=AnalysisRequest.forData(AnalysisMethod.EFA,data).copy(factorCountMethod=FactorCountMethod.FIXED,factorCount="2")
        val initial=ExploratoryFactors.compute(NumericMatrix.read(data,request.items,3,20),request)
        val changed=data.copy(rows=data.rows.reversed().map { row -> row.mapIndexed { i,value -> (value.toDouble()*(i+1)*1e-80).toString() } })
        val final=ExploratoryFactors.compute(NumericMatrix.read(changed,request.items,3,20),request)
        initial.communalities!!.indices.forEach { i -> assertEquals(initial.communalities[i],final.communalities!![i],2e-7) }
        assertEquals(initial.kmo!!,final.kmo!!,1e-10);assertEquals(initial.bartlett,final.bartlett,1e-8)
    }

    @Test fun independentVariablesDoNotProduceAForcedOrFabricatedFactor() {
        val data=CourseData(List(4) { "Item ${it+1}" },List(40) { row -> List(4) { item -> cos(2*Math.PI*(row+.5)*(item+1)/40).toString() } })
        val base=AnalysisRequest.forData(AnalysisMethod.EFA,data)
        val result=Statistics.run(data,base)
        assertTrue(result.notes.any { it.contains("zero factors") });assertNull(result.test)
        assertEquals("0",result.tables.first().rows.single { it[0]=="Extracted factors" }[1])
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            Statistics.run(data,base.copy(factorCountMethod=FactorCountMethod.FIXED,factorCount="1"))
        }.message!!.contains("no usable inter-variable correlations"))
    }

    @Test(timeout=10000) fun excessiveFactorialWorkIsRejectedBeforeRepeatedModelFitting() {
        val data=CourseData(listOf("Outcome","A","B","C"),List(20000) { row ->
            listOf((row%11+sin(row.toDouble())).toString(),(row%6).toString(),(row/6%5).toString(),(row/30%2).toString()) })
        val error=assertThrows(IllegalArgumentException::class.java) {
            Statistics.run(data,AnalysisRequest(method=AnalysisMethod.FACTORIAL_ANOVA,factors=listOf(1,2,3)))
        }
        assertTrue(error.message!!.contains("budget"))
    }

    @Test(timeout=10000) fun parallelAnalysisMemoryAndRuntimeDoNotGrowWithNumberOfObservations() {
        val thresholds=ExploratoryFactors.parallel(20000,20)
        assertEquals(20,thresholds.size);assertTrue(thresholds.all { it.isFinite() && it>0 })
        assertTrue(thresholds.first() in 1.02..1.1);assertTrue(thresholds.last() in .9..1.0)
    }

    @Test(timeout=10000) fun fisherHandlesLargeLegalFrequencyMarginsWithoutAllocatingCases() {
        val data=parseDelimited("Group,Event,Count\nA,Yes,500\nA,No,1500\nB,Yes,999999500\nB,No,999998500")
        val request=AnalysisRequest.forData(AnalysisMethod.FISHER_EXACT,data)
        for(tail in Tail.entries) {
            val result=Statistics.run(data,request.copy(tail=tail))
            assertEquals(2000000000L,result.includedCases);assertTrue(result.pValue in 0.0..1.0)
            assertTrue(result.associations!!.values.all { !it.value.isNaN() })
        }
        val tooLarge=data.copy(rows=data.rows+listOf(listOf("A","Yes","200000000")))
        assertThrows(IllegalArgumentException::class.java) { Statistics.run(tooLarge,request) }
        val expensive=parseDelimited("Group,Event,Count\nA,Yes,500000000\nA,No,499999000\nB,Yes,500001000\nB,No,500000000")
        assertTrue(assertThrows(IllegalArgumentException::class.java) { Statistics.run(expensive,request) }.message!!.contains("feasible tables"))
    }

    @Test fun fisherAggregatesSplitCountsAndKeepsDirectionalOrientationAfterRowReordering() {
        val data=fictionalExample(AnalysisMethod.FISHER_EXACT);val request=AnalysisRequest.forData(AnalysisMethod.FISHER_EXACT,data).copy(comparisonGroup="B",eventCategory="Event",tail=Tail.GREATER)
        val initial=Statistics.run(data,request)
        val split=data.copy(rows=data.rows.flatMap { row -> val n=row[2].toInt();if(n>1) listOf(row.take(2)+"1",row.take(2)+(n-1).toString()) else listOf(row) }.reversed())
        val final=Statistics.run(split,request)
        assertEquals(initial.test,final.test);assertEquals(initial.associations,final.associations)
    }

    @Test fun friedmanRanksAreUnchangedByWithinParticipantMonotoneTransformations() {
        val data=fictionalExample(AnalysisMethod.FRIEDMAN);val request=AnalysisRequest.forData(AnalysisMethod.FRIEDMAN,data).copy(friedmanComparisons=true)
        val initial=Statistics.run(data,request)
        val changed=data.copy(rows=data.rows.mapIndexed { i,row -> row.mapIndexed { j,value -> if(j==0) value else (value.toDouble()*10+i*100).toString() } })
        val final=Statistics.run(changed,request)
        assertEquals(initial.test,final.test);assertEquals(initial.friedman,final.friedman)
    }

    @Test fun allNewMethodsRejectNonfiniteSelectedValuesWithoutReadingUnselectedComments() {
        for(method in listOf(AnalysisMethod.FACTORIAL_ANOVA,AnalysisMethod.ANCOVA,AnalysisMethod.EFA)) {
            val data=fictionalExample(method);val request=AnalysisRequest.forData(method,data)
            val selected=if(method==AnalysisMethod.EFA) request.items.first() else request.first
            for(value in listOf("Infinity","-Infinity","1e200","not numeric")) {
                val changed=data.copy(rows=data.rows.mapIndexed { i,row -> if(i==0) row.toMutableList().also { it[selected]=value } else row })
                assertThrows(IllegalArgumentException::class.java) { Statistics.run(changed,request) }
            }
        }
    }

    @Test fun correlationMatricesAndGroupedDescriptivesKeepOutputBoundsExplicit() {
        val matrix=fictionalExample(AnalysisMethod.CORRELATION_MATRIX)
        val request=AnalysisRequest.forData(AnalysisMethod.CORRELATION_MATRIX,matrix)
        val initial=Statistics.run(matrix,request).correlations!!
        val final=Statistics.run(matrix.copy(rows=matrix.rows.reversed()),request).correlations!!
        initial.cells.indices.forEach { i -> initial.cells.indices.forEach { j ->
            assertEquals(initial.cells[i][j].coefficient!!,final.cells[i][j].coefficient!!,1e-12)
            assertEquals(final.cells[i][j],final.cells[j][i])
        } }
        val groups=CourseData(listOf("Value","Group"),List(41) { listOf(it.toString(),"Group $it") })
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            Statistics.run(groups,AnalysisRequest(method=AnalysisMethod.DESCRIPTIVES,first=0,splitBy=1))
        }.message!!.contains("40 groups"))
    }
}
