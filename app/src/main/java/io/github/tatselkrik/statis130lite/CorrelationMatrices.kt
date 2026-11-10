package io.github.tatselkrik.statis130lite

import org.apache.commons.statistics.distribution.NormalDistribution
import kotlin.math.abs
import kotlin.math.ln1p
import kotlin.math.sqrt
import kotlin.math.tanh

data class CorrelationCell(
    val n: Int,
    val coefficient: Double?,
    val pValue: Double?,
    val lower95: Double? = null,
    val upper95: Double? = null,
    val note: String? = null,
)

data class CorrelationMatrixResult(
    val variables: List<String>,
    val kind: CorrelationKind,
    val missing: CorrelationMissing,
    val cells: List<List<CorrelationCell>>,
)

internal object CorrelationMatrices {
    private val z95 = NormalDistribution.of(0.0, 1.0).inverseSurvivalProbability(0.025)

    fun run(data: CourseData, request: AnalysisRequest): AnalysisResult {
        val selected = request.correlationVariables
        require(selected.size in 2..20 && selected.distinct().size == selected.size && selected.all { it in data.columns.indices }) {
            "Select 2–20 different numeric variables for the correlation matrix."
        }
        // Validate every non-missing selected value; other columns never affect inclusion.
        val columns = selected.map { column -> DoubleArray(data.rows.size) { row ->
            if (data.rows[row][column].isMissingNumber()) Double.NaN else Statistics.number(data, row, column)
        } }
        val complete = data.rows.indices.filter { row -> columns.all { it[row].isFinite() } }
        val listwise = request.correlationMissing == CorrelationMissing.LISTWISE
        val baseRows = if (listwise) complete else data.rows.indices.toList()
        val cells = Array(selected.size) { arrayOfNulls<CorrelationCell>(selected.size) }
        for (i in selected.indices) for (j in i until selected.size) {
            val rows = baseRows.filter { columns[i][it].isFinite() && columns[j][it].isFinite() }
            val x = DoubleArray(rows.size) { columns[i][rows[it]] }
            val y = DoubleArray(rows.size) { columns[j][rows[it]] }
            val cell = if (i == j) {
                when {
                    x.size < 2 -> CorrelationCell(x.size, null, null, note = "At least two observations are needed.")
                    x.all { it == x[0] } -> CorrelationCell(x.size, null, null, note = "This variable is constant.")
                    else -> CorrelationCell(x.size, 1.0, null)
                }
            } else calculate(x, y, request.correlationKind, request.correlationConfidence)
            cells[i][j] = cell
            cells[j][i] = cell
        }
        val matrix = CorrelationMatrixResult(selected.map { data.columns[it] }, request.correlationKind, request.correlationMissing,
            cells.map { row -> row.map { requireNotNull(it) } })
        val included = if (listwise) complete.size else data.rows.indices.count { row -> columns.any { it[row].isFinite() } }
        val notes = buildList {
            add(if (listwise) "Listwise deletion uses the same rows with non-missing values in every selected variable for all pairs."
                else "Pairwise deletion uses available rows separately for each pair. Included rows have at least one selected value; each cell's N is the relevant sample size. Pairwise matrices need not be positive semidefinite.")
            add("P-values are two-sided and unadjusted for multiple correlations. A correlation does not establish causation. Self-correlations have no p-value or confidence interval.")
            add(if (request.correlationKind == CorrelationKind.SPEARMAN)
                "Spearman correlations use average ranks, recalculated within each pair's included rows. P-values use exact permutations for untied N ≤ 9, AS 89 for untied N = 10–1,290, and a t approximation for ties or larger samples. Spearman confidence intervals are not provided."
                else "Pearson p-values use a t test with N − 2 degrees of freedom under the usual Pearson assumptions.")
            if (request.correlationConfidence && request.correlationKind == CorrelationKind.PEARSON)
                add("Pearson 95% confidence intervals use Fisher's z transformation and require N ≥ 4; their approximation assumes independent observations from a bivariate normal population. At a computed correlation of ±1 the interval collapses to that endpoint.")
            add("A coefficient needs at least two complete observations and variation in both variables; ${if (request.correlationKind == CorrelationKind.SPEARMAN) "Spearman inference also supports two pairs" else "Pearson inference needs at least three pairs"}. Undefined cells are shown as — and explained in pair details.")
        }
        return AnalysisResult(AnalysisMethod.CORRELATION_MATRIX, "${request.correlationKind.label} correlation matrix", test = null,
            includedCases = included.toLong(), excludedRows = data.rows.size - included, correlations = matrix,
            tables = tables(matrix, request.correlationConfidence && request.correlationKind == CorrelationKind.PEARSON), notes = notes)
    }

