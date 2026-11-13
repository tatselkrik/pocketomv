package io.github.tatselkrik.statis130lite

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class OmvAnalysis(val name: String, val options: Map<String, Any?>)

/** The subset of jamovi's protobuf format needed for built-in analysis options and saved tables. */
internal object OmvWire {
    private const val MAX_TABLE_CELLS = 12_000

    fun read(bytes: ByteArray): OmvAnalysis? {
        var name = ""
        var namespace = ""
        var options: ByteBuffer? = null
        var arbitraryCode = false
        Reader(bytes).fields { field, kind ->
            when {
                field == 3 && kind == 2 -> name = string()
                field == 4 && kind == 2 -> namespace = string()
                field == 6 && kind == 2 -> options = message()
                field == 20 && kind == 0 -> arbitraryCode = integer() != 0L
                else -> skip(kind)
            }
        }
        if (name == "empty" && namespace == "jmv") return null
        if (arbitraryCode || namespace != "jmv" || name.isBlank()) return OmvAnalysis("", emptyMap())
        val values = options?.let { Reader(it).options(0) }
        require(values == null || values is Map<*, *>) { "Invalid jamovi analysis options." }
        @Suppress("UNCHECKED_CAST")
        return OmvAnalysis(name, (values as? Map<String, Any?>).orEmpty())
    }

    fun write(analysis: OmvAnalysis, title: String, result: AnalysisResult?, fictional: Boolean): ByteArray = Writer().apply {
        integer(2, 2)
        string(3, analysis.name)
        string(4, "jmv")
        message(6) { options(analysis.options) }
        message(7) {
            string(2, title)
            integer(3, 3)
            message(8) {
                notice("pocketomv", if (result == null) "Change an option to calculate this analysis in jamovi."
                    else "Saved results from pocketomv. Changing an option recalculates the analysis in jamovi.")
                if (fictional) notice("fictional", "Fictional practice data.")
                result?.let {
                    val tables = it.formattedReport("pocketomv", fictional, emptyList()).blocks
                        .filterIsInstance<ReportBlock.Table>()
                    var remaining = MAX_TABLE_CELLS
                    var omitted = false
                    tables.forEachIndexed { index, block ->
                        val cells = block.value.headers.size * block.value.rows.size
                        if (cells <= remaining) {
                            table(index, block.value)
                            remaining -= cells
                        } else omitted = true
                    }
                    if (omitted) notice("tableLimit", "Large tables are available when this project is recalculated.")
                }
            }
        }
        integer(8, 3)
        integer(10, 1)
        integer(15, 1)
        integer(21, 1)
    }.bytes()

    private fun Writer.notice(name: String, message: String) = message(1) {
        string(1, name)
        message(24) {
            string(1, message)
            integer(2, 3)
        }
    }

    private fun Writer.table(index: Int, table: AnalysisTable) = message(1) {
        string(1, "pocketomvTable${index}")
        string(2, table.title)
        integer(3, 3)
        message(6) {
            table.headers.forEachIndexed { col, title ->
                message(1) {
                    string(1, "col${col}")
                    string(2, title)
                    string(3, "text")
                    table.rows.forEach { row -> message(7) { string(3, row.getOrElse(col) { "" }.take(512)) } }
                }
            }
        }
    }

    private class Reader(private val buffer: ByteBuffer) {
        constructor(bytes: ByteArray) : this(ByteBuffer.wrap(bytes))

        fun fields(read: Reader.(Int, Int) -> Unit) {
            while (buffer.hasRemaining()) {
                val tag = integer()
                require(tag in 8..0xffff_ffffL) { "Invalid jamovi analysis field." }
                read((tag ushr 3).toInt(), tag.toInt() and 7)
            }
        }

        fun integer(): Long {
            var value = 0L
            for (index in 0..9) {
                require(buffer.hasRemaining()) { "Incomplete jamovi analysis." }
                val byte = buffer.get().toInt() and 255
                require(index != 9 || byte <= 1) { "Invalid jamovi analysis integer." }
                value = value or ((byte and 127).toLong() shl (7 * index))
                if (byte < 128) return value
            }
            error("Unreachable")
        }

