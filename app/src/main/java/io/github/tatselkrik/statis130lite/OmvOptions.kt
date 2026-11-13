package io.github.tatselkrik.statis130lite

import io.github.tatselkrik.statis130lite.AnalysisMethod.*

internal data class OmvImportedOptions(val requests: List<AnalysisRequest>, val limited: Boolean = false)

/** Maps the built-in analysis selections shared by pocketomv and jamovi. */
internal object OmvOptions {
    fun exportMeasures(data: CourseData, request: AnalysisRequest): List<ColumnMeasure> {
        val categories = when {
            request.method.usesGroups -> listOf(request.second)
            request.method.isFactorialModel -> request.factors
            request.method == DESCRIPTIVES -> listOfNotNull(request.splitBy) +
                if (!request.descriptiveNumeric) request.descriptiveVariables ?: listOf(request.first) else emptyList()
            request.method == CHI_GOODNESS -> listOf(request.first)
            request.method.usesCounts -> listOf(request.first, request.second)
            else -> emptyList()
        }
        val numeric = data.numericColumns().toSet()
        return data.columns.indices.map { index ->
            val stored = data.columnMetadata[index]?.measure
            when {
                index in categories -> stored.takeIf { it == ColumnMeasure.ORDINAL } ?: ColumnMeasure.NOMINAL
                stored != null -> stored
                index in numeric -> ColumnMeasure.CONTINUOUS
                else -> ColumnMeasure.NOMINAL
            }
        }
    }

