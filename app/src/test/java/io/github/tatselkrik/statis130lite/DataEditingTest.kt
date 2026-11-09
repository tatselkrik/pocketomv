package io.github.tatselkrik.statis130lite

import org.junit.Assert.*
import org.junit.Test

class DataEditingTest {
    private fun items() = parseDelimited("Q1,Q2,Q3,Group\n1,5,2,A\n2,4,3,A\n3,NA,4,B\n5,1,NA,B\nNA,NA,NA,C")

    @Test fun editingCellsRetainsQuotesMultilineUnicodeAndOtherValuesExactly() {
        val before = items()
        val text = " A, \"quoted\"\n🙂 "
        val updated = DataEditing.apply(before, TableEdit.Cell(0, 3, text)).data
        assertEquals(text, updated.rows[0][3])
        assertEquals(before.rows.drop(1), updated.rows.drop(1))
        assertEquals("A", before.rows[0][3])
        val again = DataEditing.apply(updated, TableEdit.Cell(0, 3, "")).data
        assertEquals("", again.rows[0][3])
        assertTrue(updated.asCsv().contains("\" A, \"\"quoted\"\"\n🙂 \""))
    }

    @Test fun rowAndColumnOperationsHaveStablePositionsAndNeverMutateTheSource() {
        val original = items()
        val inserted = DataEditing.apply(original, TableEdit.AddRow(0)).data
        assertEquals(List(4) { "" }, inserted.rows[1])
        assertEquals(original, DataEditing.apply(inserted, TableEdit.DeleteRow(1)).data)
        val appended = DataEditing.apply(original, TableEdit.AddColumn(" New item ")).data
        assertEquals("New item", appended.columns.last())
        assertTrue(appended.rows.all { it.last() == "" })
        val renamed = DataEditing.apply(appended, TableEdit.RenameColumn(4, "Q4")).data
        assertEquals("Q4", renamed.columns.last())
        assertEquals(original, DataEditing.apply(renamed, TableEdit.DeleteColumn(4)).data)
        assertEquals(4, original.columns.size)
    }

    @Test fun renamedOrImportedHeadersWithDelimiterCharactersRoundTripInCsv() {
        val one = CourseData(listOf("Score"), listOf(listOf("1"), listOf("2")))
        val renamed = DataEditing.apply(one, TableEdit.RenameColumn(0, "Score; batch")).data
        assertEquals(renamed, parseDelimited(renamed.asCsv()))
        val imported = CourseData(listOf("Item\t1", "Group; label"), listOf(listOf("1", "A; east"), listOf("2", "yes\tno")))
        assertEquals(imported, parseDelimited(imported.asCsv()))
    }

    @Test fun reverseScoringUsesStatedBoundsPreservesMissingAndIsAnInvolution() {
        val data = items()
        val result = DataEditing.reverse(data, 0, "Q1_R", "1", "5")
        assertEquals(listOf("5", "4", "3", "1", ""), result.values)
        val appended = result.appendTo(data).data
        assertEquals(data.rows.map { it[0] }, appended.rows.map { it[0] })
        val back = DataEditing.reverse(appended, 4, "Q1_again", "1", "5")
        assertEquals(listOf("1", "2", "3", "5", ""), back.values)
        assertEquals(1, result.missingCount)
    }

    @Test fun decimalAndNegativeBoundsProduceExactReadableScores() {
        val data = parseDelimited("Item\n-0.2\n0.1\n0.6")
        assertEquals(listOf("0.6", "0.3", "-0.2"), DataEditing.reverse(data, 0, "Reverse", "-0.2", "0.6").values)
        val wide = parseDelimited("Item\n-1.7e308\n0\n1.7e308")
        assertEquals(listOf("1.7E+308", "0", "-1.7E+308"), DataEditing.reverse(wide, 0, "Reverse", "-1.7e308", "1.7e308").values)
    }

    @Test fun reverseRejectsOutOfScaleAndInvalidInputsWithoutPartialColumns() {
        val data = parseDelimited("Item\n1\n6\n3")
        val error = assertThrows(IllegalArgumentException::class.java) { DataEditing.reverse(data, 0, "R", "1", "5") }
        assertTrue(error.message!!.contains("Row 2"))
        assertEquals(1, data.columns.size)
        for (bounds in listOf("5" to "1", "1" to "1", "NaN" to "5", "0" to "Infinity")) {
            assertThrows(IllegalArgumentException::class.java) { DataEditing.reverse(items(), 0, "R", bounds.first, bounds.second) }
        }
    }

