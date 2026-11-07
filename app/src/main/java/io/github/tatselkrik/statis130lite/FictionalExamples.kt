package io.github.tatselkrik.statis130lite

/** Fictional practice datasets for the supported analyses. */
fun fictionalExample(method: AnalysisMethod): CourseData {
    val text = when (method) {
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
        AnalysisMethod.PEARSON, AnalysisMethod.SPEARMAN, AnalysisMethod.REGRESSION ->
            "Practice X,Practice Y\n1,3\n2,1\n3,4\n4,3\n5,6\n6,5\n7,7\n8,8"
    }
    return parseDelimited(text)
}
