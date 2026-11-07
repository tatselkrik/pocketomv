<h1 align="center">pocketomv 📊</h1>

<p align="center">
  <a href="https://github.com/tatselkrik/pocketomv/actions/workflows/android-quality.yml"><img src="https://github.com/tatselkrik/pocketomv/actions/workflows/android-quality.yml/badge.svg" alt="Android quality checks"></a>
  <img src="https://img.shields.io/badge/version-0.1.0-006b5e" alt="Version 0.1.0">
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&amp;logoColor=white" alt="Android 8.0 or newer">
  <img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&amp;logoColor=white" alt="Kotlin and Jetpack Compose">
  <img src="https://img.shields.io/badge/repository-private-172d28" alt="Private repository">
</p>

<p align="center">
  An offline Android app for statistics classes. Enter or import data, run common statistical tests, and export clear results.
</p>

<p align="center">
  <strong>Data → Analyze → Results</strong>
</p>

## Overview

pocketomv brings common classroom analyses to a phone. Enter a table or open a CSV/TSV file, select a test and its variables, and review statistics, descriptive summaries, confidence intervals and method notes. Copy the report or save it as a text file.

Current work saves automatically in private device storage. The app restores applied data, unfinished edits, analysis options and the current screen when reopened, and recalculates saved results.

## Features

- Twelve analyses covering comparisons of means, ranks, frequencies, correlation and regression.
- Kotlin interface with separate Data, Analyze and Results screens.
- Data entry with optional column headings and CSV/TSV import.
- Student and Welch options for independent t tests, plus ordinary and Welch one-way ANOVA.
- Directional alternatives for supported tests and custom expected weights for goodness of fit.
- Explicit handling of missing values, tied ranks, zero paired differences and frequency counts.
- Descriptive statistics, available 95% confidence intervals and calculation notes.
- A fictional example for every analysis.
- Automatic work recovery with visible saving status and Retry after a failed save.
- CSV data export, report copying and text report export.
- Portrait, landscape, larger text and dark mode.

## Supported analyses

| Area | Analyses |
| --- | --- |
| Means | One-sample, independent-samples and paired-samples t tests |
| Group means | One-way ANOVA |
| Ranks | Mann–Whitney U, Wilcoxon signed-rank and Kruskal–Wallis H |
| Frequencies | Chi-square goodness of fit and independence |
| Association | Pearson and Spearman correlation |
| Prediction | Simple linear regression |

Mann–Whitney and Wilcoxon use exact probabilities for eligible small samples and corrected approximations otherwise. Zero paired differences are removed before Wilcoxon ranks are assigned. Spearman p-values use a labeled t approximation. Method notes identify the calculation used for each result.

## Preview

<p align="center">
  <img src="docs/screenshots/results.png" alt="pocketomv showing an automatically saved one-sample t test using fictional practice data" width="340">
</p>

<p align="center"><em>Android 16 emulator capture with fictional practice data.</em></p>

## Use the app

1. In **Data**, enter rows or choose **Open CSV / TSV**. Set whether the first row contains column names.
2. Choose **Use data**, then select the method and columns in **Analyze**.
3. Set the reference mean, alternative hypothesis, variance assumption or frequency column as needed.
4. Tap **Run analysis** and review the **Results** screen.
5. Use **Copy report**, **Save report as text** or **Save data as CSV** for a separate file.

**Saved on this device** confirms the current work has been written. Edited data must be applied before running an analysis. For a quick walkthrough, choose **Try fictional data** or load the fictional example for a selected test.

### Data formats

The importer accepts UTF-8 and UTF-16 text with commas, tabs or semicolons as separators. Quoted fields and optional headers are supported. Excel worksheets can be saved as CSV before importing.

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

Numeric values use a decimal point. Blanks, `NA`, `N/A` and `NaN` are treated as missing in numeric columns. Frequency columns contain nonnegative whole numbers. Import limits are 2 MB per file, 20,000 data rows, 60 columns and 250,000 cells.

## Privacy

The application declares no Android Internet permission. Calculations run on the device, and automatic session files use private storage excluded from Android backup.

Imports and exports use Android's document picker. Exported files are written to the location selected by the user.

## Build and install

Use JDK 25, Android SDK Platform 37.2 and Android Build Tools 37.0.0. Set `ANDROID_HOME` to the SDK location or configure the SDK through Android Studio.

On Windows, run the project wrapper.

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --no-daemon "-Pkotlin.compiler.execution.strategy=in-process"
```

On macOS or Linux, make the wrapper executable and run the same checks.

```bash
chmod +x gradlew
./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon -Pkotlin.compiler.execution.strategy=in-process
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. Transfer it to the phone and open it with a file manager to install, approving installation from that source when Android asks.

With Android platform tools and USB debugging configured, install from a computer.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The application identifier is `io.github.tatselkrik.statis130lite`. Version 0.1.0 uses debug signing.

## Build stack

| Component | Version |
| --- | --- |
| Gradle | 9.9.0, with distribution checksum verification |
| Android Gradle Plugin | 9.5.1 |
| Kotlin and Compose compiler | 2.4.21 |
| Compose UI and Foundation | 1.12.1 |
| Material 3 | 1.4.0 |
| Activity Compose | 1.13.0 |
| Apache Commons Statistics | 1.3 |
| Minimum Android version | Android 8.0 / API 26 |

Statistical kernels use Apache Commons Statistics. [Apache Commons Statistics](https://commons.apache.org/proper/commons-statistics/)

## Verification

| Check | Status |
| --- | --- |
| Automated tests | 52 passed locally |
| Android lint | Zero errors, one dependency-check warning |
| Debug APK assembly | Passed locally |
| Twelve analysis workflows | Exercised in an offline Android 16 emulator |
| CSV import/export and report copying/saving | Verified in the emulator |
| Restart recovery, unfinished edits and failed-save retry | Verified in the emulator |
| Interrupted writes, unreadable files and pending exports | Recovery paths verified in the emulator |
| Physical Samsung Galaxy S24 FE | Pending |

The Android quality workflow runs unit tests, lint and debug APK assembly for pushes and pull requests to `main`. Tests include independently calculated statistical references and fictional data throughout.