    fun toJamovi(r: AnalysisRequest, data: CourseData, measures: List<ColumnMeasure>): OmvAnalysis? {
        fun column(index: Int): String = data.columns.getOrNull(index) ?: throw Unmapped()
        fun number(index: Int): String {
            if (measures.getOrNull(index) != ColumnMeasure.CONTINUOUS) throw Unmapped()
            return column(index)
        }
        fun numbers(indices: List<Int>, min: Int): List<String> {
            if (indices.size < min || indices.distinct().size != indices.size) throw Unmapped()
            return indices.map(::number)
        }
        fun optionalCount(): String? = r.count?.let { index ->
            if (data.rows.any { row -> row.getOrNull(index)?.isMissingNumber() != false }) throw Unmapped()
            number(index)
        }
        val directional = when (r.tail) {
            Tail.TWO_SIDED -> "different"
            Tail.GREATER -> "oneGreater"
            Tail.LESS -> "twoGreater"
        }
        val output = linkedMapOf<String, Any?>()
        fun names(vararg entries: Pair<String, Any?>) { output.putAll(entries) }
        fun tDetails() {
            names("norm" to r.checkNormality, "qq" to r.showQQPlot, "meanDiff" to true, "ci" to true,
                "effectSize" to true, "desc" to true)
        }
        fun modelOptions(factors: List<String>, covariates: List<String>) {
            val terms = modelTerms(factors, covariates, r.factorInteractions)
            names("dep" to number(r.first), "factors" to factors, "modelTerms" to terms,
                "ss" to sums.getValue(r.sumsOfSquares), "norm" to r.checkNormality,
                "qq" to r.showQQPlot, "homo" to r.checkVariances, "effectSize" to listOf("partEta"),
                "emmTables" to r.modelMarginalMeans, "emmPlots" to false)
            if (covariates.isNotEmpty()) names("covs" to covariates)
            if (r.modelMarginalMeans) names("emMeans" to factors.map(::listOf))
            if (r.modelComparisons && r.modelComparisonFamily == ModelComparisonFamily.PER_FACTOR) {
                names("postHoc" to factors.map(::listOf), "postHocCorr" to listOf(adjustments.getValue(r.modelAdjustment)))
            }
        }
        val name = try {
            when (r.method) {
                DESCRIPTIVES -> {
                    val selected = r.descriptiveVariables ?: listOf(r.first)
                    val variables = if (r.descriptiveNumeric) numbers(selected, 1) else selected.map(::column)
                    if (variables.isEmpty() || r.weight != null || r.population) throw Unmapped()
                    names("vars" to variables, "splitBy" to r.splitBy?.let { listOf(column(it)) },
                        "freq" to true, "hist" to variables.any { measures[data.columns.indexOf(it)] == ColumnMeasure.CONTINUOUS },
                        "box" to r.showBoxplot, "bar" to r.showBarChart, "qq" to r.showQQPlot,
                        "mode" to true, "variance" to true, "range" to true, "se" to true, "iqr" to true,
                        "skew" to true, "kurt" to true, "pc" to r.percentiles.isNotBlank(), "pcValues" to r.percentiles)
                    "descriptives"
                }
                ONE_SAMPLE_T -> {
                    names("vars" to listOf(number(r.first)), "students" to true,
                        "testValue" to (r.reference.trim().toDoubleOrNull()?.takeIf(Double::isFinite) ?: throw Unmapped()),
                        "hypothesis" to when (r.tail) {
                            Tail.TWO_SIDED -> "dt"
                            Tail.GREATER -> "gt"
                            Tail.LESS -> "lt"
                        })
                    tDetails()
                    "ttestOneS"
                }
                INDEPENDENT_T, MANN_WHITNEY -> {
                    val parametric = r.method == INDEPENDENT_T
                    names("vars" to listOf(number(r.first)), "group" to column(r.second),
                        "students" to (parametric && r.equalVariance), "welchs" to (parametric && !r.equalVariance),
                        "mann" to !parametric, "hypothesis" to directional, "eqv" to r.checkVariances)
                    if (parametric) tDetails()
                    "ttestIS"
                }
                PAIRED_T, WILCOXON -> {
                    val parametric = r.method == PAIRED_T
                    names("pairs" to listOf(mapOf("i1" to number(r.first), "i2" to number(r.second))),
                        "students" to parametric, "wilcoxon" to !parametric, "hypothesis" to directional)
                    if (parametric) tDetails()
                    "ttestPS"
                }
                ANOVA, KRUSKAL_WALLIS -> {
                    names("deps" to listOf(number(r.first)), "group" to column(r.second))
                    if (r.method == KRUSKAL_WALLIS) "anovaNP" else {
                        names("fishers" to r.equalVariance, "welchs" to !r.equalVariance,
                            "desc" to true, "norm" to r.checkNormality, "qq" to r.showQQPlot,
                            "eqv" to r.checkVariances, "phMethod" to if (!r.pairwiseComparisons) "none"
                                else if (r.equalVariance) "tukey" else "gamesHowell")
                        "anovaOneW"
                    }
                }
                CHI_GOODNESS -> {
                    val category = column(r.first)
                    val count = optionalCount()
                    val weights = r.expectedWeights.trim().takeIf(String::isNotEmpty)?.split(Regex("[,;\\s]+"))?.map {
                        it.toDoubleOrNull()?.takeIf { value -> value.isFinite() && value > 0.0 } ?: throw Unmapped()
                    }
                    if (weights != null && weights.size != data.orderedCategories(r.first,
                            data.rows.map { it[r.first].trim() }.filter(String::isNotEmpty)).size) throw Unmapped()
                    names("var" to category, "counts" to count, "expected" to true, "ratio" to weights)
                    "propTestN"
                }
                CHI_INDEPENDENCE, FISHER_EXACT -> {
                    val exact = r.method == FISHER_EXACT
                    val rows = column(r.first)
                    val cols = column(r.second)
                    val count = optionalCount()
                    val categories = runCatching { Statistics.contingencyCategoryOrder(data, r) }.getOrNull() ?: throw Unmapped()
                    val (groups, events) = categories
                    if (groups.size < 2 || events.size < 2 || exact && (groups.size != 2 || events.size != 2)) throw Unmapped()
                    val reversed = exact && ((r.comparisonGroup.isNotEmpty() && r.comparisonGroup != groups.firstOrNull()) !=
                        (r.eventCategory.isNotEmpty() && r.eventCategory != events.firstOrNull()))
                    val hypothesis = if (!reversed || r.tail == Tail.TWO_SIDED) directional
                        else if (r.tail == Tail.GREATER) "twoGreater" else "oneGreater"
                    names("rows" to rows, "cols" to cols, "counts" to count,
                        "chiSq" to !exact, "fisher" to exact, "phiCra" to true, "exp" to true, "hypothesis" to hypothesis)
                    "contTables"
                }
                PEARSON, SPEARMAN, CORRELATION_MATRIX -> {
                    val matrix = r.method == CORRELATION_MATRIX
                    val selected = if (matrix) r.correlationVariables else listOf(r.first, r.second)
                    val tail = if (matrix) Tail.TWO_SIDED else r.tail
                    if (matrix && r.correlationMissing == CorrelationMissing.LISTWISE && selected.size > 2 &&
                        selected.any { index -> data.rows.any { row -> row.getOrNull(index)?.isMissingNumber() != false } }) throw Unmapped()
                    val pearson = r.method == PEARSON || (matrix && r.correlationKind == CorrelationKind.PEARSON)
                    names("vars" to numbers(selected, 2), "pearson" to pearson, "spearman" to !pearson,
                        "n" to true, "ci" to (pearson && (!matrix || r.correlationConfidence)), "plots" to r.showScatterplot,
                        "hypothesis" to when (tail) { Tail.TWO_SIDED -> "corr"; Tail.GREATER -> "pos"; Tail.LESS -> "neg" })
                    "corrMatrix"
                }
                REGRESSION, MULTIPLE_REGRESSION -> {
                    val simple = r.method == REGRESSION
                    val covariates = numbers(if (simple) listOf(r.first) else r.predictors, 1)
                    names("dep" to number(if (simple) r.second else r.first), "covs" to covariates,
                        "blocks" to listOf(covariates.map(::listOf)), "ci" to true,
                        "stdEst" to !simple, "r2Adj" to !simple, "modelTest" to true, "collin" to !simple,
                        "norm" to r.checkNormality, "qqPlot" to r.showQQPlot)
                    "linReg"
                }
                RELIABILITY -> {
                    names("vars" to numbers(r.items, 2), "alphaScale" to true, "omegaScale" to true,
                        "alphaItems" to true, "itemRestCor" to true, "corPlot" to r.showItemCorrelations)
                    "reliability"
                }
                FRIEDMAN -> {
                    names("measures" to numbers(r.items, 3),
                        "pairs" to (r.friedmanComparisons && r.friedmanAdjustment == FriedmanAdjustment.NONE), "desc" to true)
                    "anovaRMNP"
                }
                REPEATED_ANOVA -> {
                    val items = numbers(r.items, 2)
                    val factor = "Condition"
                    names("rm" to listOf(mapOf("label" to factor, "levels" to items)),
                        "rmCells" to items.map { mapOf("measure" to it, "cell" to listOf(it)) },
                        "rmTerms" to listOf(listOf(factor)), "spherTests" to true,
                        "spherCorr" to listOf(corrections.getValue(r.repeatedCorrection)))
                    "anovaRM"
                }
                FACTORIAL_ANOVA, ANCOVA -> {
                    val factors = r.factors.map(::column)
                    val covariates = if (r.method == ANCOVA) numbers(r.covariates, 1) else emptyList()
                    if (factors.size < (if (r.method == ANCOVA) 1 else 2)) throw Unmapped()
                    modelOptions(factors, covariates)
                    if (r.method == ANCOVA) "ancova" else "anova"
                }
                EFA -> {
                    names("vars" to numbers(r.items, 3), "nFactorMethod" to factorCounts.getValue(r.factorCountMethod),
                        "extraction" to if (r.factorExtraction == FactorExtraction.MINRES) "minres" else "pa",
                        "rotation" to r.factorRotation.name.lowercase(), "sortLoadings" to r.factorSortLoadings,
                        "hideLoadings" to (r.factorLoadingCutoff.toDoubleOrNull()?.takeIf(Double::isFinite) ?: throw Unmapped()),
                        "screePlot" to r.factorScreePlot, "kmo" to r.factorKmo, "bartlett" to r.factorBartlett,
                        "eigen" to true, "factorCor" to true, "factorSummary" to true)
                    when (r.factorCountMethod) {
                        FactorCountMethod.FIXED -> names("nFactors" to (r.factorCount.toIntOrNull()?.takeIf { it in 1..20 } ?: throw Unmapped()))
                        FactorCountMethod.EIGENVALUE -> {
                            if (r.factorEigenvalueBasis != EigenvalueBasis.COMMON_FACTOR) throw Unmapped()
                            names("minEigen" to (r.factorEigenvalue.toDoubleOrNull()?.takeIf(Double::isFinite) ?: throw Unmapped()))
                        }
                        FactorCountMethod.PARALLEL -> if (r.factorParallelMethod != FactorParallelMethod.COMMON_FACTOR) throw Unmapped()
                    }
                    "efa"
                }
            }
        } catch (_: Unmapped) {
            return null
        }
        return OmvAnalysis(name, output)
    }

