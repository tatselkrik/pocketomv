package io.github.tatselkrik.statis130lite

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONArray
import org.json.JSONObject

enum class ExportKind { DATA, REPORT }

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
            .put("request", JSONObject()
                .put("method", request.method.name)
                .put("first", request.first)
                .put("second", request.second)
                .put("count", request.count ?: JSONObject.NULL)
                .put("reference", request.reference)
                .put("tail", request.tail.name)
                .put("equalVariance", request.equalVariance)
                .put("expectedWeights", request.expectedWeights))
            .put("pendingExport", session.pendingExport?.name ?: JSONObject.NULL)
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
            request = AnalysisRequest(
                method = AnalysisMethod.valueOf(request.text("method")),
                first = request.integer("first"),
                second = request.integer("second"),
                count = if (request.isNull("count")) null else request.integer("count"),
                reference = request.text("reference"),
                tail = Tail.valueOf(request.text("tail")),
                equalVariance = request.flag("equalVariance"),
                expectedWeights = request.text("expectedWeights"),
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
        val text = listOf(state.draft, state.appliedDraft, state.source, request.reference, request.expectedWeights)
        require(text.all { it.length <= MAX_TEXT }) { "A saved text field is too large." }
        require(request.first in 0..59 && request.second in 0..59 &&
            (request.count == null || request.count in 0..59)
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
        require(session.pendingExport != ExportKind.REPORT || session.hadResult) { "The saved report is unavailable." }
        require(session.pendingExport == null || state.data != null) { "The saved export has no table." }
    }

    private fun JSONObject.text(key: String): String =
        (get(key) as? String) ?: throw IllegalArgumentException("Invalid saved text field.")

    private fun JSONObject.integer(key: String): Int =
        (get(key) as? Int) ?: throw IllegalArgumentException("Invalid saved integer field.")

    private fun JSONObject.flag(key: String): Boolean =
        (get(key) as? Boolean) ?: throw IllegalArgumentException("Invalid saved flag.")

    private fun JSONArray.stringAt(index: Int): String =
        (get(index) as? String) ?: throw IllegalArgumentException("Invalid saved table value.")
}
