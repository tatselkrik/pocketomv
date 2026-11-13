package io.github.tatselkrik.statis130lite

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OmvProjectTest {
    @Test fun openDesktopFormatAndApplyMissingValuesAndFilters() {
        val parts = linkedMapOf<String, ByteArray>()
        val fields = JSONArray()
            .put(field("Filter 1", "Integer", "Nominal").put("columnType", "Filter").put("active", true))
            .put(field("Rating", "Integer", "Ordinal"))
            .put(field("Score", "Decimal", "Continuous").put("missingValues", JSONArray(listOf("== -99"))))
            .put(field("Identifier", "Text", "ID"))
        parts["meta"] = manifest()
        parts["metadata.json"] = JSONObject().put("dataSet", JSONObject().put("rowCount", 4).put("columnCount", 4)
            .put("fields", fields)).toString().toByteArray()
        parts["xdata.json"] = """{"Rating":{"labels":[[7,"High","H",true],[1,"Low","L",true]]}}""".toByteArray()
        val ints = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN)
        listOf(1, 0, 1, 1, 7, 1, 1, Int.MIN_VALUE).forEach(ints::putInt)
        listOf(1.25, 99.0, -99.0, Double.NaN).forEach(ints::putDouble)
        listOf(0, 5, 5, Int.MIN_VALUE).forEach(ints::putInt)
        parts["data.bin"] = ints.array()
        parts["strings.bin"] = "é-1\u0000β-2\u0000".toByteArray(Charsets.UTF_8)
        parts["index.html"] = "<script>throw 0</script>".toByteArray()
        val data = read(zip(parts)).data
        assertEquals(listOf("Rating", "Score", "Identifier"), data.columns)
        assertEquals(listOf(listOf("High", "1.25", "é-1"), listOf("Low", "", "β-2"), listOf("", "", "")), data.rows)
        assertEquals(ColumnMetadata(ColumnMeasure.ORDINAL, listOf("High", "Low")), data.columnMetadata[0])
        assertEquals(listOf(1), data.numericColumns())
        assertEquals(listOf("High", "Low"), data.orderedCategories(0, listOf("Low", "High")))
    }

    @Test fun preparedProjectsRestoreRequestAndRecalculateResult() {
        val method = AnalysisMethod.PAIRED_T
        val data = fictionalExample(method)
        val request = AnalysisRequest.forData(method, data).copy(first = 1, second = 0, tail = Tail.LESS, checkNormality = true)
        val result = Statistics.run(data, request)
        val written = OmvProjects.write(data, request, result, true)
        assertTrue(written.includesJamoviAnalysis)
        val detected = readDataImport(ByteArrayInputStream(written.bytes), "document", null, false) as DataImport.Project
        assertEquals(data.rows, detected.project.data.rows)
        assertEquals(request, detected.project.analyses.single())
        assertTrue(detected.project.restoreResult)
        assertTrue(detected.project.fictional)
        assertEquals(result.statistic, Statistics.run(detected.project.data, request).statistic, 0.0)
        val archive = unzip(written.bytes).apply { remove("pocketomv.json") }
        val native = read(zip(archive)).analyses.single()
        assertEquals(request, native)
        assertEquals("ttestPS", OmvWire.read(archive.getValue("02 ttestPS/analysis"))?.name)
    }

    @Test fun unsupportedNativeSettingsAreKeptForPocketomv() {
        val method = AnalysisMethod.DESCRIPTIVES
        val data = fictionalExample(method)
        val request = AnalysisRequest.forData(method, data).copy(weight = 1, population = true)
        val output = OmvProjects.write(data, request, null, false)
        assertFalse(output.includesJamoviAnalysis)
        val loaded = read(output.bytes)
        assertEquals(request, loaded.analyses.single())
        assertFalse(loaded.restoreResult)
        assertFalse(loaded.fictional)
    }

    @Test fun retainsImportedMeasureAndCategoryOrderAcrossEditsAndSessionRestore() {
        val data = CourseData(listOf("Unused", "Band", "Value"), listOf(listOf("x", "2", "6"), listOf("y", "1", "3")),
            mapOf(1 to ColumnMetadata(ColumnMeasure.ORDINAL, listOf("2", "1"))))
        val edited = DataEditing.apply(data, TableEdit.DeleteColumn(0)).data
        val session = SavedSession.capture(AnalysisScreenState(data = edited), null)
        val reloaded = SessionCodec.decode(SessionCodec.encode(session)).screen.data!!
        assertEquals(edited, reloaded)
        assertEquals(listOf(1), reloaded.numericColumns())
        assertEquals(listOf("2", "1"), reloaded.orderedCategories(0, listOf("1", "2")))
    }

    @Test fun rejectsIncorrectDataLengthAndCaseWeights() {
        val data = fictionalExample(AnalysisMethod.ONE_SAMPLE_T)
        val bytes = OmvProjects.write(data, AnalysisRequest(), null, false).bytes
        val archive = unzip(bytes)
        val table = archive.getValue("data.bin")
        archive["data.bin"] = table.copyOf(table.size - 1)
        assertThrows(IllegalArgumentException::class.java) { read(zip(archive)) }
        archive["data.bin"] = table
        val metadata = JSONObject(archive.getValue("metadata.json").toString(Charsets.UTF_8))
        metadata.getJSONObject("dataSet").put("weights", "Score")
        archive["metadata.json"] = metadata.toString().toByteArray()
        assertThrows(IllegalArgumentException::class.java) { read(zip(archive)) }
    }

    @Test fun ignoresAnUnusableAnalysisAndAnOutOfDateAppExtension() {
        val data = fictionalExample(AnalysisMethod.ONE_SAMPLE_T)
        val archive = unzip(OmvProjects.write(data, AnalysisRequest(reference = "4"), null, false).bytes)
        archive["03 invalid/analysis"] = byteArrayOf(50, 16, 1)
        val reopened = read(zip(archive))
        assertEquals(data.rows, reopened.data.rows)
        assertEquals(4.0, reopened.analyses.single().reference.toDouble(), 0.0)
        assertTrue(reopened.notes.any { "could not be imported" in it })
        assertTrue(reopened.notes.any { "no longer match" in it })
    }

    @Test fun pairedProjectCanOfferWilcoxonAlongsideTTestWithDiagnostics() {
        val data = fictionalExample(AnalysisMethod.PAIRED_T)
        val record = OmvAnalysis("ttestPS", mapOf(
            "pairs" to listOf(mapOf("i1" to "Before", "i2" to "After")),
            "students" to true, "wilcoxon" to true, "norm" to true, "qq" to true,
        ))
        val restored = OmvOptions.fromJamovi(record, data).requests
        assertEquals(listOf(AnalysisMethod.PAIRED_T, AnalysisMethod.WILCOXON), restored.map { it.method })
        assertTrue(restored[0].checkNormality && restored[0].showQQPlot)
        assertFalse(restored[1].checkNormality || restored[1].showQQPlot)
        restored.forEach { assertTrue(Statistics.run(data, it).pValue.isFinite()) }
    }

    private fun read(bytes: ByteArray): OmvProject = OmvProjects.read(ByteArrayInputStream(bytes))

    private fun field(name: String, storage: String, measure: String): JSONObject = JSONObject()
        .put("name", name).put("dataType", storage).put("measureType", measure)

    private fun manifest(): ByteArray = "Manifest-Version: 1.0\njamovi-Archive-Version: 11.0\n".toByteArray()

    private fun unzip(bytes: ByteArray): MutableMap<String, ByteArray> = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        buildMap {
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes())
            }
        }.toMutableMap()
    }

    private fun zip(parts: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            parts.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
