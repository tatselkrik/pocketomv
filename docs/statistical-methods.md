# Statistical methods

This guide describes pocketomv's statistical conventions and interpretation. All calculations run on the device.

## Normality

Shapiro–Wilk tests are available for one-sample, independent-samples and paired-samples t tests, one-way ANOVA and multiple-regression residuals. The one-sample check uses the selected sample; grouped analyses check each group separately. The paired check uses complete-pair differences. Regression checks fitted-model residuals.

The statistic and p-value follow Royston's AS R94 approximation, with the exact small-sample transformation for three observations. Supported sample size is 3–5,000. Constant samples and unsupported sample sizes produce an explanation instead of an invented result. Group normality p-values are unadjusted diagnostic values.

Values are normalized before calculation to limit loss of precision at very large or small scales. Normal quantiles and tail probabilities use Apache Commons Statistics. The coefficient and p-value approximations adapt SciPy's implementation; its [BSD license is included in the APK](../app/src/main/assets/third-party/scipy-LICENSE.txt).

## Homogeneity of variances

Independent-samples t tests and one-way ANOVA offer a median-centered Levene test, also called the Brown–Forsythe variant. It applies a one-way F test to absolute distances from each group's median. Its degrees of freedom are the number of groups minus one, and the included sample size minus the number of groups.

Each group needs at least two observations, with positive variation in absolute deviations within the groups collectively. An undefined check is reported as unavailable, while the valid main analysis remains visible.

A small diagnostic p-value is evidence against the tested assumption. A nonsignificant value does not establish that the assumption holds, especially in small samples. Independence is a property of the study design and cannot be established from these tests. These checks do not automatically select Student, Welch, a rank test or another analysis.

## ANOVA follow-up comparisons

Enable pairwise comparisons to compare every distinct pair of groups. The existing variance selection determines the method:

| Main analysis | Follow-up method |
| --- | --- |
| Ordinary ANOVA, equal variances | Tukey–Kramer |
| Welch ANOVA, unequal variances | Games–Howell |

Every row shows first group minus second group, its standard error, signed t statistic, degrees of freedom, adjusted two-sided p-value and a 95% interval using the same family adjustment. Tukey–Kramer uses the common ANOVA residual mean square; Games–Howell uses the two group variance estimates and Welch–Satterthwaite degrees of freedom. The interval multiplier is the 95th percentile of the studentized range divided by the square root of two. Games–Howell is an approximate procedure. Tukey–Kramer is exact for balanced normal, equal-variance groups and conservative for unequal sample sizes under those assumptions.

Adjusted probabilities account for the complete family of group pairs. Requested comparisons are displayed independently of the omnibus p-value. The screen shows up to 100 comparisons; a saved or copied text report includes every pair, up to the existing 40-group limit.

Adjusted probabilities and intervals use the studentized-range distribution. With two groups, the calculation reduces to the two-sided t distribution.

## References

