package io.github.tatselkrik.statis130lite

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

internal const val OMV_MIME = "application/vnd.jamovi.omv"

internal data class OmvProject(
    val data: CourseData,
    val analyses: List<AnalysisRequest>,
    val notes: List<String>,
    val fictional: Boolean = false,
    val restoreResult: Boolean = false,
)

internal data class OmvExport(val bytes: ByteArray, val includesJamoviAnalysis: Boolean)

internal object OmvProjects {
    private const val LEGACY_MANIFEST = "META-INF/MANIFEST.MF"
    private const val APP_OPTIONS = "pocketomv.json"
    private const val MISSING = Int.MIN_VALUE
    private const val MAX_ARCHIVE = 16 * 1024 * 1024
    private const val MAX_EXPANDED = 64 * 1024 * 1024
    private const val MAX_EDITABLE_CHARS = 2_000_000
    private val archiveVersion = Regex("""^jamovi-Archive-Version: *(\d+)\.\d+ *$""", RegexOption.MULTILINE)
    private val analysisEntry = Regex("""\d{2,} [^/]+/analysis""")

    fun hasManifestHeader(input: BufferedInputStream): Boolean {
        input.mark(64)
        return try {
            val header = ByteArray(30)
            if (!input.fill(header) || !header.copyOf(4).contentEquals(byteArrayOf(80, 75, 3, 4))) return false
            val length = (header[26].toInt() and 255) + ((header[27].toInt() and 255) shl 8)
            if (length !in setOf(4, LEGACY_MANIFEST.length)) return false
            val name = ByteArray(length)
            input.fill(name) && name.toString(Charsets.US_ASCII) in setOf("meta", LEGACY_MANIFEST)
        } finally {
            input.reset()
        }
    }

    fun read(input: InputStream): OmvProject {
        val archive = readArchive(input)
        val entries = archive.entries
        val manifest = requireNotNull(entries["meta"] ?: entries[LEGACY_MANIFEST]) { "The .omv file has no jamovi manifest." }
        val version = archiveVersion.find(utf8(manifest).replace("\r\n", "\n"))?.groupValues?.get(1)?.toIntOrNull()
        require(version in 1..12) { "This .omv archive version is unsupported." }
        val dataset = jsonObject(requireNotNull(entries["metadata.json"]) { "The .omv file has no table metadata." })
            .getJSONObject("dataSet")
        val rowCount = dataset.get("rowCount") as? Int
        val fields = dataset.getJSONArray("fields")
        require(rowCount != null && rowCount in 1..20_000 && fields.length() in 1..60 &&
            rowCount.toLong() * fields.length() <= 250_000 &&
            (!dataset.has("columnCount") || dataset.get("columnCount") == fields.length())
        ) { "Use a jamovi project with 1–20,000 rows, 1–60 columns and at most 250,000 cells." }
        require(dataset.isNull("weights") || dataset.optString("weights").isEmpty()) {
            "This project uses case weights. Clear the active weight in a copy in jamovi before opening it here."
        }
        val xdata = entries["xdata.json"]?.let(::jsonObject) ?: JSONObject()
        val columns = List(fields.length()) { column(fields.getJSONObject(it), xdata) }
        require(columns.map { it.name }.distinct().size == columns.size) { "The .omv file has duplicate column names." }
        val expectedBytes = columns.sumOf { it.width * rowCount }
        val cellBytes = requireNotNull(entries["data.bin"]) { "The .omv file has no saved table values." }
        require(cellBytes.size == expectedBytes) { "The saved jamovi table is incomplete or inconsistent." }
        val cells = ByteBuffer.wrap(cellBytes).order(ByteOrder.LITTLE_ENDIAN)
        val strings = StringTable(entries["strings.bin"])
        val included = BooleanArray(rowCount) { true }
        var offset = 0
        columns.forEach { column ->
            if (column.filter && column.active) {
                require(column.storage == Storage.INTEGER) { "The .omv file has an invalid row filter." }
                for (row in 0 until rowCount) {
                    val value = cells.getInt(offset + row * 4)
                    require(value == 0 || value == 1 || value == MISSING) { "The .omv file has an invalid row filter." }
                    included[row] = included[row] && value == 1
                }
            }
            offset += column.width * rowCount
        }
        val kept = included.count { it }
        require(kept > 0) { "The project's active filters exclude all rows." }
        val variables = columns.filterNot { it.filter }
        require(variables.isNotEmpty()) { "The project has no data columns." }
        val rows = List(kept) { ArrayList<String>(variables.size) }
        var characters = variables.sumOf { it.name.length }
        for (column in columns) {
            if (column.filter) {
                cells.position(cells.position() + column.width * rowCount)
                continue
            }
            var target = 0
            for (row in 0 until rowCount) {
                if (included[row]) {
                    val value = column.read(cells, strings)
                    characters += value.length
                    require(characters <= MAX_EDITABLE_CHARS) { "This jamovi project is too large to edit." }
                    rows[target++].add(value)
                } else {
                    cells.position(cells.position() + column.width)
                }
            }
        }
        val data = CourseData(variables.map { it.name }, rows, variables.mapIndexed { index, column ->
            index to ColumnMetadata(column.measure, column.labels.values.toList())
        }.toMap())
        DataEditing.validate(data)
        val local = entries[APP_OPTIONS]?.let { bytes ->
            runCatching {
                val value = jsonObject(bytes)
                require(value.getInt("version") == 1 && value.getJSONArray("columns").asStrings() == data.columns)
                require(value.getString("projectFingerprint") == fingerprint(entries, archive.analysisDigests))
                LocalOptions(SessionCodec.decodeRequest(value.getJSONObject("request")),
                    value.getBoolean("fictional"), value.getBoolean("ran"))
            }.getOrNull()
        }
        val imported = if (local == null) archive.analyses.map { OmvOptions.fromJamovi(it, data) } else emptyList()
        val requests = local?.let { listOf(it.request) } ?: imported.flatMap { it.requests }.distinct()
        val notes = buildList {
            if (kept < rowCount) add("Skipped ${rowCount - kept} rows excluded by jamovi filters.")
            if (columns.any { it.filter }) add("Filter rules stay in the original jamovi file.")
            if (variables.any { it.calculated }) add("Imported saved values from calculated and transformed columns.")
            if (variables.any { it.missing.isNotEmpty() }) add("Converted custom missing values to blank cells.")
            if (local == null && (archive.unreadableAnalyses || imported.any { it.limited })) {
                add("Some jamovi analyses or options could not be imported.")
            }
            if (entries.containsKey(APP_OPTIONS) && local == null) add("The pocketomv settings no longer match this file.")
        }
        return OmvProject(data, requests, notes, local?.fictional == true, local?.ran == true)
    }