    fun fromJamovi(analysis: OmvAnalysis, data: CourseData): OmvImportedOptions {
        val o = Options(analysis.options, data)
        val requests = try {
            when (analysis.name) {
                "descriptives" -> {
                    val variables = o.columns("vars", 1, 20)
                    val split = o.optionalColumns("splitBy")
                    o.require(split.size <= 1)
                    val numeric = data.numericColumns().toSet()
                    variables.groupBy { it in numeric }.map { (isNumeric, columns) ->
                        AnalysisRequest(DESCRIPTIVES, first = columns.first(), descriptiveVariables = columns,
                            splitBy = split.firstOrNull(), descriptiveNumeric = isNumeric,
                            percentiles = if (isNumeric && o.flag("pc")) o.text("pcValues", "25,50,75") else "",
                            showBoxplot = isNumeric && o.flag("box"), showQQPlot = isNumeric && o.flag("qq"),
                            showBarChart = !isNumeric && o.flag("bar"))
                    }
                }
                "ttestOneS" -> {
                    o.require(o.flag("students", true))
                    o.check(!o.flag("wilcoxon") && !o.flag("bf") && !o.flag("mann"))
                    val columns = o.columns("vars", 1, 20)
                    o.require(columns.size == 1 || o.text("miss", "perAnalysis") != "listwise")
                    columns.map { column ->
                        AnalysisRequest(ONE_SAMPLE_T, first = column, reference = o.numeric("testValue", 0.0).toString(),
                            tail = o.tail("dt", "gt", "lt"), checkNormality = o.flag("norm"), showQQPlot = o.flag("qq"))
                    }
                }
                "ttestIS" -> {
                    o.check(!o.flag("bf"))
                    val columns = o.columns("vars", 1, 20)
                    o.require(columns.size == 1 || o.text("miss", "perAnalysis") != "listwise")
                    val group = o.column("group")
                    val choices = buildList {
                        if (o.flag("students", true)) add(INDEPENDENT_T to true)
                        if (o.flag("welchs")) add(INDEPENDENT_T to false)
                        if (o.flag("mann")) add(MANN_WHITNEY to false)
                    }
                    columns.flatMap { column -> choices.map { (method, equal) ->
                        AnalysisRequest(method, first = column, second = group, equalVariance = equal,
                            tail = o.tail(), checkNormality = method.supportsNormalityCheck && o.flag("norm"),
                            checkVariances = method.supportsVarianceCheck && o.flag("eqv"),
                            showQQPlot = method.supportsQQPlot && o.flag("qq"))
                    } }
                }
                "ttestPS" -> {
                    o.check(!o.flag("bf"))
                    val pairs = o.list("pairs")
                    o.require(pairs.size in 1..20)
                    o.require(pairs.size == 1 || o.text("miss", "perAnalysis") != "listwise")
                    val methods = buildList {
                        if (o.flag("students", true)) add(PAIRED_T)
                        if (o.flag("wilcoxon")) add(WILCOXON)
                    }
                    pairs.flatMap { value ->
                        val pair = o.group(value)
                        methods.map { method -> AnalysisRequest(method, first = pair.column("i1"), second = pair.column("i2"),
                            tail = o.tail(), checkNormality = method.supportsNormalityCheck && o.flag("norm"),
                            showQQPlot = method.supportsQQPlot && o.flag("qq")) }
                    }
                }
                "anovaOneW", "anovaNP" -> {
                    val columns = o.columns("deps", 1, 20)
                    o.require(columns.size == 1 || o.text("miss", "perAnalysis") != "listwise")
                    val group = o.column("group")
                    val postHoc = o.text("phMethod", "none")
                    if (analysis.name == "anovaNP") {
                        o.check(!o.flag("pairs") && !o.flag("pairsDunn"))
                        columns.map { AnalysisRequest(KRUSKAL_WALLIS, first = it, second = group) }
                    } else {
                        val variants = buildList {
                            if (o.flag("fishers")) add(true)
                            if (o.flag("welchs", true)) add(false)
                        }
                        o.check(postHoc == "none" || variants.any { postHoc == if (it) "tukey" else "gamesHowell" })
                        columns.flatMap { column -> variants.map { equal ->
                            AnalysisRequest(ANOVA, first = column, second = group, equalVariance = equal,
                                checkNormality = o.flag("norm"), showQQPlot = o.flag("qq"), checkVariances = o.flag("eqv"),
                                pairwiseComparisons = postHoc == if (equal) "tukey" else "gamesHowell")
                        } }
                    }
                }
                "propTestN" -> {
                    val column = o.column("var")
                    val ratio = o.list("ratio")
                    listOf(AnalysisRequest(CHI_GOODNESS, first = column, count = o.optionalColumn("counts"),
                        expectedWeights = ratio.joinToString(",") { value ->
                            (value as? Number)?.toDouble()?.takeIf { it.isFinite() && it > 0.0 }?.toString() ?: throw Unmapped()
                        }))
                }
                "contTables" -> {
                    o.require(o.list("layers").isEmpty())
                    o.check(!o.flag("chiSqCorr") && !o.flag("likeRat") && !o.flag("mh") && !o.flag("taub") && !o.flag("gamma"))
                    val first = o.column("rows")
                    val second = o.column("cols")
                    val count = o.optionalColumn("counts")
                    val twoByTwo = listOf(first, second).all { index ->
                        data.rows.asSequence().map { it[index].trim() }.filter(String::isNotEmpty).distinct().take(3).count() == 2
                    }
                    buildList {
                        if (o.flag("chiSq", true)) add(AnalysisRequest(CHI_INDEPENDENCE, first = first, second = second, count = count))
                        if (o.flag("fisher") && twoByTwo) add(AnalysisRequest(FISHER_EXACT, first = first, second = second,
                            count = count, tail = o.tail()))
                        if (o.flag("fisher") && !twoByTwo) o.check(false)
                    }
                }
                "corrMatrix" -> {
                    val vars = o.columns("vars", 2, 20)
                    val tail = o.tail("corr", "pos", "neg")
                    o.require(vars.size == 2 || tail == Tail.TWO_SIDED)
                    o.check(!o.flag("kendall") && (!o.flag("ci") || o.numeric("ciWidth", 95.0) == 95.0))
                    buildList {
                        if (o.flag("pearson", true)) add(correlation(vars, tail, CorrelationKind.PEARSON, o.flag("ci")))
                        if (o.flag("spearman")) add(correlation(vars, tail, CorrelationKind.SPEARMAN, false))
                    }
                }
                "linReg" -> {
                    o.require(o.list("factors").isEmpty() && o.optionalColumn("weights") == null)
                    val dep = o.column("dep")
                    val covariates = o.columns("covs", 1, 12)
                    val blocks = o.list("blocks")
                    o.require(blocks.isEmpty() || blocks == listOf(covariates.map { listOf(data.columns[it]) }))
                    listOf(AnalysisRequest(MULTIPLE_REGRESSION, first = dep, predictors = covariates,
                        showQQPlot = o.flag("qqPlot"), checkNormality = o.flag("norm")))
                }
                "reliability" -> {
                    o.require(o.list("revItems").isEmpty())
                    listOf(AnalysisRequest(RELIABILITY, items = o.columns("vars", 2, 60), showItemCorrelations = o.flag("corPlot")))
                }
                "anovaRMNP" -> listOf(AnalysisRequest(FRIEDMAN, items = o.columns("measures", 3, 12),
                    friedmanComparisons = o.flag("pairs")))
                "anovaRM" -> listOf(importRepeated(o))
                "anova", "ancova" -> listOf(importModel(o, analysis.name == "ancova"))
                "efa" -> listOf(importFactors(o))
                else -> throw Unmapped()
            }
        } catch (_: Unmapped) {
            return OmvImportedOptions(emptyList(), limited = true)
        }
        return OmvImportedOptions(requests, o.limited || requests.isEmpty())
    }

