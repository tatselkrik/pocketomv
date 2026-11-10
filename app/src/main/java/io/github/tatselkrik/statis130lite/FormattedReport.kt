package io.github.tatselkrik.statis130lite

sealed interface ReportBlock {
    data class Paragraph(val text: String, val heading: Boolean = false) : ReportBlock
    data class Table(val value: AnalysisTable) : ReportBlock
    data class Figure(val title: String, val scene: ChartScene, val caption: String) : ReportBlock
}
data class FormattedReport(val title: String, val fictional: Boolean, val blocks: List<ReportBlock>)

internal fun AnalysisResult.formattedReport(source: String, fictional: Boolean, preparationNotes: List<String>): FormattedReport {
    val blocks = buildList {
        fun paragraph(text: String, heading: Boolean = false) { add(ReportBlock.Paragraph(text, heading)) }
        fun table(title: String, headers: List<String>, rows: List<List<String>>, notes: List<String> = emptyList()) {
            add(ReportBlock.Table(AnalysisTable(title, headers, rows, notes)))
        }
        paragraph(calculation)
        paragraph(source)
        if (fictional) paragraph("Fictional practice data")
        paragraph("${includedCases} included · ${excludedRows} rows excluded")
        test?.let { t -> table("Main analysis", listOf(t.label, "df", "p"), listOf(listOf(formatStatistic(t.statistic),
            degreesOfFreedom.joinToString { formatStatistic(it) }.ifEmpty { "—" }, formatProbability(t.pValue)))) }
        if (method == AnalysisMethod.DESCRIPTIVES) tables.forEach { add(ReportBlock.Table(it)) }
        descriptions.forEach { summary ->
            fun detailTitle(base: String) = if (descriptions.size > 1) "$base · ${summary.label}" else base
            table("Summary · ${summary.label}", listOf("Measure", "Value"), summary.summaryRows())
            summary.numeric?.let { numeric ->
                if (numeric.percentiles.isNotEmpty()) table(detailTitle("Percentiles · R type 7"), listOf("Percentile", "Value"),
                    numeric.percentiles.map { listOf(formatStatistic(it.percent), formatStatistic(it.value)) })
                add(ReportBlock.Figure("Histogram · ${summary.label}", ReportCharts.histogram(numeric.histogram, summary.label),
                    "Variable/group: ${summary.label}. Unweighted frequencies. The final interval includes its upper boundary."))
                table(detailTitle("Histogram intervals"), listOf("Interval", "Count"), numeric.histogram.mapIndexed { i, bin ->
                    listOf(bin.intervalLabel(i == numeric.histogram.lastIndex), bin.count.toString())
                })
            }
            if (summary.showBarChart && summary.validCount > 0 && summary.frequencies.isNotEmpty()) {
                val observed = summary.frequencies.filter { it.count > 0 }
                observed.take(20).chunked(10).forEachIndexed { index, values ->
                    add(ReportBlock.Figure("Bar chart · ${summary.label} · ${index + 1}",
                        ReportCharts.bars(values, summary.frequencies.maxOf { it.count }),
                        "Category counts on a shared frequency scale; labels are shortened in the figure and given in full in the frequency table." +
                            if (observed.size > 20) " Figures show the first 20 observed categories in label order. The table retains every observed category." else ""))
                }
            }
            add(ReportBlock.Table(summary.frequencyTable(detailTitle("Value frequencies"))))
            summary.notes.forEach { paragraph(it) }
        }
        if (estimates.isNotEmpty()) table("Estimates", listOf("Parameter", "Estimate", "95% CI"), estimates.map {
            listOf(it.label, formatStatistic(it.value), it.confidenceInterval())
        })
        if (samples.isNotEmpty()) table("Descriptive statistics", listOf("Sample", "N", "Mean", "SD", "Median", "Min", "Max"), samples.map {
            listOf(it.label, it.size.toString(), formatStatistic(it.mean), it.standardDeviation?.let(::formatStatistic) ?: "—",
                formatStatistic(it.median), formatStatistic(it.minimum), formatStatistic(it.maximum))
        })
        frequencies?.let { freq ->
            table("Observed counts", listOf("Category") + freq.columnLabels, freq.rowLabels.indices.map {
                listOf(freq.rowLabels[it]) + freq.observed[it].map(Long::toString)
            })
            table("Expected counts", listOf("Category") + freq.columnLabels, freq.rowLabels.indices.map {
                listOf(freq.rowLabels[it]) + freq.expected[it].map(::formatStatistic)
            })
        }
        if (assumptionChecks.isNotEmpty()) table("Assumption checks", listOf("Check", "Sample", "N", "Statistic", "df", "p", "Note"), assumptionChecks.map {
            listOf(it.name, it.sample, it.size.toString(), it.test?.let { test -> "${test.label} = ${formatStatistic(test.statistic)}" } ?: "Unavailable",
                it.degreesOfFreedom.joinToString { df -> formatStatistic(df) }, it.test?.pValue?.let(::formatProbability) ?: "—", it.note ?: "")
        })
        followUp?.let { comparisons ->
            if (comparisons.unavailable != null) paragraph("${comparisons.method}: ${comparisons.unavailable}") else
                table("Pairwise comparisons · ${comparisons.method}", pairwiseHeaders, comparisons.comparisons.map { it.displayRow() }, listOf(comparisons.note))
        }
        if (method != AnalysisMethod.DESCRIPTIVES) tables.forEach { add(ReportBlock.Table(it)) }
        plots?.let { plots ->
            plots.scree?.let { add(ReportBlock.Figure("Scree plot", ReportCharts.scree(it),
                "${it.basis.label} in descending order. " + (if (it.parallel95.isNotEmpty()) "Parallel thresholds are 95th percentiles from ${it.replications} null simulations" +
                    (it.seed?.let { seed -> " with seed $seed" } ?: "") + " of the same N and variable count. " else "") + "Every value is retained in the eigenvalue table.")) }
            if (plots.boxes.isNotEmpty()) {
                table("Boxplot summaries", boxplotHeaders, plots.boxes.map { it.displayRow() })
                val axis = PlotAxis(plots.boxes.minOf { it.minimum }, plots.boxes.maxOf { it.maximum })
                plots.boxes.chunked(8).forEachIndexed { i, group -> add(ReportBlock.Figure(
                    "Boxplot${if (plots.boxes.size > 8) " · group ${i + 1}" else ""}", ReportCharts.boxes(group, axis),
                    "R type 7 quartiles; whiskers within 1.5 IQR. Circles are outliers, retained in the analysis. Full labels are in the table.")) }
            }
            plots.qq.forEach { qq ->
                if (qq.unavailable != null) paragraph("Normal Q–Q · ${qq.label}: ${qq.unavailable}") else add(ReportBlock.Figure(
                    "Normal Q–Q · ${qq.label}", ReportCharts.points(qq.points, "Theoretical normal quantiles", "Observed values", qq.reference),
                    "N = ${qq.size}. ${qq.referenceLabel}; appearance alone does not establish normality." + (qq.detail?.let { " $it" } ?: "")))
            }
            plots.scatter?.let { scatter -> add(ReportBlock.Figure(
                if (method.usesResiduals) "Residuals vs fitted" else "Scatterplot",
                ReportCharts.points(scatter.points, "X · ${scatter.xLabel}", "Y · ${scatter.yLabel}", scatter.fittedLine),
                "X: ${scatter.xLabel}. Y: ${scatter.yLabel}. ${scatter.points.size} complete observations. All points included; overlaps share markers." +
                    if (scatter.fittedLine != null) " Line: fitted least-squares regression." else "")) }
        }
        if (preparationNotes.isNotEmpty()) {
            paragraph("Data preparation · saved values", true)
            preparationNotes.forEach { paragraph(it) }
        }
        if (notes.isNotEmpty()) {
            paragraph("Method notes", true)
            notes.forEach { paragraph(it) }
        }
    }
    return FormattedReport(method.title, fictional, blocks)
}

