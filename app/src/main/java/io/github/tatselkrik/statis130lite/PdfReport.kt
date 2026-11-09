package io.github.tatselkrik.statis130lite

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream
import kotlin.math.max

internal object PdfReport {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val LEFT = 36f
    private const val RIGHT = 559f
    private const val BOTTOM = 797f
    private const val MAX_PAGES = 400
    private const val MAX_BYTES = 32 * 1024 * 1024

    fun render(report: FormattedReport): ByteArray = Writer(report).render()

    private class Writer(val report: FormattedReport) {
        val document = PdfDocument()
        var page: PdfDocument.Page? = null
        var pageNumber = 0
        var y = 44f
        val canvas: Canvas get() = requireNotNull(page).canvas
        val regular = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL); textSize = 10f; color = Color.rgb(23, 45, 40) }
        val bold = TextPaint(regular).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD) }
        val rule = Paint(Paint.ANTI_ALIAS_FLAG)

        fun render(): ByteArray {
            try {
                newPage()
                paragraph(report.title, 19f, true)
                report.blocks.forEach { block -> when (block) {
                    is ReportBlock.Paragraph -> paragraph(block.text, if (block.heading) 13f else 10f, block.heading)
                    is ReportBlock.Table -> table(block.value)
                    is ReportBlock.Figure -> {
                        val height = (RIGHT - LEFT) * block.scene.height / block.scene.width
                        if (BOTTOM - y < height + 65) newPage()
                        paragraph(block.title, 13f, true)
                        if (BOTTOM - y < height + 12) newPage()
                        figure(block.scene, height)
                        paragraph(block.caption, 9f)
                    }
                } }
                finishPage()
                val bytes = object : ByteArrayOutputStream() {
                    override fun write(b: Int) { require(count < MAX_BYTES) { "The PDF is too large. Export HTML or a smaller dataset." }; super.write(b) }
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        require(count.toLong() + len <= MAX_BYTES) { "The PDF is too large. Export HTML or a smaller dataset." }
                        super.write(b, off, len)
                    }
                }
                document.writeTo(bytes)
                return bytes.toByteArray()
            } finally {
                if (page != null) finishPage()
                document.close()
            }
        }

        fun finishPage() {
            val current = page ?: return
            regular.textSize = 8f
            canvas.drawText(if (report.fictional) "pocketomv · Fictional practice data" else "pocketomv", LEFT, 819f, regular)
            canvas.drawText("Page $pageNumber", RIGHT - 42, 819f, regular)
            document.finishPage(current)
            page = null
        }

        fun newPage() {
            finishPage()
            require(pageNumber < MAX_PAGES) { "The PDF exceeds 400 pages. Export HTML or a smaller dataset." }
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            canvas.drawColor(Color.WHITE)
            regular.textSize = 8f
            canvas.drawText("pocketomv · ${report.title}", LEFT, 26f, regular)
            y = 44f
        }

        fun layout(text: String, width: Float, size: Float, strong: Boolean = false): StaticLayout {
            val paint = TextPaint(if (strong) bold else regular).apply { textSize = size }
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(2f, 1f).build()
        }

        fun window(text: String, offset: Int): String {
            var end = (offset + 12_000).coerceAtMost(text.length)
            if (end < text.length && end > offset && Character.isHighSurrogate(text[end - 1])) end--
            return text.substring(offset, end)
        }

        fun segment(text: String, width: Float, size: Float, available: Float, strong: Boolean = false): Pair<String, StaticLayout>? {
            val full = layout(text, width, size, strong)
            var lines = 0
            while (lines < full.lineCount && full.getLineBottom(lines) <= available) lines++
            if (lines == 0) return null
            val selected = text.substring(0, full.getLineEnd(lines - 1))
            return selected to layout(selected, width, size, strong)
        }

        fun draw(layout: StaticLayout, x: Float, top: Float) {
            canvas.save(); canvas.translate(x, top); layout.draw(canvas); canvas.restore()
        }

        fun paragraph(text: String, size: Float = 10f, strong: Boolean = false) {
            if (text.isEmpty()) return
            if (strong && BOTTOM - y < 52) newPage()
            var offset = 0
            while (offset < text.length) {
                val part = segment(window(text, offset), RIGHT - LEFT, size, BOTTOM - y - 6, strong)
                if (part == null) { newPage(); continue }
                draw(part.second, LEFT, y)
                y += part.second.height + 6
                offset += part.first.length
                if (offset < text.length) newPage()
            }
        }

        fun table(table: AnalysisTable) {
            val groups = if (table.headers.size <= 5) listOf(table.headers.indices.toList())
                else (1 until table.headers.size).toList().chunked(4).map { listOf(0) + it }
            groups.forEachIndexed { groupIndex, selected ->
                val title = table.title + if (groups.size > 1) " · columns ${groupIndex + 1}/${groups.size}" else ""
                val headers = selected.map { index ->
                    val original = table.headers[index]
                    val cleaned = original.replace(Regex("\\s+"), " ")
                    if (cleaned.codePointCount(0, cleaned.length) > 80 || cleaned != original) {
                        paragraph("Column ${index + 1} label: $original", 9f)
                        "Column ${index + 1} (see label)"
                    } else cleaned
                }
                val firstWidth = if (selected.size == 2) 300f else if (selected.size > 2) 145f else RIGHT - LEFT
                val widths = FloatArray(selected.size) { i -> if (i == 0) firstWidth else (RIGHT - LEFT - firstWidth) / (selected.size - 1) }
                val headerLayouts = headers.indices.map { layout(headers[it], widths[it] - 10, 9f, true) }
                val headerHeight = headerLayouts.maxOf { it.height } + 10f
                fun heading(continued: Boolean) {
                    if (BOTTOM - y < headerHeight + 60) newPage()
                    paragraph(title + if (continued) " (continued)" else "", 12f, true)
                    if (BOTTOM - y < headerHeight + 24) newPage()
                    rule.color = Color.rgb(233, 243, 239); rule.style = Paint.Style.FILL
                    canvas.drawRect(LEFT, y, RIGHT, y + headerHeight, rule)
                    var x = LEFT
                    headerLayouts.forEachIndexed { i, header -> draw(header, x + 5, y + 5); x += widths[i] }
                    y += headerHeight
                }
                heading(false)
                table.rows.forEach { row ->
                    val cells = selected.map { row.getOrElse(it) { "" } }
                    val offsets = IntArray(cells.size)
                    var first = true
                    while (first || cells.indices.any { offsets[it] < cells[it].length }) {
                        first = false
                        if (BOTTOM - y < 25) { newPage(); heading(true) }
                        val available = BOTTOM - y - 10
                        val parts = cells.indices.map { i -> segment(window(cells[i], offsets[i]), widths[i] - 10, 9f, available) }
                        if (parts.any { it == null }) { newPage(); heading(true); continue }
                        val rowHeight = max(20f, parts.maxOf { it!!.second.height.toFloat() } + 10)
                        var x = LEFT
                        parts.forEachIndexed { i, part ->
                            val current = requireNotNull(part)
                            rule.style = Paint.Style.STROKE; rule.strokeWidth = 0.5f; rule.color = Color.rgb(203, 216, 209)
                            canvas.drawRect(x, y, x + widths[i], y + rowHeight, rule)
                            draw(current.second, x + 5, y + 5)
                            offsets[i] += current.first.length
                            x += widths[i]
                        }
                        y += rowHeight
                        if (cells.indices.any { offsets[it] < cells[it].length }) { newPage(); heading(true) }
                    }
                }
                y += 10
            }
            table.notes.forEach { paragraph(it, 9f) }
        }

        fun figure(scene: ChartScene, height: Float) {
            canvas.save(); canvas.translate(LEFT, y)
            val scale = (RIGHT - LEFT) / scene.width
            canvas.scale(scale, scale)
            scene.marks.forEach { mark -> when (mark) {
                is ChartMark.Line -> {
                    rule.style = Paint.Style.STROKE; rule.strokeWidth = mark.width.toFloat(); rule.color = Color.parseColor(mark.color); rule.alpha = 255
                    canvas.drawLine(mark.x1.toFloat(), mark.y1.toFloat(), mark.x2.toFloat(), mark.y2.toFloat(), rule)
                }
                is ChartMark.Rectangle -> {
                    rule.style = Paint.Style.FILL; rule.color = Color.parseColor(mark.fill); rule.alpha = 255
                    canvas.drawRect(mark.x.toFloat(), mark.y.toFloat(), (mark.x + mark.width).toFloat(), (mark.y + mark.height).toFloat(), rule)
                    rule.style = Paint.Style.STROKE; rule.color = Color.parseColor(mark.stroke); rule.strokeWidth = 1f
                    canvas.drawRect(mark.x.toFloat(), mark.y.toFloat(), (mark.x + mark.width).toFloat(), (mark.y + mark.height).toFloat(), rule)
                }
                is ChartMark.Circle -> {
                    rule.color = Color.rgb(0, 107, 94); rule.alpha = 180; rule.strokeWidth = 1f
                    rule.style = if (mark.filled) Paint.Style.FILL else Paint.Style.STROKE
                    canvas.drawCircle(mark.x.toFloat(), mark.y.toFloat(), mark.radius.toFloat(), rule)
                }
                is ChartMark.Text -> {
                    regular.textSize = mark.size.toFloat()
                    regular.textAlign = when (mark.anchor) { "middle" -> Paint.Align.CENTER; "end" -> Paint.Align.RIGHT; else -> Paint.Align.LEFT }
                    canvas.drawText(mark.text, mark.x.toFloat(), mark.y.toFloat(), regular)
                    regular.textAlign = Paint.Align.LEFT
                }
            } }
            rule.alpha = 255
            canvas.restore(); y += height + 8
        }
    }
}