    private fun importRepeated(o: Options): AnalysisRequest {
        o.require(o.list("bs").isEmpty() && o.list("cov").isEmpty())
        val factor = o.group(o.list("rm").singleOrNull())
        val label = factor.text("label", "")
        val levels = factor.list("levels")
        val cells = o.list("rmCells").map(o::group)
        o.require(levels.size in 2..12 && cells.size == levels.size && label.isNotEmpty() &&
            (o.list("rmTerms").isEmpty() || o.list("rmTerms") == listOf(listOf(label))))
        val items = levels.map { level -> cells.singleOrNull { it.list("cell") == listOf(level) }?.column("measure") ?: throw Unmapped() }
        o.require(items.distinct().size == items.size)
        val selected = o.list("spherCorr").filter { it != "none" }
        o.check(selected.size <= 1 && o.list("postHoc").isEmpty())
        val correction = corrections.entries.firstOrNull { it.value == selected.firstOrNull() }?.key ?: RepeatedCorrection.NONE
        return AnalysisRequest(REPEATED_ANOVA, items = items, repeatedCorrection = correction)
    }

    private fun importModel(o: Options, ancova: Boolean): AnalysisRequest {
        val factors = o.columns("factors", if (ancova) 1 else 2, 3)
        val covariates = if (ancova) o.columns("covs", 1, 6) else emptyList()
        val names = factors.map(o::name)
        val additive = modelTerms(names, covariates.map(o::name), false)
        val full = modelTerms(names, covariates.map(o::name), true)
        val terms = o.list("modelTerms")
        o.require(terms.isEmpty() || terms == full || terms == additive)
        val postHoc = o.list("postHoc")
        val means = o.list("emMeans").filter { it != emptyList<Any>() }
        val adjustment = o.list("postHocCorr").ifEmpty { listOf("tukey") }.singleOrNull()
        val supportedPostHoc = postHoc == names.map(::listOf) && adjustment in adjustments.values
        o.check((postHoc.isEmpty() || supportedPostHoc) && (means.isEmpty() || means == names.map(::listOf)))
        return AnalysisRequest(if (ancova) ANCOVA else FACTORIAL_ANOVA, first = o.column("dep"),
            factors = factors, covariates = covariates, factorInteractions = terms.isEmpty() || terms == full,
            sumsOfSquares = sums.entries.firstOrNull { it.value == o.text("ss", "3") }?.key ?: throw Unmapped(),
            checkNormality = o.flag("norm"), checkVariances = o.flag("homo"), showQQPlot = o.flag("qq"),
            modelMarginalMeans = o.flag("emmTables") && means == names.map(::listOf), modelComparisons = supportedPostHoc,
            modelAdjustment = adjustments.entries.firstOrNull { it.value == adjustment }?.key ?: ModelAdjustment.TUKEY)
    }

