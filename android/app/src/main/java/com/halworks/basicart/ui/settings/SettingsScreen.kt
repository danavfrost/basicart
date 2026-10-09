package com.halworks.basicart.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halworks.basicart.BuildConfig
import com.halworks.basicart.data.AppContainer
import com.halworks.basicart.data.ExportFormat
import com.halworks.basicart.data.ThemeMode
import com.halworks.basicart.data.ThumbCache
import com.halworks.basicart.fonts.FontFamily as BaFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val ABOUT_TAGLINE = "No ads. No accounts. No internet. No in-app purchases. A simple, basic art editing tool."
/** Shown as plain text (no links). */
const val SOURCE_URL = "github.com/danavfrost/basicart"

@Composable
private fun SectionHeader(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) { Column { content() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: AppContainer, onBack: () -> Unit, onLicenses: () -> Unit) {
    val s = app.settings
    val theme by s.theme.collectAsState()
    val snapping by s.snapping.collectAsState()
    val fmt by s.exportFormat.collectAsState()
    val quality by s.jpegQuality.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()).verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp)) {
                SectionHeader("Appearance")
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("Theme", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(10.dp))
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            ThemeMode.entries.forEachIndexed { i, m ->
                                SegmentedButton(
                                    selected = theme == m, onClick = { s.setTheme(m) },
                                    shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                                ) { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) }
                            }
                        }
                    }
                }
                SectionHeader("Editing")
                Card {
                    ListItem(
                        headlineContent = { Text("Snapping") },
                        supportingContent = { Text("Snap to the canvas center and edges, and to 0/45/90° when rotating") },
                        trailingContent = { Switch(checked = snapping, onCheckedChange = { s.setSnapping(it) }) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.clickable { s.setSnapping(!snapping) },
                    )
                }
                SectionHeader("Export defaults")
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("Format", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(10.dp))
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            ExportFormat.entries.forEachIndexed { i, f ->
                                SegmentedButton(
                                    selected = fmt == f, onClick = { s.setExportFormat(f) },
                                    shape = SegmentedButtonDefaults.itemShape(i, ExportFormat.entries.size),
                                ) { Text(f.label) }
                            }
                        }
                        // Quality only matters for JPG.
                        AnimatedVisibility(visible = fmt == ExportFormat.JPEG) {
                            Column {
                                Spacer(Modifier.height(16.dp))
                                var q by remember(quality) { mutableFloatStateOf(quality.toFloat()) }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("JPG quality", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                    Text("${q.toInt()}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                }
                                Slider(value = q, onValueChange = { q = it }, onValueChangeFinished = { s.setJpegQuality(q.toInt()) }, valueRange = 50f..100f)
                            }
                        }
                    }
                }
                SectionHeader("Projects")
                Card {
                    ListItem(
                        leadingContent = { Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
                        headlineContent = { Text("Clear all project history", color = MaterialTheme.colorScheme.error) },
                        supportingContent = { Text("Delete every saved project. Exported images are not affected.") },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.clickable { confirmClear = true },
                    )
                }
                SectionHeader("About")
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("Basic Art", style = MaterialTheme.typography.titleLarge)
                        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Text(ABOUT_TAGLINE, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Privacy: Basic Art collects no data and makes no network connections. Projects are kept only in the app on this device, and can be included in your device's own system backups (Android backup), according to your settings. Uninstalling the app deletes them; exported images remain.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("Basic Art is open source (MIT). Source code:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(SOURCE_URL, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ListItem(
                        headlineContent = { Text("Licenses & Credits") },
                        supportingContent = { Text("App license, bundled fonts and libraries") },
                        trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.clickable(onClick = onLicenses),
                    )
                }
            }
        }
    }
    if (confirmClear) {
        ClearAllDialog(
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) { app.store.clearAll() }
                    snack.showSnackbar("All projects deleted")
                }
            },
            onDismiss = { confirmClear = false },
        )
    }
}


