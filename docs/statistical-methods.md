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

- [R Mauchly sphericity test](https://github.com/wch/r-source/blob/trunk/src/library/stats/R/mlm.R)
- [Pingouin sphericity and epsilon formulas](https://github.com/raphaelvallat/pingouin/blob/master/src/pingouin/distribution.py)
- [R multiple-testing adjustments](https://stat.ethz.ch/R-manual/R-devel/library/stats/html/p.adjust.html)
