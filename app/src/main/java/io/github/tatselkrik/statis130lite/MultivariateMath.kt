package io.github.tatselkrik.statis130lite

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Selected numeric columns, with a single listwise inclusion rule. */
internal data class NumericMatrix(val labels: List<String>, val columns: List<DoubleArray>, val excluded: Int) {
    val n: Int get() = columns.first().size
    val k: Int get() = columns.size

    companion object {
        fun read(data: CourseData, selected: List<Int>, minimum: Int, maximum: Int): NumericMatrix {
            require(selected.size in minimum..maximum && selected.distinct().size == selected.size &&
                selected.all { it in data.columns.indices }) { "Select $minimum–$maximum different numeric columns." }
            val rows = data.rows.indices.filter { row -> selected.none { data.rows[row][it].isMissingNumber() } }
            require(rows.isNotEmpty()) { "Add complete observations in the selected columns." }
            return NumericMatrix(selected.map { data.columns[it] }, selected.map { column ->
                DoubleArray(rows.size) { Statistics.number(data, rows[it], column) }
            }, data.rows.size - rows.size)
        }
    }
}

internal data class StandardColumn(val maximum: Double, val mean: Double, val sd: Double, val z: DoubleArray) {
    val originalMean: Double get() = mean * maximum
    val originalSD: Double get() = sd * maximum
}

internal object MatrixMath {
    fun dot(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        var correction = 0.0
        for (i in a.indices) {
            val next = a[i] * b[i] - correction
            val total = sum + next
            correction = (total - sum) - next
            sum = total
        }
        return sum
    }

    fun sum(values: DoubleArray): Double = dot(values, DoubleArray(values.size) { 1.0 })

    fun standardize(values: DoubleArray): StandardColumn {
        val scale = values.maxOf { abs(it) }.takeIf { it > 0.0 } ?: 1.0
        val origin = values.first()
        val differences = DoubleArray(values.size) { values[it] - origin }
        // Remove a shared offset before division so small, representable differences survive.
        // Scaling first can lose those differences when the mean is much larger than the SD.
        val shifted = differences.all(Double::isFinite)
        val spread = if (shifted) differences.maxOf { abs(it) } else scale
        if (spread == 0.0) return StandardColumn(scale, origin / scale,
            if (values.size > 1) 0.0 else Double.NaN, DoubleArray(values.size))
        val normalized = DoubleArray(values.size) { if (shifted) differences[it] / spread else values[it] / scale }
        val mean = sum(normalized) / values.size
        val centered = DoubleArray(values.size) { normalized[it] - mean }
        val sd = sqrt(dot(centered, centered) / (values.size - 1))
        val z = if (sd > 0.0) DoubleArray(values.size) { centered[it] / sd } else DoubleArray(values.size)
        val ratio = spread / scale
        return StandardColumn(scale, if (shifted) origin / scale + mean * ratio else mean, sd * ratio, z)
    }

    fun correlation(columns: List<StandardColumn>): Array<DoubleArray> = Array(columns.size) { i ->
        DoubleArray(columns.size) { j -> if (i == j) 1.0 else
            (dot(columns[i].z, columns[j].z) / (columns[i].z.size - 1)).coerceIn(-1.0, 1.0) }
    }

    data class Eigen(val values: DoubleArray, val vectors: Array<DoubleArray>)

    /** Symmetric Jacobi eigensolver; columns of vectors are orthonormal eigenvectors. */
    fun eigen(matrix: Array<DoubleArray>): Eigen {
        val n = matrix.size
        require(n > 0 && matrix.all { it.size == n && it.all(Double::isFinite) })
        val a = Array(n) { matrix[it].copyOf() }
        val v = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
        val scale = a.maxOf { row -> row.maxOf { abs(it) } }
        if (scale == 0.0) return Eigen(DoubleArray(n), v)
        var converged = n == 1
        for (iteration in 0 until 100 * n * n) {
            var p = 0; var q = 0; var largest = 0.0
            for (i in 0 until n) for (j in i + 1 until n) if (abs(a[i][j]) > largest) {
                largest = abs(a[i][j]); p = i; q = j
            }
            if (largest <= scale * 2e-14) { converged = true; break }
            val tau = (a[q][q] - a[p][p]) / (2 * a[p][q])
            val t = (if (tau >= 0) 1.0 else -1.0) / (abs(tau) + Math.hypot(1.0, tau))
            val c = 1 / sqrt(1 + t * t)
            val s = t * c
            val off = a[p][q]
            a[p][p] -= t * off; a[q][q] += t * off
            a[p][q] = 0.0; a[q][p] = 0.0
            for (i in 0 until n) {
                if (i != p && i != q) {
                    val ap = a[i][p]; val aq = a[i][q]
                    a[i][p] = c * ap - s * aq; a[p][i] = a[i][p]
                    a[i][q] = s * ap + c * aq; a[q][i] = a[i][q]
                }
                val vp = v[i][p]; val vq = v[i][q]
                v[i][p] = c * vp - s * vq; v[i][q] = s * vp + c * vq
            }
        }
        require(converged) { "The covariance calculation did not converge. Check the selected variables." }
        val order = (0 until n).sortedByDescending { a[it][it] }
        return Eigen(DoubleArray(n) { a[order[it]][order[it]] }, Array(n) { i -> DoubleArray(n) { j -> v[i][order[j]] } })
    }

