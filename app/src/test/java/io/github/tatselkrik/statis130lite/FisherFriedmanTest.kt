package io.github.tatselkrik.statis130lite

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

class FisherFriedmanTest {
    private val reference = JSONObject(javaClass.getResource("/fisher-friedman-reference.json")!!.readText())
    private fun categorical(values: List<Long>): CourseData = CourseData(listOf("Group", "Response", "Count"),
        listOf(listOf("A","Event",values[0].toString()),listOf("A","Other",values[1].toString()),
            listOf("B","Event",values[2].toString()),listOf("B","Other",values[3].toString())))
    private val request = AnalysisRequest(method = AnalysisMethod.FISHER_EXACT, first = 0, second = 1, count = 2)

    @Test fun exactProbabilitiesAndAssociationMeasuresMatchIndependentScipyReferences() {
        val cases = reference.getJSONArray("categorical")
        for (i in 0 until cases.length()) {
            val expected = cases.getJSONObject(i)
            val counts = expected.getJSONArray("table")
            val data = categorical(List(4) { counts.getJSONArray(it/2).getLong(it%2) })
            for (tail in Tail.entries) {
                val result = Statistics.run(data, request.copy(tail = tail))
                close(expected, "odds", result.statistic)
                close(expected.getJSONObject("p"), tail.name, result.pValue)
                close(expected, "cramer", result.estimates.single().value)
                val measures = result.associations!!.values
                assertEquals(abs(expected.getDouble("phi")),measures[0].value,1e-12)
                close(expected,"phi",measures.single { it.label=="Phi (signed)" }.value)
                val sampleOdds=measures.firstOrNull { it.label=="Odds ratio (uncorrected sample)" }?:measures[1]
                close(expected,"odds",sampleOdds.value)
                close(expected,"oddsLow",sampleOdds.lower95)
                close(expected,"oddsHigh",sampleOdds.upper95)
                close(expected,"risk",measures[2].value)
                close(expected,"riskLow",measures[2].lower95)
                close(expected,"riskHigh",measures[2].upper95)
                assertTrue(result.degreesOfFreedom.isEmpty())
            }
        }
    }

    @Test fun changingComparisonGroupReversesRatiosPhiAndDirectionalAlternatives() {
        val data = categorical(listOf(1,9,11,3).map(Int::toLong))
        val initial = Statistics.run(data, request.copy(tail = Tail.GREATER))
        val reverse = Statistics.run(data, request.copy(comparisonGroup = "B", tail = Tail.LESS))
        assertEquals(initial.pValue,reverse.pValue,1e-14)
        val first = initial.associations!!.values; val second = reverse.associations!!.values
        assertEquals(first[0].value,second[0].value,1e-14)
        assertEquals(-first.single { it.label=="Phi (signed)" }.value,second.single { it.label=="Phi (signed)" }.value,1e-14)
        assertEquals(1/first[1].value,second[1].value,1e-12)
        assertEquals(1/first[2].value,second[2].value,1e-12)
        assertEquals("A",reverse.associations.referenceGroup)
    }

    @Test fun eventSelectionUsesTheCorrectProbabilitiesAndIsExplained() {
        val data = categorical(listOf(1L,9L,11L,3L))
        val result = Statistics.run(data, request.copy(eventCategory = "Other"))
        val summary = result.associations!!
        assertEquals(33.0,summary.values[1].value,1e-12)
        assertEquals(4.2,summary.values[2].value,1e-12)
        assertEquals("Other",summary.event)
        assertTrue(result.notes.any { it.contains("A versus B") && it.contains("event: Other") })
    }

    @Test fun zeroCellsRetainUncorrectedBoundariesAlongsideJamoviCorrectedOddsAndIntervals() {
        val data = categorical(listOf(5L,0L,0L,7L))
        val result = Statistics.run(data,request)
        assertEquals(Double.POSITIVE_INFINITY,result.statistic,0.0)
        assertEquals(1.0,result.associations!!.values[0].value,0.0)
        assertNotNull(result.associations.values[1].lower95)
        assertTrue(result.associations.values[1].value.isFinite())
        val raw=result.associations.values.single { it.label=="Odds ratio (uncorrected sample)" }
        assertEquals(Double.POSITIVE_INFINITY,raw.value,0.0);assertNull(raw.lower95)
        assertNull(result.associations.values[2].lower95)
        assertTrue(result.tables.single().rows[1].last().contains("0.5"))
        assertTrue(result.asReport("Fictional",true).contains("∞"))
    }

    @Test fun countedAndUncountedInputsUseTheSameTableAndInference() {
        val values = listOf(1L,9L,11L,3L)
        val collapsed = categorical(values)
        val expanded = CourseData(listOf("Group","Response"), collapsed.rows.flatMap { row ->
            List(row[2].toInt()) { row.take(2) }
        })
        val counted = Statistics.run(collapsed,request)
        val raw = Statistics.run(expanded,request.copy(count=null))
        assertEquals(counted.frequencies,raw.frequencies)
        assertEquals(counted.test,raw.test)
        assertEquals(counted.associations,raw.associations)
        assertEquals(24L,raw.includedCases)
    }

