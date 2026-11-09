package io.github.tatselkrik.statis130lite

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType

@Composable
internal fun FactorialOptions(data: CourseData, request: AnalysisRequest, enabled: Boolean, onChange: (AnalysisRequest) -> Unit) {
    val suggested = AnalysisRequest.forData(request.method, data)
    ColumnSelection("Categorical factors", data, request.factors, data.columns.indices.filter { it != request.first }, 3,
        suggested.factors.filter { it != request.first }, enabled) {
        onChange(request.copy(factors = it, covariates = request.covariates - it.toSet()))
    }
    Text("Select ${if (request.method == AnalysisMethod.ANCOVA) "1–3" else "2–3"} factors with 2–6 levels each. Numeric factor labels are categories. One row represents one independent observation.", style = MaterialTheme.typography.bodySmall)
    if (request.method == AnalysisMethod.ANCOVA) {
        ColumnSelection("Numeric covariates", data, request.covariates,
            data.columns.indices.filter { it != request.first && it !in request.factors }, 6,
            suggested.covariates.filter { it != request.first && it !in request.factors }, enabled) { onChange(request.copy(covariates = it)) }
        Text("Covariates enter with common linear slopes. All terms and diagnostics use the same complete observations.", style = MaterialTheme.typography.bodySmall)
        CheckOption("Check homogeneity of regression slopes", request.checkSlopes, enabled) { onChange(request.copy(checkSlopes = it)) }
    }
    CheckOption("Include factor interactions", request.factorInteractions, enabled) { onChange(request.copy(factorInteractions = it)) }
    Text("Includes every interaction among the selected factors, including the three-way interaction with three factors. Clear for an additive model.", style = MaterialTheme.typography.bodySmall)
    ChoiceField("Sums of squares", request.sumsOfSquares, SumsOfSquares.entries.map { it to it.label }, enabled) { onChange(request.copy(sumsOfSquares = it)) }
    CheckOption("Estimated marginal means", request.modelMarginalMeans, enabled) { onChange(request.copy(modelMarginalMeans = it)) }
    CheckOption("Compare marginal means", request.modelComparisons, enabled) { onChange(request.copy(modelComparisons = it)) }
    if (request.modelComparisons) ChoiceField("Comparison p-value adjustment", request.modelAdjustment,
        FriedmanAdjustment.entries.map { it to it.label }, enabled) { onChange(request.copy(modelAdjustment = it)) }
    Text("Marginal means give the other factor levels equal weight and hold covariates at their means. Interpret interactions before averaged main effects. Comparison adjustment covers every displayed pair together.", style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun FactorAnalysisOptions(data: CourseData, request: AnalysisRequest, enabled: Boolean, onChange: (AnalysisRequest) -> Unit) {
    ColumnSelection("EFA variables", data, request.items, data.columns.indices.toList(), 20,
        AnalysisRequest.forData(request.method, data).items, enabled) { onChange(request.copy(items = it)) }
    Text("Select 3–20 varying numeric items. Leave IDs and previously calculated total scores unselected. Uses complete participants and Pearson correlations.", style = MaterialTheme.typography.bodySmall)
    ChoiceField("Factor count", request.factorCountMethod, FactorCountMethod.entries.map { it to it.label }, enabled) { onChange(request.copy(factorCountMethod = it)) }
    if (request.factorCountMethod == FactorCountMethod.FIXED) FactorNumberField("Number of factors", request.factorCount, enabled, false) { onChange(request.copy(factorCount = it)) }
    if (request.factorCountMethod == FactorCountMethod.EIGENVALUE) FactorNumberField("Minimum eigenvalue (strictly greater)", request.factorEigenvalue, enabled) { onChange(request.copy(factorEigenvalue = it)) }
    if (request.factorCountMethod == FactorCountMethod.PARALLEL) Text("200 Gaussian null correlation matrices, 95th-percentile thresholds and a fixed seed. This PCA-eigenvalue retention rule can differ from jamovi's common-factor parallel analysis. A zero-factor suggestion is retained.", style = MaterialTheme.typography.bodySmall)
    ChoiceField("Extraction", request.factorExtraction, FactorExtraction.entries.map { it to it.label }, enabled) { onChange(request.copy(factorExtraction = it)) }
    ChoiceField("Rotation", request.factorRotation, FactorRotation.entries.map { it to it.label }, enabled) { onChange(request.copy(factorRotation = it)) }
    FactorNumberField("Hide absolute loadings below", request.factorLoadingCutoff, enabled) { onChange(request.copy(factorLoadingCutoff = it)) }
    CheckOption("Sort loadings by factor and size", request.factorSortLoadings, enabled) { onChange(request.copy(factorSortLoadings = it)) }
    CheckOption("Scree plot", request.factorScreePlot, enabled) { onChange(request.copy(factorScreePlot = it)) }
    CheckOption("KMO and individual MSA", request.factorKmo, enabled) { onChange(request.copy(factorKmo = it)) }
    CheckOption("Bartlett's sphericity test", request.factorBartlett, enabled) { onChange(request.copy(factorBartlett = it)) }
    Text("Varimax keeps factors uncorrelated; Promax allows correlations and reports both pattern and structure matrices. These analyses do not establish questionnaire validity.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun FactorNumberField(label: String, value: String, enabled: Boolean, decimal: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(value, { if (it.length <= 32) onChange(it) }, Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number))
}
