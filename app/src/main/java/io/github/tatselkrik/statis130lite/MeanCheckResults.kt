package io.github.tatselkrik.statis130lite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

@Composable
internal fun MeanCheckOptions(request: AnalysisRequest, enabled: Boolean, onChange: (AnalysisRequest) -> Unit) {
    if (!request.method.supportsNormalityCheck || request.method == AnalysisMethod.MULTIPLE_REGRESSION) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Assumption checks (optional)", style = MaterialTheme.typography.titleSmall)
        CheckOption("Shapiro–Wilk normality", request.checkNormality, enabled) {
            onChange(request.copy(checkNormality = it))
        }
        Text(when (request.method) {
            AnalysisMethod.PAIRED_T -> "Checks the differences within complete pairs."
            AnalysisMethod.ONE_SAMPLE_T -> "Checks the selected sample's distribution."
            else -> "Checks the outcome separately within each group."
        }, style = MaterialTheme.typography.bodySmall)
        if (request.method.supportsVarianceCheck) {
            CheckOption("Levene variance check", request.checkVariances, enabled) {
                onChange(request.copy(checkVariances = it))
            }
            Text("Compares group variances using deviations from each group's median.", style = MaterialTheme.typography.bodySmall)
        }
        if (request.method == AnalysisMethod.ANOVA) {
            Text("ANOVA follow-up (optional)", style = MaterialTheme.typography.titleSmall)
            val method = if (request.equalVariance) "Tukey–Kramer" else "Games–Howell"
            CheckOption("$method pairwise comparisons", request.pairwiseComparisons, enabled) {
                onChange(request.copy(pairwiseComparisons = it))
            }
            Text(if (request.equalVariance) "Uses the equal-variance ANOVA estimate, with adjusted p-values and 95% intervals."
                else "Uses separate group variances and Welch degrees of freedom, with adjusted p-values and 95% intervals.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal fun LazyListScope.meanCheckItems(result: AnalysisResult) {
    val normality = result.assumptionChecks.filter { it.name == "Shapiro–Wilk" }
    if (normality.isNotEmpty()) item {
        TitledTable("Normality · Shapiro–Wilk", listOf("Sample", "N", "W", "p"), normality.map {
            listOf(it.sample, it.size.toString(), it.test?.statistic?.let(::formatStatistic) ?: "—",
                it.test?.pValue?.let(::formatProbability) ?: "—")
        })
        normality.filter { it.note != null }.forEach {
            Text("${it.sample}: ${it.note}", style = MaterialTheme.typography.bodySmall)
        }
    }
    result.assumptionChecks.filter { it.name == "Levene (median)" }.forEach { check -> item {
        if (check.test != null) {
            TitledTable("Homogeneity · Levene (median)", listOf("F", "df1", "df2", "p"), listOf(listOf(
                formatStatistic(check.test.statistic), formatStatistic(check.degreesOfFreedom[0]),
                formatStatistic(check.degreesOfFreedom[1]), formatProbability(check.test.pValue),
            )))
        } else {
            Text("Homogeneity · Levene (median)", style = MaterialTheme.typography.titleMedium)
            Text(check.note ?: "This check is unavailable for the selected observations.")
        }
    } }
    result.followUp?.let { followUp -> item {
        if (followUp.unavailable == null) {
            TitledTable("Pairwise comparisons · ${followUp.method}", pairwiseHeaders, followUp.comparisons.take(100).map { it.displayRow() })
            if (followUp.comparisons.size > 100) {
                Text("Showing the first 100 of ${followUp.comparisons.size} comparisons. Save the report for every pair.",
                    style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Text("Pairwise comparisons · ${followUp.method}", style = MaterialTheme.typography.titleMedium)
            Text(followUp.unavailable)
        }
        Text(followUp.note, style = MaterialTheme.typography.bodySmall)
    } }
}

internal val pairwiseHeaders = listOf("Comparison", "Mean diff.", "SE", "t", "df", "p (adjusted)", "95% interval")

internal fun PairwiseComparison.displayRow(): List<String> = listOf(
    "$first − $second", formatStatistic(meanDifference), formatStatistic(standardError),
    formatStatistic(t), formatStatistic(degreesOfFreedom), formatProbability(adjustedP),
    "[${formatStatistic(lower95)}, ${formatStatistic(upper95)}]",
)