    fun write(data: CourseData, request: AnalysisRequest, result: AnalysisResult?, fictional: Boolean): OmvExport {
        DataEditing.validate(data)
        val measures = OmvOptions.exportMeasures(data, request)
        val numericSort = request.method != AnalysisMethod.DESCRIPTIVES && !request.method.isFactorialModel
        val columns = data.columns.indices.map { index -> outputColumn(data, index, measures[index], numericSort) }
        val native = OmvOptions.toJamovi(request, data, columns.map { it.measure })
        val metadata = JSONArray()
        val xdata = JSONObject()
        val strings = ByteArrayOutputStream()
        val stringOffsets = mutableMapOf<String, Int>()
        val cellBytes = ByteBuffer.allocate(columns.sumOf { it.width * data.rows.size }).order(ByteOrder.LITTLE_ENDIAN)
        for ((index, column) in columns.withIndex()) {
            metadata.put(column.metadata(index + 1))
            if (column.labels.isNotEmpty()) {
                val labels = JSONArray()
                column.labels.forEach { (code, label) -> labels.put(JSONArray(listOf(code, label, label, true))) }
                xdata.put(column.name, JSONObject().put("labels", labels))
            }
            val labelCodes = column.labels.entries.associate { (code, label) -> label to code }
            for (row in data.rows) {
                val value = row[index]
                when {
                    column.storage == Storage.DECIMAL -> cellBytes.putDouble(value.toDoubleOrNull() ?: Double.NaN)
                    column.storage == Storage.TEXT && column.measure == ColumnMeasure.ID -> {
                        val position = if (value.isBlank()) MISSING else stringOffsets.getOrPut(value) {
                            val position = strings.size()
                            strings.write(value.toByteArray(Charsets.UTF_8))
                            strings.write(0)
                            position
                        }
                        cellBytes.putInt(position)
                    }
                    column.categorical -> cellBytes.putInt(labelCodes[value] ?: MISSING)
                    else -> cellBytes.putInt(value.toIntOrNull() ?: MISSING)
                }
            }
        }
        val dataset = JSONObject().put("rowCount", data.rows.size).put("columnCount", columns.size)
            .put("fields", metadata).put("transforms", JSONArray())
            .put("removedRows", JSONArray()).put("addedRows", JSONArray()).put("weights", JSONObject.NULL)
        val entries = linkedMapOf(
            "meta" to ("Manifest-Version: 1.0\nData-Archive-Version: 1.0.2\n" +
                "jamovi-Archive-Version: 11.0\nCreated-By: pocketomv\n").toByteArray(),
            "metadata.json" to JSONObject().put("document", JSONObject().put("resultsLanguage", "en"))
                .put("dataSet", dataset).toString().toByteArray(),
            "xdata.json" to xdata.toString().toByteArray(),
            "data.bin" to cellBytes.array(),
        )
        if (columns.any { it.storage == Storage.TEXT && it.measure == ColumnMeasure.ID }) entries["strings.bin"] = strings.toByteArray()
        if (native != null) entries["02 ${native.name}/analysis"] = OmvWire.write(native, request.method.title, result, fictional)
        val analysisDigests = entries.filterKeys(analysisEntry::matches).mapValues { digest(it.value) }
        entries[APP_OPTIONS] = JSONObject().put("version", 1).put("columns", JSONArray(data.columns))
            .put("projectFingerprint", fingerprint(entries, analysisDigests)).put("request", SessionCodec.encodeRequest(request))
            .put("fictional", fictional).put("ran", result != null).toString().toByteArray()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                require(bytes.size <= entryLimit(name)) { "The saved jamovi project is too large." }
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        require(output.size() <= MAX_ARCHIVE) { "The saved jamovi project is too large." }
        return OmvExport(output.toByteArray(), native != null)
    }

