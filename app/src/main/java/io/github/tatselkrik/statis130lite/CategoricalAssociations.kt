package io.github.tatselkrik.statis130lite

import org.apache.commons.statistics.distribution.NormalDistribution
import org.apache.commons.statistics.inference.FisherExactTest
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

data class AssociationValue(val label: String, val value: Double, val lower95: Double? = null,
    val upper95: Double? = null, val note: String = "")

data class AssociationSummary(val comparisonGroup: String, val referenceGroup: String,
    val event: String, val otherEvent: String, val values: List<AssociationValue>)

internal object CategoricalAssociations {
    private val z95 = NormalDistribution.of(0.0, 1.0).inverseSurvivalProbability(0.025)

    private data class Oriented(val a: Long, val b: Long, val c: Long, val d: Long,
        val comparison: String, val reference: String, val event: String, val other: String)

    private fun oriented(table: FrequencyTable, request: AnalysisRequest): Oriented {
        require(table.rowLabels.size == 2 && table.columnLabels.size == 2) { "Fisher's exact test and comparative measures require a 2 × 2 table with nonempty margins." }
        val group = request.comparisonGroup.ifEmpty { table.rowLabels.first() }
        val event = request.eventCategory.ifEmpty { table.columnLabels.first() }
        val row = table.rowLabels.indexOf(group)
        val col = table.columnLabels.indexOf(event)
        require(row >= 0 && col >= 0) { "Choose a comparison group and event category that are present in the current table." }
        return Oriented(table.observed[row][col], table.observed[row][1-col], table.observed[1-row][col],
            table.observed[1-row][1-col], group, table.rowLabels[1-row], event, table.columnLabels[1-col])
    }

    fun fisher(base: AnalysisResult, request: AnalysisRequest): AnalysisResult {
        val counts = oriented(requireNotNull(base.frequencies), request)
        require(base.includedCases <= Int.MAX_VALUE) { "The exact 2 × 2 test supports a total frequency up to 2,147,483,647. Use smaller counts or chi-square independence." }
        val feasibleTables = minOf(counts.a + counts.b, counts.c + counts.d, counts.a + counts.c, counts.b + counts.d) + 1
        require(feasibleTables <= 100_000) { "This exact test would evaluate too many feasible tables. On-device Fisher tests support at most 100,000 feasible tables under the fixed margins. Use chi-square independence when appropriate or a desktop exact-test implementation." }
        val table = arrayOf(intArrayOf(counts.a.toInt(), counts.b.toInt()), intArrayOf(counts.c.toInt(), counts.d.toInt()))
        val test = FisherExactTest.withDefaults().with(request.tail.hypothesis).test(table)
        return base.copy(calculation = "Fisher's exact conditional test · ${request.tail.label}",
            test = TestResult("Odds ratio (sample)", test.statistic, test.pValue), degreesOfFreedom = emptyList(),
            notes = listOf("Conditions on both row and column totals; two-sided p-values sum tables with probability no greater than the observed table. Directional alternatives test the selected comparison-group/event odds ratio against one.",
                "The displayed sample odds ratio is ad/bc; it is not a conditional maximum-likelihood estimate. The exact p-value is separate from the approximate comparative-measure intervals.",
                "Zero-only categories are omitted. Requires two nonempty row categories and two nonempty column categories. The on-device calculation allows up to 100,000 feasible tables under the fixed margins to bound runtime."))
    }

    fun attach(result: AnalysisResult, request: AnalysisRequest): AnalysisResult {
        val table = requireNotNull(result.frequencies)
        if (table.rowLabels.size != 2 || table.columnLabels.size != 2) return result.copy(notes = result.notes +
            "Cramér's V is reported for this table. Signed Phi, odds ratios and relative risks require two row and two column categories.")
        val counts = oriented(table, request)
        val a = counts.a.toDouble(); val b = counts.b.toDouble(); val c = counts.c.toDouble(); val d = counts.d.toDouble()
        val phi = ((a*d - b*c) / sqrt((a+b)*(c+d)*(a+c)*(b+d))).coerceIn(-1.0, 1.0)
        val odds = a*d/(b*c)
        val risk = (a/(a+b))/(c/(c+d))
        fun interval(value: Double, se: Double): Pair<Double, Double> =
            exp(ln(value) - z95*se) to exp(ln(value) + z95*se)
        val oddsInterval = if (a > 0 && b > 0 && c > 0 && d > 0)
            interval(odds, sqrt(1/a+1/b+1/c+1/d)) else null
        val riskInterval = if (a > 0 && c > 0)
            interval(risk, sqrt(b/(a*(a+b)) + d/(c*(c+d)))) else null
        val values = listOf(
            AssociationValue("Phi (signed)", phi),
            AssociationValue("Odds ratio (sample)", odds, oddsInterval?.first, oddsInterval?.second,
                if (oddsInterval == null) "Log-Wald interval unavailable with a zero cell; no continuity correction applied." else "Log-Wald 95% interval."),
            AssociationValue("Risk ratio (relative risk)", risk, riskInterval?.first, riskInterval?.second,
                if (riskInterval == null) "Log-Wald interval unavailable with zero events in a group; no continuity correction applied." else "Katz log-Wald 95% interval."))
        val orientation = "Comparison: ${counts.comparison} versus ${counts.reference}; event: ${counts.event} (other category: ${counts.other}). OR = ad/bc; RR = [a/(a+b)]/[c/(c+d)] in this orientation."
        val output = AnalysisTable("2 × 2 association measures", listOf("Measure", "Estimate", "95% CI", "Note"), values.map {
            listOf(it.label, formatStatistic(it.value), if (it.lower95 != null && it.upper95 != null)
                "[${formatStatistic(it.lower95)}, ${formatStatistic(it.upper95)}]" else "—", it.note)
        }, listOf(orientation, "Cramér's V is reported in Estimates. Phi changes sign when one category order is reversed. Confidence intervals are large-sample approximations and can be unreliable with sparse counts. Risk ratios require a sampling design that supports comparing group event probabilities; association alone does not establish causation."))
        return result.copy(associations = AssociationSummary(counts.comparison, counts.reference, counts.event, counts.other, values),
            tables = result.tables + output, notes = result.notes + orientation)
    }
}
