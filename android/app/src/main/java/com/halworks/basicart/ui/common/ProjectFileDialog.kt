package com.halworks.basicart.ui.common

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.halworks.basicart.data.AppContainer
import com.halworks.basicart.data.ProjectFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Export project file" (specs §11a): builds the .zip off the main thread with progress,
 * then offers Save to device or Share. [prepare] runs first (e.g. flush autosave).
 */
@Composable
fun ProjectFileDialog(
    app: AppContainer,
    projectId: String,
    projectName: String,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    prepare: suspend () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableFloatStateOf(0f) }
    var zip by remember { mutableStateOf<File?>(null) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // After a save the dialog stays open and says where the file went.
    var savedTo by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(projectId) {
        try {
            prepare()
            zip = withContext(Dispatchers.IO) {
                ProjectFiles.buildZip(app, projectId, projectName) { p -> progress = p }
            }
        } catch (e: Exception) {
            failed = true
        }
    }
    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ProjectFiles.MIME)) { uri ->
        val z = zip
        if (uri != null && z != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { ProjectFiles.writeTo(context, z, uri) }.isSuccess }
            if (ok) { savedTo = com.halworks.basicart.data.Exporter.actualName(context, uri) ?: z.name; z.delete() } else { onMessage("Couldn't save the project file"); onDismiss() }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Export project file") },
        text = {
            Column {
                when {
                    savedTo != null -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Icon(androidx.compose.material.icons.Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Project file saved", style = MaterialTheme.typography.titleMedium)
                            Text(savedTo!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    failed -> Text("Couldn't create the project file. Please try again.")
                    zip == null -> {
                        Text("Packing “$projectName”…", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                    else -> {
                        Text(
                            "A .zip with every layer, so the project can be opened in Basic Art on another phone or tablet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(zip!!.name + " · " + sizeText(zip!!.length()), style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(20.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                enabled = !busy,
                                onClick = {
                                    val z = zip!!
                                    if (Build.VERSION.SDK_INT >= 29) {
                                        busy = true
                                        scope.launch {
                                            val where = withContext(Dispatchers.IO) { runCatching { ProjectFiles.saveToDownloads(context, z) }.getOrNull() }
                                            busy = false
                                            if (where != null) { savedTo = where; z.delete() } else { onMessage("Couldn't save the project file"); onDismiss() }
                                        }
                                    } else createDoc.launch(z.name)
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Save")
                            }
                            OutlinedButton(
                                enabled = !busy,
                                onClick = { context.startActivity(ProjectFiles.shareIntent(context, zip!!)); onDismiss() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Share")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(if (savedTo != null) "Done" else if (zip == null && !failed) "Cancel" else "Close") } },
    )
}

private fun sizeText(b: Long): String = when {
    b >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f MB", b / 1_000_000.0)
    else -> "${maxOf(1, b / 1000)} KB"
}
