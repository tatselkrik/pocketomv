package io.github.tatselkrik.statis130lite

import org.apache.commons.statistics.distribution.FDistribution
import org.apache.commons.statistics.distribution.TDistribution
import org.apache.commons.statistics.inference.OneWayAnova
import kotlin.math.abs
import kotlin.math.sqrt

enum class SumsOfSquares(val label: String) {
    III("Type III · each term adjusted for all others"),
    II("Type II · respect the model hierarchy"),
    I("Type I · sequential, in displayed term order"),
}

enum class ModelHomogeneity(val label: String) {
    MODEL_RESIDUALS("Absolute model residuals"), CELL_MEDIAN("Cell-median Levene"),
}
enum class ModelQQBasis(val label: String) {
    STANDARDIZED("Standardized residuals · identity line"), RAW("Raw residuals · quartile line"),
}
enum class ModelComparisonFamily(val label: String) {
    PER_FACTOR("Each factor separately"), ALL_FACTORS("All displayed factors"),
}
enum class ModelAdjustment(val label: String) {
    TUKEY("Tukey"), HOLM("Holm"), BONFERRONI("Bonferroni"), NONE("Unadjusted"),
}

internal data class ModelTerm(val name: String, val factors: Set<Int>, val covariate: Int? = null,
    val columns: List<DoubleArray>)
internal data class LinearFit(val beta: DoubleArray, val inverse: Array<DoubleArray>, val fitted: DoubleArray,
    val residual: DoubleArray, val sse: Double, val df: Int, val hat: DoubleArray)
internal data class AnovaTermResult(val label: String, val ss: Double, val df: Int, val f: Double, val p: Double, val partialEta: Double)
internal data class MarginalMean(val factor: String, val level: String, val mean: Double, val se: Double, val lower: Double,
    val upper: Double, val contrast: DoubleArray)
internal data class ModelComparison(val factor: String, val first: String, val second: String, val difference: Double,
    val se: Double, val t: Double, val p: Double, val adjustedP: Double)
internal data class FactorialFit(val terms: List<AnovaTermResult>, val sse: Double, val df: Int, val f: Double,
    val p: Double, val r2: Double, val adjustedR2: Double, val means: List<MarginalMean>, val comparisons: List<ModelComparison>,
    val residuals: DoubleArray, val fitted: DoubleArray, val groups: List<Statistics.Group>, val n: Int, val excluded: Int,
    val checks: List<AssumptionCheck>, val covariateMeans: List<Pair<String, Double>>, val modelFormula: String,
    val standardizedResiduals: List<Double?>)

/** Full-rank fixed-effects least squares. Sum contrasts make Type III factor hypotheses explicit. */
internal object FactorialAnova {
    private const val MAX_PARAMETERS = 60