@Composable
private fun ClearAllDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Delete ALL projects?") },
        text = {
            Column {
                Text("This permanently removes every project from Basic Art and cannot be undone. Images you've already exported are not affected.")
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = typed, onValueChange = { typed = it.take(10) }, singleLine = true,
                    label = { Text("Type DELETE to confirm") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(); onDismiss() },
                enabled = typed.trim() == "DELETE",
                // Strong destructive red in both themes.
                colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFFC62828), contentColor = androidx.compose.ui.graphics.Color.White),
            ) { Text("Delete everything") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val MIT = """MIT License

Copyright (c) 2026 Dana Frost

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE."""

private val LIBRARIES = listOf(
    "AndroidX (Core, Activity, Lifecycle, ExifInterface)" to "Apache License 2.0 — The Android Open Source Project",
    "Jetpack Compose (UI, Foundation, Material 3, Material Icons)" to "Apache License 2.0 — The Android Open Source Project",
    "Kotlin standard library & kotlinx.coroutines" to "Apache License 2.0 — JetBrains s.r.o. and Kotlin contributors",
    "kotlinx.serialization" to "Apache License 2.0 — JetBrains s.r.o. and contributors",
)

@Composable
private fun LicenseRow(title: String, subtitle: String, detail: String? = null, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f), maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (onClick != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(app: AppContainer, onBack: () -> Unit) {
    var openLicense by remember { mutableStateOf<Pair<String, String>?>(null) } // title to asset path
    val fonts by produceState<List<BaFont>>(emptyList()) { value = withContext(Dispatchers.IO) { app.fonts.fonts.sortedBy { it.family.lowercase() } } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Licenses & Credits") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 32.dp),
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val apache = app.fonts.fonts.firstOrNull { it.licenseName.startsWith("Apache") }?.licenseFile
            item { Box(Modifier.widthIn(max = 640.dp).fillMaxWidth()) { SectionHeader("Basic Art") } }
            item {
                Box(Modifier.widthIn(max = 640.dp)) {
                    Card {
                        LicenseRow("Basic Art app code", "MIT License") { openLicense = "MIT License" to "" }
                    }
                }
            }
            item { Box(Modifier.widthIn(max = 640.dp).fillMaxWidth()) { SectionHeader("Libraries") } }
            item {
                Box(Modifier.widthIn(max = 640.dp)) {
                    Card {
                        LIBRARIES.forEachIndexed { i, (n, l) ->
                            if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            LicenseRow(n, l, onClick = apache?.let { { openLicense = "$n — Apache License 2.0" to it } })
                        }
                    }
                }
            }
            item {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                    SectionHeader("Fonts · ${fonts.size} families")
                    Text(
                        "Every font is bundled unmodified under the SIL Open Font License or the Apache License. Tap a font to read its license.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    )
                }
            }
            itemsIndexed(fonts, key = { _, f -> f.id }) { i, f ->
                // One continuous card: rounded top on the first row, bottom on the last.
                val top = if (i == 0) 16.dp else 0.dp
                val bottom = if (i == fonts.lastIndex) 16.dp else 0.dp
                Surface(
                    shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Column {
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        LicenseRow(f.family, "${f.author} · ${f.licenseName}", f.copyright.takeIf { it.isNotBlank() }) {
                            openLicense = "${f.family} — ${f.licenseName}" to f.licenseFile
                        }
                    }
                }
            }
        }
    }
    openLicense?.let { (title, path) ->
        val text by produceState("Loading…", path) {
            value = if (path.isEmpty()) MIT else withContext(Dispatchers.IO) {
                try { app.app.assets.open(path).use { it.readBytes().decodeToString() } } catch (e: Exception) { "License file not found." }
            }
        }
        AlertDialog(
            onDismissRequest = { openLicense = null },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp))
                }
            },
            confirmButton = { TextButton(onClick = { openLicense = null }) { Text("Close") } },
        )
    }
}