    @Test fun exactRecodingIsCaseSensitivePreservesLiteralNAAndKeepsUnmatchedWhenChosen() {
        val data = CourseData(listOf("Answer"), listOf("yes", "YES", "NA", "", "no", "1.0", "1").map { listOf(it) })
        val rules = listOf(RecodeRule("yes", "1"), RecodeRule("no", "0"), RecodeRule("NA", "Not applicable"), RecodeRule("1", "One"))
        val result = DataEditing.recode(data, 0, "Coded", RecodeMatch.EXACT, rules, true)
        assertEquals(listOf("1", "YES", "Not applicable", "", "0", "1.0", "One"), result.values)
        assertTrue(result.details.contains("4 matched · 2 unmatched"))
        val missing = DataEditing.recode(data, 0, "Coded", RecodeMatch.EXACT, rules, false)
        assertEquals(listOf("1", "", "Not applicable", "", "0", "", "One"), missing.values)
    }

    @Test fun numericRangesAreInclusiveOpenEndedAndUseTheFirstMatchingRule() {
        val data = parseDelimited("Score\n100\n85\n84.9\n75\n50\n49\nNA")
        val rules = listOf(RecodeRule("85", "High"), RecodeRule("75", "Middle", "85"), RecodeRule("50", "Pass", "75"))
        val result = DataEditing.recode(data, 0, "Grade", RecodeMatch.RANGE, rules, false)
        assertEquals(listOf("High", "High", "Middle", "Middle", "Pass", "", ""), result.values)
        assertTrue(result.details.contains("5 matched · 1 unmatched"))
        val equal = DataEditing.recode(parseDelimited("X\n1\n1.0\n2"), 0, "One", RecodeMatch.RANGE, listOf(RecodeRule("1", "Yes", "1")), true)
        assertEquals(listOf("Yes", "Yes", "2"), equal.values)
    }

    @Test fun recodeValidationPreventsAmbiguousOrInvalidRules() {
        for (rules in listOf(emptyList(), listOf(RecodeRule()), listOf(RecodeRule("A", "1"), RecodeRule("A", "2")))) {
            assertThrows(IllegalArgumentException::class.java) { DataEditing.recode(items(), 3, "Code", RecodeMatch.EXACT, rules, true) }
        }
        for (rules in listOf(listOf(RecodeRule()), listOf(RecodeRule("5", "1", "2")), listOf(RecodeRule("bad", "1", "5")))) {
            assertThrows(IllegalArgumentException::class.java) { DataEditing.recode(items(), 0, "Code", RecodeMatch.RANGE, rules, true) }
        }
        assertThrows(IllegalArgumentException::class.java) { DataEditing.recode(items(), 3, "Code", RecodeMatch.RANGE, listOf(RecodeRule("1", "A", "5")), true) }
    }

    @Test fun completeAndAvailableItemScoresUseExplicitMinimumAnsweredRules() {
        val data = items()
        fun score(mode: ItemScore, missing: MissingItems, required: Int = 1) = DataEditing.score(data, listOf(0, 1, 2), "Score", mode, missing, required).values
        assertEquals(listOf("8", "9", "", "", ""), score(ItemScore.SUM, MissingItems.COMPLETE))
        assertEquals(score(ItemScore.SUM, MissingItems.COMPLETE), score(ItemScore.SUM, MissingItems.COMPLETE, 99))
        assertEquals(listOf("8", "9", "7", "6", ""), score(ItemScore.SUM, MissingItems.AVAILABLE, 2))
        assertEquals(listOf("3", "3", "", "3", ""), DataEditing.score(data, listOf(0, 1), "Score", ItemScore.MEAN, MissingItems.AVAILABLE, 2).values)
        val mean = score(ItemScore.MEAN, MissingItems.AVAILABLE, 2)
        assertEquals(8.0 / 3.0, mean[0].toDouble(), 1e-15)
        assertEquals(listOf("3", "3.5", "3", ""), mean.drop(1))
    }

    @Test fun scoringReverseKeyedItemsUsesTheNewColumnOnlyWhenSelected() {
        val base = items()
        val reversed = DataEditing.reverse(base, 1, "Q2_R", "1", "5").appendTo(base).data
        val result = DataEditing.score(reversed, listOf(0, 4, 2), "Total", ItemScore.SUM, MissingItems.COMPLETE, 1)
        assertEquals(listOf("4", "7", "", "", ""), result.values)
        assertEquals(base.rows.map { it[1] }, reversed.rows.map { it[1] })
    }

    @Test fun bigDecimalScoringAvoidsIntermediateOverflowAndCancellation() {
        val data = parseDelimited("A,B,C\n1.7e308,1.7e308,-1.7e308\n0.1,0.2,0.3")
        val mean = DataEditing.score(data, listOf(0, 1, 2), "Mean", ItemScore.MEAN, MissingItems.COMPLETE, 1)
        assertEquals(1.7e308 / 3, mean.values[0].toDouble(), 1e292)
        assertEquals("0.2", mean.values[1])
        val overflow = parseDelimited("A,B\n1.7e308,1.7e308")
        assertThrows(IllegalArgumentException::class.java) { DataEditing.score(overflow, listOf(0, 1), "Sum", ItemScore.SUM, MissingItems.COMPLETE, 1) }
    }

