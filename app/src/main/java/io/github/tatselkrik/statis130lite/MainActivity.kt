package io.github.tatselkrik.statis130lite

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var model: AnalysisViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[AnalysisViewModel::class.java]
        setContent {
            val colors = if (isSystemInDarkTheme()) {
                darkColorScheme(primary = Color(0xFF8DD6C8), primaryContainer = Color(0xFF005047))
            } else {
                lightColorScheme(
                    primary = Color(0xFF006B5E),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFB5F0DF),
                    onPrimaryContainer = Color(0xFF002F28),
                    surface = Color(0xFFF7FAF8),
                    onSurface = Color(0xFF172D28),
                    surfaceContainerLow = Color(0xFFF0F5F1),
                )
            }
            MaterialTheme(colorScheme = colors) { StatisticsApp(model) }
        }
    }

    override fun onStop() {
        model.saveBeforeBackground()
        super.onStop()
    }
}

@Composable
private fun StatisticsApp(model: AnalysisViewModel) {
    val state = model.state
    val saveStatus by model.saveStatus.collectAsState()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var licensesOpen by remember { mutableStateOf(false) }
    if (licensesOpen) LicenseNotices { licensesOpen = false }
    val resolver = context.applicationContext.contentResolver
    val openData = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importData(resolver, uri)
    }
    val saveData = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        model.finishExport(resolver, uri)
    }
    val saveReport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        model.finishExport(resolver, uri)
    }
    val saveHtml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri -> model.finishExport(resolver, uri) }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> model.finishExport(resolver, uri) }
    val saveProject = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(OMV_MIME)) { uri ->
        model.finishExport(resolver, uri)
    }
    val exportProject: () -> Unit = {
        scope.launch { if (model.prepareExport(ExportKind.PROJECT)) saveProject.launch("pocketomv-project.omv") }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            model.clearMessage()
        }
    }
    BackHandler(enabled = state.page != AppPage.DATA || state.busy != null) {
        if (state.busy == null) {
            model.navigate(if (state.page == AppPage.RESULTS) AppPage.ANALYSIS else AppPage.DATA)
        }
    }
    ExcelImportDialog(model)
    OmvImportDialog(model)
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        topBar = {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("pocketomv", Modifier.weight(1f).padding(end = 12.dp),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { licensesOpen = true }) { Text("Licenses") }
                }
                if (state.busy != null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(state.busy, Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium)
                }
                if (saveStatus.failed) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Autosave failed. Export your work or retry.",
                            Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = model::retryAutosave, enabled = state.busy == null) { Text("Retry") }
                    }
                } else if (saveStatus.saving || saveStatus.hasSavedWork) {
                    Text(if (saveStatus.saving) "Saving changes…" else "Saved on this device",
                        Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.error?.let { error ->
                    Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { ErrorNotice(error) }
                }
            }
        },
        bottomBar = {
            NavigationBar {
                AppPage.entries.forEach { page ->
                    NavigationBarItem(
                        selected = state.page == page,
                        enabled = state.busy == null,
                        onClick = {
                            focus.clearFocus()
                            model.navigate(page)
                        },
                        icon = {
                            Box(
                                Modifier.size(28.dp).background(
                                    if (state.page == page) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                                    CircleShape,
                                ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(page.step, color = if (state.page == page) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                            }
                        },
                        label = { Text(page.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            key(state.page) {
                when (state.page) {
                    AppPage.DATA -> DataScreen(state, model,
                        // Some Android storage providers assign .omv an unknown MIME type.
                        onImport = { openData.launch(arrayOf("*/*")) },
                        onSave = {
                            scope.launch {
                                if (model.prepareExport(ExportKind.DATA)) saveData.launch("pocketomv-data.csv")
                            }
                        },
                        onSaveProject = exportProject,
                    )
                    AppPage.ANALYSIS -> AnalysisScreen(state, model) {
                        focus.clearFocus()
                        model.calculate()
                    }
                    AppPage.RESULTS -> ResultsScreen(state, model,
                        onCopy = {
                            state.result?.let { result ->
                                val report = result.asReport(state.source, state.fictional, state.preparationNotes)
                                if (report.length > 100_000) {
                                    scope.launch { snackbar.showSnackbar("This report is too large to copy. Save it as a text file instead.") }
                                } else {
                                    try {
                                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                                        clipboard.setPrimaryClip(ClipData.newPlainText(result.method.title, report))
                                        scope.launch { snackbar.showSnackbar("Report copied") }
                                    } catch (_: RuntimeException) {
                                        scope.launch { snackbar.showSnackbar("The report could not be copied. Save it as a text file instead.") }
                                    }
                                }
                            }
                        },
                        onSave = {
                            scope.launch {
                                if (model.prepareExport(ExportKind.REPORT)) saveReport.launch("pocketomv-results.txt")
                            }
                        },
                        onSaveHtml = { scope.launch { if (model.prepareExport(ExportKind.HTML)) saveHtml.launch("pocketomv-results.html") } },
                        onSavePdf = { scope.launch { if (model.prepareExport(ExportKind.PDF)) savePdf.launch("pocketomv-results.pdf") } },
                        onSaveProject = exportProject,
                    )
                }
            }
        }
    }
}

@Composable
private fun DataScreen(
    state: AnalysisScreenState,
    model: AnalysisViewModel,
    onImport: () -> Unit,
    onSave: () -> Unit,
    onSaveProject: () -> Unit,
) {
    val enabled = state.busy == null
    LazyColumn(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { PageHeading("YOUR DATA", "Start with a table", "Enter data or open a jamovi, Excel, CSV or TSV file.") }
        item {
            Panel {
                Button(onClick = onImport, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Open .omv / Excel / CSV / TSV") }
                OutlinedButton(onClick = model::loadExample, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text("Try fictional data")
                }
                if (state.data == null && state.draft.isBlank()) OutlinedButton(onClick = model::newTable, enabled = enabled,
                    modifier = Modifier.fillMaxWidth()) { Text("New empty table") }
                if (state.data != null) ChoiceField("Data editor", state.dataEditor,
                    listOf(DataEditorView.TEXT to "Text / paste", DataEditorView.CELLS to "Cells"), enabled, model::selectDataEditor)
            }
        }
        if (state.dataEditor == DataEditorView.CELLS && state.data != null) item {
            CellDataEditor(model)
            OutlinedButton(onClick = onSave, enabled = enabled && !state.hasUnappliedChanges,
                modifier = Modifier.fillMaxWidth()) { Text("Save data as CSV") }
        } else item {
            Panel {
                Text("Enter or paste data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Separate columns with commas, tabs, or semicolons. Put one observation on each row.",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = model::editDraft,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                    label = { Text("Table") },
                    placeholder = { Text("Group,Score\nA,7\nA,9\nB,6\nB,8") },
                    minLines = 6,
                    maxLines = 12,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                )
                CheckOption("First row contains column names", state.firstRowHasNames, enabled, model::setHeaders)
                CheckOption("Fictional practice data", state.draftFictional, enabled, model::setFictional)
                Button(
                    onClick = model::applyDraft,
                    enabled = enabled && state.draft.isNotBlank() && (state.data == null || state.hasUnappliedChanges),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Use data") }
            }
        }
        state.data?.let { data ->
            item { DatasetSummary(state) }
            if (state.dataEditor == DataEditorView.TEXT) item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Preview · first ${minOf(8, data.rows.size)} of ${data.rows.size} rows",
                        style = MaterialTheme.typography.titleSmall)
                    ResultTable(data.columns, data.rows.take(8))
                    OutlinedButton(onClick = onSave, enabled = enabled && !state.hasUnappliedChanges,
                        modifier = Modifier.fillMaxWidth()) { Text("Save data as CSV") }
                }
            }
            item {
                OutlinedButton(onClick = onSaveProject, enabled = enabled && !state.hasUnappliedChanges,
                    modifier = Modifier.fillMaxWidth()) { Text("Save jamovi project (.omv)") }
            }
        }
        item {
            Text("Use a dot for decimal values. Blanks, NA, N/A and NaN are treated as missing in numeric columns. Your current work is saved automatically on this device.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AnalysisScreen(state: AnalysisScreenState, model: AnalysisViewModel, onRun: () -> Unit) {
    val enabled = state.busy == null
    val request = state.request
    LazyColumn(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { PageHeading("CHOOSE A METHOD", "Set up your analysis", "Select an analysis and the columns it should use.") }
        item {
            Panel {
                ChoiceField("Analysis", request.method, AnalysisMethod.entries.map { it to it.title }, enabled, model::selectMethod)
                Text(request.method.purpose, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = model::loadExample, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text("Load a fictional example")
                }
            }
        }
        val data = state.data
        if (data == null) {
            item { EmptyPanel("Add a dataset", "Type data or open Excel / CSV / TSV to choose its columns.", "Go to Data") {
                model.navigate(AppPage.DATA)
            } }
        } else {
            item { DatasetSummary(state) }
            item {
                Panel {
                    val columns = data.columns.mapIndexed { index, name -> index to name }
                    if (!request.method.usesVariableSelection) ChoiceField(request.method.firstLabel, request.first, columns, enabled) {
                        model.configure(if (request.method == AnalysisMethod.DESCRIPTIVES) request.copy(
                            first = it, weight = request.weight.takeUnless { weight -> weight == it },
                            descriptiveNumeric = it in data.numericColumns(),
                            showBoxplot = it in data.numericColumns() && request.showBoxplot,
                            showQQPlot = it in data.numericColumns() && request.showQQPlot,
                        ) else request.copy(first = it, predictors = request.predictors.filterNot { predictor -> predictor == it },
                            factors = request.factors - it, covariates = request.covariates - it,
                            comparisonGroup = "", eventCategory = ""))
                    }
                    request.method.secondLabel?.let { label ->
                        ChoiceField(label, request.second, columns, enabled) {
                            model.configure(request.copy(second = it, comparisonGroup = "", eventCategory = ""))
                        }
                    }
                    if (request.method == AnalysisMethod.DESCRIPTIVES) {
                        DescriptiveOptions(data, request, enabled, model::configure)
                    }
                    if (request.method == AnalysisMethod.CORRELATION_MATRIX) {
                        CorrelationOptions(data, request, enabled, model::configure)
                    }
                    if (request.method.usesCounts) {
                        val choices: List<Pair<Int?, String>> = listOf(null to "One row = one observation") + columns
                        ChoiceField("Frequency column", request.count, choices, enabled) {
                            model.configure(request.copy(count = it))
                        }
                        Text("Choose a frequency column when rows summarize counts.",
                            style = MaterialTheme.typography.bodySmall)
                        if (request.count != null) ChoiceField("Missing frequency counts", request.countMissingPolicy,
                            CountMissingPolicy.entries.map { it to it.label }, enabled) {
                            model.configure(request.copy(countMissingPolicy = it))
                        }
                    }
                    if (request.method == AnalysisMethod.ONE_SAMPLE_T) {
                        OutlinedTextField(
                            value = request.reference,
                            onValueChange = { model.configure(request.copy(reference = it)) },
                            enabled = enabled,
                            label = { Text("Reference mean") },
                            supportingText = { Text("The population mean under the null hypothesis.") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (request.method.directional) {
                        ChoiceField("Alternative hypothesis", request.tail, Tail.entries.map { it to it.label }, enabled) {
                            model.configure(request.copy(tail = it))
                        }
                        Text(when {
                            request.method.usesGroups -> "Directional tests compare the first group with the second. Numeric group labels are ordered numerically, and text labels alphabetically."
                            request.method == AnalysisMethod.ONE_SAMPLE_T -> "Compares the sample mean with the reference mean."
                            request.method == AnalysisMethod.PEARSON || request.method == AnalysisMethod.SPEARMAN -> "Tests a correlation equal to zero."
                            request.method == AnalysisMethod.FISHER_EXACT -> "Directional alternatives test the comparison-group/event odds ratio against one. Review the group and event choices below."
                            else -> "Paired differences are first measurement minus second measurement."
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                    if (request.method.usesVarianceChoice) {
                        val pooledLabel = if (request.method == AnalysisMethod.ANOVA) "Equal variances · ordinary ANOVA"
                        else "Equal variances · Student"
                        ChoiceField("Variance assumption", request.equalVariance,
                            listOf(false to "Unequal variances · Welch", true to pooledLabel), enabled) {
                            model.configure(request.copy(equalVariance = it))
                        }
                    }
                    AdvancedOptions(data, request, enabled, model::configure)
                    MeanCheckOptions(request, enabled, model::configure)
                    PlotOptions(request, enabled, model::configure)
                    if (request.method == AnalysisMethod.CHI_GOODNESS) {
                        val order = remember(data, request.first, request.count) {
                            runCatching { Statistics.goodnessCategoryOrder(data, request) }.getOrDefault(emptyList())
                        }
                        OutlinedTextField(
                            value = request.expectedWeights,
                            onValueChange = { model.configure(request.copy(expectedWeights = it)) },
                            enabled = enabled,
                            label = { Text("Expected weights (optional)") },
                            supportingText = { Text("Leave blank for equal proportions, or enter positive weights separated by commas.") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (order.isNotEmpty()) Text("Category order · ${order.take(40).joinToString()}" +
                            if (order.size > 40) "… (use at most 40 categories)" else "",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.hasUnappliedChanges) {
                        Text("Apply your data changes before running the analysis.", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { model.navigate(AppPage.DATA) }) { Text("Review data changes") }
                    }
                    Button(onClick = onRun, enabled = enabled && !state.hasUnappliedChanges,
                        modifier = Modifier.fillMaxWidth()) { Text("Run analysis") }
                }
            }
        }
    }
}

@Composable
private fun ResultsScreen(
    state: AnalysisScreenState,
    model: AnalysisViewModel,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onSaveHtml: () -> Unit,
    onSavePdf: () -> Unit,
    onSaveProject: () -> Unit,
) {
    val result = state.result
    LazyColumn(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (result == null) {
            item { PageHeading("YOUR RESULTS", "Ready when you are", "Run an analysis to see its statistics and method notes.") }
            item { EmptyPanel("Choose an analysis", "Results use the data and options you select.", "Go to Analyze") {
                model.navigate(AppPage.ANALYSIS)
            } }
        } else {
            item { PageHeading("YOUR RESULTS", result.method.title, result.calculation) }
            item { DatasetSummary(state) }
            graphItems(result, state.request.qqSample, state.busy == null, model::selectQQSample)
            if (result.method == AnalysisMethod.DESCRIPTIVES) result.tables.forEach { table -> item {
                TitledTable(table.title, table.headers, table.rows)
                table.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            } }
            descriptiveItems(result.descriptions)
            if (result.test != null) item {
                Panel {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(result.statisticLabel, style = MaterialTheme.typography.labelLarge)
                            Text(formatStatistic(result.statistic), style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.SemiBold)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("p", style = MaterialTheme.typography.labelLarge)
                            Text(formatProbability(result.pValue), style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (result.degreesOfFreedom.isNotEmpty()) {
                        Text("Degrees of freedom · ${result.degreesOfFreedom.joinToString { formatStatistic(it) }}",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("${result.includedCases} cases included · ${result.excludedRows} rows excluded",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (result.estimates.isNotEmpty()) {
                item { TitledTable("Estimates", listOf("Parameter", "Estimate", "95% CI"),
                    result.estimates.map { listOf(it.label, formatStatistic(it.value), it.confidenceInterval()) }) }
            }
            if (result.samples.isNotEmpty()) {
                item { TitledTable("Descriptive statistics", listOf("Sample", "N", "Mean", "SD", "Median", "Min", "Max"),
                    result.samples.map {
                        listOf(it.label, it.size.toString(), formatStatistic(it.mean),
                            it.standardDeviation?.let(::formatStatistic) ?: "—", formatStatistic(it.median),
                            formatStatistic(it.minimum), formatStatistic(it.maximum))
                    }) }
            }
            result.frequencies?.let { table ->
                item { TitledTable("Observed counts", listOf("Category") + table.columnLabels,
                    table.rowLabels.indices.map { listOf(table.rowLabels[it]) + table.observed[it].map(Long::toString) }) }
                item { TitledTable("Expected counts", listOf("Category") + table.columnLabels,
                    table.rowLabels.indices.map { listOf(table.rowLabels[it]) + table.expected[it].map(::formatStatistic) }) }
            }
            meanCheckItems(result)
            if (result.method != AnalysisMethod.DESCRIPTIVES) result.tables.forEach { table -> item {
                TitledTable(table.title, table.headers, table.rows)
                table.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            } }
            if (result.notes.isNotEmpty()) {
                item {
                    Panel {
                        Text("Method notes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        result.notes.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            item {
                Panel {
                    Button(onClick = onCopy, enabled = state.busy == null, modifier = Modifier.fillMaxWidth()) {
                        Text("Copy report")
                    }
                    OutlinedButton(onClick = onSave, enabled = state.busy == null, modifier = Modifier.fillMaxWidth()) {
                        Text("Save report as text")
                    }
                    OutlinedButton(onClick = onSaveHtml, enabled = state.busy == null, modifier = Modifier.fillMaxWidth()) { Text("Save report as HTML") }
                    OutlinedButton(onClick = onSavePdf, enabled = state.busy == null, modifier = Modifier.fillMaxWidth()) { Text("Save report as PDF") }
                    OutlinedButton(onClick = onSaveProject, enabled = state.busy == null && !state.hasUnappliedChanges,
                        modifier = Modifier.fillMaxWidth()) { Text("Save jamovi project (.omv)") }
                    TextButton(onClick = { model.navigate(AppPage.ANALYSIS) }, enabled = state.busy == null,
                        modifier = Modifier.fillMaxWidth()) { Text("Edit analysis") }
                }
            }
        }
    }
}

@Composable
private fun PageHeading(eyebrow: String, title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(eyebrow, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DatasetSummary(state: AnalysisScreenState) {
    val data = state.data ?: return
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("${data.rows.size} ${if (data.rows.size == 1) "row" else "rows"}")
            Pill("${data.columns.size} ${if (data.columns.size == 1) "column" else "columns"}")
        }
        Text(state.source, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        if (state.fictional) Text("Fictional practice data", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary)
        if (state.preparationNotes.isNotEmpty()) Text("Data preparation notes are available in the cell editor and in reports.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Pill(text: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
private fun EmptyPanel(title: String, body: String, action: String, onAction: () -> Unit) {
    Panel {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(action) }
    }
}

@Composable
private fun ErrorNotice(message: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(message, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun CheckOption(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> ChoiceField(
    label: String,
    value: T,
    choices: List<Pair<T, String>>,
    enabled: Boolean,
    onChange: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = choices.firstOrNull { it.first == value }?.second ?: "Choose a column",
            onValueChange = {},
            readOnly = true,
            maxLines = 3,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 360.dp)) {
            choices.forEach { choice ->
                DropdownMenuItem(text = { Text(choice.second, maxLines = 3, overflow = TextOverflow.Ellipsis) }, onClick = {
                    expanded = false
                    onChange(choice.first)
                })
            }
        }
    }
}

@Composable
internal fun TitledTable(title: String, headers: List<String>, rows: List<List<String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (headers.size > 2) Text("Swipe the table sideways to see every column.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ResultTable(headers, rows)
    }
}

@Composable
private fun ResultTable(headers: List<String>, rows: List<List<String>>) {
    Surface(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row(Modifier.background(MaterialTheme.colorScheme.primaryContainer)) {
                headers.forEachIndexed { index, value -> TableCell(value, index == 0, header = true) }
            }
            rows.forEachIndexed { row, values ->
                Row(Modifier.background(if (row % 2 == 0) MaterialTheme.colorScheme.surface
                else MaterialTheme.colorScheme.surfaceContainerLow)) {
                    values.forEachIndexed { index, value -> TableCell(value.ifBlank { "—" }, index == 0) }
                }
                HorizontalDivider(Modifier.width((164 + 120 * (headers.size - 1)).dp))
            }
        }
    }
}

@Composable
private fun TableCell(value: String, first: Boolean, header: Boolean = false) {
    Text(
        value,
        Modifier.width(if (first) 164.dp else 120.dp).padding(horizontal = 12.dp, vertical = 12.dp),
        style = if (header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
    )
}