    private fun importFactors(o: Options): AnalysisRequest {
        val extraction = when (o.text("extraction", "minres")) {
            "minres" -> FactorExtraction.MINRES
            "pa" -> FactorExtraction.PRINCIPAL_AXIS
            else -> throw Unmapped()
        }
        val rotation = FactorRotation.entries.firstOrNull { it.name.lowercase() == o.text("rotation", "oblimin") } ?: throw Unmapped()
        val count = factorCounts.entries.firstOrNull { it.value == o.text("nFactorMethod", "parallel") }?.key ?: throw Unmapped()
        o.check(count != FactorCountMethod.PARALLEL)
        return AnalysisRequest(EFA, items = o.columns("vars", 3, 20), factorExtraction = extraction, factorRotation = rotation,
            factorCountMethod = count, factorCount = o.numeric("nFactors", 1.0).toInt().toString(),
            factorEigenvalue = o.numeric("minEigen", 0.0).toString(), factorLoadingCutoff = o.numeric("hideLoadings", 0.3).toString(),
            factorSortLoadings = o.flag("sortLoadings"), factorScreePlot = o.flag("screePlot"),
            factorKmo = o.flag("kmo"), factorBartlett = o.flag("bartlett"))
    }

    private fun correlation(vars: List<Int>, tail: Tail, kind: CorrelationKind, confidence: Boolean): AnalysisRequest =
        if (vars.size == 2) AnalysisRequest(if (kind == CorrelationKind.PEARSON) PEARSON else SPEARMAN,
            first = vars[0], second = vars[1], tail = tail)
        else AnalysisRequest(CORRELATION_MATRIX, correlationVariables = vars, tail = tail,
            correlationKind = kind, correlationConfidence = confidence)

