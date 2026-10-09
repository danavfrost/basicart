package com.halworks.basicart.ui.editor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.halworks.basicart.data.AppContainer
import com.halworks.basicart.data.ExportFormat
import com.halworks.basicart.data.Exporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

private enum class SizeOpt(val label: String) { FULL("100%"), HALF("50%"), QUARTER("25%"), CUSTOM("Custom") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(app: AppContainer, st: EditorState, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val doc = st.doc ?: return
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val defFormat by app.settings.exportFormat.collectAsState()
    val defQuality by app.settings.jpegQuality.collectAsState()
    // Remembers the last format used this session (falls back to the Settings default).
    var format by remember { mutableStateOf(app.lastExportFormat ?: defFormat) }
    var quality by remember { mutableFloatStateOf(defQuality.toFloat()) }
    var sizeOpt by remember { mutableStateOf(SizeOpt.FULL) }
    val longEdge = max(doc.canvas.width, doc.canvas.height)
    var customText by remember { mutableStateOf(longEdge.toString()) }
    var name by remember { mutableStateOf(doc.name) }
    var busy by remember { mutableStateOf<String?>(null) }
    var projectFile by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val custom = customText.toIntOrNull()
    val scale = when (sizeOpt) {
        SizeOpt.FULL -> 1f; SizeOpt.HALF -> 0.5f; SizeOpt.QUARTER -> 0.25f
        SizeOpt.CUSTOM -> ((custom ?: longEdge).coerceIn(16, 8192).toFloat() / longEdge)
    }
    val (ow, oh) = Exporter.outputSize(doc, scale)
    val valid = sizeOpt != SizeOpt.CUSTOM || (custom != null && custom in 16..8192)

    fun run(save: Boolean) {
        if (busy != null) return
        busy = "Rendering…"; progress = 0f
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) {
                    Exporter.encode(st.renderer, doc, format, scale, quality.roundToInt()) { s, p ->
                        scope.launch { busy = s; progress = p }
                    }
                }
                app.lastExportFormat = format
                if (save) {
                    val where = withContext(Dispatchers.IO) { Exporter.saveToDevice(context, bytes, name, format) }
                    onMessage("Saved to $where")
                    onDismiss()
                } else {
                    val intent = withContext(Dispatchers.IO) { Exporter.shareIntent(context, bytes, name, format) }
                    context.startActivity(intent)
                }
            } catch (e: Throwable) {
                android.util.Log.e("Export", "Export failed", e)
                onMessage(if (e is OutOfMemoryError) "Export failed: not enough memory. Try a smaller size." else "Export failed. Please try again.")
            } finally {
                busy = null
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) run(true) else onMessage("Storage permission is needed to save on this Android version.")
    }

    ModalBottomSheet(onDismissRequest = { if (busy == null) onDismiss() }, sheetState = sheet, modifier = Modifier.statusBarsPadding()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().imePadding().padding(bottom = 16.dp),
        ) {
            Text("Export", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Text("Format", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ExportFormat.entries.forEachIndexed { i, f ->
                    SegmentedButton(selected = format == f, onClick = { format = f }, shape = SegmentedButtonDefaults.itemShape(i, ExportFormat.entries.size)) { Text(f.label) }
                }
            }
            Text(
                when (format) {
                    ExportFormat.PNG -> "Lossless. Keeps transparency."
                    ExportFormat.JPEG -> "Smaller files. Transparent areas become white."
                    ExportFormat.GIF -> "A still image with up to 256 colors. Keeps transparency."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
            )
            AnimatedVisibility(format == ExportFormat.JPEG) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Quality", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(72.dp))
                    Slider(quality, { quality = it.roundToInt().toFloat() }, valueRange = 50f..100f, modifier = Modifier.weight(1f))
                    Text("${quality.roundToInt()}", modifier = Modifier.width(40.dp).padding(start = 8.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Size", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SizeOpt.entries.forEach { o -> FilterChip(selected = sizeOpt == o, onClick = { sizeOpt = o }, label = { Text(o.label) }) }
            }
            AnimatedVisibility(sizeOpt == SizeOpt.CUSTOM) {
                OutlinedTextField(
                    customText, { customText = it.filter(Char::isDigit).take(4) }, singleLine = true,
                    label = { Text("Long edge (px)") }, isError = !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            Text("$ow × $oh px", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(name, { name = it.take(80) }, singleLine = true, label = { Text("File name") }, suffix = { Text(".${format.ext}") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            busy?.let { s ->
                Text(s, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            }
            // Side by side normally; stacked at large font scales so labels never wrap.
            val stacked = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.15f
            val shareBtn: @Composable (Modifier) -> Unit = { m ->
                OutlinedButton(onClick = { run(false) }, enabled = valid && busy == null, modifier = m.height(52.dp)) {
                    Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Share…", maxLines = 1, softWrap = false)
                }
            }
            val saveBtn: @Composable (Modifier) -> Unit = { m ->
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                            permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else run(true)
                    },
                    enabled = valid && busy == null, modifier = m.height(52.dp),
                ) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Save to device", maxLines = 1, softWrap = false)
                }
            }
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { saveBtn(Modifier.fillMaxWidth()); shareBtn(Modifier.fillMaxWidth()) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { shareBtn(Modifier.weight(1f)); saveBtn(Modifier.weight(1f)) }
            }

            // The editable project, kept apart from the image formats above (specs §11a).
            androidx.compose.material3.HorizontalDivider(Modifier.padding(top = 24.dp, bottom = 16.dp))
            Text("Project file (.zip)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Everything needed to keep editing, with all layers. Opens in Basic Art on another phone or tablet.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            OutlinedButton(onClick = { projectFile = true }, enabled = busy == null, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Outlined.Inventory2, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Export project file\u2026", maxLines = 1, softWrap = false)
            }
        }
    }
    if (projectFile) {
        com.halworks.basicart.ui.common.ProjectFileDialog(
            app, st.projectId, doc.name,
            onDismiss = { projectFile = false },
            onMessage = onMessage,
            prepare = {
                st.endLive()
                st.saveNow().join()
                // The package carries where the user left off, including an in-progress text selection.
                val state = st.stateBeforeExport ?: st.editorStateDoc()
                withContext(app.saveDispatcher) { app.store.writeEditorState(st.projectId, state.write()) }
            },
        )
    }
}
