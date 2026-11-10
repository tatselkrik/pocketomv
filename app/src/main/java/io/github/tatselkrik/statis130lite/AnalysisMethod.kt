package io.github.tatselkrik.statis130lite

import org.apache.commons.statistics.inference.AlternativeHypothesis

enum class AnalysisMethod(
    val title: String,
    val purpose: String,
    val firstLabel: String,
    val secondLabel: String? = null,
    val directional: Boolean = false,
) {
    DESCRIPTIVES("Descriptives", "Summarize selected variables, optionally split by a group.", "Variable"),
    ONE_SAMPLE_T("One-sample t test", "Compare a sample mean with a specified value.", "Sample values", directional = true),
    INDEPENDENT_T("Independent-samples t test", "Compare means from two independent groups.", "Outcome", "Group", true),
    PAIRED_T("Paired-samples t test", "Compare means from two measurements on the same cases.", "First measurement", "Second measurement", true),
    ANOVA("One-way ANOVA", "Compare means across independent groups.", "Outcome", "Group"),
    MANN_WHITNEY("Mann–Whitney U", "Compare ranks from two independent groups.", "Outcome", "Group", true),
    WILCOXON("Wilcoxon signed-rank", "Compare ranked differences within matched pairs.", "First measurement", "Second measurement", true),
    KRUSKAL_WALLIS("Kruskal–Wallis H", "Compare ranks across independent groups.", "Outcome", "Group"),
    CHI_GOODNESS("Chi-square goodness of fit", "Compare observed category counts with expected proportions.", "Category"),
    CHI_INDEPENDENCE("Chi-square independence", "Test for an association between two categorical variables.", "Row category", "Column category"),
    FISHER_EXACT("Fisher's exact test (2 × 2)", "Test association in a two-by-two table using exact conditional probabilities.", "Row category", "Column category", true),
    PEARSON("Pearson correlation", "Measure the strength and direction of a linear association.", "First variable", "Second variable", true),
    SPEARMAN("Spearman correlation", "Measure a monotonic association using average ranks for ties.", "First variable", "Second variable", true),
    CORRELATION_MATRIX("Correlation matrix", "Examine correlations among several numeric variables in one table.", "Variables"),
    REGRESSION("Simple linear regression", "Predict an outcome from one numeric variable.", "Predictor", "Outcome"),
    RELIABILITY("Reliability analysis", "Estimate internal consistency and inspect item diagnostics.", "Items"),
    MULTIPLE_REGRESSION("Multiple linear regression", "Model an outcome using simultaneous numeric predictors.", "Outcome"),
    REPEATED_ANOVA("Repeated-measures ANOVA", "Compare conditions of one within-subject factor; one column per condition.", "Conditions"),
    FRIEDMAN("Friedman test", "Compare ranks across repeated measurements of the same participants.", "Conditions"),
    FACTORIAL_ANOVA("Factorial ANOVA", "Compare independent groups using two or three categorical factors and their interactions.", "Outcome"),
    ANCOVA("ANCOVA", "Compare categorical factors while adjusting for continuous covariates.", "Outcome"),
    EFA("Exploratory factor analysis", "Explore common factors among numeric items, with adequacy checks and factor retention.", "Variables"),
    ;

    val usesCounts: Boolean get() = this in setOf(CHI_GOODNESS, CHI_INDEPENDENCE, FISHER_EXACT)
    val usesGroups: Boolean get() = this in groupedMethods
    val usesVarianceChoice: Boolean get() = this == INDEPENDENT_T || this == ANOVA
    val isFactorialModel: Boolean get() = this == FACTORIAL_ANOVA || this == ANCOVA
    val usesResiduals: Boolean get() = this == MULTIPLE_REGRESSION || isFactorialModel
    val supportsNormalityCheck: Boolean get() = this in setOf(ONE_SAMPLE_T, INDEPENDENT_T, PAIRED_T, ANOVA, MULTIPLE_REGRESSION) || isFactorialModel
    val supportsVarianceCheck: Boolean get() = this == INDEPENDENT_T || this == ANOVA || isFactorialModel
    val supportsBoxplot: Boolean get() = this == DESCRIPTIVES || this == REPEATED_ANOVA || this == FRIEDMAN || (supportsNormalityCheck && !usesResiduals) ||
        this in setOf(MANN_WHITNEY, WILCOXON, KRUSKAL_WALLIS)
    val supportsQQPlot: Boolean get() = this == DESCRIPTIVES || supportsNormalityCheck
    val supportsScatterplot: Boolean get() = this in setOf(PEARSON, SPEARMAN, REGRESSION, MULTIPLE_REGRESSION) || isFactorialModel
    val usesItemSelection: Boolean get() = this in setOf(RELIABILITY, REPEATED_ANOVA, FRIEDMAN, EFA)
    val usesVariableSelection: Boolean get() = usesItemSelection || this == DESCRIPTIVES || this == CORRELATION_MATRIX

    companion object {
        private val groupedMethods = setOf(INDEPENDENT_T, ANOVA, MANN_WHITNEY, KRUSKAL_WALLIS)
    }
}

