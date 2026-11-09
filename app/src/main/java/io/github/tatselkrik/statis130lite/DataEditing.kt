package io.github.tatselkrik.statis130lite

import java.math.BigDecimal
import java.math.MathContext

enum class DataEditorView { TEXT, CELLS }
internal enum class RecodeMatch(val label: String) { EXACT("Exact text"), RANGE("Numeric ranges") }
internal enum class ItemScore(val label: String) { SUM("Sum"), MEAN("Mean") }
internal enum class MissingItems(val label: String) { COMPLETE("Require every item"), AVAILABLE("Use answered items") }
internal data class RecodeRule(val from: String = "", val to: String = "", val upper: String = "")

internal data class PreparedColumn(
    val name: String,
    val values: List<String>,
    val inputs: List<String>,
    val explanation: String,
    val details: String,
) {
    val missingCount: Int get() = values.count(String::isBlank)
    fun appendTo(data: CourseData): TableChange {
        require(values.size == data.rows.size) { "The table changed. Preview the calculation again." }
        val output = DataEditing.columnName(data, name)
        val next = CourseData(data.columns + output, data.rows.mapIndexed { row, cells -> cells + values[row] })
        DataEditing.validate(next)
        return TableChange(next, note = "Created $output: $explanation Values were calculated at creation; rescore after changing inputs.")
    }
}

internal sealed interface TableEdit {
    data class Cell(val row: Int, val column: Int, val value: String) : TableEdit
    data class AddRow(val after: Int) : TableEdit
    data class DeleteRow(val row: Int) : TableEdit
    data class AddColumn(val name: String) : TableEdit
    data class RenameColumn(val column: Int, val name: String) : TableEdit
    data class DeleteColumn(val column: Int) : TableEdit
    data class Derived(val prepared: PreparedColumn) : TableEdit
}

internal data class TableChange(val data: CourseData, val removedColumn: Int? = null, val note: String? = null)

/** Bounded, immutable table edits. A failed validation never changes the original table. */
internal object DataEditing {
    fun validate(data: CourseData) {
        require(data.columns.size in 1..60 && data.rows.size in 1..20_000 &&
            data.rows.size.toLong() * data.columns.size <= 250_000) { "Use at most 20,000 rows, 60 columns and 250,000 data cells." }
        require(data.rows.all { it.size == data.columns.size }) { "Every row must have the same number of cells." }
        require(data.columns.none(String::isBlank) && data.columns.distinct().size == data.columns.size) { "Use a different, nonblank name for every column." }
        require((data.columns.asSequence() + data.rows.asSequence().flatMap { it.asSequence() }).none { '\u0000' in it || '\uFFFD' in it }) {
            "Remove unsupported characters from the table."
        }
        require(data.asCsv().length <= 2_000_000) { "This edit would exceed the editable table limit of 2 MB." }
    }

    fun columnName(data: CourseData, proposed: String, replacing: Int? = null): String {
        val name = proposed.trim()
        require(name.isNotEmpty() && name.length <= 120 && '\n' !in name && '\r' !in name && '\t' !in name) {
            "Enter a column name of 1–120 characters on one line."
        }
        require(data.columns.indices.none { it != replacing && data.columns[it] == name }) { "That column name is already used." }
        return name
    }

    fun apply(data: CourseData, edit: TableEdit): TableChange {
        val result = when (edit) {
            is TableEdit.Cell -> {
                require(edit.row in data.rows.indices && edit.column in data.columns.indices) { "Select an existing cell." }
                TableChange(data.copy(rows = data.rows.mapIndexed { row, cells ->
                    if (row == edit.row) cells.mapIndexed { column, value -> if (column == edit.column) edit.value else value } else cells
                }))
            }
            is TableEdit.AddRow -> {
                require(edit.after in -1..data.rows.lastIndex) { "Select an existing row." }
                val rows = data.rows.toMutableList().apply { add(edit.after + 1, List(data.columns.size) { "" }) }
                TableChange(data.copy(rows = rows))
            }
            is TableEdit.DeleteRow -> {
                require(edit.row in data.rows.indices) { "Select an existing row." }
                require(data.rows.size > 1) { "Keep at least one row. Clear its cells instead." }
                TableChange(data.copy(rows = data.rows.filterIndexed { row, _ -> row != edit.row }))
            }
            is TableEdit.AddColumn -> {
                val name = columnName(data, edit.name)
                TableChange(CourseData(data.columns + name, data.rows.map { it + "" }))
            }
            is TableEdit.RenameColumn -> {
                require(edit.column in data.columns.indices) { "Select an existing column." }
                val name = columnName(data, edit.name, edit.column)
                TableChange(data.copy(columns = data.columns.mapIndexed { i, old -> if (i == edit.column) name else old }))
            }
            is TableEdit.DeleteColumn -> {
                require(edit.column in data.columns.indices) { "Select an existing column." }
                require(data.columns.size > 1) { "Keep at least one column." }
                TableChange(CourseData(data.columns.filterIndexed { i, _ -> i != edit.column },
                    data.rows.map { row -> row.filterIndexed { i, _ -> i != edit.column } }), removedColumn = edit.column)
            }
            is TableEdit.Derived -> edit.prepared.appendTo(data)
        }
        validate(result.data)
        return result
    }

