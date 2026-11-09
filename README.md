<h1 align="center">pocketomv 📊</h1>

<p align="center">
  <img src="https://img.shields.io/badge/version-0.2.0-006b5e" alt="Version 0.2.0">
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&amp;logoColor=white" alt="Android 8.0 or newer">
  <img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&amp;logoColor=white" alt="Kotlin and Jetpack Compose">
  <img src="https://img.shields.io/badge/repository-private-172d28" alt="Private repository">
</p>

<p align="center">
  An offline Android app for statistics classes. Enter or import data, explore distributions, run common statistical tests, and export clear results.
</p>

<p align="center">
  <strong>Data → Analyze → Results</strong>
</p>

## Overview

pocketomv brings common classroom analyses to a phone. Enter a table, choose a worksheet from an Excel workbook, or open a CSV/TSV file. Select an analysis and its variables, then review statistics, distributions, available confidence intervals and method notes. Copy the report or save it as text, HTML or PDF.

Current work saves automatically in private device storage. The app restores applied data, unfinished edits, analysis options and the current screen when reopened, and recalculates saved results.

## Features

- Classroom analyses covering descriptives, internal consistency, comparisons of means and ranks, frequencies, correlation and regression.
- Mean, optional weighted mean, median, mode, sample/population variance and standard deviation, and custom percentiles.
- Numeric or categorical frequency tables, plus numeric histograms with automatic or custom intervals.
- Optional boxplots, normal Q–Q plots and scatterplots, with a fitted line for simple regression.
- Separate Data, Analyze and Results screens.
- Data entry with optional column headings, direct Excel `.xlsx` import with worksheet selection, and CSV/TSV import.
- A cell grid with row/column editing, one-step undo, recoding, reverse scoring and item sum/mean scores.
- Student and Welch options for independent t tests, plus ordinary and Welch one-way ANOVA.
- Optional Shapiro–Wilk normality checks for t tests and one-way ANOVA, plus median-based Levene variance checks for independent groups.
- ANOVA pairwise comparisons with Tukey–Kramer or Games–Howell, adjusted p-values and 95% intervals.
- Reliability with raw/standardized Cronbach's alpha, one-factor McDonald's omega, item-rest correlations and alpha if an item is removed.
- Multiple linear regression with 1–12 numeric predictors, coefficient intervals, standardized coefficients, VIF and optional residual diagnostics.
- Repeated-measures ANOVA for one within-subject factor, with sphericity corrections and optional paired follow-up comparisons.
- Directional alternatives for supported tests and custom expected weights for goodness of fit.
- Explicit handling of missing values, tied ranks, zero paired differences and frequency counts.
- Descriptive statistics, available 95% confidence intervals and calculation notes.
- A fictional example for every analysis.
- Automatic work recovery with visible saving status and Retry after a failed save.
- CSV data export, report copying, text reports and self-contained PDF/HTML reports with tables and figures.
- Portrait, landscape, larger text and dark mode.

## Supported analyses

| Area | Analyses |
| --- | --- |
| Descriptives | Numeric summaries, percentiles, frequencies and histograms; category counts, percentages and mode |
| Means | One-sample, independent-samples and paired-samples t tests |
| Group means | One-way ANOVA; one-factor repeated-measures ANOVA |
| Ranks | Mann–Whitney U, Wilcoxon signed-rank and Kruskal–Wallis H |
| Frequencies | Chi-square goodness of fit and independence |
| Association | Pearson and Spearman correlation |
| Prediction | Simple and multiple linear regression |
| Reliability | Cronbach's alpha, one-factor McDonald's omega and item diagnostics |

Mann–Whitney and Wilcoxon use exact probabilities for eligible small samples and corrected approximations otherwise. Zero paired differences are removed before Wilcoxon ranks are assigned. Spearman p-values use a labeled t approximation. Method notes identify the calculation used for each result.

### Assumption checks and ANOVA follow-ups

For a t test or one-way ANOVA, enable **Shapiro–Wilk normality** in **Analyze** to check the selected sample, each independent group, or the differences within pairs. Independent-samples t tests and ANOVA also offer **Levene variance check**, centered on group medians. Undefined checks display an explanation while retaining the main analysis.

For ANOVA, enable **pairwise comparisons** to identify differences between individual groups. The variance choice selects **Tukey–Kramer** for ordinary equal-variance ANOVA or **Games–Howell** for Welch ANOVA. Results include signed mean differences, standard errors, t statistics, degrees of freedom, adjusted p-values and 95% intervals. Reports include all comparisons; the screen shows up to 100.

These options start unchecked. Diagnostic p-values do not automatically change the main analysis, and a nonsignificant check does not prove an assumption. Independence depends on study design. See [calculation details, interpretation and sources](docs/statistical-methods.md).

<p align="center">
  <img src="docs/screenshots/assumption-options.png" alt="Optional normality, variance and ANOVA pairwise checks in pocketomv" width="300">
</p>

### Descriptives