    @Test fun invalidItemSelectionCountsAndValuesAreRejected() {
        for (columns in listOf(emptyList(), listOf(0, 0), listOf(99))) assertThrows(IllegalArgumentException::class.java) {
            DataEditing.score(items(), columns, "Score", ItemScore.SUM, MissingItems.COMPLETE, 1)
        }
        for (count in listOf(0, 4)) assertThrows(IllegalArgumentException::class.java) {
            DataEditing.score(items(), listOf(0, 1, 2), "Score", ItemScore.SUM, MissingItems.AVAILABLE, count)
        }
        assertThrows(IllegalArgumentException::class.java) { DataEditing.score(items(), listOf(0, 3), "Score", ItemScore.MEAN, MissingItems.COMPLETE, 1) }
    }

    @Test fun columnAndTableLimitsFailBeforeAnEditIsApplied() {
        for (name in listOf("", "Q1", "bad\nname", "A".repeat(121))) assertThrows(IllegalArgumentException::class.java) {
            DataEditing.apply(items(), TableEdit.AddColumn(name))
        }
        val one = CourseData(listOf("X"), listOf(listOf("1")))
        assertThrows(IllegalArgumentException::class.java) { DataEditing.apply(one, TableEdit.DeleteRow(0)) }
        assertThrows(IllegalArgumentException::class.java) { DataEditing.apply(one, TableEdit.DeleteColumn(0)) }
        assertThrows(IllegalArgumentException::class.java) { DataEditing.apply(one, TableEdit.Cell(0, 0, "X".repeat(2_000_001))) }
        val wide = CourseData(List(60) { "X$it" }, listOf(List(60) { "1" }))
        assertThrows(IllegalArgumentException::class.java) { DataEditing.apply(wide, TableEdit.AddColumn("Extra")) }
        val long = CourseData(listOf("X"), List(20_000) { listOf("1") })
        assertThrows(IllegalArgumentException::class.java) { DataEditing.apply(long, TableEdit.AddRow(0)) }
        val full = CourseData(List(50) { "X$it" }, List(5_000) { List(50) { "1" } })
        assertThrows(IllegalArgumentException::class.java) { DataEditing.apply(full, TableEdit.AddColumn("Extra")) }
    }

    @Test fun derivedColumnsAreExplicitSnapshotsAndPreviewNeverChangesTheTable() {
        val data = items()
        val prepared = DataEditing.reverse(data, 0, "Q1_R", "1", "5")
        assertEquals(4, data.columns.size)
        val added = prepared.appendTo(data)
        assertNotNull(added.note)
        val changed = DataEditing.apply(added.data, TableEdit.Cell(0, 0, "4")).data
        assertEquals("5", changed.rows[0][4])
        assertEquals("1", data.rows[0][0])
        assertTrue(added.note!!.contains("rescore"))
    }

    @Test fun editsInvalidateResultsPreserveOptionsAndRemapUnremovedSelections() {
        val data = fictionalExample(AnalysisMethod.REGRESSION)
        val request = AnalysisRequest(AnalysisMethod.REGRESSION, showScatterplot = true)
        val before = AnalysisScreenState(data = data, draft = data.asCsv(), appliedDraft = data.asCsv(),
            request = request, result = Statistics.run(data, request))
        val updated = before.withTableChange(DataEditing.apply(data, TableEdit.Cell(0, 0, "2")))
        assertNull(updated.result)
        assertEquals(request, updated.request)
        assertFalse(updated.hasUnappliedChanges)
        assertEquals(DataEditorView.CELLS, updated.dataEditor)
        val shifted = CourseData(listOf("ID", "X", "Y", "Weight"), listOf(listOf("1", "2", "4", "1")))
        val selected = before.copy(data = shifted, draft = shifted.asCsv(), appliedDraft = shifted.asCsv(),
            request = request.copy(first = 1, second = 2, weight = 3), result = null)
        val removed = selected.withTableChange(DataEditing.apply(shifted, TableEdit.DeleteColumn(0)))
        assertEquals(0, removed.request.first); assertEquals(1, removed.request.second); assertEquals(2, removed.request.weight)
        assertTrue(removed.request.showScatterplot)
    }

    @Test fun deletingASelectedVariableResetsSelectionsAndUnappliedTextIsProtected() {
        val data = items()
        val state = AnalysisScreenState(data = data, draft = data.asCsv(), appliedDraft = data.asCsv(),
            request = AnalysisRequest(AnalysisMethod.PAIRED_T))
        val change = DataEditing.apply(data, TableEdit.DeleteColumn(0))
        val edited = state.withTableChange(change)
        assertEquals(AnalysisRequest.forData(AnalysisMethod.PAIRED_T, change.data), edited.request)
        assertThrows(IllegalArgumentException::class.java) { state.copy(draft = "unfinished").withTableChange(change) }
    }
}
