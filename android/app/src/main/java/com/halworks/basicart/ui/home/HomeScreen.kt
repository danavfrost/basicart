package com.halworks.basicart.ui.home

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Add
import com.halworks.basicart.ui.BrandBlue
import com.halworks.basicart.ui.BrandViolet
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.halworks.basicart.data.AppContainer
import com.halworks.basicart.data.Projects
import com.halworks.basicart.data.ThumbCache
import com.halworks.basicart.model.ProjectEntry
import com.halworks.basicart.ui.common.Checkerboard
import com.halworks.basicart.ui.common.ConfirmDialog
import com.halworks.basicart.ui.common.RenameDialog
import com.halworks.basicart.ui.common.ProjectFileDialog
import com.halworks.basicart.data.ProjectFiles
import com.halworks.basicart.model.ImportResult
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(app: AppContainer, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    val entries by produceState<List<ProjectEntry>?>(null, refresh) {
        value = withContext(Dispatchers.IO) { app.store.list() }
    }
    var showNew by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val cfg = LocalConfiguration.current
    val minTile = if (cfg.screenWidthDp >= 600) 200.dp else 150.dp
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    // Import a project file (specs §11a): picker, "Open with" and share-to all land here.
    fun runImport(uri: android.net.Uri) {
        if (importing) return
        importing = true
        scope.launch {
            // Same queue as the start-up cleanup of .import-* folders, so the two never overlap.
            val r = withContext(app.saveDispatcher) { ProjectFiles.import(app, uri) }
            importing = false
            when (r) {
                is ImportResult.Ok -> { refresh++; onOpen(r.projectId) }
                is ImportResult.Rejected -> importError = ProjectFiles.message(r.reason)
                null -> importError = "Basic Art couldn't read this file."
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) runImport(uri) }
    val pending by app.pendingImport.collectAsState()
    LaunchedEffect(pending) {
        val u = pending ?: return@LaunchedEffect
        app.pendingImport.value = null
        runImport(u)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Basic Art") },
                actions = {
                    TextButton(onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed")) }) {
                        Icon(Icons.Outlined.FileOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Import")
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Settings") }
                },
                scrollBehavior = scroll,
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minTile),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            item(key = "new") { NewTile { showNew = true } }
            val list = entries
            if (list != null) {
                items(list, key = { it.id }) { e ->
                    ProjectTile(
                        app, e,
                        onOpen = { onOpen(e.id) },
                        onChanged = { refresh++ },
                        onMessage = { m -> scope.launch { snack.showSnackbar(m) } },
                    )
                }
                if (list.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "Tap New image to start with a blank canvas or a photo.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                        )
                    }
                }
            }
        }
    }
    importError?.let { msg ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("Couldn't import") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { importError = null }) { Text("OK") } },
        )
    }
    if (importing) {
        Dialog(onDismissRequest = {}) {
            Row(
                Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(16.dp))
                Text("Importing project\u2026", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    if (showNew) {
        NewImageSheet(
            app = app,
            onDismiss = { showNew = false },
            onCreated = { id -> showNew = false; onOpen(id) },
            onError = { scope.launch { snack.showSnackbar(it) } },
        )
    }
}

@Composable
private fun NewTile(onClick: () -> Unit) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .shadow(6.dp, RoundedCornerShape(18.dp), ambientColor = BrandViolet, spotColor = BrandViolet)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(BrandBlue, BrandViolet)))
                .clickable(onClickLabel = "Create a new image", onClick = onClick)
                .semantics { contentDescription = "New image" },
            contentAlignment = Alignment.Center,
        ) {
            // Soft highlight in the top-left corner for depth.
            Box(Modifier.matchParentSize().background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(0f, 0f), radius = 420f)))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f))
                        .border(1.5.dp, Color.White.copy(alpha = 0.45f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Add, null, Modifier.size(36.dp), tint = Color.White) }
                Spacer(Modifier.height(12.dp))
                Text("New image", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text("Blank or from a photo", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(" ", style = MaterialTheme.typography.bodyMedium)
        Text(" ", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ProjectTile(
    app: AppContainer,
    e: ProjectEntry,
    onOpen: () -> Unit,
    onChanged: () -> Unit,
    onMessage: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var exportFile by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val name = when (e) {
        is ProjectEntry.Ok -> e.name
        is ProjectEntry.Newer -> e.name ?: "Newer project"
        is ProjectEntry.Corrupt -> "Can't open this project"
    }
    val thumbFile = e.thumb
    val thumb by produceState<android.graphics.Bitmap?>(null, e) {
        value = withContext(Dispatchers.IO) {
            if (!thumbFile.exists() && e is ProjectEntry.Ok) {
                // Thumbnails are a cache: regenerate when missing (FORMAT.md §10.5).
                (app.store.load(e.id) as? com.halworks.basicart.model.LoadResult.Ok)?.let {
                    Projects.writeThumb(app, app.rendererFor(e.id), it.doc)
                }
            }
            val mt = thumbFile.lastModified()
            if (!thumbFile.exists()) null
            else ThumbCache.get(e.id, mt) ?: try {
                BitmapFactory.decodeFile(thumbFile.absolutePath)?.also { ThumbCache.put(e.id, mt, it) }
            } catch (t: Throwable) { null }
        }
    }
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                .clickable(enabled = e is ProjectEntry.Ok, role = Role.Button, onClickLabel = "Open project", onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            val t = thumb
            if (t != null) {
                // Letterboxed thumbnail on a checkerboard so transparency is visible.
                val aspect = t.width.toFloat() / t.height
                Box(
                    Modifier
                        .padding(10.dp)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = aspect < 1f).clip(RoundedCornerShape(4.dp))) {
                        Checkerboard(Modifier.matchParentSize())
                        Image(t.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize())
                    }
                }
            } else if (e is ProjectEntry.Ok) {
                val aspect = e.width.toFloat() / e.height
                Box(Modifier.padding(10.dp).fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = aspect < 1f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                    )
                }
            }
            if (e is ProjectEntry.Corrupt || e is ProjectEntry.Newer) {
                Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = if (t != null) 0.85f else 1f)).padding(14.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        if (e is ProjectEntry.Corrupt) Icons.Outlined.BrokenImage else Icons.Outlined.NewReleases, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (e is ProjectEntry.Corrupt) "This project file is damaged. You can delete it from the ⋮ menu."
                        else "This project was made with a newer version of Basic Art. Update the app to open it.",
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menu = true }) {
                    Box(
                        Modifier.size(32.dp).clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "Options for $name", modifier = Modifier.size(20.dp))
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (e is ProjectEntry.Ok) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; rename = true })
                        DropdownMenuItem(text = { Text("Duplicate") }, onClick = {
                            menu = false
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { app.store.duplicate(e.id, (e.name + " copy").take(100)) }
                                if (ok == null) onMessage("Couldn't duplicate the project")
                                onChanged()
                            }
                        })
                        DropdownMenuItem(text = { Text("Export project file") }, onClick = { menu = false; exportFile = true })
                    }
                    DropdownMenuItem(
                        text = { Text("Delete project", color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; confirmDelete = true },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            Projects.relativeDate(sortMillis(e)),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete '$name'?",
            text = "This removes the project from Basic Art. Images you've already exported are not affected.",
            confirm = "Delete",
            destructive = true,
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) { app.store.delete(e.id) }
                    ThumbCache.invalidate(e.id)
                    onChanged()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
    if (exportFile && e is ProjectEntry.Ok) {
        ProjectFileDialog(app, e.id, e.name, onDismiss = { exportFile = false }, onMessage = onMessage)
    }
    if (rename && e is ProjectEntry.Ok) {
        RenameDialog("Rename project", e.name, onRename = { n ->
            scope.launch {
                withContext(Dispatchers.IO) { app.store.rename(e.id, n) }
                onChanged()
            }
        }, onDismiss = { rename = false })
    }
}

private fun sortMillis(e: ProjectEntry) = when (e) {
    is ProjectEntry.Ok -> e.modifiedMillis
    is ProjectEntry.Newer -> e.modifiedMillis
    is ProjectEntry.Corrupt -> e.modifiedMillis
}
