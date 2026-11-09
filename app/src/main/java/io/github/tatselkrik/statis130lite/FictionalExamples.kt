package io.github.tatselkrik.statis130lite

/** Fictional practice datasets for the supported analyses. */
fun fictionalExample(method: AnalysisMethod, descriptiveNumeric: Boolean = true): CourseData {
    val text = when (method) {
        AnalysisMethod.DESCRIPTIVES -> if (descriptiveNumeric) "Score,Weight,Follow-up,Group\n1,1,3,A\n2,2,4,A\n2,1,3,A\n4,3,5,B\n5,3,8,B"
            else "Category,Group\nA,First\nA,First\nB,Second\nC,Second\nC,Second"
        AnalysisMethod.ONE_SAMPLE_T -> "Score\n4\n6\n7\n9\n10\n13\n14"
        AnalysisMethod.PAIRED_T, AnalysisMethod.WILCOXON ->
            "Before,After\n4,2\n6,5\n7,5\n9,8\n10,9\n13,11\n14,12"
        AnalysisMethod.INDEPENDENT_T, AnalysisMethod.MANN_WHITNEY ->
            "Group,Score\nA,4\nA,6\nA,7\nA,9\nA,10\nA,13\nA,14\nB,2\nB,5\nB,5\nB,8\nB,9\nB,11"
        AnalysisMethod.ANOVA, AnalysisMethod.KRUSKAL_WALLIS ->
            "Group,Score\nA,4\nA,7\nA,8\nA,10\nB,5\nB,6\nB,10\nB,12\nB,14\nC,9\nC,11\nC,13\nC,15"
        AnalysisMethod.CHI_GOODNESS -> "Category,Count\nA,18\nB,27\nC,15"
        AnalysisMethod.CHI_INDEPENDENCE ->
            "Group,Response,Count\nA,X,12\nA,Y,8\nA,Z,10\nB,X,7\nB,Y,15\nB,Z,13"
        AnalysisMethod.FISHER_EXACT -> "Group,Response,Count\nA,Event,1\nA,Other,9\nB,Event,11\nB,Other,3"
        AnalysisMethod.PEARSON, AnalysisMethod.SPEARMAN, AnalysisMethod.REGRESSION ->
            "Practice X,Practice Y\n1,3\n2,1\n3,4\n4,3\n5,6\n6,5\n7,7\n8,8"
        AnalysisMethod.CORRELATION_MATRIX -> "Practice X,Practice Y,Practice Z\n1,3,8\n2,1,6\n3,4,7\n4,3,4\n5,6,5\n6,5,2\n7,7,3\n8,8,1"
        AnalysisMethod.RELIABILITY -> reliabilityExample().asCsv()
        AnalysisMethod.MULTIPLE_REGRESSION -> "Outcome,X1,X2\n2.4,1,3\n3.2,2,4\n7.1,3,1\n5.3,4,6\n8.8,5,2\n8.2,6,5\n13.2,7,1\n9.4,8,8\n13.4,9,4\n15.1,10,3\n14.1,11,7\n18.1,12,4"
        AnalysisMethod.REPEATED_ANOVA -> "ID,Before,Middle,After\n1,4,5,6\n2,6,5,8\n3,7,8,8\n4,9,11,12\n5,10,9,13\n6,13,15,16\n7,14,14,17"
        AnalysisMethod.FRIEDMAN -> "ID,Before,Middle,After\n1,4,5,6\n2,6,5,8\n3,7,8,8\n4,9,11,12\n5,10,9,13\n6,13,15,16\n7,14,14,17"
        AnalysisMethod.FACTORIAL_ANOVA, AnalysisMethod.ANCOVA -> factorialExample().asCsv()
        AnalysisMethod.EFA -> factorExample().asCsv()
    }
    return parseDelimited(text)
}

private fun factorialExample(): CourseData = CourseData(listOf("Outcome", "Teaching", "Practice", "Baseline"), List(32) { row ->
    val a = row / 16; val b = row / 8 % 2; val i = row % 8
    val baseline = 4 + i * 0.7 + a * 0.4 - b * 0.3
    val noise = listOf(-0.8, 1.1, -0.4, 0.6, -1.3, 0.2, 1.0, -0.5)[i]
    listOf((10 + 2 * a + 1.5 * b + 1.2 * a * b + 0.9 * baseline + noise).toString(),
        if (a == 0) "Standard" else "Guided", if (b == 0) "Short" else "Extended", baseline.toString())
})

private fun factorExample(): CourseData = CourseData(List(6) { "Item ${it + 1}" }, List(120) { row -> List(6) { item ->
    val phase = 2 * kotlin.math.PI * (row + 0.5) / 120
    val first = kotlin.math.sqrt(2.0) * kotlin.math.cos(phase)
    val second = 0.3 * first + kotlin.math.sqrt(2 * (1 - 0.3 * 0.3)) * kotlin.math.sin(phase)
    val loading = listOf(0.8, 0.75, 0.7, 0.85, 0.72, 0.78)[item]
    val unique = kotlin.math.sqrt(2.0) * kotlin.math.cos((item + 2) * phase)
    (3 + loading * (if (item < 3) first else second) + kotlin.math.sqrt(1 - loading * loading) * unique).toString()
} })

private fun reliabilityExample(): CourseData {
    val loadings = listOf(0.7, 0.75, 0.8, 0.65)
    val scales = listOf(1.0, 1.1, 0.9, 1.2)
    return CourseData(List(4) { "Item ${it + 1}" }, List(24) { row -> List(4) { i ->
        val phase = 2 * kotlin.math.PI * (row + 0.5) / 24
        (3 + scales[i] * kotlin.math.sqrt(2.0) * (loadings[i] * kotlin.math.cos(phase) +
            kotlin.math.sqrt(1 - loadings[i] * loadings[i]) * kotlin.math.cos((i + 2) * phase))).toString()
    } })
}