    private fun modelTerms(factors: List<String>, covariates: List<String>, interactions: Boolean): List<List<String>> = buildList {
        addAll((factors + covariates).map(::listOf))
        if (interactions) {
            for (first in factors.indices) for (second in first + 1 until factors.size) {
                add(listOf(factors[first], factors[second]))
            }
            if (factors.size == 3) add(factors)
        }
    }

    private val sums = mapOf(SumsOfSquares.I to "1", SumsOfSquares.II to "2", SumsOfSquares.III to "3")
    private val corrections = mapOf(RepeatedCorrection.NONE to "none", RepeatedCorrection.GREENHOUSE_GEISSER to "GG",
        RepeatedCorrection.HUYNH_FELDT to "HF")
    private val adjustments = mapOf(ModelAdjustment.TUKEY to "tukey", ModelAdjustment.HOLM to "holm",
        ModelAdjustment.BONFERRONI to "bonf", ModelAdjustment.NONE to "none")
    private val factorCounts = mapOf(FactorCountMethod.PARALLEL to "parallel",
        FactorCountMethod.EIGENVALUE to "eigen", FactorCountMethod.FIXED to "fixed")

    private class Unmapped : RuntimeException()

    private class Options(private val values: Map<*, *>, private val data: CourseData) {
        var limited = false
            private set

