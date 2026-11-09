package io.github.tatselkrik.statis130lite

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.DefaultHandler2

internal data class ExcelSheet(val id: String, val name: String, val part: String, val hidden: Boolean)
internal data class ExcelTable(val data: CourseData, val formulaCount: Int)

/** Reads stored XLSX values. It never evaluates formulas or follows external relationships. */
internal class ExcelWorkbook private constructor(
    private val parts: Map<String, ByteArray>,
    val sheets: List<ExcelSheet>,
    private val sharedStringsPart: String?,
) {
    private val sharedStrings by lazy { sharedStringsPart?.let { readStrings(part(it)) } ?: emptyList() }

    fun readSheet(id: String, firstRowHasNames: Boolean): ExcelTable {
        val sheet = sheets.singleOrNull { it.id == id }
            ?: throw IllegalArgumentException("Choose a worksheet from this workbook.")
        val records = mutableListOf<List<String>>()
        val row = sortedMapOf<Int, String>()
        val seenColumns = mutableSetOf<Int>()
        val value = StringBuilder()
        val inline = StringBuilder()
        var lastRow = 0
        var rowNumber: Int? = null
        var column = 0
        var nextColumn = 0
        var type = ""
        var cellName = ""
        var hasValue = false
        var hasFormula = false
        var formulaCount = 0
        var characters = 0
        var width = 0
        readXml(part(sheet.part), "worksheet", start = { path, attrs ->
            when (path.joinToString("/")) {
                "worksheet/sheetData/row" -> {
                    row.clear()
                    seenColumns.clear()
                    nextColumn = 0
                    rowNumber = attrs.getValue("r")?.let {
                        it.toIntOrNull().also { index ->
                            require(index != null && index in 1..1_048_576 && index > lastRow) { "The worksheet has an invalid row number." }
                        }
                    }
                }
                "worksheet/sheetData/row/c" -> {
                    val reference = attrs.getValue("r")
                    column = if (reference == null) nextColumn else {
                        val match = CELL_REFERENCE.matchEntire(reference.uppercase())
                            ?: throw IllegalArgumentException("The worksheet has an invalid cell address.")
                        val index = match.groupValues[2].toIntOrNull()
                        require(index != null && index in 1..1_048_576) { "The worksheet has an invalid cell address." }
                        if (rowNumber == null) rowNumber = index
                        require(rowNumber == index) { "The worksheet has an inconsistent cell address." }
                        match.groupValues[1].fold(0) { result, letter -> result * 26 + (letter - 'A' + 1) } - 1
                    }
                    require(column in 0..16_383 && seenColumns.add(column)) { "The worksheet has an invalid or repeated cell address." }
                    nextColumn = column + 1
                    cellName = reference ?: "row ${rowNumber ?: lastRow + 1}, column ${column + 1}"
                    type = attrs.getValue("t") ?: "n"
                    value.clear()
                    inline.clear()
                    hasValue = false
                    hasFormula = false
                }
                "worksheet/sheetData/row/c/v" -> {
                    require(!hasValue) { "Cell $cellName has more than one stored value." }
                    hasValue = true
                }
                "worksheet/sheetData/row/c/f" -> hasFormula = true
            }
        }, text = { path, text ->
            if (path == listOf("worksheet", "sheetData", "row", "c", "v")) value.append(text)
            if (type == "inlineStr" && path.take(5) == listOf("worksheet", "sheetData", "row", "c", "is") &&
                path.last() == "t" && "rPh" !in path
            ) inline.append(text)
        }, end = { path ->
            when (path.joinToString("/")) {
                "worksheet/sheetData/row/c" -> {
                    if (hasFormula) {
                        require(hasValue && (value.isNotBlank() || type == "str")) {
                            "Formula in $cellName has no saved result. Recalculate and save the workbook in Excel, then import it again."
                        }
                        formulaCount++
                    }
                    val raw = value.toString()
                    val content = when (type) {
                        "n" -> raw.trim().also {
                            require(it.isBlank() || it.toDoubleOrNull()?.isFinite() == true) { "Cell $cellName contains an invalid number." }
                        }
                        "s" -> if (!hasValue || raw.isBlank()) "" else {
                            val index = raw.trim().toIntOrNull()
                            require(index != null && index in sharedStrings.indices) { "Cell $cellName references missing text." }
                            sharedStrings[index]
                        }
                        "inlineStr" -> decodeExcelText(inline.toString())
                        "str", "d" -> decodeExcelText(raw)
                        "b" -> when (raw.trim()) {
                            "" -> ""
                            "0" -> "FALSE"
                            "1" -> "TRUE"
                            else -> throw IllegalArgumentException("Cell $cellName contains an invalid Boolean value.")
                        }
                        "e" -> throw IllegalArgumentException("Cell $cellName contains an Excel error ($raw). Correct it before importing.")
                        else -> throw IllegalArgumentException("Cell $cellName has an unsupported value type.")
                    }.trim()
                    require('\u0000' !in content && '\uFFFD' !in content) { "Cell $cellName contains unsupported text." }
                    if (content.isNotBlank()) {
                        require(column < 60) { "Use a worksheet with at most 60 columns of data." }
                        characters += content.length
                        require(characters <= 2_000_000) { "The worksheet contains too much text. Import a smaller table." }
                        row[column] = content
                    }
                }
                "worksheet/sheetData/row" -> {
                    val index = rowNumber ?: lastRow + 1
                    require(index > lastRow) { "The worksheet has repeated or unordered rows." }
                    lastRow = index
                    if (row.isNotEmpty()) {
                        require(records.size < 20_001) { "Use at most 20,000 data rows." }
                        width = maxOf(width, row.lastKey() + 1)
                        require((records.size + 1) * width <= 250_000) { "Use a table with at most 250,000 cells." }
                        records.add(List(row.lastKey() + 1) { row[it] ?: "" })
                    }
                }
            }
        })
        return ExcelTable(tableFromRecords(records, firstRowHasNames), formulaCount)
    }

    private fun part(name: String): ByteArray = parts[name]
        ?: throw IllegalArgumentException("A required worksheet or workbook part is missing. Choose a valid .xlsx file.")

    companion object {
        fun read(input: InputStream): ExcelWorkbook {
            val bytes = readLimited(input, 8_000_000, "Choose an Excel workbook smaller than 8 MB.")
            require(bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                "Choose a valid, unencrypted Excel .xlsx workbook. Older .xls files are not supported."
            }
            val parts = linkedMapOf<String, ByteArray>()
            var expanded = 0
            var count = 0
            try {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        require(++count <= 1_000) { "This workbook contains too many parts. Choose a smaller workbook." }
                        val name = entry.name
                        require(!name.startsWith('/') && '\\' !in name &&
                            name.split('/').none { it == ".." || it == "." }
                        ) { "This workbook contains an invalid part name." }
                        if (!entry.isDirectory) {
                            val data = readLimited(zip, minOf(8_000_000, 32_000_000 - expanded),
                                "This workbook expands to too much data. Choose a smaller workbook.")
                            expanded += data.size
                            if (name.endsWith(".xml", true) || name.endsWith(".rels", true)) {
                                require(parts.put(name, data) == null) { "This workbook has repeated parts." }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            } catch (error: java.io.IOException) {
                throw IllegalArgumentException("This workbook could not be read. Choose a valid, unencrypted .xlsx file.", error)
            }
            val root = relationships(parts["_rels/.rels"]
                ?: throw IllegalArgumentException("Choose an Excel .xlsx workbook, rather than a ZIP or another document."))
            val office = root.values.singleOrNull { it.kind == "officeDocument" && !it.external }
                ?: throw IllegalArgumentException("This file is not a supported Excel workbook.")
            val workbookPart = resolvePart("", office.target)
            val workbook = parts[workbookPart] ?: throw IllegalArgumentException("The Excel workbook is incomplete.")
            val directory = workbookPart.substringBeforeLast('/', "")
            val relName = (if (directory.isEmpty()) "" else "$directory/") + "_rels/${workbookPart.substringAfterLast('/')}.rels"
            val related = relationships(parts[relName] ?: throw IllegalArgumentException("The workbook has no worksheet links."))
            val sheets = mutableListOf<ExcelSheet>()
            val ids = mutableSetOf<String>()
            readXml(workbook, "workbook", start = { path, attrs ->
                if (path == listOf("workbook", "sheets", "sheet")) {
                    require(sheets.size < 100) { "Use a workbook with at most 100 worksheets." }
                    val id = attrs.getValue(OFFICE_REL, "id") ?: attrs.getValue(STRICT_REL, "id")
                    val name = attrs.getValue("name")
                    require(!id.isNullOrBlank() && !name.isNullOrBlank() && name.length <= 200 && ids.add(id)) {
                        "The workbook contains an invalid worksheet name or link."
                    }
                    val relation = related[id] ?: throw IllegalArgumentException("A worksheet link is missing.")
                    if (relation.kind == "worksheet") {
                        require(!relation.external) { "External worksheet links are not supported. Choose a workbook containing its data." }
                        val target = resolvePart(workbookPart, relation.target)
                        require(parts.containsKey(target)) { "A worksheet is missing from this workbook." }
                        sheets.add(ExcelSheet(id, name, target, attrs.getValue("state")?.let { it != "visible" } ?: false))
                    }
                }
            })
            require(sheets.isNotEmpty()) { "This workbook has no worksheets to import." }
            val shared = related.values.singleOrNull { it.kind == "sharedStrings" && !it.external }
                ?.let { resolvePart(workbookPart, it.target) }
            return ExcelWorkbook(parts, sheets, shared)
        }
    }
}

private data class ExcelRelationship(val kind: String, val target: String, val external: Boolean)
private const val OFFICE_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
private const val STRICT_REL = "http://purl.oclc.org/ooxml/officeDocument/relationships"
private val CELL_REFERENCE = Regex("([A-Z]{1,3})([1-9][0-9]{0,6})")
private val EXCEL_ESCAPE = Regex("_x([0-9a-fA-F]{4})_")

private fun decodeExcelText(value: String): String {
    val decoded = EXCEL_ESCAPE.replace(value) { it.groupValues[1].toInt(16).toChar().toString() }
    var index = 0
    while (index < decoded.length) {
        val character = decoded[index++]
        if (character.isHighSurrogate()) {
            require(index < decoded.length && decoded[index++].isLowSurrogate()) { "The workbook contains invalid Unicode text." }
        } else require(!character.isLowSurrogate()) { "The workbook contains invalid Unicode text." }
    }
    return decoded
}

private fun readLimited(input: InputStream, limit: Int, message: String): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { message }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun resolvePart(source: String, target: String): String {
    val uri = try { URI("/$source").resolve(URI(target)).normalize() } catch (error: java.net.URISyntaxException) {
        throw IllegalArgumentException("The workbook contains an invalid internal link.", error)
    }
    require(uri.scheme == null && uri.authority == null && uri.query == null && uri.fragment == null &&
        uri.path.startsWith('/') && uri.path.split('/').none { it == ".." || it == "." } && '\\' !in uri.path
    ) { "Only internal workbook data can be imported." }
    return uri.path.removePrefix("/")
}

private fun relationships(bytes: ByteArray): Map<String, ExcelRelationship> {
    val result = linkedMapOf<String, ExcelRelationship>()
    readXml(bytes, "Relationships", start = { path, attrs ->
        if (path == listOf("Relationships", "Relationship")) {
            val id = attrs.getValue("Id")
            val type = attrs.getValue("Type")
            val target = attrs.getValue("Target")
            require(!id.isNullOrBlank() && !type.isNullOrBlank() && !target.isNullOrBlank()) { "The workbook has an invalid part link." }
            require(result.put(id, ExcelRelationship(type.substringAfterLast('/'), target,
                attrs.getValue("TargetMode") == "External")) == null) { "The workbook has repeated part links." }
        }
    })
    return result
}

private fun readStrings(bytes: ByteArray): List<String> {
    val strings = mutableListOf<String>()
    val current = StringBuilder()
    var characters = 0
    readXml(bytes, "sst", start = { path, _ ->
        if (path == listOf("sst", "si")) current.clear()
    }, text = { path, value ->
        if (path.size >= 3 && path[1] == "si" && path.last() == "t" && "rPh" !in path) {
            characters += value.length
            require(characters <= 2_000_000) { "The workbook contains too much shared text. Choose a smaller workbook." }
            current.append(value)
        }
    }, end = { path ->
        if (path == listOf("sst", "si")) {
            require(strings.size < 250_000) { "The workbook contains too many text values." }
            strings.add(decodeExcelText(current.toString()))
        }
    })
    return strings
}

private fun readXml(
    bytes: ByteArray, root: String,
    start: (List<String>, Attributes) -> Unit = { _, _ -> },
    text: (List<String>, String) -> Unit = { _, _ -> },
    end: (List<String>) -> Unit = {},
) {
    val path = mutableListOf<String>()
    val handler = object : DefaultHandler2() {
        override fun startDTD(name: String?, publicId: String?, systemId: String?) {
            throw SAXException("Document type declarations are not allowed in Excel data.")
        }
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource {
            throw SAXException("External XML entities are not allowed.")
        }
        override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource {
            throw SAXException("External XML entities are not allowed.")
        }
        override fun startElement(uri: String?, localName: String, qName: String?, attributes: Attributes) {
            require(path.size < 64) { "This workbook contains XML that is too deeply nested." }
            if (path.isEmpty()) require(localName == root) { "A workbook part has an invalid format." }
            path.add(localName)
            start(path, attributes)
        }
        override fun characters(chars: CharArray, offset: Int, length: Int) {
            text(path, String(chars, offset, length))
        }
        override fun endElement(uri: String?, localName: String?, qName: String?) {
            end(path)
            path.removeAt(path.lastIndex)
        }
        override fun error(error: SAXParseException) { throw error }
        override fun fatalError(error: SAXParseException) { throw error }
    }
    try {
        val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true; isValidating = false }.newSAXParser().xmlReader
        reader.contentHandler = handler
        reader.errorHandler = handler
        reader.entityResolver = handler
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        reader.parse(InputSource(ByteArrayInputStream(bytes)))
    } catch (error: SAXException) {
        throw IllegalArgumentException("The workbook contains invalid or unsupported XML. Choose a valid .xlsx file.", error)
    }
}
