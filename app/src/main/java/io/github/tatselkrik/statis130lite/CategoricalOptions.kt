package io.github.tatselkrik.statis130lite

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
internal fun CategoricalOptions(data: CourseData, request: AnalysisRequest, enabled: Boolean, onChange: (AnalysisRequest) -> Unit) {
    val order = remember(data, request.first, request.second, request.count) {
        runCatching { Statistics.contingencyCategoryOrder(data, request) }.getOrNull()
    }
    if (order != null && order.first.size == 2 && order.second.size == 2) {
        fun choices(current: String, labels: List<String>): List<Pair<String, String>> =
            (if (current.isNotEmpty() && current !in labels) listOf(current to "Unavailable · $current") else emptyList()) + labels.map { it to it }
        ChoiceField("Comparison group", request.comparisonGroup.ifEmpty { order.first.first() },
            choices(request.comparisonGroup, order.first), enabled) { onChange(request.copy(comparisonGroup = it)) }
        ChoiceField("Event category", request.eventCategory.ifEmpty { order.second.first() },
            choices(request.eventCategory, order.second), enabled) { onChange(request.copy(eventCategory = it)) }
        Text("The other row category is the reference group. Odds and risk ratios compare the selected event between these groups; reversing a choice changes their interpretation.", style = MaterialTheme.typography.bodySmall)
        Text("Results include Cramér's V, signed Phi, a sample odds ratio and relative risk. 95% ratio intervals use log-Wald approximations where defined; no automatic zero-cell correction is applied.", style = MaterialTheme.typography.bodySmall)
    } else Text(if (request.method == AnalysisMethod.FISHER_EXACT)
        "Select variables forming exactly two nonempty row categories and two nonempty column categories. The exact test supports a total frequency up to 2,147,483,647."
        else "Cramér's V describes association for the supported table sizes. Phi, odds ratios and relative risks are added for 2 × 2 tables.",
        style = MaterialTheme.typography.bodySmall)
}