        fun check(condition: Boolean) { if (!condition) limited = true }
        fun require(condition: Boolean) { if (!condition) throw Unmapped() }
        fun name(index: Int): String = data.columns[index]
        fun text(key: String, default: String): String = values[key]?.let { it as? String ?: throw Unmapped() } ?: default
        fun flag(key: String, default: Boolean = false): Boolean = values[key]?.let { it as? Boolean ?: throw Unmapped() } ?: default
        fun numeric(key: String, default: Double): Double = values[key]?.let {
            (it as? Number)?.toDouble()?.takeIf(Double::isFinite) ?: throw Unmapped()
        } ?: default

        fun tail(neutral: String = "different", greater: String = "oneGreater", less: String = "twoGreater"): Tail =
            when (text("hypothesis", neutral)) {
                neutral -> Tail.TWO_SIDED
                greater -> Tail.GREATER
                less -> Tail.LESS
                else -> throw Unmapped()
            }

        fun column(key: String): Int = index(values[key])
        fun optionalColumn(key: String): Int? = values[key]?.takeUnless { it == "" }?.let(::index)
        fun list(key: String): List<*> = values[key]?.let { it as? List<*> ?: throw Unmapped() } ?: emptyList<Any>()
        fun optionalColumns(key: String): List<Int> = list(key).map(::index)
        fun columns(key: String, min: Int, max: Int): List<Int> = optionalColumns(key).also {
            require(it.size in min..max && it.distinct().size == it.size)
        }
        fun group(value: Any?): Options = Options(value as? Map<*, *> ?: throw Unmapped(), data)

        private fun index(value: Any?): Int = data.columns.indexOf(value as? String ?: throw Unmapped()).also { require(it >= 0) }
    }
}
