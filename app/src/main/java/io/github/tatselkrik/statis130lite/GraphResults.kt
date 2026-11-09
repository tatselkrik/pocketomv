package io.github.tatselkrik.statis130lite

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun PlotOptions(request: AnalysisRequest, enabled: Boolean, onChange: (AnalysisRequest) -> Unit) {
    val numeric = request.method != AnalysisMethod.DESCRIPTIVES || request.descriptiveNumeric
    if (!numeric || (!request.method.supportsBoxplot && !request.method.supportsScatterplot)) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Graphs (optional)", style = MaterialTheme.typography.titleSmall)
        if (request.method.supportsBoxplot) CheckOption("Boxplot", request.showBoxplot, enabled) {
            onChange(request.copy(showBoxplot = it))
        }
        if (request.method.supportsQQPlot) {
            CheckOption("Normal Q–Q plot", request.showQQPlot, enabled) { onChange(request.copy(showQQPlot = it)) }
            Text(when (request.method) {
                AnalysisMethod.PAIRED_T -> "Q–Q plots use the differences within complete pairs."
                AnalysisMethod.MULTIPLE_REGRESSION -> "Q–Q plots use model residuals."
                AnalysisMethod.INDEPENDENT_T, AnalysisMethod.ANOVA -> "Q–Q plots are available separately for each group."
                else -> "Compare the sample's distribution with normal quantiles."
            }, style = MaterialTheme.typography.bodySmall)
        }
        if (request.method.supportsScatterplot) {
            CheckOption(if (request.method == AnalysisMethod.MULTIPLE_REGRESSION) "Residuals vs fitted" else "Scatterplot", request.showScatterplot, enabled) { onChange(request.copy(showScatterplot = it)) }
            Text(if (request.method == AnalysisMethod.MULTIPLE_REGRESSION) "Plots model residuals against fitted outcome values."
                else if (request.method == AnalysisMethod.REGRESSION) "Plots complete pairs with the fitted regression line."
                else "Plots the original values of complete pairs, including for Spearman correlation.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal fun LazyListScope.graphItems(result: AnalysisResult, selectedQQ: String, enabled: Boolean, onSelect: (String) -> Unit) {
    val plots = result.plots ?: return
    if (plots.boxes.isNotEmpty()) {
        item { BoxplotChart(plots.boxes) }
        item { TitledTable("Boxplot summaries", boxplotHeaders, plots.boxes.map { it.displayRow() }) }
    }
    if (plots.qq.isNotEmpty()) item {
        val plot = plots.qq.firstOrNull { it.label == selectedQQ } ?: plots.qq.first()
        GraphCard("Normal Q–Q plot") {
            if (plots.qq.size > 1) ChoiceField("Q–Q sample", plot.label, plots.qq.map { it.label to it.label }, enabled, onSelect)
            Text("${plot.label} · N = ${plot.size}", style = MaterialTheme.typography.bodyMedium)
            if (plot.unavailable != null) Text(plot.unavailable) else {
                PointsChart(plot.points, "Theoretical normal quantiles", "Observed values", plot.reference,
                    "Normal Q–Q plot for ${plot.label}, ${plot.size} observations. Reference line passes through the first and third quartiles.")
                Text("Line through Q1 and Q3. A roughly straight pattern supports normality; a graph alone does not establish it.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Q1 = ${formatStatistic(plot.q1)} · Q3 = ${formatStatistic(plot.q3)}", style = MaterialTheme.typography.bodySmall)
                if (plot.points.first().y == plot.points.last().y) Text("All values are equal; normality cannot be assessed.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    plots.scatter?.let { scatter -> item {
        GraphCard(if (result.method == AnalysisMethod.MULTIPLE_REGRESSION) "Residuals vs fitted" else "Scatterplot") {
            Text("${scatter.points.size} complete pairs · ${scatter.yLabel} vs ${scatter.xLabel}", style = MaterialTheme.typography.bodyMedium)
            PointsChart(scatter.points, "X · ${scatter.xLabel}", "Y · ${scatter.yLabel}", scatter.fittedLine,
                "Scatterplot of ${scatter.yLabel} against ${scatter.xLabel}, ${scatter.points.size} complete pairs" +
                    if (scatter.fittedLine != null) ", with fitted least-squares regression line." else ".")
            Text(if (scatter.fittedLine != null) "Line: fitted least-squares regression. All complete pairs are plotted; overlapping values share a marker."
                else "Original values of all complete pairs are plotted. Overlapping values share a marker.",
                style = MaterialTheme.typography.bodySmall)
        }
    } }
}

internal val boxplotHeaders = listOf("Sample", "N", "Q1", "Median", "Q3", "Lower whisker", "Upper whisker", "Outliers")
internal fun BoxPlotSummary.displayRow(): List<String> = listOf(label, size.toString(), formatStatistic(q1),
    formatStatistic(median), formatStatistic(q3), formatStatistic(lowerWhisker), formatStatistic(upperWhisker), outliers.size.toString())

@Composable
private fun GraphCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun graphHeight(): androidx.compose.ui.unit.Dp {
    val height = LocalWindowInfo.current.containerSize.height
    return if (height < with(LocalDensity.current) { 500.dp.toPx() }) 160.dp else 220.dp
}

@Composable
private fun AxisLabels(axis: PlotAxis, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxHeight(), verticalArrangement = if (axis.minimum == axis.maximum) Arrangement.Center else Arrangement.SpaceBetween) {
        val ticks = if (axis.minimum == axis.maximum) listOf(axis.minimum) else listOf(axis.maximum, axis.midpoint, axis.minimum)
        ticks.forEach { Text(axis.label(it), style = MaterialTheme.typography.labelSmall) }
    }
}

private fun PlotAxis.label(value: Double): String =
    if (minimum != maximum && formatStatistic(minimum) == formatStatistic(maximum)) value.toString() else formatStatistic(value)

@Composable
private fun PointsChart(points: List<PlotPoint>, xLabel: String, yLabel: String, line: PlotLine?, description: String) {
    val x = remember(points) { PlotAxis(points.minOf { it.x }, points.maxOf { it.x }) }
    val y = remember(points) { PlotAxis(points.minOf { it.y }, points.maxOf { it.y }) }
    val ink = MaterialTheme.colorScheme.primary
    val lineColor = MaterialTheme.colorScheme.tertiary
    val axis = MaterialTheme.colorScheme.outline
    Text(yLabel, style = MaterialTheme.typography.labelMedium)
    Row(Modifier.fillMaxWidth().height(graphHeight())) {
        AxisLabels(y, Modifier.width(88.dp).padding(end = 8.dp))
        Canvas(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = description }) {
            val frame = PlotFrame(6.dp.toPx(), size.width - 6.dp.toPx(), 6.dp.toPx(), size.height - 6.dp.toPx())
            grid(frame, axis)
            clipRect(frame.left, frame.top, frame.right, frame.bottom) {
                line?.let { drawLine(lineColor, frame.point(0.0, it.left), frame.point(1.0, it.right), 2.dp.toPx()) }
            }
            points.forEach { point ->
                drawCircle(ink.copy(alpha = 0.65f), 3.dp.toPx(), frame.point(x.fraction(point.x), y.fraction(point.y)))
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 88.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val ticks = if (x.minimum == x.maximum) listOf(x.minimum) else listOf(x.minimum, x.midpoint, x.maximum)
        ticks.forEachIndexed { index, value ->
            Text(x.label(value), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                textAlign = if (ticks.size == 1 || index == 1) TextAlign.Center else if (index == 0) TextAlign.Start else TextAlign.End)
        }
    }
    Text(xLabel, Modifier.fillMaxWidth().padding(start = 88.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
}

@Composable
private fun BoxplotChart(boxes: List<BoxPlotSummary>) {
    val y = remember(boxes) { PlotAxis(boxes.minOf { it.minimum }, boxes.maxOf { it.maximum }) }
    val ink = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outline
    val plotHeight = graphHeight()
    GraphCard("Boxplot") {
        Text("Observed values", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth()) {
            AxisLabels(y, Modifier.width(88.dp).height(plotHeight).padding(end = 8.dp))
            BoxWithConstraints(Modifier.weight(1f)) {
                val plotWidth = maxOf(maxWidth, 96.dp * boxes.size)
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    Canvas(Modifier.width(plotWidth).height(plotHeight).semantics {
                        contentDescription = "Boxplots for ${boxes.size} samples. Quartiles, whiskers and outlier counts are listed in the boxplot summaries table."
                    }) {
                        val frame = PlotFrame(6.dp.toPx(), size.width - 6.dp.toPx(), 6.dp.toPx(), size.height - 6.dp.toPx())
                        grid(frame, axis)
                        val step = size.width / boxes.size
                        boxes.forEachIndexed { i, box ->
                            val cx = step * (i + 0.5f)
                            val half = minOf(step * 0.25f, 24.dp.toPx())
                            fun py(value: Double) = frame.point(0.5, y.fraction(value)).y
                            val top = py(box.q3)
                            val bottom = py(box.q1)
                            drawLine(ink, Offset(cx, py(box.lowerWhisker)), Offset(cx, py(box.upperWhisker)), 2.dp.toPx())
                            drawRect(ink.copy(alpha = 0.18f), Offset(cx - half, top), Size(half * 2, bottom - top))
                            drawRect(ink, Offset(cx - half, top), Size(half * 2, bottom - top), style = Stroke(2.dp.toPx()))
                            listOf(box.lowerWhisker, box.upperWhisker, box.median).forEach { value ->
                                drawLine(ink, Offset(cx - half, py(value)), Offset(cx + half, py(value)), 2.dp.toPx())
                            }
                            box.outliers.forEach { value -> drawCircle(ink, 3.dp.toPx(), Offset(cx, py(value)), style = Stroke(1.5.dp.toPx())) }
                        }
                    }
                    Row(Modifier.width(plotWidth)) {
                        boxes.forEach { box -> Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                            Text(box.label, Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("N = ${box.size}", Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                        } }
                    }
                }
            }
        }
        Text("Box: Q1–Q3; line: median. Whiskers reach the last values within 1.5 IQR; circles mark outliers. Outliers remain in the analysis.",
            style = MaterialTheme.typography.bodySmall)
        if (boxes.size > 2) Text("Swipe the plot sideways to see additional samples. Full names are in the summaries below.",
            style = MaterialTheme.typography.bodySmall)
    }
}

private data class PlotFrame(val left: Float, val right: Float, val top: Float, val bottom: Float) {
    fun point(x: Double, y: Double) = Offset(left + ((right - left) * x).toFloat(), bottom - ((bottom - top) * y).toFloat())
}

private fun DrawScope.grid(frame: PlotFrame, color: Color) {
    listOf(0.0, 0.5, 1.0).forEach {
        drawLine(color.copy(alpha = 0.35f), frame.point(0.0, it), frame.point(1.0, it), 1.dp.toPx())
    }
    drawLine(color, frame.point(0.0, 0.0), frame.point(0.0, 1.0), 1.dp.toPx())
    drawLine(color, frame.point(0.0, 0.0), frame.point(1.0, 0.0), 1.dp.toPx())
}