    data class QR(val q: List<DoubleArray>, val r: Array<DoubleArray>) {
        fun solve(y: DoubleArray): DoubleArray = backSolve(DoubleArray(q.size) { dot(q[it], y) })
        fun backSolve(rhs: DoubleArray): DoubleArray {
            val x = rhs.copyOf()
            for (i in x.lastIndex downTo 0) {
                for (j in i + 1..x.lastIndex) x[i] -= r[i][j] * x[j]
                x[i] /= r[i][i]
            }
            return x
        }
        fun gramInverse(): Array<DoubleArray> {
            val inverse = Array(r.size) { DoubleArray(r.size) }
            for (j in r.indices) {
                val column = backSolve(DoubleArray(r.size) { if (it == j) 1.0 else 0.0 })
                for (i in r.indices) inverse[i][j] = column[i]
            }
            return Array(r.size) { i -> DoubleArray(r.size) { j -> dot(inverse[i], inverse[j]) } }
        }
    }

    /** Twice-reorthogonalized QR for centered, standardized predictor columns. */
    fun qr(columns: List<DoubleArray>): QR {
        val q = mutableListOf<DoubleArray>()
        val r = Array(columns.size) { DoubleArray(columns.size) }
        columns.forEachIndexed { j, column ->
            val work = column.copyOf()
            repeat(2) {
                for (i in 0 until j) {
                    val projection = dot(q[i], work)
                    r[i][j] += projection
                    for (row in work.indices) work[row] -= projection * q[i][row]
                }
            }
            val norm = sqrt(dot(work, work))
            require(norm > 1e-10 * sqrt(dot(column, column))) {
                "Predictors are redundant or nearly redundant. Remove duplicate/derived predictors or one indicator from a complete category set."
            }
            r[j][j] = norm
            q.add(DoubleArray(work.size) { work[it] / norm })
        }
        return QR(q, r)
    }

    data class Optimum(val x: DoubleArray, val value: Double, val converged: Boolean)

    fun minimize(start: DoubleArray, objective: (DoubleArray) -> Pair<Double, DoubleArray>, gradientTolerance: Double = 2e-7): Optimum {
        val n = start.size
        var x = start.copyOf()
        var evaluation = objective(x)
        var h = Array(n) { i -> DoubleArray(n) { if (it == i) 1.0 else 0.0 } }
        repeat(600) {
            val (value, gradient) = evaluation
            if (gradient.maxOf { abs(it) } < gradientTolerance) return Optimum(x, value, true)
            var direction = DoubleArray(n) { i -> -dot(h[i], gradient) }
            if (dot(direction, gradient) >= 0 || direction.any { !it.isFinite() }) {
                h = Array(n) { i -> DoubleArray(n) { if (it == i) 1.0 else 0.0 } }
                direction = DoubleArray(n) { -gradient[it] }
            }
            val derivative = dot(direction, gradient)
            var step = 1.0
            var next = x
            var nextEvaluation = evaluation
            var accepted = false
            repeat(40) {
                if (!accepted) {
                    next = DoubleArray(n) { i -> x[i] + step * direction[i] }
                    nextEvaluation = objective(next)
                    if (nextEvaluation.first.isFinite() && nextEvaluation.first <= value + 1e-4 * step * derivative) accepted = true
                    else step *= 0.5
                }
            }
            if (!accepted) return Optimum(x, value, gradient.maxOf { abs(it) } < gradientTolerance * 100)
            val s = DoubleArray(n) { next[it] - x[it] }
            val y = DoubleArray(n) { nextEvaluation.second[it] - gradient[it] }
            val sy = dot(s, y)
            if (sy > 1e-14 * max(1e-30, sqrt(dot(s, s) * dot(y, y)))) {
                val hy = DoubleArray(n) { dot(h[it], y) }
                val factor = (1 + dot(y, hy) / sy) / sy
                for (i in 0 until n) for (j in 0 until n) h[i][j] += factor * s[i] * s[j] - (s[i] * hy[j] + hy[i] * s[j]) / sy
            } else h = Array(n) { i -> DoubleArray(n) { if (it == i) 1.0 else 0.0 } }
            x = next; evaluation = nextEvaluation
        }
        return Optimum(x, evaluation.first, false)
    }
}