    private fun calculate(x: DoubleArray, y: DoubleArray, kind: CorrelationKind, confidence: Boolean): CorrelationCell {
        val n = x.size
        if (n < 2) return CorrelationCell(n, null, null, note = "At least two complete observations are needed.")
        if (x.all { it == x[0] } || y.all { it == y[0] })
            return CorrelationCell(n, null, null, note = "A variable is constant in these complete observations.")
        val r = Statistics.correlationCoefficient(x, y, kind == CorrelationKind.SPEARMAN)
        val p = if (kind == CorrelationKind.SPEARMAN) SpearmanProbability.calculate(x, y, r, Tail.TWO_SIDED).p else if (n < 3) null else {
            val t = if (abs(r) == 1.0) Double.POSITIVE_INFINITY else r * sqrt((n - 2.0) / ((1.0 - r) * (1.0 + r)))
            Statistics.tProbability(t, n - 2.0, Tail.TWO_SIDED)
        }
        val interval = if (confidence && kind == CorrelationKind.PEARSON && n >= 4) {
            if (abs(r) == 1.0) r to r else {
                val z = (ln1p(r) - ln1p(-r)) / 2
                val margin = z95 / sqrt(n - 3.0)
                tanh(z - margin) to tanh(z + margin)
            }
        } else null
        val note = when {
            n < 3 && kind == CorrelationKind.PEARSON -> "P-value needs at least three complete observations."
            confidence && kind == CorrelationKind.PEARSON && n < 4 -> "Fisher z interval needs at least four complete observations."
            else -> null
        }
        return CorrelationCell(n, r, p, interval?.first, interval?.second, note)
    }

    private fun tables(matrix: CorrelationMatrixResult, confidence: Boolean): List<AnalysisTable> {
        fun table(title: String, format: (CorrelationCell) -> String) = AnalysisTable(title, listOf("Variable") + matrix.variables,
            matrix.variables.indices.map { i -> listOf(matrix.variables[i]) + matrix.cells[i].map(format) })
        return buildList {
            add(table("Correlation coefficients · ${matrix.kind.label}") { it.coefficient?.let(::formatStatistic) ?: "—" })
            add(table("P-values · two-sided, unadjusted") { it.pValue?.let(::formatProbability) ?: "—" })
            add(table("Sample sizes · N") { it.n.toString() })
            val headers = listOf("First variable", "Second variable", matrix.kind.label, "N", "p") +
                (if (confidence) listOf("95% CI") else emptyList()) + listOf("Note")
            val rows = buildList {
                for (i in matrix.variables.indices) for (j in i + 1 until matrix.variables.size) {
                    val cell = matrix.cells[i][j]
                    add(listOf(matrix.variables[i], matrix.variables[j], cell.coefficient?.let(::formatStatistic) ?: "—",
                        cell.n.toString(), cell.pValue?.let(::formatProbability) ?: "—") +
                        (if (confidence) listOf(if (cell.lower95 != null && cell.upper95 != null)
                            "[${formatStatistic(cell.lower95)}, ${formatStatistic(cell.upper95)}]" else "—") else emptyList()) +
                        listOf(cell.note ?: ""))
                }
            }
            add(AnalysisTable("Correlation pair details", headers, rows, rowLabelColumns = 2))
        }
    }
}
