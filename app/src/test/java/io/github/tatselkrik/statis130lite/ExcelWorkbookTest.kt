package io.github.tatselkrik.statis130lite

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class ExcelWorkbookTest {
    @Test fun worksheetsFollowWorkbookOrderAndRetainSparseCellAlignment() {
        val workbook = read(book(listOf(
            "Scores" to "<row r=\"1\">${inline("A1", "Group")}${inline("B1", "Before")}${inline("C1", "After")}</row>" +
                "<row r=\"2\">${inline("A2", "A")}${number("C2", "7")}</row>" +
                "<row r=\"4\">${inline("A4", "B")}${number("B4", "4")}${number("C4", "9")}</row>",
            "Other" to rows("Value", "2", "6"),
        )))
        assertEquals(listOf("Scores", "Other"), workbook.sheets.map { it.name })
        val data = workbook.readSheet("r1", true).data
        assertEquals(listOf("Group", "Before", "After"), data.columns)
        assertEquals(listOf(listOf("A", "", "7"), listOf("B", "4", "9")), data.rows)
        assertEquals(listOf(listOf("2"), listOf("6")), workbook.readSheet("r2", true).data.rows)
    }

    @Test fun headerOptionKeepsTheFirstObservationAndMakesUniqueColumnNames() {
        val xml = "<row>${inline("A1", "Score")}${inline("B1", "Score")}${number("D1", "4")}</row>" +
            "<row>${number("A2", "1")}${number("D2", "7")}</row>"
        val workbook = read(book(listOf("Example" to xml)))
        assertEquals(listOf("Score", "Score (2)", "Column 3", "4"), workbook.readSheet("r1", true).data.columns)
        val noHeaders = workbook.readSheet("r1", false).data
        assertEquals(listOf("Column 1", "Column 2", "Column 3", "Column 4"), noHeaders.columns)
        assertEquals(listOf("Score", "Score", "", "4"), noHeaders.rows.first())
        assertEquals(2, noHeaders.rows.size)
    }

    @Test fun sharedAndInlineRichTextExcludePhoneticsAndDecodeExcelEscapesOnce() {
        val strings = "<sst xmlns=\"$NS\"><si><r><t>Sc</t></r><r><t>ore</t></r><rPh sb=\"0\" eb=\"2\"><t>ignore</t></rPh></si>" +
            "<si><t>_x005F_x0041_ &amp; café _xD83D__xDE00_</t></si></sst>"
        val xml = "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row>" +
            "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>1</v></c></row>" +
            "<row r=\"3\"><c r=\"A3\" t=\"inlineStr\"><is><r><t>East</t></r><r><t>_x000A_Team</t></r><rPh><t>ignore</t></rPh></is></c></row>"
        val data = read(book(listOf("Words" to xml), strings)).readSheet("r1", true).data
        assertEquals(listOf("Score"), data.columns)
        assertEquals(listOf(listOf("_x0041_ & café 😀"), listOf("East\nTeam")), data.rows)
        assertEquals(data, parseDelimited(data.asCsv()))
    }

    @Test fun cachedFormulaValuesAreImportedWithoutEvaluatingTheExpression() {
        val xml = "<row r=\"1\">${inline("A1", "Score")}</row>" +
            "<row r=\"2\"><c r=\"A2\"><f>NETWORK_OR_UNKNOWN_FUNCTION()</f><v>12</v></c></row>" +
            "<row r=\"3\"><c r=\"A3\" t=\"str\"><f>IF(TRUE,\"\",\"x\")</f><v/></c>${number("B3", "3")}</row>"
        val imported = read(book(listOf("Cached" to xml))).readSheet("r1", true)
        assertEquals(2, imported.formulaCount)
        assertEquals(listOf(listOf("12", ""), listOf("", "3")), imported.data.rows)
    }

    @Test fun missingFormulaResultsAndExcelErrorsRequireCorrectionRatherThanSilentBlanks() {
        for (cell in listOf("<c r=\"A2\"><f>1+2</f></c>", "<c r=\"A2\"><f>1+2</f><v/></c>",
            "<c r=\"A2\" t=\"e\"><v>#DIV/0!</v></c>")) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                read(book(listOf("Invalid" to "<row>${inline("A1", "Score")}</row><row>$cell</row>"))).readSheet("r1", true)
            }
            assertTrue(error.message!!.contains("A2"))
        }
    }

    @Test fun numbersBooleansAndIsoDateStringsKeepTheirStoredValues() {
        val xml = "<row>${inline("A1", "Number")}${inline("B1", "Flag")}${inline("C1", "Date")}</row>" +
            "<row>${number("A2", "-1.25E+3")}<c r=\"B2\" t=\"b\"><v>1</v></c><c r=\"C2\" t=\"d\"><v>2026-11-08</v></c></row>" +
            "<row>${number("A3", "2")}<c r=\"B3\" t=\"b\"><v>0</v></c></row>"
        val data = read(book(listOf("Types" to xml))).readSheet("r1", true).data
        assertEquals(listOf("-1.25E+3", "TRUE", "2026-11-08"), data.rows.first())
        assertEquals(listOf("2", "FALSE", ""), data.rows.last())
    }

    @Test fun invalidNumbersBooleanValuesAndSharedStringReferencesAreRejected() {
        for (cell in listOf(number("A2", "Infinity"), number("A2", "nonsense"),
            "<c r=\"A2\" t=\"b\"><v>4</v></c>", "<c r=\"A2\" t=\"s\"><v>99</v></c>")) {
            assertThrows(IllegalArgumentException::class.java) {
                read(book(listOf("Invalid" to "<row>${inline("A1", "Score")}</row><row>$cell</row>"))).readSheet("r1", true)
            }
        }
    }

    @Test fun malformedUnicodeEscapesAreRejectedBeforeTheyCanBreakAutosave() {
        for (value in listOf("_xD800_", "_xDC00_", "_xD800_abc", "_x0000_")) {
            val xml = "<row>${inline("A1", "Value")}</row><row>${inline("A2", value)}</row>"
            assertThrows(IllegalArgumentException::class.java) { read(book(listOf("Invalid text" to xml))).readSheet("r1", true) }
        }
    }

    @Test fun implicitAddressesAndBlankFormattedCellsDoNotCreateExtraObservationsOrColumns() {
        val xml = "<row><c t=\"inlineStr\"><is><t>Score</t></is></c></row>" +
            "<row><c><v>4</v></c><c r=\"XFD2\" s=\"1\"/></row>" +
            "<row r=\"7\"><c r=\"XFD7\" s=\"1\"/></row>" +
            "<row r=\"8\">${number("A8", "6")}</row>"
        val data = read(book(listOf("Sparse" to xml))).readSheet("r1", true).data
        assertEquals(listOf("Score"), data.columns)
        assertEquals(listOf(listOf("4"), listOf("6")), data.rows)
    }

    @Test fun invalidAndRepeatedAddressesAreRejected() {
        for (xml in listOf("<row r=\"2\">${number("A3", "2")}</row>",
            "<row r=\"1\">${number("A1", "2")}${number("A1", "3")}</row>",
            "<row r=\"1\">${number("ZZZ1", "2")}</row>",
            "<row r=\"2\">${number("A2", "2")}</row><row r=\"1\">${number("A1", "3")}</row>")) {
            assertThrows(IllegalArgumentException::class.java) { read(book(listOf("Invalid" to xml))).readSheet("r1", false) }
        }
    }

    @Test fun emptySheetsHeadersWithoutObservationsAndUnknownSelectionsHaveClearFailures() {
        val workbook = read(book(listOf("Empty" to "", "Header" to "<row>${inline("A1", "Score")}</row>")))
        for (id in listOf("r1", "r2", "not-a-sheet")) {
            assertThrows(IllegalArgumentException::class.java) { workbook.readSheet(id, true) }
        }
    }

    @Test fun hiddenWorksheetsAreIdentifiedAndCanBeExplicitlySelected() {
        val parts = parts(listOf("Visible" to rows("Score", "4"), "Hidden" to rows("Score", "8")))
        parts["xl/workbook.xml"] = parts.getValue("xl/workbook.xml").replace("name=\"Hidden\"", "name=\"Hidden\" state=\"hidden\"")
        val workbook = read(zip(parts))
        assertFalse(workbook.sheets[0].hidden)
        assertTrue(workbook.sheets[1].hidden)
        assertEquals("8", workbook.readSheet("r2", true).data.rows.single().single())
    }

    @Test fun partLinksAreResolvedWithoutHardCodedWorksheetOrWorkbookPaths() {
        val parts = parts(listOf("Moved" to rows("Score", "4")))
        parts["_rels/.rels"] = parts.getValue("_rels/.rels").replace("xl/workbook.xml", "books/main.xml")
        parts["books/main.xml"] = parts.remove("xl/workbook.xml")!!
        parts["books/_rels/main.xml.rels"] = parts.remove("xl/_rels/workbook.xml.rels")!!.replace("worksheets/sheet1.xml", "../tables/values.xml")
        parts["tables/values.xml"] = parts.remove("xl/worksheets/sheet1.xml")!!
        assertEquals("4", read(zip(parts)).readSheet("r1", true).data.rows.single().single())
    }

    @Test fun absoluteInternalPartLinksAndStrictNamespacesAreSupported() {
        val parts = parts(listOf("Strict" to rows("Score", "4")))
        parts["xl/_rels/workbook.xml.rels"] = parts.getValue("xl/_rels/workbook.xml.rels")
            .replace("Target=\"worksheets/", "Target=\"/xl/worksheets/")
        for ((key, value) in parts.toMap()) parts[key] = value.replace(NS, "http://purl.oclc.org/ooxml/spreadsheetml/main")
            .replace(REL, "http://purl.oclc.org/ooxml/officeDocument/relationships")
        assertEquals("4", read(zip(parts)).readSheet("r1", true).data.rows.single().single())
    }

    @Test fun externalLinksAndPartTraversalCannotBeImported() {
        for (target in listOf("https://example.invalid/data.xml\" TargetMode=\"External", "../../outside.xml", "%2e%2e/evil.xml")) {
            val parts = parts(listOf("Invalid" to rows("Score", "4")))
            parts["xl/_rels/workbook.xml.rels"] = parts.getValue("xl/_rels/workbook.xml.rels").replace("worksheets/sheet1.xml", target)
            assertThrows(IllegalArgumentException::class.java) { read(zip(parts)) }
        }
    }

    @Test fun documentTypesAndEntitiesAreRejectedBeforeExpansion() {
        for (dtd in listOf("<!DOCTYPE workbook [<!ENTITY x 'expanded'>]>",
            "<!DOCTYPE workbook SYSTEM 'file:///must-not-be-read'>")) {
            val parts = parts(listOf("Safe" to rows("Score", "4")))
            parts["xl/workbook.xml"] = dtd + parts.getValue("xl/workbook.xml")
            assertThrows(IllegalArgumentException::class.java) { read(zip(parts)) }
        }
    }

    @Test fun malformedXmlNonWorkbooksAndMissingPartsFailWithoutReturningPartialData() {
        val valid = parts(listOf("Score" to rows("Score", "4")))
        for (parts in listOf(mapOf("random.xml" to "<random/>"), valid - "xl/worksheets/sheet1.xml",
            valid + ("xl/workbook.xml" to "<workbook>"))) {
            assertThrows(IllegalArgumentException::class.java) { read(zip(parts)) }
        }
        assertThrows(IllegalArgumentException::class.java) { read("not an Excel file".toByteArray()) }
    }

    @Test fun inputAndExpandedPartLimitsRejectOversizedFiles() {
        assertThrows(IllegalArgumentException::class.java) { read(ByteArray(8_000_001)) }
        val large = parts(listOf("Small" to rows("Score", "4"))) + ("xl/media/padding.bin" to "x".repeat(8_000_001))
        assertThrows(IllegalArgumentException::class.java) { read(zip(large)) }
    }

    @Test fun rowColumnAndRectangularCellLimitsMatchTheExistingTableModel() {
        val tooManyRows = (1..20_002).joinToString("") { "<row>${number("A$it", "$it")}</row>" }
        val tooWide = "<row>${number("BI1", "1")}</row>"
        val tooManyCells = (1..4_168).joinToString("") { "<row>${number("BH$it", "$it")}</row>" }
        for (xml in listOf(tooManyRows, tooWide, tooManyCells)) {
            assertThrows(IllegalArgumentException::class.java) { read(book(listOf("Big" to xml))).readSheet("r1", true) }
        }
    }

    @Test fun excelAndDelimitedDataReachTheSameAnalysisAndSavedSession() {
        val xml = "<row>${inline("A1", "Before")}${inline("B1", "After")}</row>" +
            listOf(4 to 7, 6 to 8, 9 to 10, 8 to 12).mapIndexed { index, pair ->
                "<row>${number("A${index + 2}", pair.first.toString())}${number("B${index + 2}", pair.second.toString())}</row>"
            }.joinToString("")
        val data = read(book(listOf("Pairs" to xml))).readSheet("r1", true).data
        val request = AnalysisRequest.forData(AnalysisMethod.PAIRED_T, data)
        val result = Statistics.run(data, request)
        assertEquals(Statistics.run(parseDelimited(data.asCsv()), request).statistic, result.statistic, 0.0)
        val text = data.asCsv()
        val screen = AnalysisScreenState(data = data, draft = text, appliedDraft = text, source = "Fictional.xlsx · Pairs",
            page = AppPage.RESULTS, request = request, result = result)
        val restored = SessionCodec.decode(SessionCodec.encode(SavedSession.capture(screen, null))).restoreScreen()
        assertEquals(data, restored.data)
        assertEquals(screen.source, restored.source)
        assertEquals(result.statistic, restored.result!!.statistic, 0.0)
    }

    @Test fun importRoutingSupportsGenericMimeTypesAndKeepsCsvCompatible() {
        val bytes = book(listOf("Example" to rows("Score", "4")))
        assertTrue(readDataImport(ByteArrayInputStream(bytes), "DATA.XLSX", null, true) is DataImport.Workbook)
        assertTrue(readDataImport(ByteArrayInputStream(bytes), "download", "application/octet-stream", true) is DataImport.Workbook)
        val csv = readDataImport(ByteArrayInputStream("Score\n4\n6".toByteArray()), "scores.csv", "application/vnd.ms-excel", true)
        assertEquals(listOf(listOf("4"), listOf("6")), (csv as DataImport.Table).data.rows)
        assertThrows(IllegalArgumentException::class.java) { readDataImport(ByteArrayInputStream(bytes), "old.xls", null, true) }
        assertThrows(IllegalArgumentException::class.java) { readDataImport(ByteArrayInputStream("Score\n4".toByteArray()), "bad.xlsx", null, true) }
    }

    @Test fun streamsWithShortReadsStillDetectExcelWithoutAFilenameOrMimeType() {
        val bytes = book(listOf("Example" to rows("Score", "4")))
        val stream = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, minOf(1, length))
            override fun available(): Int = 0
        }
        assertTrue(readDataImport(stream, "download", null, true) is DataImport.Workbook)
    }

    private fun read(bytes: ByteArray): ExcelWorkbook = ExcelWorkbook.read(ByteArrayInputStream(bytes))
    private fun number(ref: String, value: String) = "<c r=\"$ref\"><v>$value</v></c>"
    private fun inline(ref: String, value: String) = "<c r=\"$ref\" t=\"inlineStr\"><is><t>$value</t></is></c>"
    private fun rows(header: String, vararg values: String) = "<row>${inline("A1", header)}</row>" +
        values.mapIndexed { index, value -> "<row>${number("A${index + 2}", value)}</row>" }.joinToString("")
    private fun book(sheets: List<Pair<String, String>>, strings: String? = null) = zip(parts(sheets, strings))
    private fun parts(sheets: List<Pair<String, String>>, strings: String? = null): MutableMap<String, String> {
        val parts = linkedMapOf(
            "_rels/.rels" to "<Relationships xmlns=\"$PACKAGE\"><Relationship Id=\"doc\" Type=\"$REL/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>",
            "xl/workbook.xml" to "<workbook xmlns=\"$NS\" xmlns:r=\"$REL\"><sheets>" +
                sheets.mapIndexed { index, sheet -> "<sheet name=\"${sheet.first}\" sheetId=\"${index + 1}\" r:id=\"r${index + 1}\"/>" }.joinToString("") + "</sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to "<Relationships xmlns=\"$PACKAGE\">" +
                sheets.indices.joinToString("") { "<Relationship Id=\"r${it + 1}\" Type=\"$REL/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" } +
                (if (strings == null) "" else "<Relationship Id=\"ss\" Type=\"$REL/sharedStrings\" Target=\"sharedStrings.xml\"/>") + "</Relationships>",
        )
        sheets.forEachIndexed { index, sheet -> parts["xl/worksheets/sheet${index + 1}.xml"] =
            "<worksheet xmlns=\"$NS\"><sheetData>${sheet.second}</sheetData></worksheet>" }
        if (strings != null) parts["xl/sharedStrings.xml"] = strings
        return parts
    }
    private fun zip(parts: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            parts.toList().reversed().forEach { (name, xml) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    companion object {
        private const val NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        private const val PACKAGE = "http://schemas.openxmlformats.org/package/2006/relationships"
    }
}