    private fun readArchive(input: InputStream): Archive {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= MAX_ARCHIVE) { "Choose a jamovi project under 16 MB." }
            bytes.write(buffer, 0, count)
        }
        val entries = mutableMapOf<String, ByteArray>()
        val analyses = mutableListOf<Pair<String, OmvAnalysis>>()
        val analysisDigests = mutableMapOf<String, ByteArray>()
        val seen = mutableSetOf<String>()
        var unreadableAnalyses = false
        var expanded = 0
        var count = 0
        ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= 1_024) { "The jamovi project has too many archive entries." }
                val name = entry.name
                val isAnalysis = analysisEntry.matches(name)
                val limit = entryLimit(name)
                val output = if (limit != 0 && !entry.isDirectory) ByteArrayOutputStream() else null
                if (output != null) require(seen.add(name)) { "The jamovi project has duplicate archive entries." }
                while (true) {
                    val size = zip.read(buffer)
                    if (size < 0) break
                    expanded += size
                    require(expanded <= MAX_EXPANDED) { "The expanded jamovi project exceeds 64 MB." }
                    if (output != null) {
                        require(output.size() + size <= limit) { "A part of this jamovi project is too large." }
                        output.write(buffer, 0, size)
                    }
                }
                if (output == null) continue
                val content = output.toByteArray()
                if (isAnalysis) {
                    require(analysisDigests.size < 128) { "The jamovi project has too many analysis records." }
                    analysisDigests[name] = digest(content)
                    try {
                        OmvWire.read(content)?.let { analyses.add(name to it) }
                    } catch (_: Exception) {
                        unreadableAnalyses = true
                    }
                } else entries[name] = content
            }
        }
        return Archive(entries, analyses.sortedBy { it.first.substringBefore(' ').toIntOrNull() }.map { it.second },
            analysisDigests, unreadableAnalyses)
    }

    private fun entryLimit(name: String): Int = when (name) {
        "meta", LEGACY_MANIFEST -> 16 * 1024
        "metadata.json", "data.bin", APP_OPTIONS -> 2 * 1024 * 1024
        "xdata.json", "strings.bin" -> 8 * 1024 * 1024
        else -> if (analysisEntry.matches(name)) 8 * 1024 * 1024 else 0
    }

    private fun column(field: JSONObject, xdata: JSONObject): Column {
        val name = field.get("name") as? String
        require(!name.isNullOrBlank()) { "A jamovi column has no name." }
        val measureName = field.optString("measureType", "Nominal")
        val measure = when (measureName) {
            "Continuous" -> ColumnMeasure.CONTINUOUS
            "Nominal", "NominalText" -> ColumnMeasure.NOMINAL
            "Ordinal" -> ColumnMeasure.ORDINAL
            "ID" -> ColumnMeasure.ID
            else -> throw IllegalArgumentException("A jamovi column has an unsupported measurement type.")
        }
        val storage = when (field.optString("dataType")) {
            "Integer" -> Storage.INTEGER
            "Decimal" -> Storage.DECIMAL
            "Text" -> Storage.TEXT
            "" -> when {
                measureName == "NominalText" || field.optString("type") == "string" -> Storage.TEXT
                measure == ColumnMeasure.CONTINUOUS || field.optString("type") == "number" -> Storage.DECIMAL
                else -> Storage.INTEGER
            }
            else -> throw IllegalArgumentException("A jamovi column has an unsupported data type.")
        }
        val kind = field.optString("columnType", "Data")
        val labels = linkedMapOf<Int, String>()
        val distinctLabels = mutableSetOf<String>()
        val encodedLabels = xdata.optJSONObject(name)?.optJSONArray("labels")
        if (encodedLabels != null) {
            require(encodedLabels.length() <= 20_000) { "A jamovi column has too many category labels." }
            for (index in 0 until encodedLabels.length()) {
                val pair = encodedLabels.getJSONArray(index)
                val code = pair.get(0) as? Int
                val label = pair.get(1) as? String
                require(code != null && code != MISSING && !label.isNullOrBlank() && code !in labels &&
                    distinctLabels.add(label.trim())
                ) { "Column $name has blank or duplicate category labels." }
                labels[code] = label
            }
        }
        val missing = field.optJSONArray("missingValues")?.let {
            require(it.length() <= 100) { "Column $name has too many missing-value rules." }
            it.asStrings().map(MissingRule::parse)
        }.orEmpty()
        return Column(name, measure, storage, labels, missing,
            filter = kind == "Filter", active = field.optBoolean("active", true), calculated = kind != "Data")
    }

    private fun outputColumn(data: CourseData, index: Int, measure: ColumnMeasure, numericSort: Boolean): Column {
        val values = data.rows.map { it[index] }
        val name = data.columns[index]
        if (measure == ColumnMeasure.CONTINUOUS) {
            val present = values.filterNot(String::isMissingNumber)
            require(present.all { it.toDoubleOrNull()?.isFinite() == true }) { "Column $name contains invalid numeric values." }
            val whole = present.all { it.toIntOrNull()?.let { value -> value != MISSING } == true }
            return Column(name, measure, if (whole) Storage.INTEGER else Storage.DECIMAL)
        }
        if (measure == ColumnMeasure.ID) {
            require(values.all { it.toByteArray(Charsets.UTF_8).size < 512 }) {
                "Column $name contains an ID longer than the jamovi format can reopen."
            }
            return Column(name, measure, Storage.TEXT)
        }
        val labels = data.orderedCategories(index, values.filterNot(String::isBlank), numericSort)
        // Unused levels still carry the declared category order and labels.
        val stored = data.columnMetadata[index]?.levels.orEmpty().filterNot(String::isBlank)
        val ordered = (stored + labels).distinct()
        require(ordered.size <= 20_000) { "Column $name has too many category labels." }
        require(ordered.map(String::trim).distinct().size == ordered.size) { "Column $name contains ambiguous category labels." }
        return Column(name, measure, Storage.TEXT,
            ordered.mapIndexed { code, label -> code to label }.toMap())
    }

    private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun fingerprint(entries: Map<String, ByteArray>, analysisDigests: Map<String, ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (name in listOf("metadata.json", "xdata.json", "data.bin", "strings.bin")) {
            val value = entries[name] ?: continue
            digest.update(ByteBuffer.allocate(4).putInt(value.size).array())
            digest.update(value)
        }
        analysisDigests.toSortedMap().forEach { (name, checksum) ->
            digest.update(name.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(checksum)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun jsonObject(bytes: ByteArray): JSONObject {
        val text = utf8(bytes)
        var depth = 0
        var quoted = false
        var escaped = false
        for (character in text) {
            when {
                escaped -> escaped = false
                quoted && character == '\\' -> escaped = true
                character == '"' -> quoted = !quoted
                !quoted && (character == '{' || character == '[') -> {
                    require(++depth <= 24) { "The jamovi project metadata is too deeply nested." }
                }
                !quoted && (character == '}' || character == ']') -> depth--
            }
        }
        return JSONObject(text)
    }

    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()

    private fun JSONArray.asStrings(): List<String> = List(length()) { index ->
        (get(index) as? String) ?: throw IllegalArgumentException("Invalid text in jamovi project metadata.")
    }

    private fun InputStream.fill(target: ByteArray): Boolean {
        var offset = 0
        while (offset < target.size) {
            val count = read(target, offset, target.size - offset)
            if (count < 0) return false
            offset += count
        }
        return true
    }

    private data class Archive(
        val entries: Map<String, ByteArray>,
        val analyses: List<OmvAnalysis>,
        val analysisDigests: Map<String, ByteArray>,
        val unreadableAnalyses: Boolean,
    )
    private data class LocalOptions(val request: AnalysisRequest, val fictional: Boolean, val ran: Boolean)
    private enum class Storage(val title: String) { INTEGER("Integer"), DECIMAL("Decimal"), TEXT("Text") }

    private data class Column(
        val name: String,
        val measure: ColumnMeasure,
        val storage: Storage,
        val labels: Map<Int, String> = emptyMap(),
        val missing: List<MissingRule> = emptyList(),
        val filter: Boolean = false,
        val active: Boolean = true,
        val calculated: Boolean = false,
    ) {
        val width: Int get() = if (storage == Storage.DECIMAL) 8 else 4
        val categorical: Boolean get() = measure == ColumnMeasure.NOMINAL || measure == ColumnMeasure.ORDINAL

        fun read(buffer: ByteBuffer, strings: StringTable): String {
            if (storage == Storage.DECIMAL) {
                val value = buffer.double
                if (value.isNaN() || missing.any { it.matches(value.toString(), value) }) return ""
                require(value.isFinite()) { "Column $name contains an infinite value." }
                return value.toString()
            }
            val value = buffer.int
            if (value == MISSING) return ""
            val text = when {
                storage == Storage.TEXT && measure == ColumnMeasure.ID -> strings[value]
                categorical -> labels[value] ?: value.toString()
                else -> value.toString()
            }
            val numeric = if (storage == Storage.INTEGER) value.toDouble() else null
            return if (missing.any { it.matches(text, numeric) }) "" else text
        }

        fun metadata(id: Int): JSONObject = JSONObject().put("name", name).put("id", id)
            .put("columnType", "Data").put("dataType", storage.title)
            .put("measureType", when (measure) {
                ColumnMeasure.CONTINUOUS -> "Continuous"
                ColumnMeasure.NOMINAL -> "Nominal"
                ColumnMeasure.ORDINAL -> "Ordinal"
                ColumnMeasure.ID -> "ID"
            }).put("type", when {
                storage == Storage.DECIMAL -> "number"
                storage == Storage.TEXT && measure == ColumnMeasure.ID -> "string"
                else -> "integer"
            }).put("importName", name).put("trimLevels", false).put("missingValues", JSONArray())
    }

    private class StringTable(private val bytes: ByteArray?) {
        private val cache = mutableMapOf<Int, String>()

        operator fun get(offset: Int): String = cache.getOrPut(offset) {
            val source = requireNotNull(bytes) { "The jamovi text table is missing." }
            require(offset in source.indices && (offset == 0 || source[offset - 1] == 0.toByte())) {
                "The jamovi text table has an invalid offset."
            }
            var end = offset
            while (end < source.size && source[end] != 0.toByte()) end++
            require(end < source.size && end - offset <= MAX_EDITABLE_CHARS) { "The jamovi text table has an invalid value." }
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(source, offset, end - offset)).toString()
        }
    }

    private data class MissingRule(val comparison: String, val operand: String, val number: Double?) {
        fun matches(text: String, numeric: Double?): Boolean {
            val order = if (number == null) text.compareTo(operand) else {
                val value = numeric ?: return false
                if (value == number) 0 else value.compareTo(number)
            }
            return when (comparison) {
                "==" -> order == 0
                "!=" -> order != 0
                ">" -> order > 0
                "<" -> order < 0
                ">=" -> order >= 0
                else -> order <= 0
            }
        }

        companion object {
            private val syntax = Regex("""^(==|!=|>=|<=|>|<)\s*(.+)$""")

            fun parse(rule: String): MissingRule {
                val parts = syntax.matchEntire(rule.trim())?.groupValues
                val operand = parts?.get(2).orEmpty()
                val number = operand.toDoubleOrNull()?.takeIf(Double::isFinite)
                val quoted = operand.length >= 2 && operand.first() in "'\"" && operand.last() == operand.first() &&
                    operand.first() !in operand.substring(1, operand.lastIndex)
                require(parts != null && (number != null || quoted && parts[1] in setOf("==", "!="))) {
                    "This project uses an unsupported custom missing-value rule."
                }
                return MissingRule(parts[1], if (quoted) operand.substring(1, operand.lastIndex) else operand, number)
            }
        }
    }
}
