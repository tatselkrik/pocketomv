package io.github.tatselkrik.statis130lite

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

sealed interface ChartMark {
    data class Line(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val color: String = "#006b5e", val width: Double = 1.5) : ChartMark
    data class Rectangle(val x: Double, val y: Double, val width: Double, val height: Double, val fill: String = "#c5e6dd", val stroke: String = "#006b5e") : ChartMark
    data class Circle(val x: Double, val y: Double, val radius: Double = 2.2, val filled: Boolean = true) : ChartMark
    data class Text(val x: Double, val y: Double, val text: String, val size: Double = 11.0, val anchor: String = "start") : ChartMark
}
data class ChartScene(val width: Int, val height: Int, val marks: List<ChartMark>)

internal object ReportCharts {
    private const val LEFT = 88.0
    private const val RIGHT = 620.0
    private const val TOP = 42.0
    private const val BOTTOM = 272.0
    private fun px(x: Double) = LEFT + (RIGHT - LEFT) * x
    private fun py(y: Double) = BOTTOM - (BOTTOM - TOP) * y
    private fun label(text: String, maximum: Int): String {
        val singleLine = text.replace(Regex("\\s+"), " ")
        return if (singleLine.codePointCount(0, singleLine.length) <= maximum) singleLine
            else singleLine.substring(0, singleLine.offsetByCodePoints(0, maximum - 1)) + "…"
    }

    private fun MutableList<ChartMark>.axes(y: PlotAxis, yLabel: String, x: PlotAxis? = null, xLabel: String = "") {
        add(ChartMark.Text(8.0, 20.0, label(yLabel, 45)))
        for (fraction in listOf(0.0, 0.5, 1.0)) add(ChartMark.Line(LEFT, py(fraction), RIGHT, py(fraction), "#d3dfda", 0.8))
        add(ChartMark.Line(LEFT, TOP, LEFT, BOTTOM, "#556b62", 1.0))
        if (y.minimum == y.maximum) add(ChartMark.Text(8.0, py(0.5) + 4, y.label(y.minimum))) else {
            add(ChartMark.Text(8.0, TOP + 4, y.label(y.maximum)))
            add(ChartMark.Text(8.0, py(0.5) + 4, y.label(y.midpoint)))
            add(ChartMark.Text(8.0, BOTTOM + 4, y.label(y.minimum)))
        }
        if (x != null) {
            val values = if (x.minimum == x.maximum) listOf(0.5 to x.minimum) else listOf(0.0 to x.minimum, 0.5 to x.midpoint, 1.0 to x.maximum)
            values.forEach { (fraction, value) -> add(ChartMark.Text(px(fraction), BOTTOM + 20, x.label(value), anchor = if (fraction == 0.0) "start" else if (fraction == 1.0) "end" else "middle")) }
            add(ChartMark.Text((LEFT + RIGHT) / 2, 325.0, label(xLabel, 45), anchor = "middle"))
        }
    }

    fun points(points: List<PlotPoint>, xLabel: String, yLabel: String, line: PlotLine?): ChartScene {
        val x = PlotAxis(points.minOf { it.x }, points.maxOf { it.x })
        val y = PlotAxis(points.minOf { it.y }, points.maxOf { it.y })
        val marks = mutableListOf<ChartMark>()
        marks.axes(y, yLabel, x, xLabel)
        line?.let {
            val slope = it.right - it.left
            val interval = if (slope == 0.0) if (it.left in 0.0..1.0) 0.0 to 1.0 else null else {
                val a = -it.left / slope; val b = (1 - it.left) / slope
                val lower = max(0.0, min(a, b)); val upper = min(1.0, max(a, b))
                if (lower <= upper) lower to upper else null
            }
            interval?.let { (a, b) -> marks.add(ChartMark.Line(px(a), py(line.left + slope * a), px(b), py(line.left + slope * b), "#a75a73", 2.0)) }
        }
        points.forEach { marks.add(ChartMark.Circle(px(x.fraction(it.x)), py(y.fraction(it.y)))) }
        return ChartScene(640, 340, marks)
    }

    fun histogram(bins: List<HistogramBin>, variable: String): ChartScene {
        val marks = mutableListOf<ChartMark>()
        val maximum = bins.maxOf { it.count }.coerceAtLeast(1).toDouble()
        marks.axes(PlotAxis(0.0, maximum), "Frequency", PlotAxis(bins.first().lower, bins.last().upper), variable)
        val width = (RIGHT - LEFT) / bins.size
        bins.forEachIndexed { i, bin ->
            val height = (BOTTOM - TOP) * bin.count / maximum
            marks.add(ChartMark.Rectangle(LEFT + i * width + 0.5, BOTTOM - height, (width - 1).coerceAtLeast(0.5), height))
        }
        return ChartScene(640, 340, marks)
    }

