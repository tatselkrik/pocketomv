package io.github.tatselkrik.statis130lite

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONArray
import org.json.JSONObject

enum class ExportKind { DATA, REPORT, HTML, PDF;
    val requiresResult: Boolean get() = this != DATA
}

internal data class SavedSession(
    val screen: AnalysisScreenState,
    val hadResult: Boolean,
    val pendingExport: ExportKind? = null,
) {
    fun restoreScreen(): AnalysisScreenState {
        val result = if (hadResult) {
            require(screen.data != null && !screen.hasUnappliedChanges) { "The saved analysis is incomplete." }
            Statistics.run(screen.data, screen.request)
        } else null
        return screen.copy(result = result)
    }

    companion object {
        fun capture(screen: AnalysisScreenState, pendingExport: ExportKind?): SavedSession = SavedSession(
            screen.copy(result = null, busy = null, error = null, message = null),
            hadResult = screen.result != null,
            pendingExport = pendingExport,
        )
    }
}

/** Versioned data only. Results are recalculated with the current statistics engine on restore. */
internal object SessionCodec {
    const val MAX_BYTES = 32 * 1024 * 1024
    private const val MAX_TEXT = 4_000_000

    fun encode(session: SavedSession): ByteArray {
        validate(session)
        val state = session.screen
        val request = state.request
        val json = JSONObject()
            .put("version", 1)
            .put("page", state.page.name)
            .put("draft", state.draft)
            .put("firstRowHasNames", state.firstRowHasNames)
            .put("appliedDraft", state.appliedDraft)
            .put("appliedNames", state.appliedNames)
            .put("source", state.source)
            .put("fictional", state.fictional)
            .put("draftFictional", state.draftFictional)
            .put("hadResult", session.hadResult)
            .put("dataEditor", state.dataEditor.name)
            .put("preparationNotes", JSONArray(state.preparationNotes))
            .put("request", JSONObject()
                .put("method", request.method.name)
                .put("first", request.first)
                .put("second", request.second)
                .put("count", request.count ?: JSONObject.NULL)
                .put("countMissingPolicy", request.countMissingPolicy.name)
                .put("reference", request.reference)
                .put("tail", request.tail.name)
                .put("equalVariance", request.equalVariance)
                .put("expectedWeights", request.expectedWeights))
        json.getJSONObject("request")
            .put("descriptiveNumeric", request.descriptiveNumeric)
            .put("weight", request.weight ?: JSONObject.NULL)
            .put("population", request.population)
            .put("percentiles", request.percentiles)
            .put("histogramBins", request.histogramBins)
            .put("checkNormality", request.checkNormality)
            .put("independentNormality", request.independentNormality.name)
            .put("anovaNormality", request.anovaNormality.name)
            .put("checkVariances", request.checkVariances)
            .put("meanLeveneCenter", request.meanLeveneCenter.name)
            .put("pairwiseComparisons", request.pairwiseComparisons)
            .put("showBoxplot", request.showBoxplot)
            .put("showQQPlot", request.showQQPlot)
            .put("showScatterplot", request.showScatterplot)
            .put("qqSample", request.qqSample)
            .put("items", JSONArray(request.items))
            .put("predictors", JSONArray(request.predictors))
            .put("showItemCorrelations", request.showItemCorrelations)
            .put("repeatedCorrection", request.repeatedCorrection.name)
            .put("repeatedComparisons", request.repeatedComparisons)
            .put("descriptiveVariables", request.descriptiveVariables?.let(::JSONArray) ?: JSONObject.NULL)
            .put("splitBy", request.splitBy ?: JSONObject.NULL)
            .put("showBarChart", request.showBarChart)
            .put("correlationVariables", JSONArray(request.correlationVariables))
            .put("correlationKind", request.correlationKind.name)
            .put("correlationMissing", request.correlationMissing.name)
            .put("correlationConfidence", request.correlationConfidence)
            .put("comparisonGroup", request.comparisonGroup)
            .put("eventCategory", request.eventCategory)
            .put("friedmanComparisons", request.friedmanComparisons)
            .put("friedmanAdjustment", request.friedmanAdjustment.name)
            .put("factors", JSONArray(request.factors))
            .put("covariates", JSONArray(request.covariates))
            .put("factorInteractions", request.factorInteractions)
            .put("sumsOfSquares", request.sumsOfSquares.name)
            .put("modelMarginalMeans", request.modelMarginalMeans)
            .put("modelComparisons", request.modelComparisons)
            .put("modelAdjustment", request.modelAdjustment.name)
            .put("modelComparisonFamily", request.modelComparisonFamily.name)
            .put("checkSlopes", request.checkSlopes)
            .put("modelHomogeneity", request.modelHomogeneity.name)
            .put("modelQQBasis", request.modelQQBasis.name)
            .put("factorCountMethod", request.factorCountMethod.name)
            .put("factorParallelMethod", request.factorParallelMethod.name)
            .put("factorParallelSeed", request.factorParallelSeed)
            .put("factorCount", request.factorCount)
            .put("factorEigenvalue", request.factorEigenvalue)
            .put("factorEigenvalueBasis", request.factorEigenvalueBasis.name)
            .put("factorExtraction", request.factorExtraction.name)
            .put("minresConvergence", request.minresConvergence.name)
            .put("principalAxisConvergence", request.principalAxisConvergence.name)
            .put("factorRotation", request.factorRotation.name)
            .put("varimaxConvergence", request.varimaxConvergence.name)
            .put("factorLoadingCutoff", request.factorLoadingCutoff)
            .put("factorSortLoadings", request.factorSortLoadings)
            .put("factorScreePlot", request.factorScreePlot)
            .put("factorKmo", request.factorKmo)
            .put("factorBartlett", request.factorBartlett)
        json.put("pendingExport", session.pendingExport?.name ?: JSONObject.NULL)
        state.data?.let { data ->
            val rows = JSONArray()
            data.rows.forEach { rows.put(JSONArray(it)) }
            json.put("data", JSONObject().put("columns", JSONArray(data.columns)).put("rows", rows))
        }
        val buffer = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(json.toString()))
        require(buffer.remaining() <= MAX_BYTES) { "This session is too large to save automatically. Export your data." }
        return ByteArray(buffer.remaining()).also {
            buffer.get(it)
        }
    }

    fun decode(bytes: ByteArray): SavedSession {
        require(bytes.size <= MAX_BYTES) { "The saved session is too large." }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
        val json = JSONObject(text.toString())
        require(json.integer("version") == 1) { "The saved session uses an unsupported format." }
        val request = json.getJSONObject("request")
        val data = if (json.has("data") && !json.isNull("data")) {
            val saved = json.getJSONObject("data")
            val names = saved.getJSONArray("columns")
            val rows = saved.getJSONArray("rows")
            require(names.length() in 1..60 && rows.length() in 1..20_000 &&
                names.length().toLong() * rows.length() <= 250_000
            ) { "The saved table has invalid dimensions." }
            val columns = List(names.length()) { names.stringAt(it) }
            val cells = List(rows.length()) { index ->
                val row = rows.getJSONArray(index)
                require(row.length() == columns.size) { "The saved table has an incomplete row." }
                List(row.length()) { row.stringAt(it) }
            }
            CourseData(columns, cells)
        } else null
        val state = AnalysisScreenState(
            page = AppPage.valueOf(json.text("page")),
            draft = json.text("draft"),
            firstRowHasNames = json.flag("firstRowHasNames"),
            appliedDraft = json.text("appliedDraft"),
            appliedNames = json.flag("appliedNames"),
            data = data,
            source = json.text("source"),
            fictional = json.flag("fictional"),
            draftFictional = json.flag("draftFictional"),
            dataEditor = if (json.has("dataEditor")) DataEditorView.valueOf(json.text("dataEditor")) else DataEditorView.TEXT,
            preparationNotes = if (json.has("preparationNotes")) json.getJSONArray("preparationNotes").let { notes ->
                require(notes.length() <= 60) { "The saved preparation notes are too large." }
                List(notes.length()) { notes.stringAt(it) }
            } else emptyList(),
            request = AnalysisRequest(
                method = AnalysisMethod.valueOf(request.text("method")),
                first = request.integer("first"),
                second = request.integer("second"),
                count = if (request.isNull("count")) null else request.integer("count"),
                countMissingPolicy = if (request.has("countMissingPolicy")) CountMissingPolicy.valueOf(request.text("countMissingPolicy")) else CountMissingPolicy.OMIT,
                reference = request.text("reference"),
                tail = Tail.valueOf(request.text("tail")),
                equalVariance = request.flag("equalVariance"),
                expectedWeights = request.text("expectedWeights"),
                descriptiveNumeric = if (request.has("descriptiveNumeric")) request.flag("descriptiveNumeric") else true,
                weight = if (request.isNull("weight")) null else request.integer("weight"),
                population = if (request.has("population")) request.flag("population") else false,
                percentiles = if (request.has("percentiles")) request.text("percentiles") else "25,50,75",
                histogramBins = if (request.has("histogramBins")) request.text("histogramBins") else "",
                checkNormality = if (request.has("checkNormality")) request.flag("checkNormality") else false,
                independentNormality = if (request.has("independentNormality")) NormalityBasis.valueOf(request.text("independentNormality")) else NormalityBasis.GROUPS,
                anovaNormality = if (request.has("anovaNormality")) NormalityBasis.valueOf(request.text("anovaNormality")) else NormalityBasis.GROUPS,
                checkVariances = if (request.has("checkVariances")) request.flag("checkVariances") else false,
                meanLeveneCenter = if (request.has("meanLeveneCenter")) LeveneCenter.valueOf(request.text("meanLeveneCenter")) else LeveneCenter.MEDIAN,
                pairwiseComparisons = if (request.has("pairwiseComparisons")) request.flag("pairwiseComparisons") else false,
                showBoxplot = if (request.has("showBoxplot")) request.flag("showBoxplot") else false,
                showQQPlot = if (request.has("showQQPlot")) request.flag("showQQPlot") else false,
                showScatterplot = if (request.has("showScatterplot")) request.flag("showScatterplot") else false,
                qqSample = if (request.has("qqSample")) request.text("qqSample") else "",
                items = request.indices("items"),
                predictors = request.indices("predictors"),
                showItemCorrelations = if (request.has("showItemCorrelations")) request.flag("showItemCorrelations") else false,
                repeatedCorrection = if (request.has("repeatedCorrection")) RepeatedCorrection.valueOf(request.text("repeatedCorrection")) else RepeatedCorrection.GREENHOUSE_GEISSER,
                repeatedComparisons = if (request.has("repeatedComparisons")) request.flag("repeatedComparisons") else false,
                descriptiveVariables = if (request.isNull("descriptiveVariables")) null else request.indices("descriptiveVariables"),
                splitBy = if (request.isNull("splitBy")) null else request.integer("splitBy"),
                showBarChart = if (request.has("showBarChart")) request.flag("showBarChart") else false,
                correlationVariables = request.indices("correlationVariables"),
                correlationKind = if (request.has("correlationKind")) CorrelationKind.valueOf(request.text("correlationKind")) else CorrelationKind.PEARSON,
                correlationMissing = if (request.has("correlationMissing")) CorrelationMissing.valueOf(request.text("correlationMissing")) else CorrelationMissing.PAIRWISE,
                correlationConfidence = if (request.has("correlationConfidence")) request.flag("correlationConfidence") else false,
                comparisonGroup = if (request.has("comparisonGroup")) request.text("comparisonGroup") else "",
                eventCategory = if (request.has("eventCategory")) request.text("eventCategory") else "",
                friedmanComparisons = if (request.has("friedmanComparisons")) request.flag("friedmanComparisons") else false,
                friedmanAdjustment = if (request.has("friedmanAdjustment")) FriedmanAdjustment.valueOf(request.text("friedmanAdjustment")) else FriedmanAdjustment.HOLM,
                factors = request.indices("factors"),
                covariates = request.indices("covariates"),
                factorInteractions = if (request.has("factorInteractions")) request.flag("factorInteractions") else true,
                sumsOfSquares = if (request.has("sumsOfSquares")) SumsOfSquares.valueOf(request.text("sumsOfSquares")) else SumsOfSquares.III,
                modelMarginalMeans = if (request.has("modelMarginalMeans")) request.flag("modelMarginalMeans") else true,
                modelComparisons = if (request.has("modelComparisons")) request.flag("modelComparisons") else false,
                modelAdjustment = if (request.has("modelAdjustment")) ModelAdjustment.valueOf(request.text("modelAdjustment")) else ModelAdjustment.HOLM,
                modelComparisonFamily = if (request.has("modelComparisonFamily")) ModelComparisonFamily.valueOf(request.text("modelComparisonFamily")) else ModelComparisonFamily.ALL_FACTORS,
                checkSlopes = if (request.has("checkSlopes")) request.flag("checkSlopes") else false,
                modelHomogeneity = if (request.has("modelHomogeneity")) ModelHomogeneity.valueOf(request.text("modelHomogeneity")) else ModelHomogeneity.CELL_MEDIAN,
                modelQQBasis = if (request.has("modelQQBasis")) ModelQQBasis.valueOf(request.text("modelQQBasis")) else ModelQQBasis.RAW,
                factorCountMethod = if (request.has("factorCountMethod")) FactorCountMethod.valueOf(request.text("factorCountMethod")) else FactorCountMethod.PARALLEL,
                factorParallelMethod = if (request.has("factorParallelMethod")) FactorParallelMethod.valueOf(request.text("factorParallelMethod")) else FactorParallelMethod.PCA_WISHART,
                factorParallelSeed = if (request.has("factorParallelSeed")) request.text("factorParallelSeed") else "20261109",
                factorCount = if (request.has("factorCount")) request.text("factorCount") else "1",
                factorEigenvalue = if (request.has("factorEigenvalue")) request.text("factorEigenvalue") else "1",
                factorEigenvalueBasis = if (request.has("factorEigenvalueBasis")) EigenvalueBasis.valueOf(request.text("factorEigenvalueBasis")) else EigenvalueBasis.CORRELATION,
                factorExtraction = if (request.has("factorExtraction")) FactorExtraction.valueOf(request.text("factorExtraction")) else FactorExtraction.MINRES,
                minresConvergence = if (request.has("minresConvergence")) MinresConvergence.valueOf(request.text("minresConvergence")) else MinresConvergence.TIGHT,
                principalAxisConvergence = if (request.has("principalAxisConvergence")) PrincipalAxisConvergence.valueOf(request.text("principalAxisConvergence")) else PrincipalAxisConvergence.TIGHT,
                factorRotation = if (request.has("factorRotation")) FactorRotation.valueOf(request.text("factorRotation")) else FactorRotation.VARIMAX,
                varimaxConvergence = if (request.has("varimaxConvergence")) VarimaxConvergence.valueOf(request.text("varimaxConvergence")) else VarimaxConvergence.TIGHT,
                factorLoadingCutoff = if (request.has("factorLoadingCutoff")) request.text("factorLoadingCutoff") else "0.30",
                factorSortLoadings = if (request.has("factorSortLoadings")) request.flag("factorSortLoadings") else false,
                factorScreePlot = if (request.has("factorScreePlot")) request.flag("factorScreePlot") else false,
                factorKmo = if (request.has("factorKmo")) request.flag("factorKmo") else true,
                factorBartlett = if (request.has("factorBartlett")) request.flag("factorBartlett") else true,
            ),
        )
        return SavedSession(
            state, json.flag("hadResult"),
            if (json.isNull("pendingExport")) null else ExportKind.valueOf(json.text("pendingExport")),
        ).also(::validate)
    }

    private fun validate(session: SavedSession) {
        val state = session.screen
        val request = state.request
        val text = listOf(state.draft, state.appliedDraft, state.source, request.reference,
            request.expectedWeights, request.percentiles, request.histogramBins, request.qqSample,
            request.comparisonGroup, request.eventCategory) + state.preparationNotes
        require(text.all { it.length <= MAX_TEXT }) { "A saved text field is too large." }
        require(state.preparationNotes.size <= 60) { "The saved preparation notes are too large." }
        require(request.items.size <= 60 && request.predictors.size <= 12 &&
            (request.descriptiveVariables?.size ?: 0) <= 20 && request.correlationVariables.size <= 20 && request.factors.size <= 3 && request.covariates.size <= 6 &&
            listOfNotNull(request.items, request.predictors, request.descriptiveVariables, request.correlationVariables, request.factors, request.covariates).all {
            it.distinct().size == it.size && it.all { index -> index in 0..59 }
        }) { "The saved column list is invalid." }
        require(listOf(request.factorCount, request.factorEigenvalue, request.factorLoadingCutoff, request.factorParallelSeed).all { it.length <= 64 }) { "The saved factor option is too large." }
        require(request.first in 0..59 && request.second in 0..59 &&
            (request.count == null || request.count in 0..59) &&
            (request.weight == null || request.weight in 0..59)
            && (request.splitBy == null || request.splitBy in 0..59)
        ) { "The saved column selection is invalid." }
        state.data?.let { data ->
            require(data.columns.size in 1..60 && data.rows.size in 1..20_000 &&
                data.rows.size.toLong() * data.columns.size <= 250_000 &&
                data.rows.all { it.size == data.columns.size }
            ) { "The saved table has invalid dimensions." }
            val characters = data.columns.sumOf { it.length.toLong() } +
                data.rows.sumOf { row -> row.sumOf { it.length.toLong() } }
            require(characters <= MAX_TEXT) { "The saved table is too large." }
        }
        require(!session.hadResult || (state.data != null && !state.hasUnappliedChanges)) {
            "The saved result has no matching applied table."
        }
        require(session.pendingExport?.requiresResult != true || session.hadResult) { "The saved report is unavailable." }
        require(session.pendingExport == null || state.data != null) { "The saved export has no table." }
    }

    private fun JSONObject.text(key: String): String =
        (get(key) as? String) ?: throw IllegalArgumentException("Invalid saved text field.")

    private fun JSONObject.indices(key: String): List<Int> {
        if (!has(key)) return emptyList()
        val array = getJSONArray(key)
        require(array.length() <= 60) { "The saved column list is too large." }
        return List(array.length()) { (array.get(it) as? Int) ?: throw IllegalArgumentException("Invalid saved column index.") }
    }

    private fun JSONObject.integer(key: String): Int =
        (get(key) as? Int) ?: throw IllegalArgumentException("Invalid saved integer field.")

    private fun JSONObject.flag(key: String): Boolean =
        (get(key) as? Boolean) ?: throw IllegalArgumentException("Invalid saved flag.")

    private fun JSONArray.stringAt(index: Int): String =
        (get(index) as? String) ?: throw IllegalArgumentException("Invalid saved table value.")
}
