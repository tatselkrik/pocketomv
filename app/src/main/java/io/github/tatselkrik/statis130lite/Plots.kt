package io.github.tatselkrik.statis130lite

import org.apache.commons.statistics.distribution.NormalDistribution
import kotlin.math.abs
import kotlin.math.max

data class PlotPoint(val x: Double, val y: Double)

/** Endpoints in normalized axis coordinates. Values outside 0..1 are clipped when drawn. */
data class PlotLine(val left: Double, val right: Double)

data class PlotAxis(val minimum: Double, val maximum: Double) {
    init { require(minimum.isFinite() && maximum.isFinite() && minimum <= maximum) }

    fun fraction(value: Double): Double {
        if (minimum == maximum) return 0.5
        val range = maximum - minimum
        return if (range.isFinite()) (value - minimum) / range else {
            val scale = max(abs(minimum), abs(maximum))
            (value / scale - minimum / scale) / (maximum / scale - minimum / scale)
        }
    }

    val midpoint: Double get() = minimum / 2.0 + maximum / 2.0
}

data class BoxPlotSummary(
    val label: String, val size: Int, val minimum: Double, val q1: Double,
    val median: Double, val q3: Double, val maximum: Double,
    val lowerWhisker: Double, val upperWhisker: Double, val outliers: List<Double>,
)

data class NormalQQPlot(
    val label: String, val size: Int, val points: List<PlotPoint>,
    val q1: Double, val q3: Double, val reference: PlotLine?, val unavailable: String? = null,
)

data class ScatterPlot(
    val xLabel: String, val yLabel: String, val points: List<PlotPoint>, val fittedLine: PlotLine?,
)

data class AnalysisPlots(
    val boxes: List<BoxPlotSummary> = emptyList(),
    val qq: List<NormalQQPlot> = emptyList(),
    val scatter: ScatterPlot? = null,
    val scree: ScreePlot? = null,
)

internal object PlotCalculator {
    private val normal = NormalDistribution.of(0.0, 1.0)
    private val zQuartile = normal.inverseCumulativeProbability(0.75)

    fun attach(data: CourseData, request: AnalysisRequest, result: AnalysisResult): AnalysisResult {
        require(!request.showBoxplot || request.method.supportsBoxplot) { "Boxplots require numeric sample data." }
        require(!request.showQQPlot || request.method.supportsQQPlot) { "Q–Q plots are available for numeric Descriptives, t tests and ANOVA." }
        require(!request.showScatterplot || request.method.supportsScatterplot) { "Scatterplots are available for correlation and regression." }
        if (request.method.usesResiduals || request.method == AnalysisMethod.EFA) return result
        if (request.method == AnalysisMethod.DESCRIPTIVES) {
            require((!request.showBoxplot && !request.showQQPlot) || request.descriptiveNumeric) { "Choose Numeric data for boxplots and Q–Q plots." }
            return result
        }
        if (!request.showBoxplot && !request.showQQPlot && !request.showScatterplot) return result
        if (request.method == AnalysisMethod.REPEATED_ANOVA || request.method == AnalysisMethod.FRIEDMAN) {
            val matrix = NumericMatrix.read(data, request.items, if (request.method == AnalysisMethod.FRIEDMAN) 3 else 2, 12)
            return result.copy(plots = AnalysisPlots(boxes = matrix.labels.indices.map { box(matrix.labels[it], matrix.columns[it]) }))
        }
        require(request.method != AnalysisMethod.DESCRIPTIVES || request.descriptiveNumeric) { "Choose Numeric data for boxplots and Q–Q plots." }

        if (request.showScatterplot) {
            val pairs = Statistics.readPairs(data, request)
            val points = pairs.first.indices.map { PlotPoint(pairs.first[it], pairs.second[it]) }
            return result.copy(plots = AnalysisPlots(scatter = scatter(data.columns[request.first],
                data.columns[request.second], points, request.method == AnalysisMethod.REGRESSION)))
        }

        val paired = request.method == AnalysisMethod.PAIRED_T || request.method == AnalysisMethod.WILCOXON
        val pairs = if (paired) Statistics.readPairs(data, request) else null
        val samples: List<Pair<String, DoubleArray>> = when {
            request.method.usesGroups -> Statistics.readGroups(data, request).map { it.label to it.values }
            pairs != null -> listOf(data.columns[request.first] to pairs.first, data.columns[request.second] to pairs.second)
            request.method == AnalysisMethod.DESCRIPTIVES -> {
                // Descriptives accepts the full finite Double range, unlike inferential tests.
                val values = data.rows.mapNotNull { row ->
                    row[request.first].takeUnless { it.isMissingNumber() }?.toDouble()
                }.toDoubleArray()
                listOf(data.columns[request.first] to values)
            }
            else -> listOf(data.columns[request.first] to Statistics.readNumbers(data, request.first))
        }
        val qqSamples = if (pairs != null) listOf(
            "${data.columns[request.first]} − ${data.columns[request.second]} (differences)" to pairs.differences()
        ) else samples
        return result.copy(plots = AnalysisPlots(
            boxes = if (request.showBoxplot) samples.map { box(it.first, it.second) } else emptyList(),
            qq = if (request.showQQPlot) qqSamples.map { qq(it.first, it.second) } else emptyList(),
        ))
    }