    fun scree(plot: ScreePlot): ChartScene {
        val marks = mutableListOf<ChartMark>()
        val values = plot.observed + plot.parallel95
        val y = PlotAxis(values.min().coerceAtMost(0.0), values.max().coerceAtLeast(1.0))
        marks.axes(y, if (plot.basis == EigenvalueBasis.COMMON_FACTOR) "Common-factor eigenvalue" else "Correlation eigenvalue", PlotAxis(1.0, plot.observed.size.toDouble()), "Eigenvalue order")
        if (y.minimum < 0) marks.add(ChartMark.Line(LEFT, py(y.fraction(0.0)), RIGHT, py(y.fraction(0.0)), "#8ca39a", 0.8))
        fun curve(values: List<Double>, color: String) {
            val points = values.mapIndexed { i, value -> px(i.toDouble() / (plot.observed.size - 1)) to py(y.fraction(value)) }
            points.zipWithNext().forEach { (a, b) -> marks.add(ChartMark.Line(a.first, a.second, b.first, b.second, color, 2.0)) }
            if (color == "#006b5e") points.forEach { marks.add(ChartMark.Circle(it.first, it.second)) }
            else points.forEach { marks.add(ChartMark.Rectangle(it.first - 2, it.second - 2, 4.0, 4.0, color, color)) }
        }
        curve(plot.observed, "#006b5e")
        marks.add(ChartMark.Text(LEFT, 355.0, "Circles: observed eigenvalues", 11.0))
        if (plot.parallel95.isNotEmpty()) {
            curve(plot.parallel95, "#a75a73")
            marks.add(ChartMark.Text(340.0, 355.0, "Squares: parallel null 95th percentile", 11.0))
        }
        return ChartScene(640, 375, marks)
    }

    fun boxes(boxes: List<BoxPlotSummary>, axis: PlotAxis): ChartScene {
        val marks = mutableListOf<ChartMark>()
        marks.axes(axis, "Observed values")
        val step = (RIGHT - LEFT) / boxes.size
        boxes.forEachIndexed { index, box ->
            val center = LEFT + step * (index + 0.5)
            val half = min(20.0, step * 0.27)
            fun y(value: Double) = py(axis.fraction(value))
            marks.add(ChartMark.Line(center, y(box.lowerWhisker), center, y(box.upperWhisker)))
            marks.add(ChartMark.Rectangle(center - half, y(box.q3), half * 2, y(box.q1) - y(box.q3)))
            listOf(box.lowerWhisker, box.median, box.upperWhisker).forEach { marks.add(ChartMark.Line(center - half, y(it), center + half, y(it))) }
            box.outliers.forEach { marks.add(ChartMark.Circle(center, y(it), filled = false)) }
            marks.add(ChartMark.Text(center, 297.0, label(box.label, (step / 10).toInt().coerceIn(3, 12)), size = 10.0, anchor = "middle"))
            marks.add(ChartMark.Text(center, 315.0, "N=${box.size}", size = 10.0, anchor = "middle"))
        }
        return ChartScene(640, 340, marks)
    }

    fun bars(values: List<ValueFrequency>, maximum: Int): ChartScene {
        require(values.isNotEmpty() && values.size <= 10 && maximum > 0)
        val marks = mutableListOf<ChartMark>()
        marks.add(ChartMark.Text(8.0, 20.0, "Category"))
        marks.add(ChartMark.Text(210.0, 20.0, "Frequency"))
        for (fraction in listOf(0.0, 0.5, 1.0)) {
            val x = 210 + 360 * fraction
            marks.add(ChartMark.Line(x, 35.0, x, 290.0, "#d3dfda", 0.8))
            marks.add(ChartMark.Text(x, 312.0, formatStatistic(maximum * fraction), anchor = "middle"))
        }
        values.forEachIndexed { index, value ->
            val y = 45.0 + index * 24
            marks.add(ChartMark.Text(8.0, y + 10, label(value.value, 24), size = 10.0))
            marks.add(ChartMark.Rectangle(210.0, y, 360.0 * value.count / maximum, 14.0))
            marks.add(ChartMark.Text(625.0, y + 11, value.count.toString(), anchor = "end"))
        }
        return ChartScene(640, 340, marks)
    }

    fun svg(scene: ChartScene, title: String): String = buildString {
        fun n(x: Double) = ((x * 1000).roundToInt() / 1000.0).toString()
        append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ${scene.width} ${scene.height}\" role=\"img\" aria-label=\"${HtmlReport.escape(title)}\"><title>${HtmlReport.escape(title)}</title>")
        scene.marks.forEach { mark -> when (mark) {
            is ChartMark.Line -> append("<line x1=\"${n(mark.x1)}\" y1=\"${n(mark.y1)}\" x2=\"${n(mark.x2)}\" y2=\"${n(mark.y2)}\" stroke=\"${mark.color}\" stroke-width=\"${n(mark.width)}\"/>")
            is ChartMark.Rectangle -> append("<rect x=\"${n(mark.x)}\" y=\"${n(mark.y)}\" width=\"${n(mark.width)}\" height=\"${n(mark.height)}\" fill=\"${mark.fill}\" stroke=\"${mark.stroke}\"/>")
            is ChartMark.Circle -> append("<circle cx=\"${n(mark.x)}\" cy=\"${n(mark.y)}\" r=\"${n(mark.radius)}\" fill=\"${if (mark.filled) "#3b8b7d" else "none"}\" stroke=\"#006b5e\" opacity=\"0.7\"/>")
            is ChartMark.Text -> append("<text x=\"${n(mark.x)}\" y=\"${n(mark.y)}\" font-size=\"${n(mark.size)}\" text-anchor=\"${mark.anchor}\" fill=\"#172d28\" font-family=\"sans-serif\">${HtmlReport.escape(mark.text)}</text>")
        } }
        append("</svg>")
    }
}