    private fun fit(columns: List<DoubleArray>, y: DoubleArray): LinearFit {
        require(columns.size <= MAX_PARAMETERS && y.size > columns.size) {
            "The model needs more complete observations than parameters, with at most $MAX_PARAMETERS parameters. Reduce levels, factors or covariates."
        }
        require(y.size.toLong() * columns.size * columns.size <= 40_000_000L) { "This model is too large for on-device fitting. Reduce factor levels, terms or observations." }
        val qr = try { MatrixMath.qr(columns) } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("The model is redundant or nearly redundant. Check empty factor cells, confounded factors and constant/duplicate covariates.")
        }
        val beta = qr.solve(y)
        val fitted = DoubleArray(y.size) { row -> MatrixMath.sum(DoubleArray(columns.size) { beta[it] * columns[it][row] }) }
        val residual = DoubleArray(y.size) { y[it] - fitted[it] }
        val hat = DoubleArray(y.size) { row -> MatrixMath.sum(DoubleArray(qr.q.size) { qr.q[it][row] * qr.q[it][row] }).coerceIn(0.0, 1.0) }
        return LinearFit(beta, qr.gramInverse(), fitted, residual, MatrixMath.dot(residual, residual), y.size - columns.size, hat)
    }

    private fun probability(f: Double, numerator: Int, denominator: Int): Double =
        if (f.isInfinite()) 0.0 else FDistribution.of(numerator.toDouble(), denominator.toDouble()).survivalProbability(f)

    private fun subsets(k: Int, interactions: Boolean): List<Set<Int>> = (1 until (1 shl k))
        .map { mask -> (0 until k).filter { mask and (1 shl it) != 0 }.toSet() }
        .filter { interactions || it.size == 1 }.sortedWith(compareBy<Set<Int>> { it.size }.thenBy { it.joinToString() })

    internal fun adjust(p: List<Double>, kind: FriedmanAdjustment): List<Double> = when (kind) {
        FriedmanAdjustment.NONE -> p
        FriedmanAdjustment.BONFERRONI -> p.map { (it * p.size).coerceAtMost(1.0) }
        FriedmanAdjustment.HOLM -> {
            val out = MutableList(p.size) { 0.0 }; var previous = 0.0
            p.indices.sortedBy { p[it] }.forEachIndexed { rank, index ->
                previous = maxOf(previous, (p[index] * (p.size - rank)).coerceAtMost(1.0)); out[index] = previous
            }; out
        }
    }

    fun compute(data: CourseData, request: AnalysisRequest): FactorialFit {
        require(!request.modelComparisons || request.modelAdjustment != ModelAdjustment.TUKEY || request.modelComparisonFamily == ModelComparisonFamily.PER_FACTOR) {
            "Tukey model comparisons use a separate family for each factor. Choose Each factor separately, or use another correction for a global family."
        }
        val ancova = request.method == AnalysisMethod.ANCOVA
        val factors = request.factors
        val covariates = if (ancova) request.covariates else emptyList()
        require(factors.size in (if (ancova) 1 else 2)..3) { "Select ${if (ancova) "1–3" else "2–3"} categorical factors." }
        require(!ancova || covariates.size in 1..6) { "ANCOVA needs 1–6 separate numeric covariates." }
        val selected = listOf(request.first) + factors + covariates
        require(selected.distinct().size == selected.size && selected.all { it in data.columns.indices }) {
            "The outcome, factors and covariates must use different valid columns."
        }
        val rows = data.rows.indices.filter { row -> !data.rows[row][request.first].isMissingNumber() &&
            covariates.none { data.rows[row][it].isMissingNumber() } && factors.none { data.rows[row][it].isBlank() } }
        require(rows.size >= 4) { "Add at least four complete observations in the selected columns." }
        val labels = factors.map { column -> data.rows.let { table -> rows.map { table[it][column].trim() }.distinct().sorted() } }
        require(labels.all { it.size in 2..6 }) { "Each factor needs 2–6 observed levels among complete cases." }
        val combinations = labels.fold(listOf(emptyList<Int>())) { all, levels -> all.flatMap { prefix -> levels.indices.map { prefix + it } } }
        require(combinations.size <= MAX_PARAMETERS) { "Use at most $MAX_PARAMETERS combinations of factor levels." }
        val codes = rows.map { row -> factors.indices.map { i -> labels[i].indexOf(data.rows[row][factors[i]].trim()) } }
        val outcome = MatrixMath.standardize(DoubleArray(rows.size) { Statistics.number(data, rows[it], request.first) })
        require(outcome.sd > 0.0) { "The outcome needs variation." }
        val cov = covariates.map { column -> MatrixMath.standardize(DoubleArray(rows.size) { Statistics.number(data, rows[it], column) }) }
        require(cov.all { it.sd > 0.0 }) { "Covariates need variation among complete cases." }
        fun factorVector(levels: List<Int>, subset: Set<Int>): List<Double> = subset.fold(listOf(1.0)) { current, f ->
            current.flatMap { value -> (0 until labels[f].lastIndex).map { j -> value * when (levels[f]) { j -> 1.0; labels[f].lastIndex -> -1.0; else -> 0.0 } } }
        }
        val factorSets = subsets(factors.size, request.factorInteractions)
        val factorTerms = factorSets.map { set ->
            val vectors = codes.map { factorVector(it, set) }
            ModelTerm(set.joinToString(" × ") { data.columns[factors[it]] }, set, columns =
                vectors.first().indices.map { j -> DoubleArray(rows.size) { vectors[it][j] } })
        }
        val covTerms = cov.indices.map { i -> ModelTerm(data.columns[covariates[i]], emptySet(), i, listOf(cov[i].z)) }
        // Displayed order is also the order used for sequential Type I sums of squares.
        val terms = factorTerms.filter { it.factors.size == 1 } + covTerms + factorTerms.filter { it.factors.size > 1 }
        val parameters = 1 + terms.sumOf { it.columns.size }
        require(rows.size.toLong() * parameters * parameters * (terms.size + 2) <= 150_000_000L) {
            "This model exceeds the on-device calculation budget. Reduce factor levels, interactions or observations."
        }
        val intercept = DoubleArray(rows.size) { 1.0 }
        fun design(selectedTerms: List<ModelTerm>) = listOf(intercept) + selectedTerms.flatMap { it.columns }
        val full = fit(design(terms), outcome.z)
        require(full.sse > 1e-20 * (rows.size - 1)) { "The model has no usable residual variation. Add independent observations or simplify the model." }
        val mse = full.sse / full.df
        val yScale = outcome.sd * outcome.maximum
        fun originalSS(value: Double): Double = (value * yScale) * yScale
        val results = terms.mapIndexed { index, term ->
            val base = when (request.sumsOfSquares) {
                SumsOfSquares.I -> terms.take(index)
                SumsOfSquares.III -> terms.filter { it !== term }
                SumsOfSquares.II -> terms.filter { candidate -> candidate !== term &&
                    !(term.factors.isNotEmpty() && candidate.factors.containsAll(term.factors) && candidate.factors.size > term.factors.size) }
            }
            val reduced = fit(design(base), outcome.z)
            val augmented = if (request.sumsOfSquares == SumsOfSquares.III) full else fit(design(base + term), outcome.z)
            val difference = reduced.sse - augmented.sse
            require(difference >= -1e-9 * (rows.size - 1)) { "The model is numerically unstable. Rescale covariates or simplify it." }
            val ss = difference.coerceAtLeast(0.0)
            val df = term.columns.size
            val f = ss / df / mse
            AnovaTermResult(term.name, originalSS(ss), df, f, probability(f, df, full.df), ss / (ss + full.sse))
        }
        val modelDf = full.beta.size - 1
        val total = MatrixMath.dot(outcome.z, outcome.z)
        val modelSS = (total - full.sse).coerceAtLeast(0.0)
        val f = modelSS / modelDf / mse
        val r2 = (1 - full.sse / total).coerceIn(0.0, 1.0)
        fun predictionVector(levels: List<Int>): DoubleArray = (listOf(1.0) + terms.flatMap { term ->
            if (term.covariate != null) listOf(0.0) else factorVector(levels, term.factors)
        }).toDoubleArray()
        val inverse = full.inverse
        fun variance(vector: DoubleArray): Double = MatrixMath.dot(vector, DoubleArray(vector.size) { MatrixMath.dot(inverse[it], vector) }).coerceAtLeast(0.0)
        val tDist = TDistribution.of(full.df.toDouble())
        val critical = tDist.inverseCumulativeProbability(0.975)
        val means = if (request.modelMarginalMeans || request.modelComparisons) factors.indices.flatMap { factor -> labels[factor].indices.map { level ->
            val cells = combinations.filter { it[factor] == level }.map(::predictionVector)
            val vector = DoubleArray(full.beta.size) { col -> cells.sumOf { it[col] } / cells.size }
            val mean = outcome.originalMean + yScale * MatrixMath.dot(vector, full.beta)
            val se = yScale * sqrt(mse * variance(vector))
            MarginalMean(data.columns[factors[factor]], labels[factor][level], mean, se, mean - critical * se, mean + critical * se, vector)
        } } else emptyList()
        val rawPairs = if (request.modelComparisons) means.groupBy { it.factor }.values.flatMap { group -> group.indices.flatMap { i ->
            (i + 1 until group.size).map { j ->
                val a = group[i]; val b = group[j]
                val differenceVector = DoubleArray(full.beta.size) { a.contrast[it] - b.contrast[it] }
                val se = yScale * sqrt(mse * variance(differenceVector))
                require(se > 0.0 && se.isFinite()) { "Marginal comparison precision exceeds the supported range." }
                val difference = yScale * MatrixMath.dot(differenceVector, full.beta)
                val statistic = difference / se
                val p = (2 * tDist.survivalProbability(abs(statistic))).coerceIn(0.0, 1.0)
                ModelComparison(a.factor, a.level, b.level, difference, se, statistic, p, p)
            }
        } } else emptyList()
        val adjusted = MutableList(rawPairs.size) { 0.0 }
        val families = if (request.modelComparisonFamily == ModelComparisonFamily.PER_FACTOR)
            rawPairs.indices.groupBy { rawPairs[it].factor }.values else listOf(rawPairs.indices.toList())
        for (indices in families) {
            if (indices.isEmpty()) continue
            val probabilities = if (request.modelAdjustment == ModelAdjustment.TUKEY) {
                val factor = rawPairs[indices.first()].factor
                val levelsInFactor = means.count { it.factor == factor }
                if (full.df < 2 && levelsInFactor > 2) List(indices.size) { Double.NaN }
                else {
                    val distribution = StudentizedRange(levelsInFactor, full.df.toDouble())
                    indices.map { distribution.survival(abs(rawPairs[it].t) * sqrt(2.0)) }
                }
            } else adjust(indices.map { rawPairs[it].p }, FriedmanAdjustment.valueOf(request.modelAdjustment.name))
            indices.forEachIndexed { j, index -> adjusted[index] = probabilities[j] }
        }
        val pairs = rawPairs.mapIndexed { i, pair -> pair.copy(adjustedP = adjusted[i]) }
        val residuals = DoubleArray(rows.size) { yScale * full.residual[it] }
        val predictions = DoubleArray(rows.size) { outcome.originalMean + yScale * full.fitted[it] }
        require((results.flatMap { listOf(it.ss, it.f, it.partialEta) } + means.flatMap { listOf(it.mean, it.se, it.lower, it.upper) } +
            listOf(originalSS(full.sse)) + residuals.toList() + predictions.toList()).all(Double::isFinite)) {
            "Model estimates exceed the supported range. Rescale the outcome or covariates."
        }
        val cells = codes.indices.groupBy { codes[it] }
        val groups = cells.map { (levels, indices) -> Statistics.Group(
            factors.indices.joinToString(" · ") { "${data.columns[factors[it]]}=${labels[it][levels[it]]}" },
            DoubleArray(indices.size) { Statistics.number(data, rows[indices[it]], request.first) }) }
        val checks = buildList {
            if (request.checkNormality) add(MeanDiagnostics.normality("Model residuals", full.residual))
            if (request.checkVariances) add(if (request.modelHomogeneity == ModelHomogeneity.MODEL_RESIDUALS)
                residualHomogeneity(full.residual, cells.values.toList()) else MeanDiagnostics.levene(groups))
            if (ancova && request.checkSlopes) {
                val extra = cov.indices.flatMap { c -> factorTerms.map { term -> term.copy(
                    name = "${term.name} × ${data.columns[covariates[c]]}", covariate = c,
                    columns = term.columns.map { column -> DoubleArray(rows.size) { column[it] * cov[c].z[it] } }) } }
                add(try {
                    val expanded = fit(design(terms + extra), outcome.z)
                    require(expanded.sse > 1e-20 * (rows.size - 1)) { "The separate-slope model has no usable residual variation." }
                    val df = extra.sumOf { it.columns.size }
                    val statistic = ((full.sse - expanded.sse).coerceAtLeast(0.0) / df) / (expanded.sse / expanded.df)
                    AssumptionCheck("Homogeneity of regression slopes", "Factor terms × covariates jointly", rows.size,
                        TestResult("F", statistic, probability(statistic, df, expanded.df)), listOf(df.toDouble(), expanded.df.toDouble()))
                } catch (e: IllegalArgumentException) {
                    AssumptionCheck("Homogeneity of regression slopes", "Factor terms × covariates jointly", rows.size, null, note = e.message)
                })
            }
        }
        val residualSD = sqrt(full.sse / full.df)
        val standardized = full.residual.indices.map { i ->
            if (full.hat[i] >= 1.0 - 10 * Math.ulp(1.0)) null
            else (full.residual[i] / (residualSD * sqrt(1 - full.hat[i]))).takeIf(Double::isFinite)
        }
        return FactorialFit(results, originalSS(full.sse), full.df, f, probability(f, modelDf, full.df), r2,
            1 - (1 - r2) * (rows.size - 1) / full.df, means, pairs, residuals, predictions, groups, rows.size,
            data.rows.size - rows.size, checks, cov.indices.map { data.columns[covariates[it]] to cov[it].originalMean },
            "${data.columns[request.first]} ~ ${terms.joinToString(" + ") { it.name }}", standardized)
    }

    fun run(data: CourseData, request: AnalysisRequest): AnalysisResult {
        val fit = compute(data, request)
        val tables = buildList {
            add(AnalysisTable("${request.method.title} · ${request.sumsOfSquares.name}", listOf("Term", "Sum of squares", "df", "Mean square", "F", "p", "Partial η²"),
                fit.terms.map { listOf(it.label, formatStatistic(it.ss), it.df.toString(), formatStatistic(it.ss / it.df), formatStatistic(it.f), formatProbability(it.p), formatStatistic(it.partialEta)) } +
                    listOf(listOf("Residuals", formatStatistic(fit.sse), fit.df.toString(), formatStatistic(fit.sse / fit.df), "—", "—", "—"))))
            add(AnalysisTable("Model fit", listOf("Measure", "Value"), listOf(listOf("Complete observations", fit.n.toString()),
                listOf("R²", formatStatistic(fit.r2)), listOf("Adjusted R²", formatStatistic(fit.adjustedR2)))))
            if (fit.covariateMeans.isNotEmpty()) add(AnalysisTable("Covariate reference values", listOf("Covariate", "Complete-case mean"),
                fit.covariateMeans.map { listOf(it.first, formatStatistic(it.second)) }))
            if (fit.means.isNotEmpty()) add(AnalysisTable("Estimated marginal means", listOf("Factor", "Level", "Mean", "SE", "95% CI"),
                fit.means.map { listOf(it.factor, it.level, formatStatistic(it.mean), formatStatistic(it.se), "[${formatStatistic(it.lower)}, ${formatStatistic(it.upper)}]") },
                listOf("Equal weights across the other factors' levels; covariates held at their complete-case means. Intervals are pointwise 95% t intervals."), rowLabelColumns = 2))
            if (fit.comparisons.isNotEmpty()) add(AnalysisTable("Marginal mean comparisons · ${request.modelAdjustment.label}",
                listOf("Factor", "First", "Second", "Difference", "SE", "t", "df", "Raw p", "Adjusted p"), fit.comparisons.map {
                    listOf(it.factor, it.first, it.second, formatStatistic(it.difference), formatStatistic(it.se), formatStatistic(it.t), fit.df.toString(), formatProbability(it.p), formatProbability(it.adjustedP))
                }, listOf("First level minus second. " + if (request.modelComparisonFamily == ModelComparisonFamily.PER_FACTOR)
                    "Adjustment applies separately to each factor's displayed comparisons, following jamovi."
                    else "Adjustment applies to one family containing every displayed comparison across all selected factors.") +
                    if (fit.comparisons.any { !it.adjustedP.isFinite() }) listOf("Multilevel Tukey p-values need at least two residual degrees of freedom under the reference convention. Raw t probabilities and the main model remain available.") else emptyList(), rowLabelColumns = 3))
        }
        return AnalysisResult(request.method, "Fixed-effects least squares · ${request.sumsOfSquares.label}", TestResult("F", fit.f, fit.p),
            fit.n.toLong(), fit.excluded, listOf(fit.terms.sumOf { it.df }.toDouble(), fit.df.toDouble()), tables = tables, assumptionChecks = fit.checks,
            plots = if (request.showQQPlot || request.showScatterplot) AnalysisPlots(
                qq = if (request.showQQPlot) listOf(if (request.modelQQBasis == ModelQQBasis.STANDARDIZED)
                    PlotCalculator.qq("Standardized model residuals", fit.standardizedResiduals.filterNotNull().toDoubleArray(), identityReference = true)
                        .copy(detail = "Internally standardized residuals e / (residual SD × sqrt(1 − leverage)). ${fit.standardizedResiduals.count { it == null }} undefined values are omitted from this plot only. Shapiro–Wilk and residuals versus fitted retain raw model residuals.")
                    else PlotCalculator.qq("Raw model residuals", fit.residuals)) else emptyList(),
                scatter = if (request.showScatterplot) ScatterPlot("Fitted ${data.columns[request.first]}", "Residual", fit.fitted.indices.map { PlotPoint(fit.fitted[it], fit.residuals[it]) }, null) else null) else null,
            notes = buildList {
                add("Model: ${fit.modelFormula}. Uses the same complete observations for every term, diagnostic and marginal comparison. Factor values are categorical, including numeric labels.")
                add("Factors use sum-to-zero contrasts. Type III tests each term conditional on every other term; Type II omits higher-order relatives when testing a lower-order term; Type I is sequential in the displayed term order. Term sums need not add to the total for Types II/III.")
                add("The headline F tests the full model against an intercept-only model. Individual term tests and partial eta-squared are in the ANOVA table. Interpret interactions before marginal main effects.")
                add("Assumes independent observations, a correctly specified mean model and common residual variance. Residual and variance diagnostics do not establish these assumptions or causality.")
                if (request.checkVariances) add(if (request.modelHomogeneity == ModelHomogeneity.MODEL_RESIDUALS)
                    "Homogeneity uses an ANOVA of absolute fitted-model residuals across observed factor cells, following jamovi. It retains the fitted covariate effects and does not center the residuals again within cells."
                    else "Homogeneity uses median-centered Levene on the raw outcome within observed factor cells (Brown–Forsythe), without covariate adjustment.")
                if (request.method == AnalysisMethod.ANCOVA) add("Covariates have common linear slopes in the fitted model. The optional slope check jointly adds each included factor term × each covariate; it does not change the main fitted model. A nonsignificant check does not prove equal slopes.")
                add("Supports ${if (request.method == AnalysisMethod.ANCOVA) "1–3 factors and 1–6 covariates" else "2–3 factors"}, 2–6 levels per factor, up to 60 level combinations and 60 parameters. Rank-deficient or numerically unstable models are rejected; no levels or predictors are silently dropped.")
            })
    }

    private fun residualHomogeneity(residuals: DoubleArray, cells: List<List<Int>>): AssumptionCheck {
        val name = "Levene (model residuals)"
        val df = listOf(cells.size - 1.0, residuals.size - cells.size.toDouble())
        return try {
            require(cells.size >= 2 && residuals.size > cells.size) { "Homogeneity needs multiple factor cells and positive within-cell degrees of freedom." }
            val absolute = cells.map { indices -> DoubleArray(indices.size) { abs(residuals[indices[it]]) } }
            val within = absolute.sumOf { values ->
                val mean = MatrixMath.sum(values) / values.size
                MatrixMath.sum(DoubleArray(values.size) { (values[it] - mean) * (values[it] - mean) })
            }
            val energy = absolute.sumOf { MatrixMath.dot(it, it) }
            require(within.isFinite() && within > energy * 1e-28 && within > 0.0) {
                "Homogeneity has no reliably measurable within-cell variation in absolute model residuals. The main model is retained."
            }
            val test = OneWayAnova.withDefaults().test(absolute)
            require(test.statistic.isFinite() && test.pValue.isFinite()) { "The model-residual homogeneity check is undefined for these factor cells." }
            AssumptionCheck(name, "Observed factor cells", residuals.size, TestResult("F", test.statistic, test.pValue), df,
                "ANOVA of absolute fitted-model residuals across cells; includes the fitted covariate effects.")
        } catch (error: IllegalArgumentException) {
            AssumptionCheck(name, "Observed factor cells", residuals.size, null, df, error.message)
        }
    }
}