        private fun length(): Int {
            val value = integer()
            require(value in 0..buffer.remaining().toLong()) { "Incomplete jamovi analysis field." }
            return value.toInt()
        }

        fun message(): ByteBuffer {
            val size = length()
            val part = buffer.slice().apply { limit(size) }
            advance(size)
            return part
        }

        fun string(): String = Charsets.UTF_8.newDecoder().decode(message()).toString()

        private fun advance(size: Int) {
            require(size <= buffer.remaining()) { "Incomplete jamovi analysis field." }
            buffer.position(buffer.position() + size)
        }

        fun skip(kind: Int) {
            when (kind) {
                0 -> integer()
                1 -> advance(8)
                2 -> advance(length())
                5 -> advance(4)
                else -> throw IllegalArgumentException("Invalid jamovi analysis field.")
            }
        }

        fun options(depth: Int): Any {
            require(depth <= 12) { "The jamovi analysis options are too deeply nested." }
            val values = mutableListOf<Any?>()
            val names = mutableListOf<String>()
            var named = false
            fields { field, kind ->
                when {
                    field == 1 && kind == 2 -> {
                        require(values.size < 1_024) { "The jamovi analysis has too many options." }
                        values.add(Reader(message()).option(depth))
                    }
                    field == 2 && kind == 0 -> named = integer() != 0L
                    field == 3 && kind == 2 -> {
                        require(names.size < 1_024) { "The jamovi analysis has too many option names." }
                        names.add(string())
                    }
                    else -> skip(kind)
                }
            }
            if (!named) return values
            require(names.size == values.size && names.distinct().size == names.size) { "Invalid jamovi analysis option names." }
            return names.zip(values).toMap()
        }

        private fun option(depth: Int): Any? {
            var value: Any? = null
            fields { field, kind ->
                when {
                    field == 1 && kind == 0 -> value = integer().toInt()
                    field == 2 && kind == 1 -> value = buffer.order(ByteOrder.LITTLE_ENDIAN).double
                    field == 3 && kind == 2 -> value = string()
                    field == 4 && kind == 0 -> value = when (integer()) { 0L -> false; 1L -> true; else -> null }
                    field == 5 && kind == 2 -> value = Reader(message()).options(depth + 1)
                    else -> skip(kind)
                }
            }
            return value
        }
    }

    private class Writer {
        private val output = ByteArrayOutputStream()

        fun bytes(): ByteArray = output.toByteArray()

        private fun varint(value: Long) {
            var remaining = value
            while (remaining ushr 7 != 0L) {
                output.write((remaining.toInt() and 127) or 128)
                remaining = remaining ushr 7
            }
            output.write(remaining.toInt())
        }

        fun integer(field: Int, value: Int) {
            varint((field shl 3).toLong())
            varint(value.toLong())
        }

        private fun bytes(field: Int, value: ByteArray) {
            varint(((field shl 3) or 2).toLong())
            varint(value.size.toLong())
            output.write(value)
        }

        fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))

        fun message(field: Int, block: Writer.() -> Unit) = bytes(field, Writer().apply(block).bytes())

        fun options(values: Map<*, *>) {
            values.values.forEach { value -> message(1) { option(value) } }
            integer(2, 1)
            values.keys.forEach { key -> string(3, key as String) }
        }

        private fun option(value: Any?) {
            when (value) {
                is Int -> integer(1, value)
                is Double -> {
                    varint((2 shl 3 or 1).toLong())
                    val bits = value.toRawBits()
                    repeat(8) { index -> output.write((bits ushr (index * 8)).toInt()) }
                }
                is String -> string(3, value)
                is Boolean -> integer(4, if (value) 1 else 0)
                null -> integer(4, 2)
                is Map<*, *> -> message(5) { options(value) }
                is List<*> -> message(5) { value.forEach { item -> message(1) { option(item) } } }
                else -> error("Unsupported jamovi option type.")
            }
        }
    }
}