Choose **Descriptives** in **Analyze**, select a variable, and choose **Numeric** or **Categories / text**. Numeric data supports an optional weight column, sample or population variance and standard deviation, custom percentiles, and 1–50 histogram intervals. Leave the interval count blank for automatic selection.

- Weights affect **only the weighted mean**. Other statistics, frequencies and histogram bars count each non-missing row once. Missing and zero weights are omitted from the weighted mean; negative weights are rejected.
- Sample variance uses `n − 1`; population variance uses `N`. Sample variance and standard deviation are unavailable with only one observation.
- Percentiles use [R type 7 linear interpolation](https://stat.ethz.ch/R-manual/R-patched/library/stats/html/quantile.html). Up to 20 percentiles from 0 to 100 can be requested. Leave the field blank to omit the percentile table.
- Tied modes are listed. Following a common introductory-course convention, no mode is reported when every distinct value has the same frequency. A repeated constant value is its own mode.
- Histograms use equal-width intervals and the Sturges rule for automatic interval counts. Intervals include the lower boundary; the final interval also includes the upper boundary. Constant data produces one bar.
- The screen shows up to 100 distinct values in the frequency table. The text report contains the complete table, including the histogram's interval counts.

Category summaries treat blanks as missing and retain text such as `NA` as a category. Descriptives reports do not include a hypothesis test or p-value.

### Graphs

In **Analyze**, enable the graphs you want before running the analysis. Numeric Descriptives, t tests and ANOVA offer **Boxplot** and **Normal Q–Q plot**. Rank comparisons offer boxplots. Pearson, Spearman and simple regression offer **Scatterplot**.

- Boxplots show R type 7 quartiles, the median, whiskers within 1.5 interquartile ranges and individual outliers. Outliers remain in the analysis. Group plots scroll sideways, with full labels and numerical summaries below.
- Normal Q–Q plots compare observed values with theoretical normal quantiles. A reference line passes through the first and third quartiles. For independent groups, choose a **Q–Q sample**; paired t tests plot the differences within complete pairs. Small or constant samples include an explanation. A graph alone does not establish normality.
- Scatterplots show every complete pair using the original values, including for Spearman correlation. Simple regression adds its fitted least-squares line. Overlapping values share a marker.

Graph options and the selected Q–Q sample are saved with your work. Text reports include numerical graph summaries. PDF and HTML reports include the requested figures, including every Q–Q group. Multiple regression offers residual Q–Q and residuals-versus-fitted plots; repeated-measures ANOVA offers condition boxplots. See [graph conventions and references](docs/statistical-methods.md#graphs).

<p align="center">
  <img src="docs/screenshots/boxplot.png" alt="Boxplot marking an outlier in fictional practice data" width="300">
  <img src="docs/screenshots/scatterplot.png" alt="Scatterplot and fitted regression line for fictional practice data" width="300">
</p>

### Reliability analysis

Choose **Reliability analysis**, select 2–60 item columns and run. Results include raw and standardized alpha and omega, item means/SDs, corrected item-rest correlations and raw alpha if each item is removed. Enable the inter-item correlation table when useful. All values use the same complete participants. Use the Data scoring tools to reverse negatively keyed items first; the app never reverses or removes items automatically.

Omega uses a single congeneric factor fitted by maximum likelihood and needs at least three items. Singular correlations, constant items, weak identification and boundary solutions produce an explanation instead of a coefficient. This estimator can differ from jamovi's default omega estimator. Internal consistency does not establish validity or a single underlying dimension.

### Multiple linear regression

Choose an **Outcome** and 1–12 separate numeric **Predictors**. The model includes an intercept and enters all predictors together. Results include overall F and p, R²/adjusted R², residual RMSE, coefficient B/SE/standardized β, two-sided t tests, 95% intervals, VIF and tolerance. Optional residual Shapiro–Wilk, Q–Q and residuals-versus-fitted plots support diagnosis.

Categorical predictors require appropriately coded numeric indicators and a reference category. Constant or redundant predictors are rejected. The model needs more complete observations than predictors plus one. Interaction terms, automatic model selection and logistic regression are outside this model's scope.

### Repeated-measures ANOVA

Arrange one row per participant and one column per condition. Select 2–12 conditions, leaving the participant ID unchecked. The app fits one within-subject factor and reports F, partial eta-squared, Mauchly's sphericity diagnostic and uncorrected, Greenhouse–Geisser and Huynh–Feldt results. Greenhouse–Geisser is the default; choose the correction explicitly.

Optional paired follow-ups use **Holm-adjusted p-values** and **Bonferroni 95% family intervals**. These are different adjustments and are labeled separately. The main analysis and all follow-ups use the same complete participants. Two conditions need no sphericity correction. Multiple within-subject factors, between-subject factors and mixed models are outside this analysis's scope.

<p align="center">
  <img src="docs/screenshots/reliability.png" alt="Reliability coefficients for fictional questionnaire items" width="270">
  <img src="docs/screenshots/multiple-regression.png" alt="Multiple-regression coefficients and collinearity for fictional data" width="270">
  <img src="docs/screenshots/repeated-measures.png" alt="Repeated-measures sphericity corrections for fictional conditions" width="270">
</p>

### PDF and HTML reports

In **Results**, choose **Save report as PDF** or **Save report as HTML**, then select a location in Android's file picker. Reports include the analysis settings, included/excluded counts, result tables, requested figures, preparation notes and method notes. Fictional data stays visibly labeled. Frequency tables retain every row, and all requested Q–Q samples are included.

- PDF uses A4 pages with vector charts, repeated table headings and separate column groups for wide tables. Exports are limited to 400 pages and 32 MB.
- HTML is one self-contained file with inline figures and styles, escaped text and no scripts or external resources. Wide tables scroll sideways on a phone to keep numbers readable. Open it in a browser to view or print it. Exports are limited to 32 MB.
- Long chart-axis labels are shortened to fit, with full variable names retained in captions. Boxplot summaries retain full condition names.
- Existing CSV and text exports remain available. The document picker and pending report restore after Android process termination.

## Preview

<p align="center">
  <img src="docs/screenshots/results.png" alt="pocketomv showing descriptive statistics for fictional practice data" width="320">
  <img src="docs/screenshots/histogram.png" alt="pocketomv showing a histogram and interval counts for fictional practice data" width="320">
</p>

<p align="center">
  <img src="docs/screenshots/excel-import.png" alt="Choosing a worksheet from a fictional Excel workbook in pocketomv" width="300">
</p>

<p align="center"><em>Fictional practice data.</em></p>

## Use the app

1. In **Data**, enter rows or choose **Open Excel / CSV / TSV**. For Excel, select a worksheet, set whether its first row contains column names, then tap **Import worksheet**.
2. Choose **Use data**, then select the method and columns in **Analyze**.
3. Set the options for the selected analysis, such as sample/population summaries, a reference mean or a variance assumption.
4. Tap **Run analysis** and review the **Results** screen.
5. Copy the report, save it as text, HTML or PDF, or save the data as CSV.

**Saved on this device** confirms the current work has been written. Edited data must be applied before running an analysis. For a quick walkthrough, choose **Try fictional data** or **Load a fictional example** for the selected analysis.

### Edit cells and create scores

In **Data**, choose **Cells** in **Data editor**. Tap a cell to edit it, a column heading for rename/delete actions, or a row number for row actions. Add blank rows or columns, jump to a row, and undo the last table change during the current app session. Saved cell edits clear old results and recover when the app reopens.

Choose **Recode / score** to preview a separate recoded column, reverse-scored item, sum or mean. Recoding supports exact text and inclusive numeric ranges, with an explicit unmatched-value choice. Item scores can require every selected answer or a minimum number of answered items. Original values remain intact.

Scored columns contain **saved values calculated at creation**. Recreate scores after changing their inputs; they do not update automatically. Preparation notes accompany text reports. See [editing steps, scoring rules and examples](docs/data-editing.md).

<p align="center">
  <img src="docs/screenshots/cells.png" alt="Editable cell grid containing fictional questionnaire responses" width="300">
  <img src="docs/screenshots/scoring.png" alt="Previewing a mean score with a minimum answered-item rule using fictional data" width="300">
</p>

<p align="center"><em>Fictional questionnaire responses and score preview.</em></p>

### Data formats

Excel `.xlsx` workbooks open directly through Android's file picker. Choose one worksheet at a time, including a hidden worksheet when explicitly selected. Blank cells keep their column positions, fully blank rows are skipped, and the first nonblank row supplies column names when that option is checked. Cancelled or failed imports preserve the current table.

Excel imports stored cell values, including shared/inline text, numbers and Boolean values. [Formula cells use their last saved result](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.spreadsheet.cellvalue); formulas are not recalculated. Cells with missing formula results or Excel errors must be corrected before importing. Formatting, charts and merged-cell layout are not imported. Numeric date cells retain Excel's serial numbers; ISO date text stays text. Older `.xls`, binary `.xlsb` and encrypted workbooks are not supported.

Workbooks are limited to 8 MB, 100 worksheets and 32 MB of expanded content, with at most 8 MB in any individual part. Imported tables use the same row, column, cell and editable-text limits as entered data.

CSV/TSV import accepts UTF-8 and UTF-16 text with commas, tabs or semicolons as separators. Quoted fields and optional headers are supported.

For example, this fictional table can be used for an independent-samples comparison.

```csv
Group,Score
A,4
A,7
A,9
B,3
B,5
B,8
```

Numeric values use a decimal point. Blanks, `NA`, `N/A` and `NaN` are treated as missing in numeric columns. Frequency columns contain nonnegative whole numbers. Text files are limited to 2 MB; tables support at most 20,000 data rows, 60 columns and 250,000 cells.

## Privacy

The application declares no Android Internet permission. Calculations run on the device, and automatic session files use private storage excluded from Android backup.

Imports and exports use Android's document picker. Exported files are written to the location selected by the user.

## Install

Install the pocketomv APK on a phone running Android 8.0 or newer. Open the APK in your file manager and allow installation from that source when Android asks.

To update, install the newer APK over the existing app to retain saved work.