    @Test fun missingRowsAreExcludedAndZeroOnlyCategoriesDoNotExpandTheTable() {
        val data = categorical(listOf(1L,9L,11L,3L)).let { it.copy(rows=it.rows+listOf(
            listOf("Ghost","Event","0"),listOf("","Event","3"),listOf("A","Other","NA"))) }
        val result = Statistics.run(data,request.copy(countMissingPolicy=CountMissingPolicy.OMIT))
        assertEquals(24L,result.includedCases); assertEquals(2,result.excludedRows)
        assertEquals(listOf("A","B"),result.frequencies!!.rowLabels)
    }

    @Test fun chiSquareKeepsItsTestAndAddsComparativeMeasuresOnlyForTwoByTwoTables() {
        val data = categorical(listOf(8L,2L,1L,5L))
        val result = Statistics.run(data,request.copy(method=AnalysisMethod.CHI_INDEPENDENCE))
        assertEquals("χ²",result.statisticLabel)
        assertEquals(listOf(1.0),result.degreesOfFreedom)
        assertNotNull(result.associations)
        val larger = fictionalExample(AnalysisMethod.CHI_INDEPENDENCE)
        val wide = Statistics.run(larger,AnalysisRequest.forData(AnalysisMethod.CHI_INDEPENDENCE,larger))
        assertNull(wide.associations)
        assertEquals("Cramér's V",wide.estimates.single().label)
        assertThrows(IllegalArgumentException::class.java) { Statistics.run(larger,AnalysisRequest.forData(AnalysisMethod.FISHER_EXACT,larger)) }
    }

    @Test fun invalidFrequenciesStaleOrientationAndUnsupportedExactCountTotalsAreRejected() {
        val data = categorical(listOf(1L,9L,11L,3L))
        for (invalid in listOf(request.copy(comparisonGroup="missing"),request.copy(eventCategory="missing")))
            assertThrows(IllegalArgumentException::class.java) { Statistics.run(data,invalid) }
        for (bad in listOf("-1","2.5","Infinity")) {
            val invalid = data.copy(rows=data.rows.mapIndexed { i,row -> if (i==0) row.take(2)+bad else row })
            assertThrows(IllegalArgumentException::class.java) { Statistics.run(invalid,request) }
        }
        val huge = categorical(listOf(1_073_741_824L,0L,0L,1_073_741_824L))
        assertThrows(IllegalArgumentException::class.java) { Statistics.run(huge,request) }
        assertNotNull(Statistics.run(huge,request.copy(method=AnalysisMethod.CHI_INDEPENDENCE)).associations)
    }

    @Test fun largerSupportedChiSquareCountsDoNotOverflowAssociationProducts() {
        val data = categorical(listOf(1_000_000_000_000L,1L,3L,1_000_000_000_000L))
        val result = Statistics.run(data,request.copy(method=AnalysisMethod.CHI_INDEPENDENCE))
        val values = result.associations!!.values
        assertEquals(1e24/3,values[1].value,1e10)
        assertTrue(values.all { it.value.isFinite() })
        assertTrue(values[1].lower95!!.isFinite())
        assertTrue(values[2].upper95!!.isFinite())
    }

    @Test fun friedmanAndRankContrastsMatchIndependentScipyAndLeastSquaresReferences() {
        val cases = reference.getJSONArray("friedman")
        for (i in 0 until cases.length()) {
            val expected = cases.getJSONObject(i)
            val data = data(expected)
            val selected = expected.getJSONArray("selected").let { a -> List(a.length()) { a.getInt(it) } }
            for (adjustment in FriedmanAdjustment.entries) {
                val result = Statistics.run(data,AnalysisRequest(method=AnalysisMethod.FRIEDMAN,items=selected,
                    friedmanComparisons=true,friedmanAdjustment=adjustment))
                close(expected,"q",result.statistic);close(expected,"p",result.pValue)
                close(expected,"w",result.estimates.single().value)
                assertEquals(expected.getInt("n").toLong(),result.includedCases)
                assertEquals(expected.getInt("excluded"),result.excludedRows)
                val summary = result.friedman!!
                close(expected,"tieCorrection",summary.tieCorrection)
                for (j in selected.indices) {
                    assertEquals(expected.getJSONArray("meanRanks").getDouble(j),summary.meanRanks[j],1e-10)
                    assertEquals(expected.getJSONArray("rankSums").getDouble(j),summary.rankSums[j],1e-10)
                }
                val pairs = expected.getJSONArray("pairs")
                assertEquals(pairs.length(),summary.comparisons.size)
                for (j in 0 until pairs.length()) {
                    val pair = pairs.getJSONObject(j);val actual=summary.comparisons[j]
                    close(pair,"difference",actual.rankDifference);close(pair,"se",actual.standardError)
                    close(pair,"t",actual.statistic);close(pair,"df",actual.df);close(pair,"p",actual.pValue)
                    close(pair,when(adjustment) { FriedmanAdjustment.NONE -> "p"; FriedmanAdjustment.HOLM -> "holm";
                        FriedmanAdjustment.BONFERRONI -> "bonferroni" },actual.adjustedPValue)
                }
            }
        }
    }

