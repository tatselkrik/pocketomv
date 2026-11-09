package io.github.tatselkrik.statis130lite

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
internal fun CorrelationOptions(data: CourseData, request: AnalysisRequest, enabled: Boolean, onChange: (AnalysisRequest) -> Unit) {
    ColumnSelection("Variables", data, request.correlationVariables, data.columns.indices.toList(), 20,
        AnalysisRequest.forData(AnalysisMethod.CORRELATION_MATRIX, data).correlationVariables, enabled) {
        onChange(request.copy(correlationVariables = it))
    }
    ChoiceField("Correlation", request.correlationKind, CorrelationKind.entries.map { it to it.label }, enabled) {
        onChange(request.copy(correlationKind = it, correlationConfidence = request.correlationConfidence && it == CorrelationKind.PEARSON))
    }
    ChoiceField("Missing values", request.correlationMissing, CorrelationMissing.entries.map { it to it.label }, enabled) {
        onChange(request.copy(correlationMissing = it))
    }
    CheckOption("95% Pearson confidence intervals", request.correlationConfidence,
        enabled && request.correlationKind == CorrelationKind.PEARSON) { onChange(request.copy(correlationConfidence = it)) }
    Text(if (request.correlationKind == CorrelationKind.PEARSON)
        "Intervals use Fisher's z approximation and need at least four complete observations per pair."
        else "Spearman uses average ranks within each pair and a t approximation for p-values. Confidence intervals are not provided.",
        style = MaterialTheme.typography.bodySmall)
    Text("Results include coefficients, unadjusted two-sided p-values and sample sizes for every pair. Constant variables retain an explained blank result.",
        style = MaterialTheme.typography.bodySmall)
}
