package io.github.tatselkrik.statis130lite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun LicenseNotices(onDismiss: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf("LICENSE.txt") }
    val context = LocalContext.current
    val text by produceState("Loading…", selected) {
        value = withContext(Dispatchers.IO) { try {
            context.assets.open(selected).bufferedReader().use { it.readText() }
        } catch (_: java.io.IOException) { "The license text could not be opened. License files are also included with the application source." } }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight(0.9f), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Licenses", style = MaterialTheme.typography.titleLarge)
                    ChoiceField("License text", selected, listOf("LICENSE.txt" to "pocketomv · MIT",
                        "third-party/NOTICE.txt" to "Third-party components and notices", "third-party/Apache-2.0.txt" to "Apache License 2.0",
                        "third-party/scipy-LICENSE.txt" to "SciPy · BSD 3-Clause",
                        "third-party/scipy-minpack2-LICENSE.txt" to "SciPy · More–Thuente line search"), true) { selected = it }
                    SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        Text(text, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
                }
            }
        }
    }
}