- [jamovi one-way ANOVA options](https://docs.jamovi.org/jmv/jmv_anovaOneW.html)
- [SciPy Shapiro–Wilk documentation](https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.shapiro.html)
- [Royston, AS R94 (1995)](https://doi.org/10.2307/2986146)
- [SciPy median-centered Levene documentation](https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.levene.html)
- [Minitab multiple-comparison formulas](https://support.minitab.com/en-us/minitab/help-and-how-to/statistical-modeling/anova/how-to/one-way-anova/methods-and-formulas/multiple-comparisons/)
- [SciPy studentized-range definition](https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.studentized_range.html)

## Graphs

Graph options start unchecked. Graphs use the same selected values and missing-row rules as the main analysis. Descriptives weights affect only the weighted mean, so graphs show unweighted observations. Paired boxplots use both measurements from complete pairs; the paired t-test Q–Q plot uses first measurement minus second measurement. Wilcoxon boxplots retain complete pairs with zero differences, which are omitted only from the test's ranks.

### Boxplots

Quartiles use R type 7 linear interpolation, matching the app's descriptive percentiles. The box spans Q1 to Q3, with a line at the median. Whiskers extend to the most extreme observations within Q1 minus 1.5 IQR and Q3 plus 1.5 IQR, including values exactly on a fence. Points outside those fences are marked as outliers and remain in all calculations. These quartiles can differ from software using Tukey hinges for small samples.

Independent groups share a value axis and retain the analysis's group order. Sideways scrolling preserves readable group labels; the summary table includes full names, sample sizes, quartiles, whiskers and outlier counts. Constant samples collapse to a line.

### Normal Q–Q plots

Theoretical normal quantiles use the R `qqnorm`/`ppoints` convention: probability `(i − a) / (n + 1 − 2a)`, with i from 1 to n, a = 3/8 for n at most 10 and a = 1/2 otherwise. Sorted observed values are plotted against those quantiles. The reference line passes through the theoretical normal and observed R type 7 quartiles, matching the default `qqline` convention.

Each independent group has its own selectable plot. Fewer than three observations displays an explanation; constant samples plot as a horizontal line with a warning that normality cannot be assessed. Q–Q plots can display all 20,000 supported rows, independently of the Shapiro–Wilk sample-size limit. A roughly straight pattern supports compatibility with a normal distribution but does not establish normality or independence.

### Scatterplots

The first variable is X and the second is Y. Every complete pair is plotted, using original values for Pearson and Spearman. The regression plot adds the fitted least-squares line, computed in normalized coordinates to avoid overflow. No random sampling or jitter is applied; identical pairs share a marker. Axis normalization also handles finite data whose unscaled range would overflow.

Figures are displayed on screen and embedded as vectors in PDF/HTML reports. Text reports contain numerical summaries. Formatted reports include every requested Q–Q group, all points and complete frequency tables. Multiple-regression plots show original-unit residuals against fitted outcomes or theoretical normal quantiles. Repeated-measures boxes use the complete participants included in the analysis. Plot settings and the selected Q–Q group survive reopening.

- [NIST boxplots and outlier fences](https://www.itl.nist.gov/div898/handbook/eda/section3/boxplot.htm)
- [R quantile types](https://stat.ethz.ch/R-manual/R-patched/library/stats/html/quantile.html)
- [R normal Q–Q plots and quartile reference line](https://stat.ethz.ch/R-manual/R-devel/library/stats/html/qqnorm.html)
- [R plotting positions](https://stat.ethz.ch/R-manual/R-devel/library/stats/html/ppoints.html)

## Reliability

Select 2–60 numeric items and at least three complete participants. Missing rows are excluded listwise across selected items only. Alpha is `k / (k − 1) × (1 − sum(item variances) / variance(item sum))`; standardized alpha uses the correlation matrix. Negative alpha remains negative. Zero total variation makes raw alpha unavailable. Standardized alpha is unavailable when any selected item is constant. Item-rest correlations exclude that item from the raw total; alpha if removed uses the same complete participants rather than a different sample for each row.

Omega fits one congeneric factor by maximum likelihood to the item correlation matrix, with standardized loading λ and uniqueness `1 − λ²`. The covariance is `λ λ′ + diag(1 − λ²)`. Coefficients refer to an equally weighted sum: squared sum of loadings divided by that quantity plus summed uniqueness. Raw omega first scales loadings and uniqueness by item SDs; standardized omega uses unit item variances.

Omega requires at least three varying items, a positive-definite correlation matrix, a converged interior solution and at least three nontrivial loadings. Loadings may be negative. A global factor-sign orientation does not reverse any item. Unavailable estimates carry a reason. Alpha and omega do not establish validity, dimensionality or suitability for a diagnosis. No automatic cutoff or item removal is applied. The ML estimator is explicit and can differ from jamovi's default `psych::omega` estimator; this is not a claim of identical output for every jamovi module.

- [jamovi reliability calculations](https://github.com/jamovi/jmv/blob/master/R/reliability.b.R)
- [R maximum-likelihood factor analysis](https://stat.ethz.ch/R-manual/R-devel/library/stats/html/factanal.html)

## Multiple linear regression

The model is ordinary least squares with an intercept and 1–12 simultaneous quantitative predictors, using complete observations across outcome and predictors. There must be more than `predictors + 1` complete observations. Constant variables and nearly redundant predictor columns are rejected. Coefficients and fitted/residual values return to original units.

Residual degrees of freedom are `n − predictors − 1`. Reported statistics include R², adjusted R², residual RMSE, overall F, unstandardized B, standard errors from the inverse design Gram matrix, standardized β, two-sided t tests and 95% t intervals. VIF is the diagonal of the inverse predictor correlation matrix; tolerance is its reciprocal. Numerically perfect fits have no usable residual variation, so coefficient tests and intervals are unavailable. Their F is infinite and this condition is explained.

Optional Shapiro–Wilk and Q–Q plots diagnose residual distribution; the residuals-versus-fitted plot supports inspecting functional form and spread. They do not establish independence, equal variance, absence of influential cases or causal effects. Categorical predictors need indicator coding with a reference category; automatic coding, interaction generation and model selection are not performed.

- [R linear models](https://stat.ethz.ch/R-manual/R-devel/library/stats/html/lm.html)
- [R linear-model summaries](https://stat.ethz.ch/R-manual/R-devel/library/stats/html/summary.lm.html)
- [SciPy least-squares reference](https://docs.scipy.org/doc/scipy/reference/generated/scipy.linalg.lstsq.html)

## Repeated-measures ANOVA

One within-subject factor uses 2–12 condition columns in wide format and at least three complete participants. Participant identifiers are not conditions. All selected conditions and paired follow-ups use the same listwise sample. The condition sum of squares is `n × sum((condition mean − grand mean)²)`. Error removes participant and condition means. F divides condition MS by within-subject error MS, with uncorrected df `k − 1` and `(n − 1)(k − 1)`. Partial eta-squared is condition SS divided by condition SS plus error SS.

Orthonormal contrasts supply the within-subject covariance. Greenhouse–Geisser epsilon is squared trace divided by `(k − 1) × trace(covariance²)`, bounded from `1/(k − 1)` to 1. Huynh–Feldt is `(n(k − 1)εGG − 2) / ((k − 1)(n − 1 − (k − 1)εGG))`, capped at 1; an undefined estimate is unavailable. Corrections multiply both df by epsilon and leave F unchanged. The default is GG; a diagnostic never silently changes the selected correction.

Mauchly's W is the determinant of contrast covariance divided by its mean eigenvalue raised to `k − 1`. Its chi-square approximation uses the small-sample correction and second-order probability correction used in R's `mauchly.test`. Singular covariance or too few participants makes the diagnostic unavailable. Sphericity holds automatically for two conditions. A nonsignificant diagnostic does not prove sphericity. Participants must be independent and within-subject contrasts suitably distributed. This model does not include between-subject factors, multiple repeated factors, interactions or mixed effects.

Optional follow-ups are paired two-sided t tests. Holm-adjusted p-values control the planned pairwise family; Bonferroni 95% family confidence intervals use a different adjustment and are labeled separately. Zero-variance differences have no test or interval; those comparisons remain in the planned family count. Follow-ups are displayed independently of the omnibus p-value.

## Grouped descriptives and distribution shape

Select one or more variables and optionally one separate grouping column. Numeric or categorical interpretation applies to every selected variable. Each variable/group summary uses its own available observations. Missing values in another selected variable do not remove its valid observations. Blank group labels are excluded; literal `NA` group labels remain categories. Group order is alphabetical by stored label. An empty variable/group remains visible with N = 0 and unavailable statistics.

Let `m_j` be the jth central moment with divisor n. Adjusted sample skewness is `sqrt(n(n−1))/(n−2) × m_3/m_2^(3/2)`. Bias-corrected sample excess kurtosis is `(n−1)/((n−2)(n−3)) × ((n+1)(m_4/m_2² − 3) + 6)`. Skewness needs n ≥ 3, kurtosis n ≥ 4, and both need nonconstant values. The normal-distribution reference for excess kurtosis is zero. These are unweighted sample measures, independent of the sample/population variance and SD choice. See the [SciPy skewness](https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.skew.html) and [kurtosis definitions](https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.kurtosis.html).

Weighted means use only complete, positive-weight observations for the particular variable/group. A group with no positive weights retains its unweighted statistics with an explanation for the unavailable weighted mean. Histograms, percentiles, boxplots and Q–Q plots use that summary's unweighted values. Categorical bar charts use non-blank category counts, with a zero baseline and a common count scale within each summary. Charts show at most the first 20 categories in label order; frequency tables retain every category.

## Correlation matrices

Pearson r uses centered numeric values. Spearman rho correlates average ranks, recalculated after the pair's missing observations are removed. Pairwise deletion determines a sample for each pair; listwise deletion restricts every pair to the same rows complete across all selected variables. Unselected columns do not affect inclusion. Every matrix includes sample sizes. A pairwise matrix need not be positive semidefinite and should not automatically be used as a factor-analysis input.

Coefficients require at least two complete observations and variation in both variables. Inference requires at least three: `t = r sqrt((n−2)/(1−r²))` with n − 2 degrees of freedom, tested two-sided. This is a t approximation for Spearman, including tied ranks and small samples. P-values are unadjusted for testing multiple correlations. Constant variables and insufficient samples produce explained unavailable cells rather than zeros. Self-correlations have no p-value or interval.

Optional Pearson 95% intervals transform with `z = atanh(r)` and return `tanh(z ± Φ⁻¹(.975)/sqrt(n−3))`. They require at least four complete observations and use a large-sample approximation under independent, bivariate-normal sampling. At a computed coefficient of ±1 they collapse to that endpoint. Spearman intervals are not provided. See [Fisher-transform confidence intervals](https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats._result_classes.PearsonRResult.confidence_interval.html) and [jamovi's correlation-matrix options](https://docs.jamovi.org/jmv/jmv_corrMatrix.html). Correlation alone does not establish causation.

## Fisher's exact test and association measures

To bound exact-test runtime on a phone, the fixed margins may define at most 100,000 feasible tables: `1 + min(row total 1, row total 2, column total 1, column total 2)`. This limit is separate from the total-frequency limit. Very large totals can remain usable with narrow margins. Calculations outside either limit are rejected with an explanation; no approximate p-value is silently substituted.

Fisher's exact test supports a 2 × 2 table with positive row and column margins and a total frequency no greater than a signed 32-bit integer. Complete raw observations and collapsed whole-number frequencies share the same table calculations; zero-only categories do not expand the table. The test conditions on both margins. For a two-sided alternative, the p-value sums possible tables with probability no greater than the observed table. Directional alternatives use the corresponding hypergeometric tail. See [Apache Commons Statistics' exact-test definition](https://commons.apache.org/proper/commons-statistics/commons-statistics-inference/apidocs/org/apache/commons/statistics/inference/FisherExactTest.html).

The displayed sample odds ratio is `ad/bc`, rather than a conditional maximum-likelihood estimate. In the oriented table, `a` and `b` are event/non-event counts for the selected comparison group; `c` and `d` are event/non-event counts for the other, reference group. Relative risk is `[a/(a+b)]/[c/(c+d)]`. Reversing a group or event choice changes interpretation. Signed Phi is `(ad−bc)/sqrt((a+b)(c+d)(a+c)(b+d))`. Cramér's V is `sqrt(chi-square/[N min(r−1,c−1)])`, using the uncorrected Pearson statistic; it also remains available for larger tables.

Approximate odds-ratio intervals exponentiate `log(OR) ± z(.975) sqrt(1/a+1/b+1/c+1/d)`, with four positive cells. Katz relative-risk intervals exponentiate `log(RR) ± z(.975) sqrt(1/a−1/(a+b)+1/c−1/(c+d))`, with positive event counts in both groups. No continuity correction is inserted for zero cells. Defined boundary point estimates are retained, and unavailable intervals are explained. These intervals are large-sample approximations even when the hypothesis-test p-value is exact. See [sample versus conditional odds ratios](https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.contingency.odds_ratio.html) and [relative-risk orientation and intervals](https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.contingency.relative_risk.html). Interpret relative risks only when the sampling design supports comparing group event probabilities; association does not establish causation.

## Friedman and follow-up comparisons

Arrange 3–12 repeated conditions in wide format, with one row per participant and at least two complete participants. All selected conditions share one listwise complete sample, including summaries, mean ranks, plots and follow-ups. Rank each participant's conditions from low to high, assigning average ranks to ties. With rank sum `R_j`, participant count N and condition count k, the uncorrected statistic is `12 sum((R_j−N(k+1)/2)²)/(Nk(k+1))`. Divide it by `C = 1−sum(t³−t)/(N(k³−k))`, summing tied-run sizes t within each participant. The p-value uses a chi-square approximation with k−1 degrees of freedom. All-within-participant ties make the test undefined. Kendall's W is the corrected statistic divided by `N(k−1)`.

Durbin–Conover follow-ups use condition mean-rank differences and shared residual rank variance. If `r_ij` is a participant/condition rank, let `SSE = sum((r_ij−meanRank_j)²)` and `df = (N−1)(k−1)`. The standard error for a mean-rank difference is `sqrt(2 SSE/(N df))`; its t statistic has df degrees of freedom. Zero residual rank variance makes these follow-up t tests unavailable. P-values are two-sided. Holm is the default adjustment across all planned condition pairs; Bonferroni and unadjusted results are selectable. Every PDF section repeats both condition labels when a pair table is split into column groups.

The chi-square approximation can be inaccurate with small samples or few conditions. Participants must be independent, and measurements must permit meaningful within-participant ranking. A median/shift interpretation needs an appropriate model; nonparametric tests still have assumptions. Follow-ups are displayed independently of the omnibus p-value. See [NIST's Friedman and multiple-comparison definitions](https://www.itl.nist.gov/div898/software/dataplot/refman1/auxillar/friedman.htm), [SciPy's tied-rank statistic and approximation limits](https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.friedmanchisquare.html), and [jamovi's Friedman options](https://docs.jamovi.org/jmv/jmv_anovaRMNP.html).

- [R Mauchly sphericity test](https://github.com/wch/r-source/blob/trunk/src/library/stats/R/mlm.R)
- [Pingouin sphericity and epsilon formulas](https://github.com/raphaelvallat/pingouin/blob/master/src/pingouin/distribution.py)
- [R multiple-testing adjustments](https://stat.ethz.ch/R-manual/R-devel/library/stats/html/p.adjust.html)

## Factorial ANOVA and ANCOVA

Fixed-effects ordinary least squares fits a numeric outcome, an intercept and sum-to-zero factor contrasts. Factorial ANOVA accepts 2–3 categorical factors; ANCOVA accepts 1–3 factors and 1–6 quantitative covariates. Factor values, including numeric labels, define categories. Each factor needs 2–6 observed levels. Every term, mean, comparison and diagnostic uses the same complete-case sample. Covariates are centered at their complete-case means and enter with common linear slopes. Factor interactions are either all included, through the three-way term, or all omitted for an additive model.

The full design must be of full column rank, with more complete observations than parameters and usable residual variation. Empty cells in an interaction model, confounding, duplicate predictors and constant covariates may prevent fitting; no variable is silently dropped. Limits are 60 combinations of factor levels and 60 parameters. A calculation budget also bounds repeated model fits: `N × parameters² × (term count + 2) ≤ 150,000,000`, with each individual fit limited to `N × parameters² ≤ 40,000,000`.

The headline F compares the full model with an intercept-only model. The term table shows sums of squares, degrees of freedom, mean squares, F, p and partial η². All term F tests use the full model's residual mean square. Partial η² is `term SS / (term SS + residual SS)` for the selected sums-of-squares convention.

- **Type III (default):** test each term after all other terms, using sum contrasts. Main effects in an interaction model describe the corresponding averaged contrasts.
- **Type II:** test each term after terms that do not contain it as a higher-order relative. This respects the model hierarchy; interpret interactions before lower-order terms.
- **Type I:** add terms sequentially in the table's order: factor main effects, covariates, then interactions by order. Unbalanced designs can give different answers when factor or covariate order changes. Selection follows worksheet column order.

Types II/III term sums of squares need not add to the total. Residual sums, model R² and adjusted R² refer to the fitted full model regardless of term-test convention.

Estimated marginal means average predictions with equal weights across every combination of the other factor levels, with covariates held at their means. Their 95% t intervals are pointwise. Pairwise contrasts compare levels of each factor in that same marginal reference grid. Two-sided t tests use the full residual degrees of freedom. Holm (default), Bonferroni or unadjusted p-values apply to a single family containing all displayed pairs across all selected factors. These are comparisons of adjusted marginal means, not automatic simple-effects tests within individual cells.

Optional Shapiro–Wilk and Q–Q checks use model residuals. Median-centered Levene compares observed factor cells, using the same complete observations. The optional ANCOVA slope check jointly adds each fitted factor term × every covariate and compares the expanded model with the common-slope model. It uses the expanded model's residual variance and degrees of freedom. If that model is too large, redundant or lacks residual variation, the slope check is unavailable while the main result remains. It does not automatically change the main fitted model. Diagnostics cannot establish independence, causality, a correct mean model or that a nonsignificant assumption is satisfied.

See [jamovi's factorial ANOVA options](https://docs.jamovi.org/jmv/jmv_ANOVA.html), [ANCOVA options](https://docs.jamovi.org/jmv/jmv_ancova.html) and [statsmodels' sums-of-squares definitions](https://www.statsmodels.org/stable/generated/statsmodels.stats.anova.anova_lm.html). Available options and statistical conventions are specified here; support does not imply identical results for every jamovi configuration.

## Exploratory factor analysis

EFA fits a common-factor model to the Pearson correlation matrix of 3–20 varying numeric items. It uses listwise complete observations, with `N ≥ max(10, items + 1)`. This minimum is a calculation limit, not evidence of adequate sampling. Constant items and singular or nearly singular correlation matrices are rejected. Ordinal/polychoric analysis, factor scores and maximum-likelihood fit tests are not included.

**MINRES** minimizes the squared residual matrix after replacing each correlation diagonal with `1 − uniqueness` and retaining the leading common-factor eigenvectors. Uniqueness is constrained to [0.005, 1]. The model is optimized from squared-multiple-correlation and common starting values. **Principal axis factoring** starts from squared multiple correlations and iterates communalities to convergence, rejecting zero/negative uniqueness solutions. Both estimate common factors; PCA loadings are not substituted. Nonconvergence and invalid communalities produce an explanation. A MINRES uniqueness at its lower bound is explicitly noted.

**Varimax** uses Kaiser row normalization and orthogonal plane rotations. **Promax** starts from the Kaiser-normalized Varimax solution and a power-4 target. It permits correlated factors and reports pattern coefficients, structure correlations and factor correlations. Structure is `pattern × factor correlations`; communality is the diagonal of `pattern × factor correlations × pattern transpose`. Uniqueness is one minus communality. With no rotation or Varimax the factor correlation matrix is the identity. Factor signs and ordering are arbitrary and standardized for readability. The loading threshold affects display only. The common-variance summary describes the orthogonal extraction before rotation; correlated factors are not independent portions of explained variance.

Factor count can be fixed, based on correlation eigenvalues strictly greater than a chosen threshold, or suggested by parallel analysis. Parallel analysis compares leading ordered observed correlation eigenvalues with 95th-percentile thresholds from 200 independent Gaussian null correlation matrices of the same N and item count. It stops retention at the first failed comparison. A fixed seed, 20261109, makes results repeatable. Exact Wishart sampling with `N−1` degrees of freedom generates the null sample correlations without repeatedly allocating an N-by-items matrix. These are PCA-eigenvalue thresholds; jamovi's default common-factor parallel procedure can select a different count.

Counts are capped at six and must have nonnegative nominal model degrees of freedom: `p(p+1)/2 − [pm + p − m(m−1)/2] ≥ 0`. This condition alone does not establish identification in a particular dataset. Zero suggestions retain the eigenvalue/adequacy outputs without forcing a factor. An automatically suggested count beyond the supported limit is reported without silently truncating it; choose a smaller fixed count explicitly if justified.

KMO and each item's MSA compare squared correlations with squared partial correlations from the inverse correlation matrix. They are unavailable when both sums are zero. Bartlett's sphericity statistic is `−[N−1−(2p+5)/6] log(det(R))`, compared with chi-square on `p(p−1)/2` degrees of freedom. Its approximation assumes multivariate normality. The residual correlation RMSR is the root mean squared off-diagonal difference between observed and reproduced correlations. It is descriptive, not a significance test. KMO, Bartlett, a scree pattern and good residual fit do not establish validity, reliability or a suitable theoretical model. Items are never automatically reversed, removed or imputed.

See [jamovi's EFA options](https://docs.jamovi.org/jmv/jmv_efa.html), [the factor-analyzer method definitions](https://factor-analyzer.readthedocs.io/en/latest/factor_analyzer.html) and [UCLA's factor-analysis guide](https://stats.oarc.ucla.edu/spss/seminars/efa-spss/).