    fun box(label: String, values: DoubleArray): BoxPlotSummary {
        require(values.isNotEmpty() && values.all(Double::isFinite))
        val sorted = values.sortedArray()
        val q1 = Descriptives.percentile(sorted, 25.0)
        val q3 = Descriptives.percentile(sorted, 75.0)
        val outliers = sorted.filter { outside(it, q1, q3) }
        val inside = sorted.filterNot { outside(it, q1, q3) }
        return BoxPlotSummary(label, sorted.size, sorted.first(), q1,
            Descriptives.percentile(sorted, 50.0), q3, sorted.last(), inside.first(), inside.last(), outliers)
    }

    private fun outside(value: Double, q1: Double, q3: Double): Boolean {
        if (value in q1..q3) return false
        val high = if (value < q1) q1 else value
        val low = if (value < q1) value else q3
        val gap = high - low
        val iqr = q3 - q1
        if (gap.isFinite() && iqr.isFinite()) return gap > 1.5 * iqr
        val scale = max(abs(value), max(abs(q1), abs(q3)))
        return high / scale - low / scale > 1.5 * (q3 / scale - q1 / scale)
    }

    fun qq(label: String, values: DoubleArray): NormalQQPlot {
        require(values.isNotEmpty() && values.all(Double::isFinite))
        val sorted = values.sortedArray()
        val q1 = Descriptives.percentile(sorted, 25.0)
        val q3 = Descriptives.percentile(sorted, 75.0)
        if (sorted.size < 3) return NormalQQPlot(label, sorted.size, emptyList(), q1, q3, null,
            "At least 3 observations are needed for a useful normal Q–Q plot.")
        val a = if (sorted.size <= 10) 0.375 else 0.5
        // Mirror the lower half to preserve exact symmetry on the displayed theoretical axis.
        val z = DoubleArray(sorted.size)
        for (i in 0 until sorted.size / 2) {
            z[i] = normal.inverseCumulativeProbability((i + 1 - a) / (sorted.size + 1 - 2 * a))
            z[z.lastIndex - i] = -z[i]
        }
        val points = sorted.mapIndexed { i, y -> PlotPoint(z[i], y) }
        val yAxis = PlotAxis(sorted.first(), sorted.last())
        val y1 = yAxis.fraction(q1)
        val y3 = yAxis.fraction(q3)
        val slope = (y3 - y1) / (2 * zQuartile)
        val center = (y1 + y3) / 2
        return NormalQQPlot(label, sorted.size, points, q1, q3,
            PlotLine(center + slope * points.first().x, center + slope * points.last().x))
    }

    fun scatter(xLabel: String, yLabel: String, points: List<PlotPoint>, fit: Boolean): ScatterPlot {
        require(points.isNotEmpty() && points.all { it.x.isFinite() && it.y.isFinite() })
        val line = if (fit) {
            val x = PlotAxis(points.minOf { it.x }, points.maxOf { it.x })
            val y = PlotAxis(points.minOf { it.y }, points.maxOf { it.y })
            if (x.minimum == x.maximum) null else {
                val normalized = points.map { PlotPoint(x.fraction(it.x), y.fraction(it.y)) }
                val mx = normalized.sumOf { it.x } / normalized.size
                val my = normalized.sumOf { it.y } / normalized.size
                val xx = normalized.sumOf { (it.x - mx) * (it.x - mx) }
                val xy = normalized.sumOf { (it.x - mx) * (it.y - my) }
                val slope = xy / xx
                PlotLine(my - slope * mx, my + slope * (1 - mx))
            }
        } else null
        return ScatterPlot(xLabel, yLabel, points, line)
    }
}