enum class Tail(val label: String, val hypothesis: AlternativeHypothesis) {
    TWO_SIDED("Two-sided (≠)", AlternativeHypothesis.TWO_SIDED),
    GREATER("Greater than (>)", AlternativeHypothesis.GREATER_THAN),
    LESS("Less than (<)", AlternativeHypothesis.LESS_THAN),
}

enum class RepeatedCorrection(val label: String) {
    GREENHOUSE_GEISSER("Greenhouse–Geisser"), HUYNH_FELDT("Huynh–Feldt"), NONE("Uncorrected"),
}

enum class CorrelationKind(val label: String) { PEARSON("Pearson r"), SPEARMAN("Spearman rho") }
enum class CorrelationMissing(val label: String) {
    PAIRWISE("Pairwise · available rows for each pair"),
    LISTWISE("Listwise · complete rows across all selected variables"),
}

enum class FriedmanAdjustment(val label: String) {
    HOLM("Holm"), BONFERRONI("Bonferroni"), NONE("Unadjusted"),
}

enum class NormalityBasis(val label: String) {
    RESIDUALS("Group-centered residuals"), GROUPS("Separate groups"), BOTH("Residuals and groups"),
}
enum class LeveneCenter(val label: String) { MEAN("Mean"), MEDIAN("Median (Brown–Forsythe)") }

enum class CountMissingPolicy(val label: String) {
    REJECT("Require complete counts"), OMIT("Omit rows with missing counts"),
}