    fun reverse(data: CourseData, source: Int, name: String, minimum: String, maximum: String): PreparedColumn {
        require(source in data.columns.indices) { "Choose a source column." }
        val output = columnName(data, name)
        val low = decimal(minimum, "Enter a finite scale minimum.")
        val high = decimal(maximum, "Enter a finite scale maximum.")
        require(low < high) { "The scale maximum must be greater than the minimum." }
        val sum = low.add(high)
        val values = data.rows.mapIndexed { row, cells ->
            val raw = cells[source]
            if (raw.isMissingNumber()) "" else {
                val value = decimal(raw, "Row ${row + 1}: ${short(data.columns[source])} must contain numbers or missing values.")
                require(value >= low && value <= high) { "Row ${row + 1}: value is outside the stated scale. Correct it or change the scale bounds." }
                number(sum.subtract(value))
            }
        }
        return prepared(data, output, values, listOf(source),
            "Reverse of ${short(data.columns[source])}, scale ${number(low)} to ${number(high)} (minimum + maximum − value).",
            "Missing answers stay missing. Values outside the scale are rejected.")
    }

    fun recode(data: CourseData, source: Int, name: String, match: RecodeMatch,
        rules: List<RecodeRule>, keepUnmatched: Boolean): PreparedColumn {
        require(source in data.columns.indices) { "Choose a source column." }
        val output = columnName(data, name)
        require(rules.size in 1..50) { "Add 1–50 recode rules." }
        val ranges = if (match == RecodeMatch.RANGE) rules.mapIndexed { index, rule ->
            val low = rule.from.takeIf(String::isNotBlank)?.let { decimal(it, "Rule ${index + 1}: enter a finite lower bound.") }
            val high = rule.upper.takeIf(String::isNotBlank)?.let { decimal(it, "Rule ${index + 1}: enter a finite upper bound.") }
            require(low != null || high != null) { "Rule ${index + 1}: enter at least one range bound." }
            require(low == null || high == null || low <= high) { "Rule ${index + 1}: the upper bound is below the lower bound." }
            low to high
        } else {
            require(rules.none { it.from.isBlank() }) { "Enter a value for each exact-text rule. Blank cells remain missing." }
            require(rules.map { it.from }.distinct().size == rules.size) { "Use each exact source value only once." }
            emptyList()
        }
        var matched = 0
        var unmatched = 0
        val values = data.rows.mapIndexed { row, cells ->
            val raw = cells[source]
            val missing = if (match == RecodeMatch.RANGE) raw.isMissingNumber() else raw.isBlank()
            if (missing) "" else {
                val value = if (match == RecodeMatch.RANGE) decimal(raw, "Row ${row + 1}: numeric range recoding requires numbers or missing values.") else null
                val index = rules.indices.firstOrNull { i ->
                    if (match == RecodeMatch.EXACT) raw == rules[i].from
                    else (ranges[i].first == null || value!! >= ranges[i].first!!) &&
                        (ranges[i].second == null || value!! <= ranges[i].second!!)
                }
                if (index == null) { unmatched++; if (keepUnmatched) raw else "" }
                else { matched++; rules[index].to }
            }
        }
        val descriptions = rules.map { rule ->
            val from = if (match == RecodeMatch.EXACT) "‘${short(rule.from)}’" else "[${short(rule.from).ifBlank { "−∞" }}, ${short(rule.upper).ifBlank { "∞" }}]"
            "$from → ${if (rule.to.isBlank()) "missing" else "‘${short(rule.to)}’"}"
        }.joinToString("; ")
        return prepared(data, output, values, listOf(source),
            "Recode of ${short(data.columns[source])}, ${match.label.lowercase()}: $descriptions. Unmatched values ${if (keepUnmatched) "kept" else "set missing"}.",
            "$matched matched · $unmatched unmatched. First matching rule wins; numeric range bounds are inclusive.")
    }

