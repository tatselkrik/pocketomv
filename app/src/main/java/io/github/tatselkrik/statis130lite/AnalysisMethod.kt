package io.github.tatselkrik.statis130lite

import org.apache.commons.statistics.inference.AlternativeHypothesis

enum class AnalysisMethod(
    val title: String,
    val purpose: String,
    val firstLabel: String,
    val secondLabel: String? = null,
    val directional: Boolean = false,
) {
    ONE_SAMPLE_T("One-sample t test", "Compare a sample mean with a specified value.", "Sample values", directional = true),
    INDEPENDENT_T("Independent-samples t test", "Compare means from two independent groups.", "Outcome", "Group", true),
    PAIRED_T("Paired-samples t test", "Compare means from two measurements on the same cases.", "First measurement", "Second measurement", true),
    ANOVA("One-way ANOVA", "Compare means across independent groups.", "Outcome", "Group"),
    MANN_WHITNEY("Mann–Whitney U", "Compare ranks from two independent groups.", "Outcome", "Group", true),
    WILCOXON("Wilcoxon signed-rank", "Compare ranked differences within matched pairs.", "First measurement", "Second measurement", true),
    KRUSKAL_WALLIS("Kruskal–Wallis H", "Compare ranks across independent groups.", "Outcome", "Group"),
    CHI_GOODNESS("Chi-square goodness of fit", "Compare observed category counts with expected proportions.", "Category"),
    CHI_INDEPENDENCE("Chi-square independence", "Test for an association between two categorical variables.", "Row category", "Column category"),
    PEARSON("Pearson correlation", "Measure the strength and direction of a linear association.", "First variable", "Second variable", true),
    SPEARMAN("Spearman correlation", "Measure a monotonic association using average ranks for ties.", "First variable", "Second variable", true),
    REGRESSION("Simple linear regression", "Predict an outcome from one numeric variable.", "Predictor", "Outcome"),
    ;

    val usesCounts: Boolean get() = this == CHI_GOODNESS || this == CHI_INDEPENDENCE
    val usesGroups: Boolean get() = this in groupedMethods
    val usesVarianceChoice: Boolean get() = this == INDEPENDENT_T || this == ANOVA

    companion object {
        private val groupedMethods = setOf(INDEPENDENT_T, ANOVA, MANN_WHITNEY, KRUSKAL_WALLIS)
    }
}

enum class Tail(val label: String, val hypothesis: AlternativeHypothesis) {
    TWO_SIDED("Two-sided (≠)", AlternativeHypothesis.TWO_SIDED),
    GREATER("Greater than (>)", AlternativeHypothesis.GREATER_THAN),
    LESS("Less than (<)", AlternativeHypothesis.LESS_THAN),
}

data class AnalysisRequest(
    val method: AnalysisMethod = AnalysisMethod.ONE_SAMPLE_T,
    val first: Int = 0,
    val second: Int = 1,
    val count: Int? = null,
    val reference: String = "0",
    val tail: Tail = Tail.TWO_SIDED,
    val equalVariance: Boolean = false,
    val expectedWeights: String = "",
) {
    companion object {
        fun forData(method: AnalysisMethod, data: CourseData?): AnalysisRequest {
            if (data == null) return AnalysisRequest(method = method, equalVariance = method == AnalysisMethod.ANOVA)

            val columns = data.columns.indices.toList()
            val candidates = columns.filterNot { data.columns[it].lowercase().trim() in idNames }
                .ifEmpty { columns }
            val numeric = data.numericColumns().filter { it in candidates }
            val likelyCount = if (method.usesCounts) {
                numeric.firstOrNull { data.columns[it].lowercase() in countNames }
            } else null

            val first = when {
                method.usesCounts -> candidates.firstOrNull { it != likelyCount }
                method.usesGroups -> numeric.lastOrNull { it != likelyCount }
                else -> numeric.firstOrNull { it != likelyCount }
            } ?: candidates.first()
            val secondOptions = candidates.filter { it != first && it != likelyCount }
            val second = when {
                method.usesGroups -> secondOptions.minByOrNull { index ->
                    data.rows.asSequence().map { it[index] }.filter(String::isNotBlank).distinct().count()
                        .let { if (it < 2) Int.MAX_VALUE else it }
                }
                method.usesCounts -> secondOptions.firstOrNull()
                else -> secondOptions.firstOrNull { it in numeric }
            } ?: columns.firstOrNull { it != first } ?: first
            return AnalysisRequest(
                method = method,
                first = first,
                second = second,
                count = if (method.usesCounts && likelyCount != first &&
                    (method.secondLabel == null || likelyCount != second)
                ) likelyCount else null,
                equalVariance = method == AnalysisMethod.ANOVA,
            )
        }

        private val idNames = setOf("id", "ss", "subject", "subject id", "participant id")
        private val countNames = setOf("count", "counts", "freq", "frequency", "nofviolations")
    }
}