data class AnalysisRequest(
    val method: AnalysisMethod = AnalysisMethod.ONE_SAMPLE_T,
    val first: Int = 0,
    val second: Int = 1,
    val count: Int? = null,
    val countMissingPolicy: CountMissingPolicy = CountMissingPolicy.REJECT,
    val reference: String = "0",
    val tail: Tail = Tail.TWO_SIDED,
    val equalVariance: Boolean = false,
    val expectedWeights: String = "",
    val descriptiveNumeric: Boolean = true,
    val weight: Int? = null,
    val population: Boolean = false,
    val percentiles: String = "25,50,75",
    val histogramBins: String = "",
    val checkNormality: Boolean = false,
    val independentNormality: NormalityBasis = NormalityBasis.RESIDUALS,
    val anovaNormality: NormalityBasis = NormalityBasis.RESIDUALS,
    val checkVariances: Boolean = false,
    val meanLeveneCenter: LeveneCenter = LeveneCenter.MEAN,
    val pairwiseComparisons: Boolean = false,
    val showBoxplot: Boolean = false,
    val showQQPlot: Boolean = false,
    val showScatterplot: Boolean = false,
    val qqSample: String = "",
    val items: List<Int> = emptyList(),
    val predictors: List<Int> = emptyList(),
    val showItemCorrelations: Boolean = false,
    val repeatedCorrection: RepeatedCorrection = RepeatedCorrection.GREENHOUSE_GEISSER,
    val repeatedComparisons: Boolean = false,
    val descriptiveVariables: List<Int>? = null,
    val splitBy: Int? = null,
    val showBarChart: Boolean = false,
    val correlationVariables: List<Int> = emptyList(),
    val correlationKind: CorrelationKind = CorrelationKind.PEARSON,
    val correlationMissing: CorrelationMissing = CorrelationMissing.PAIRWISE,
    val correlationConfidence: Boolean = false,
    val comparisonGroup: String = "",
    val eventCategory: String = "",
    val friedmanComparisons: Boolean = false,
    val friedmanAdjustment: FriedmanAdjustment = FriedmanAdjustment.NONE,
    val factors: List<Int> = emptyList(),
    val covariates: List<Int> = emptyList(),
    val factorInteractions: Boolean = true,
    val sumsOfSquares: SumsOfSquares = SumsOfSquares.III,
    val modelMarginalMeans: Boolean = true,
    val modelComparisons: Boolean = false,
    val modelAdjustment: ModelAdjustment = ModelAdjustment.TUKEY,
    val modelComparisonFamily: ModelComparisonFamily = ModelComparisonFamily.PER_FACTOR,
    val checkSlopes: Boolean = false,
    val modelHomogeneity: ModelHomogeneity = ModelHomogeneity.MODEL_RESIDUALS,
    val modelQQBasis: ModelQQBasis = ModelQQBasis.STANDARDIZED,
    val factorCountMethod: FactorCountMethod = FactorCountMethod.PARALLEL,
    val factorParallelMethod: FactorParallelMethod = FactorParallelMethod.COMMON_FACTOR,
    val factorParallelSeed: String = "20261109",
    val factorCount: String = "1",
    val factorEigenvalue: String = "0",
    val factorEigenvalueBasis: EigenvalueBasis = EigenvalueBasis.COMMON_FACTOR,
    val factorExtraction: FactorExtraction = FactorExtraction.MINRES,
    val minresConvergence: MinresConvergence = MinresConvergence.REFERENCE,
    val principalAxisConvergence: PrincipalAxisConvergence = PrincipalAxisConvergence.REFERENCE,
    val factorRotation: FactorRotation = FactorRotation.OBLIMIN,
    val varimaxConvergence: VarimaxConvergence = VarimaxConvergence.REFERENCE,
    val factorLoadingCutoff: String = "0.30",
    val factorSortLoadings: Boolean = false,
    val factorScreePlot: Boolean = false,
    val factorKmo: Boolean = true,
    val factorBartlett: Boolean = true,
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
                method.isFactorialModel -> numeric.firstOrNull { data.columns[it].lowercase().trim() in setOf("outcome", "score", "y") } ?: numeric.firstOrNull()
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
            val suggestedFactors = if (method.isFactorialModel) candidates.filter { column -> column != first &&
                data.rows.asSequence().map { it[column].trim() }.filter(String::isNotBlank).distinct().take(7).count() in 2..6
            }.sortedBy { if (it in numeric) 1 else 0 }.take(3) else emptyList()
            return AnalysisRequest(
                method = method,
                first = first,
                second = second,
                count = if (method.usesCounts && likelyCount != first &&
                    (method.secondLabel == null || likelyCount != second)
                ) likelyCount else null,
                equalVariance = method == AnalysisMethod.ANOVA,
                descriptiveNumeric = first in numeric,
                items = if (method.usesItemSelection) numeric.take(when (method) { AnalysisMethod.RELIABILITY -> 60; AnalysisMethod.EFA -> 20; else -> 12 }) else emptyList(),
                predictors = if (method == AnalysisMethod.MULTIPLE_REGRESSION) numeric.filter { it != first }.take(12) else emptyList(),
                correlationVariables = if (method == AnalysisMethod.CORRELATION_MATRIX) numeric.take(20) else emptyList(),
                factors = suggestedFactors,
                covariates = if (method == AnalysisMethod.ANCOVA) numeric.filter { it != first && it !in suggestedFactors }.take(6) else emptyList(),
            )
        }

        private val idNames = setOf("id", "ss", "subject", "subject id", "participant id")
        private val countNames = setOf("count", "counts", "freq", "frequency", "nofviolations")
    }
}