    fun score(data: CourseData, sources: List<Int>, name: String, mode: ItemScore,
        missing: MissingItems, minimumAnswered: Int): PreparedColumn {
        val columns = sources.distinct().sorted()
        require(columns.isNotEmpty() && columns.all { it in data.columns.indices } && columns.size == sources.size) { "Select one or more different item columns." }
        val output = columnName(data, name)
        require(missing == MissingItems.COMPLETE || minimumAnswered in 1..columns.size) { "Minimum answered items must be between 1 and ${columns.size}." }
        val required = if (missing == MissingItems.COMPLETE) columns.size else minimumAnswered
        val values = data.rows.mapIndexed { row, cells ->
            val numbers = columns.mapNotNull { column -> cells[column].takeUnless(String::isMissingNumber)?.let {
                decimal(it, "Row ${row + 1}: ${short(data.columns[column])} must contain numbers or missing values.")
            } }
            if (numbers.size < required) "" else {
                val sum = numbers.fold(BigDecimal.ZERO, BigDecimal::add)
                number(if (mode == ItemScore.MEAN) sum.divide(BigDecimal.valueOf(numbers.size.toLong()), MathContext.DECIMAL128) else sum)
            }
        }
        return prepared(data, output, values, columns,
            "${mode.label} of ${columns.joinToString { short(data.columns[it]) }}; " +
                if (missing == MissingItems.COMPLETE) "every selected item required." else "at least $required answered items required.",
            if (mode == ItemScore.SUM && missing == MissingItems.AVAILABLE) "Partial sums include answered items only; they are not prorated."
            else if (mode == ItemScore.MEAN) "Means divide by the number of answered items. Rows below the required count stay missing."
            else "Rows with any missing selected answer stay missing.")
    }

    private fun prepared(data: CourseData, name: String, values: List<String>, sources: List<Int>, explanation: String, details: String): PreparedColumn {
        val inputs = data.rows.map { row -> sources.joinToString(" · ") { short(row[it]).ifBlank { "(missing)" } } }
        return PreparedColumn(name, values, inputs, explanation, details).also { it.appendTo(data) }
    }

    private fun decimal(raw: String, message: String): BigDecimal {
        require(raw.length <= 1024) { message }
        val value = raw.trim().toBigDecimalOrNull()
        require(value != null) { message }
        val normalized = value.stripTrailingZeros()
        val double = normalized.toDouble()
        require(double.isFinite() && (double != 0.0 || normalized.signum() == 0)) { "$message Rescale numbers outside the supported range." }
        return normalized
    }

    private fun number(value: BigDecimal): String {
        val normalized = value.stripTrailingZeros()
        val double = normalized.toDouble()
        require(double.isFinite() && (double != 0.0 || normalized.signum() == 0)) { "A score is outside the supported numeric range. Rescale its inputs." }
        return if (normalized.scale() in -6..30) normalized.toPlainString() else normalized.toString()
    }

    private fun short(value: String): String = if (value.codePointCount(0, value.length) <= 48) value
        else value.substring(0, value.offsetByCodePoints(0, 48)) + "…"
}

internal fun AnalysisScreenState.withTableChange(change: TableChange): AnalysisScreenState {
    require(data != null && !hasUnappliedChanges) { "Use data to apply your text edits before editing cells or scores." }
    val table = change.data
    val removed = change.removedColumn
    val nextRequest = if (removed == null) request else {
        fun remap(index: Int): Int? = if (index == removed) null else if (index > removed) index - 1 else index
        val first = remap(request.first)
        val second = remap(request.second)
        if ((!request.method.usesVariableSelection && first == null) || (request.method.secondLabel != null && second == null)) AnalysisRequest.forData(request.method, table)
        else request.copy(first = first ?: 0, second = second ?: first ?: 0, count = request.count?.let(::remap), weight = request.weight?.let(::remap),
            items = request.items.mapNotNull(::remap), predictors = request.predictors.mapNotNull(::remap),
            factors = request.factors.mapNotNull(::remap), covariates = request.covariates.mapNotNull(::remap),
            descriptiveVariables = request.descriptiveVariables?.mapNotNull(::remap)
                ?: if (request.method == AnalysisMethod.DESCRIPTIVES && first == null) emptyList() else null,
            splitBy = request.splitBy?.let(::remap), correlationVariables = request.correlationVariables.mapNotNull(::remap))
    }
    val text = table.asCsv()
    return copy(data = table, draft = text, appliedDraft = text, firstRowHasNames = true, appliedNames = true,
        request = nextRequest, result = null, error = null, busy = null, page = AppPage.DATA, dataEditor = DataEditorView.CELLS,
        preparationNotes = (preparationNotes + listOfNotNull(change.note)).takeLast(60))
}
