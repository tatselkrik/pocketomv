package io.github.tatselkrik.statis130lite

import java.math.BigDecimal
import java.math.MathContext
import org.apache.commons.statistics.distribution.FDistribution
import org.apache.commons.statistics.distribution.TDistribution
import kotlin.math.abs
import kotlin.math.sqrt

internal data class RegressionCoefficient(val b: Double, val se: Double?, val beta: Double?, val t: Double?, val p: Double?, val lower: Double?, val upper: Double?)
internal data class MultipleFit(val coefficients: List<RegressionCoefficient>, val rSquared: Double, val adjustedR: Double,
    val f: Double, val p: Double, val df: Int, val rmse: Double, val vif: List<Double>,
    val fitted: DoubleArray, val residuals: DoubleArray, val residualZ: DoubleArray, val perfect: Boolean)

internal object MultipleRegression {
    private fun scaled(value: Double, numerator: StandardColumn, denominator: StandardColumn? = null): Double {
        var decimal = BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(numerator.sd)).multiply(BigDecimal.valueOf(numerator.maximum))
        if (denominator != null) decimal = decimal.divide(BigDecimal.valueOf(denominator.sd).multiply(BigDecimal.valueOf(denominator.maximum)), MathContext.DECIMAL128)
        return decimal.toDouble().also { require(it.isFinite()) { "Regression estimates exceed the supported range. Rescale the variables." } }
    }

    fun compute(matrix: NumericMatrix): MultipleFit {
        val k = matrix.k - 1
        require(k in 1..12 && matrix.n > k + 1) { "Select 1–12 predictors and more than predictors + 1 complete observations." }
        val columns = matrix.columns.map(MatrixMath::standardize)
        require(columns.all { it.sd > 0 }) { "The outcome and each predictor need variation. Remove constant variables." }
        val y = columns.first()
        val x = columns.drop(1)
        val qr = MatrixMath.qr(x.map { it.z })
        val beta = qr.solve(y.z)
        val prediction = DoubleArray(matrix.n) { row -> MatrixMath.sum(DoubleArray(k) { beta[it] * x[it].z[row] }) }
        val residual = DoubleArray(matrix.n) { y.z[it] - prediction[it] }
        val rawSse = MatrixMath.dot(residual, residual)
        val perfect = rawSse <= 1e-24 * (matrix.n - 1)
        if (perfect) residual.fill(0.0)
        val sse = if (perfect) 0.0 else rawSse
        val ssr = MatrixMath.dot(prediction, prediction)
        val df = matrix.n - k - 1
        val mse = sse / df
        val f = if (sse == 0.0) Double.POSITIVE_INFINITY else (ssr / k) / mse
        val p = if (f.isInfinite()) 0.0 else FDistribution.of(k.toDouble(), df.toDouble()).survivalProbability(f)
        val r2 = (ssr / (ssr + sse)).coerceIn(0.0, 1.0)
        val inverse = qr.gramInverse()
        val distribution = TDistribution.of(df.toDouble())
        val critical = distribution.inverseCumulativeProbability(0.975)
        val mx = DoubleArray(k) { x[it].mean / x[it].sd }
        val intercept = y.mean / y.sd - MatrixMath.dot(beta, mx)
        val interceptFactor = 1.0 / matrix.n + MatrixMath.dot(mx, DoubleArray(k) { MatrixMath.dot(inverse[it], mx) })
        val coefficients = (0..k).map { i ->
            val normalizedB = if (i == 0) intercept else beta[i - 1]
            val b = scaled(normalizedB, y, if (i == 0) null else x[i - 1])
            if (perfect) RegressionCoefficient(b, 0.0, if (i == 0) null else beta[i - 1], null, null, null, null)
            else {
                val factor = if (i == 0) interceptFactor else inverse[i - 1][i - 1]
                val se = scaled(sqrt(mse * factor.coerceAtLeast(0.0)), y, if (i == 0) null else x[i - 1])
                require(se > 0.0) { "Coefficient precision is outside the supported range. Rescale the variables." }
                val t = b / se
                val probability = (2 * distribution.survivalProbability(abs(t))).coerceIn(0.0, 1.0)
                val low = b - critical * se; val high = b + critical * se
                require(low.isFinite() && high.isFinite()) { "Coefficient intervals exceed the supported range. Rescale the variables." }
                RegressionCoefficient(b, se, if (i == 0) null else beta[i - 1], t, probability, low, high)
            }
        }
        val fitted = DoubleArray(matrix.n) { (y.mean + y.sd * prediction[it]) * y.maximum }
        val residuals = DoubleArray(matrix.n) { y.sd * residual[it] * y.maximum }
        require(fitted.all(Double::isFinite) && residuals.all(Double::isFinite)) { "Predictions exceed the supported range. Rescale the outcome." }
        return MultipleFit(coefficients, r2, 1 - (1 - r2) * (matrix.n - 1) / df, f, p, df,
            scaled(sqrt(mse), y), inverse.indices.map { ((matrix.n - 1) * inverse[it][it]).coerceAtLeast(1.0) },
            fitted, residuals, residual, perfect)
    }

    fun run(data: CourseData, request: AnalysisRequest): AnalysisResult {
        require(request.predictors.isNotEmpty() && request.first !in request.predictors) { "Choose an outcome and separate predictor columns." }
        val matrix = NumericMatrix.read(data, listOf(request.first) + request.predictors, 2, 13)
        val fit = compute(matrix)
        fun value(x: Double?) = x?.let(::formatStatistic) ?: "—"
        val tables = listOf(AnalysisTable("Model fit", listOf("Measure", "Value"), listOf(
            listOf("Complete observations", matrix.n.toString()), listOf("Predictors", request.predictors.size.toString()),
            listOf("R²", formatStatistic(fit.rSquared)), listOf("Adjusted R²", formatStatistic(fit.adjustedR)),
            listOf("Residual RMSE", formatStatistic(fit.rmse)),
        )), AnalysisTable("Regression coefficients", listOf("Term", "B", "SE", "Standardized β", "t", "df", "p", "95% CI"),
            fit.coefficients.mapIndexed { i, c -> listOf(if (i == 0) "Intercept" else matrix.labels[i],
                value(c.b), value(c.se), value(c.beta), value(c.t), fit.df.toString(), c.p?.let(::formatProbability) ?: "—",
                if (c.lower == null) "—" else "[${value(c.lower)}, ${value(c.upper)}]") }),
            AnalysisTable("Collinearity", listOf("Predictor", "VIF", "Tolerance"), fit.vif.mapIndexed { i, vif ->
                listOf(matrix.labels[i + 1], formatStatistic(vif), formatStatistic(1 / vif))
            }))
        val plots = if (request.showQQPlot || request.showScatterplot) AnalysisPlots(
            qq = if (request.showQQPlot) listOf(PlotCalculator.qq("Model residuals", fit.residuals)) else emptyList(),
            scatter = if (request.showScatterplot) ScatterPlot("Fitted ${matrix.labels.first()}", "Residual",
                fit.fitted.indices.map { PlotPoint(fit.fitted[it], fit.residuals[it]) }, null) else null,
        ) else null
        return AnalysisResult(request.method, "Ordinary least squares · simultaneous predictors and intercept", TestResult("F", fit.f, fit.p),
            matrix.n.toLong(), matrix.excluded, listOf(request.predictors.size.toDouble(), fit.df.toDouble()), tables = tables, plots = plots,
            assumptionChecks = if (request.checkNormality) listOf(MeanDiagnostics.normality("Model residuals", fit.residualZ)) else emptyList(),
            notes = buildList {
                add("Uses complete cases across the outcome and all selected predictors. Coefficient tests are two-sided; intervals are 95% t intervals.")
                add("Predictors enter together. Numeric predictors are treated as quantitative; code categorical predictors into appropriate indicators with a reference category first.")
                add("Residual graphs and Shapiro–Wilk are diagnostics. They do not establish linearity, equal error variance, independence or causal effects. Inspect study design and influential observations.")
                add("VIF quantifies predictor collinearity. Redundant or numerically indistinguishable predictors are rejected instead of being silently removed.")
                if (fit.perfect) add("A numerically perfect fit has no usable residual variation. Coefficient tests and intervals are unavailable; the model F is infinite.")
            })
    }
}