internal object HtmlReport {
    fun render(report: FormattedReport): String = buildString {
        append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; img-src data:\"><title>")
        append(escape("pocketomv · ${report.title}")); append("</title><style>")
        append("body{font:16px/1.5 system-ui,sans-serif;color:#172d28;background:#fff;margin:0;overflow-wrap:anywhere}main{max-width:1100px;margin:auto;padding:28px}h1{font-size:1.8rem}h2{font-size:1.2rem;color:#006b5e;margin-top:28px}p{white-space:pre-wrap;overflow-wrap:anywhere}.table{overflow-x:auto;margin:20px 0}table{border-collapse:collapse;min-width:100%;font-size:.9rem}caption{text-align:left;font-weight:bold;color:#006b5e;padding:10px 0}th,td{min-width:5em;border:1px solid #ccd8d4;padding:9px;text-align:left;vertical-align:top;white-space:pre-wrap;overflow-wrap:anywhere}th{background:#e9f3ef}thead{display:table-header-group}.figure{break-inside:avoid;margin:24px 0}svg{display:block;width:100%;height:auto;max-height:500px}figcaption,.note{font-size:.85rem;color:#435c53}@page{size:A4;margin:15mm}@media print{main{padding:0}.table{overflow:visible}table{font-size:8pt}tr{break-inside:avoid}h2{break-after:avoid}}")
        append("</style></head><body><main><p>pocketomv</p><h1>"); append(escape(report.title)); append("</h1>")
        report.blocks.forEach { block -> when (block) {
            is ReportBlock.Paragraph -> { val tag = if (block.heading) "h2" else "p"; append("<$tag>${escape(block.text)}</$tag>") }
            is ReportBlock.Table -> {
                append("<div class=\"table\"><table><caption>${escape(block.value.title)}</caption><thead><tr>")
                block.value.headers.forEach { append("<th scope=\"col\">${escape(it)}</th>") }
                append("</tr></thead><tbody>")
                block.value.rows.forEach { row -> append("<tr>"); row.forEach { append("<td>${escape(it)}</td>") }; append("</tr>") }
                append("</tbody></table></div>")
                block.value.notes.forEach { append("<p class=\"note\">${escape(it)}</p>") }
            }
            is ReportBlock.Figure -> {
                append("<figure class=\"figure\"><h2>${escape(block.title)}</h2>")
                append(ReportCharts.svg(block.scene, block.title))
                append("<figcaption>${escape(block.caption)}</figcaption></figure>")
            }
        } }
        append("</main></body></html>")
    }

    fun escape(text: String): String = buildString { text.forEach { append(when (it) {
        '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&#39;"; else -> it.toString()
    }) } }
}
