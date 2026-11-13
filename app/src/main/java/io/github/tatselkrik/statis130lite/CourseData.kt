package io.github.tatselkrik.statis130lite

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

enum class ColumnMeasure { CONTINUOUS, NOMINAL, ORDINAL, ID }

data class ColumnMetadata(val measure: ColumnMeasure, val levels: List<String> = emptyList())

data class CourseData(
    val columns: List<String>,
    val rows: List<List<String>>,
    val columnMetadata: Map<Int, ColumnMetadata> = emptyMap(),
) {
    fun asCsv(): String = (listOf(columns) + rows).joinToString("\r\n") { row ->
        row.joinToString(",") { value ->
            if (value.any { it in ",;\t\"\r\n" }) "\"${value.replace("\"", "\"\"")}\"" else value
        }
    }

    fun numericColumns(): List<Int> = columns.indices.filter { index ->
        val measure = columnMetadata[index]?.measure
        if (measure != null && measure != ColumnMeasure.CONTINUOUS) return@filter false
        val values = rows.map { it[index] }.filterNot(String::isMissingNumber)
        values.isNotEmpty() && values.all { it.toDoubleOrNull()?.isFinite() == true }
    }

    internal fun orderedCategories(column: Int, labels: Iterable<String>, numericSort: Boolean = true): List<String> {
        val remaining = labels.toMutableSet()
        val stored = columnMetadata[column]?.levels.orEmpty().map(String::trim).filter(remaining::remove)
        val extra = if (numericSort && remaining.all { it.toDoubleOrNull()?.isFinite() == true }) {
            remaining.sortedWith(compareBy<String> { it.toDouble() }.thenBy { it })
        } else remaining.sorted()
        return stored + extra
    }
}

internal fun String.isMissingNumber(): Boolean =
    isBlank() || equals("NA", ignoreCase = true) || equals("N/A", ignoreCase = true) ||
        equals("NaN", ignoreCase = true)

private const val MAX_INPUT_SIZE = 2_000_000
private const val MAX_COLUMNS = 60
private const val MAX_DATA_ROWS = 20_000
private const val MAX_CELLS = 250_000

fun readDelimited(input: InputStream, firstRowHasNames: Boolean): CourseData {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val size = input.read(buffer)
        if (size < 0) break
        require(output.size() + size <= MAX_INPUT_SIZE) { "Choose a CSV or TSV smaller than 2 MB." }
        output.write(buffer, 0, size)
    }
    val bytes = output.toByteArray()
    val utf16 = bytes.size >= 2 && (
        (bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) ||
            (bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte())
        )
    val charset = if (utf16) StandardCharsets.UTF_16 else StandardCharsets.UTF_8
    return parseDelimited(bytes.toString(charset), firstRowHasNames)
}

fun parseDelimited(text: String, firstRowHasNames: Boolean = true): CourseData {
    require(text.length <= MAX_INPUT_SIZE) { "Use data smaller than 2 MB." }
    require('\u0000' !in text && '\uFFFD' !in text) { "Choose a CSV or TSV encoded as UTF-8 or UTF-16." }
    val input = text.removePrefix("\uFEFF")
    val delimiter = detectDelimiter(input)
    val records = splitRecords(input, delimiter)
    return tableFromRecords(records, firstRowHasNames)
}

internal fun tableFromRecords(records: List<List<String>>, firstRowHasNames: Boolean): CourseData {
    require(records.size > if (firstRowHasNames) 1 else 0) { "Add at least one row of data." }
    val width = records.maxOf { it.size }
    require(width in 1..MAX_COLUMNS) { "Use at most $MAX_COLUMNS columns." }
    require(records.size * width <= MAX_CELLS) { "Use a table with at most 250,000 cells." }
    val names = if (firstRowHasNames) records.first() else emptyList()
    val used = mutableSetOf<String>()
    val columns = List(width) { index ->
        val base = names.getOrNull(index)?.ifBlank { null } ?: "Column ${index + 1}"
        var unique = base
        var suffix = 2
        while (!used.add(unique)) unique = "$base (${suffix++})"
        unique
    }
    val data = if (firstRowHasNames) records.drop(1) else records
    require(data.size <= MAX_DATA_ROWS) { "Use at most $MAX_DATA_ROWS data rows." }
    return CourseData(columns, data.map { row -> List(width) { row.getOrElse(it) { "" } } })
}

private fun detectDelimiter(input: String): Char {
    var inQuotes = false
    var hasValue = false
    var commas = 0
    var tabs = 0
    var semicolons = 0
    for (character in input) {
        if (character == '"') {
            inQuotes = !inQuotes
            hasValue = true
        }
        if (inQuotes) continue
        when (character) {
            ',' -> commas++
            '\t' -> tabs++
            ';' -> semicolons++
            '\n', '\r' -> if (hasValue || tabs + commas + semicolons > 0) break
            else -> if (!character.isWhitespace()) hasValue = true
        }
    }
    return when {
        tabs > 0 -> '\t'
        semicolons > commas -> ';'
        else -> ','
    }
}

private fun splitRecords(input: String, delimiter: Char): List<List<String>> {
    val records = mutableListOf<List<String>>()
    val row = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var afterQuote = false

    fun finishCell() {
        require(row.size < MAX_COLUMNS) { "Use at most $MAX_COLUMNS columns." }
        row.add(cell.toString().trim())
        cell.clear()
        afterQuote = false
    }

    fun finishRecord() {
        finishCell()
        if (row.any(String::isNotBlank)) {
            require(records.size <= MAX_DATA_ROWS) { "Use at most $MAX_DATA_ROWS data rows." }
            records.add(row.toList())
        }
        row.clear()
    }

    var position = 0
    while (position < input.length) {
        val character = input[position++]
        if (quoted) {
            if (character != '"') {
                cell.append(character)
            } else if (input.getOrNull(position) == '"') {
                cell.append('"')
                position++
            } else {
                quoted = false
                afterQuote = true
            }
            continue
        }
        when {
            character == delimiter -> finishCell()
            character == '\n' || character == '\r' -> {
                if (character == '\r' && input.getOrNull(position) == '\n') position++
                finishRecord()
            }
            afterQuote -> require(character.isWhitespace()) { "Check the quotes near row ${records.size + 1}." }
            character == '"' && cell.isEmpty() -> quoted = true
            else -> cell.append(character)
        }
    }
    require(!quoted) { "Close the quoted value near row ${records.size + 1}." }
    if (row.isNotEmpty() || cell.isNotEmpty() || afterQuote) finishRecord()
    return records
}