    @Test fun friedmanSummariesAndGraphsUseTheSameCompleteParticipants() {
        val expected = reference.getJSONArray("friedman").getJSONObject(1)
        val table = data(expected)
        val result = Statistics.run(table,AnalysisRequest(method=AnalysisMethod.FRIEDMAN,items=listOf(1,2,3),
            friedmanComparisons=true,showBoxplot=true))
        assertEquals(7L,result.includedCases)
        assertTrue(result.samples.all { it.size == 7 })
        assertTrue(result.plots!!.boxes.all { it.size == 7 })
        assertEquals(3,result.friedman!!.comparisons.size)
        assertEquals(2,result.tables.last().rowLabelColumns)
    }

    @Test fun friedmanIsInvariantToParticipantOrderAndWithinParticipantMonotoneShifts() {
        val table = fictionalExample(AnalysisMethod.FRIEDMAN)
        val request = AnalysisRequest.forData(AnalysisMethod.FRIEDMAN,table).copy(friedmanComparisons=true)
        val initial = Statistics.run(table,request)
        val changed = table.copy(rows=table.rows.mapIndexed { row,values -> values.mapIndexed { col,text ->
            if (col in request.items) (text.toDouble()+1000*row).toString() else text
        } }.reversed())
        val result = Statistics.run(changed,request)
        assertEquals(initial.test,result.test)
        assertEquals(initial.friedman!!.meanRanks,result.friedman!!.meanRanks)
        initial.friedman.comparisons.zip(result.friedman.comparisons).forEach { (a,b) ->
            assertEquals(a.rankDifference,b.rankDifference,1e-12)
            assertEquals(a.adjustedPValue!!,b.adjustedPValue!!,1e-12)
        }
    }

    @Test fun perfectOrderingKeepsTheOmnibusTestButExplainsUndefinedFollowUps() {
        val data = parseDelimited("A,B,C\n1,2,3\n1,2,3\n1,2,3\n1,2,3")
        val result = Statistics.run(data,AnalysisRequest(method=AnalysisMethod.FRIEDMAN,items=listOf(0,1,2),friedmanComparisons=true))
        assertEquals(1.0,result.estimates.single().value,0.0)
        assertTrue(result.friedman!!.comparisons.all { it.statistic == null && it.pValue == null && it.adjustedPValue == null })
        assertTrue(result.tables.last().notes.single().contains("No residual rank variation"))
    }

    @Test fun invalidFriedmanSelectionsAndAllTiedParticipantsAreRejected() {
        val table = parseDelimited("ID,A,B,C\n1,1,2,3\n2,2,3,1\n3,3,1,2")
        for (items in listOf(emptyList(),listOf(1,2),listOf(1,1,2),listOf(1,2,9)))
            assertThrows(IllegalArgumentException::class.java) { Statistics.run(table,AnalysisRequest(method=AnalysisMethod.FRIEDMAN,items=items)) }
        val allTied = parseDelimited("A,B,C\n1,1,1\n2,2,2")
        assertThrows(IllegalArgumentException::class.java) { Statistics.run(allTied,AnalysisRequest(method=AnalysisMethod.FRIEDMAN,items=listOf(0,1,2))) }
        val one = table.copy(rows=table.rows.take(1))
        assertThrows(IllegalArgumentException::class.java) { Statistics.run(one,AnalysisRequest(method=AnalysisMethod.FRIEDMAN,items=listOf(1,2,3))) }
    }

    private fun data(case: JSONObject): CourseData {
        val columns = case.getJSONArray("columns");val rows=case.getJSONArray("rows")
        return CourseData(List(columns.length()) { columns.getString(it) },List(rows.length()) { i ->
            val row=rows.getJSONArray(i);List(row.length()) { row.getString(it) }
        })
    }

    private fun close(json: JSONObject,key: String,actual: Double?) {
        if (json.isNull(key)) { assertNull(key,actual);return }
        assertNotNull(key,actual)
        val expected=json.getDouble(key)
        if (expected.isInfinite()) assertEquals(key,expected,actual!!,0.0)
        else {
            assertEquals(key,expected,actual!!,max(2e-12,abs(expected)*2e-9))
            if (key in setOf("p","TWO_SIDED","LESS","GREATER") && expected in Double.MIN_VALUE..1e-10) {
                assertTrue(key,actual>0);assertEquals(key,1.0,actual/expected,2e-8)
            }
        }
    }
}
