package com.halworks.basicart.ui.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.ViewSidebar
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.halworks.basicart.data.AppContainer
import com.halworks.basicart.model.DrawingLayer
import com.halworks.basicart.model.ImageLayer
import com.halworks.basicart.model.ShapeLayer
import com.halworks.basicart.model.TextLayer
import com.halworks.basicart.ui.LocalWorkspace
import com.halworks.basicart.ui.common.ColorPickerSheet
import com.halworks.basicart.ui.common.RenameDialog
import kotlinx.coroutines.launch

/** Request for the shared color picker. */
class ColorRequest(val title: String, val initial: Int, val allowAlpha: Boolean = true, val onChange: (Int) -> Unit, val onDone: (Int) -> Unit)

@Composable
fun EditorScreen(app: AppContainer, projectId: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val st = remember(projectId) { EditorState(app, projectId, scope) }
    val view = remember(projectId) { ViewXf() }
    val cache = remember(projectId) { LayerCache(st.renderer) }
    val snapping by app.settings.snapping.collectAsState()
    val snack = remember { SnackbarHostState() }
    var colorReq by remember { mutableStateOf<ColorRequest?>(null) }
    var showExport by remember { mutableStateOf(false) }
    var renameProject by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val cfg = LocalConfiguration.current
    // Side panels only when the width allows it: landscape (phone or tablet) or very wide screens.
    // Tablet portrait uses the phone-style bottom panel so the canvas keeps the full width.
    val landscape = cfg.screenWidthDp > cfg.screenHeightDp
    val wide = cfg.screenWidthDp >= 900 || (landscape && cfg.screenWidthDp >= 600)
    // Short screens (phone landscape): tools move to a vertical rail so the canvas keeps its height.
    val rail = wide && cfg.screenHeightDp < 600
    // Docked panels are collapsible; state is remembered across sessions. Default: visible.
    // Short screens remember their own state; layers start hidden there so options get the room.
    val dockKey = if (rail) "_short" else ""
    var dockLayers by remember(rail) { mutableStateOf(app.settings.dockedPanel("layers$dockKey") ?: !rail) }
    var dockOptions by remember(rail) { mutableStateOf(app.settings.dockedPanel("options$dockKey") ?: true) }
    fun setDockLayers(v: Boolean) { dockLayers = v; app.settings.setDockedPanel("layers$dockKey", v) }
    fun setDockOptions(v: Boolean) { dockOptions = v; app.settings.setDockedPanel("options$dockKey", v) }

    LaunchedEffect(projectId) { st.load() }
    LaunchedEffect(st.hint) { st.hint?.let { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(it); st.hint = null } }

    fun close() { st.close(); onClose() }
    BackHandler {
        when {
            st.editingTextId != null -> st.finishEditing()
            st.eyedropper != null -> st.eyedropper = null
            else -> close()
        }
    }

    // Save when the app goes to the background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) { st.endLive(); st.saveNow() } }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) {
            importing = true
            scope.launch {
                val n = st.importImages(context, uris)
                importing = false
                if (n < uris.size) snack.showSnackbar(if (n == 0) "Couldn't open those images" else "Some images couldn't be opened")
                st.tool = Tool.ADJUST
            }
        }
    }

    val err = st.loadError
    if (err != null) {
        AlertDialog(
            onDismissRequest = { onClose() },
            title = { Text("Can't open project") },
            text = { Text(err) },
            confirmButton = { TextButton(onClick = onClose) { Text("Back") } },
        )
        return
    }
    val doc = st.doc
    if (doc == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val requestColor: (ColorRequest) -> Unit = { colorReq = it }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
    ) {
        EditorTopBar(
            st, doc.name,
            onBack = ::close,
            onRename = { renameProject = true },
            onExport = { st.stateBeforeExport = st.editorStateDoc(); st.finishEditing(); st.endLive(); showExport = true },
            layersShown = if (wide) dockLayers else st.showLayers,
            onToggleLayers = { if (wide) setDockLayers(!dockLayers) else st.showLayers = !st.showLayers },
            optionsShown = if (wide) dockOptions else null,
            onToggleOptions = { setDockOptions(!dockOptions) },
        )
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (rail && st.editingTextId == null) {
                ToolRail(st, onImage = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth().background(LocalWorkspace.current.backdrop)) {
                        EditorCanvas(st, view, cache, snapping, Modifier.fillMaxSize())
                        // Fit-to-screen button (steps aside while dragging or over the selection's handles)
                        androidx.compose.animation.AnimatedVisibility(
                            !st.fitHidden, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut(),
                            modifier = Modifier.align(Alignment.BottomEnd),
                        ) {
                        Surface(
                            onClick = { st.fitRequest++ },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                            tonalElevation = 2.dp, shadowElevation = 2.dp,
                            modifier = Modifier.padding(12.dp).size(44.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.FitScreen, "Fit canvas to screen", Modifier.size(22.dp)) }
                        }
                        }
                        if (st.eyedropper != null) {
                            Surface(
                                shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.inverseSurface,
                                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
                                    Text("Drag on the canvas, lift to pick", color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.bodyMedium)
                                    TextButton(onClick = { st.eyedropper = null }) { Text("Cancel", color = MaterialTheme.colorScheme.inversePrimary) }
                                }
                            }
                        }
                        if (importing) {
                            CircularProgressIndicator(Modifier.align(Alignment.Center))
                        }
                        // Phone: layers panel floats over the canvas.
                        androidx.compose.animation.AnimatedVisibility(
                            visible = !wide && st.showLayers,
                            enter = slideInHorizontally { it } + fadeIn(),
                            exit = slideOutHorizontally { it } + fadeOut(),
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                tonalElevation = 3.dp, shadowElevation = 6.dp,
                                modifier = Modifier.width(320.dp).heightIn(max = 440.dp),
                            ) { LayersPanel(st, cache, onClose = { st.showLayers = false }) }
                        }
                    }
                    if (!wide && st.eyedropper == null) {
                        ContextPanel(st, requestColor, onPickImages = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                    }
                    if (wide && st.editingTextId != null) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainer) { TextEditBar(st, tabsVisible = dockOptions) }
                    }
                    if (st.editingTextId == null && !rail) {
                        ToolBar(st, onImage = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                    }
                }
                SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
            }
            // Tablet / landscape: tool options and layers dock in a collapsible side column.
            androidx.compose.animation.AnimatedVisibility(
                visible = wide && (dockOptions || dockLayers),
                enter = androidx.compose.animation.expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                exit = androidx.compose.animation.shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
            ) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(if (rail) 320.dp else 360.dp).fillMaxHeight()) {
                    Column(Modifier.fillMaxSize()) {
                        // Options take the room they need (font browser fills it); layers get the rest.
                        if (dockOptions) {
                            DockHeader(toolTitle(st), "Hide tool options") { setDockOptions(false) }
                            Box(Modifier.weight(if (dockLayers) 1.6f else 1f).fillMaxWidth()) {
                                ContextPanel(st, requestColor, docked = true, onPickImages = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                            }
                        }
                        if (dockLayers) {
                            if (dockOptions) androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                            Box(Modifier.weight(1f)) { LayersPanel(st, cache, onClose = { setDockLayers(false) }, docked = true) }
                        }
                    }
                }
            }
        }
    }

    // Hidden text field that receives keyboard input for on-canvas text editing.
    HiddenTextInput(st)

    colorReq?.let { req ->
        ColorPickerSheet(
            app, req.title, req.initial, req.allowAlpha,
            onEyedropper = {
                colorReq = null
                st.eyedropper = { c ->
                    val picked = if (req.allowAlpha) c else com.halworks.basicart.model.Colors.withAlpha(c, 255)
                    app.settings.addRecentColor(picked)
                    req.onChange(picked); req.onDone(picked)
                    colorReq = ColorRequest(req.title, picked, req.allowAlpha, req.onChange, req.onDone)
                }
            },
            onChange = req.onChange,
            onDismiss = { c -> req.onDone(c); if (colorReq === req) colorReq = null },
        )
    }
    if (renameProject) {
        RenameDialog("Rename project", doc.name, onRename = { st.renameProject(it) }, onDismiss = { renameProject = false })
    }
    if (showExport) {
        ExportSheet(app, st, onDismiss = { showExport = false }, onMessage = { m -> scope.launch { snack.showSnackbar(m) } })
    }
}

@Composable
private fun EditorTopBar(
    st: EditorState, name: String, onBack: () -> Unit, onRename: () -> Unit, onExport: () -> Unit,
    layersShown: Boolean, onToggleLayers: () -> Unit, optionsShown: Boolean?, onToggleOptions: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
      androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().statusBarsPadding()) {
        // When the project name wouldn't fit beside a labelled Export button, Export collapses
        // to its icon so the name gets the room.
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val density = androidx.compose.ui.platform.LocalDensity.current
        val titleStyle = MaterialTheme.typography.titleMedium
        val labelStyle = MaterialTheme.typography.labelLarge
        val titleW = with(density) { measurer.measure(name, titleStyle).size.width.toDp() } + 16.dp
        val exportW = with(density) { measurer.measure("Export", labelStyle).size.width.toDp() } + 24.dp + 18.dp + 6.dp + 4.dp
        val iconsW = if (optionsShown != null) (48 * 3 + 2 * 82 + 8).dp else (48 * 4 + 8).dp
        val compactExport = maxWidth - iconsW - exportW < titleW
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to home") }
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = "Rename project", onClick = onRename)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            )
            IconButton(onClick = { st.undo() }, enabled = st.canUndo) { Icon(Icons.AutoMirrored.Outlined.Undo, "Undo") }
            IconButton(onClick = { st.redo() }, enabled = st.canRedo) { Icon(Icons.AutoMirrored.Outlined.Redo, "Redo") }
            if (optionsShown != null) {
                // Docked layout: labelled toggles for the two side panels.
                PanelToggle("Options", Icons.Outlined.ViewSidebar, optionsShown, onToggleOptions)
                PanelToggle("Layers", Icons.Outlined.Layers, layersShown, onToggleLayers)
            } else {
                IconToggleButton(checked = layersShown, onCheckedChange = { onToggleLayers() }) {
                    Icon(Icons.Outlined.Layers, if (layersShown) "Hide layers" else "Show layers",
                        tint = if (layersShown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (compactExport) {
                IconButton(onClick = onExport) { Icon(Icons.Outlined.IosShare, "Export", tint = MaterialTheme.colorScheme.primary) }
            } else {
                TextButton(onClick = onExport, modifier = Modifier.padding(end = 4.dp)) {
                    Icon(Icons.Outlined.IosShare, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export", maxLines = 1, softWrap = false)
                }
            }
        }
      }
    }
}

@Composable
private fun PanelToggle(label: String, icon: ImageVector, on: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(18.dp),
        color = if (on) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.padding(horizontal = 2.dp).height(36.dp)
            .semantics { contentDescription = (if (on) "Hide " else "Show ") + label.lowercase() },
    ) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class ToolItem(val tool: Tool, val icon: ImageVector)

private val TOOLS = listOf(
    ToolItem(Tool.SELECT, Icons.Outlined.NearMe),
    ToolItem(Tool.TEXT, Icons.Outlined.TextFields),
    ToolItem(Tool.IMAGE, Icons.Outlined.AddPhotoAlternate),
    ToolItem(Tool.DRAW, Icons.Outlined.Brush),
    ToolItem(Tool.SHAPES, Icons.Outlined.Category),
    ToolItem(Tool.ADJUST, Icons.Outlined.Tune),
    ToolItem(Tool.CANVAS, Icons.Outlined.AspectRatio),
)

private fun selectTool(st: EditorState, t: Tool, onImage: () -> Unit) {
    st.finishEditing()
    if (t == Tool.IMAGE) { onImage(); return }
    if (st.tool == t) st.panelCollapsed = !st.panelCollapsed else st.panelCollapsed = false
    st.tool = t
    // Keep the selection only when it fits the tool.
    val sel = st.selected
    val keep = when (t) {
        Tool.TEXT -> sel is TextLayer
        Tool.SHAPES -> sel is ShapeLayer
        Tool.DRAW -> true // the eraser works on any selected layer
        Tool.ADJUST -> sel is ImageLayer
        else -> true
    }
    if (!keep) st.selectedId = null
}

@Composable
private fun ToolButton(st: EditorState, t: ToolItem, modifier: Modifier, onImage: () -> Unit) {
    val active = st.tool == t.tool && t.tool != Tool.IMAGE
    val color = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = t.tool.label) { selectTool(st, t.tool, onImage) }
            .padding(vertical = 4.dp)
            .semantics { contentDescription = "${t.tool.label} tool" + if (active) ", selected" else "" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(width = 52.dp, height = 30.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(if (active) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent),
            contentAlignment = Alignment.Center,
        ) { Icon(t.icon, null, tint = color, modifier = Modifier.size(22.dp)) }
        Text(t.tool.label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun ToolBar(st: EditorState, onImage: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (t in TOOLS) ToolButton(st, t, Modifier.weight(1f), onImage)
        }
    }
}

/** Vertical tool rail for short (landscape phone) screens. */
@Composable
private fun ToolRail(st: EditorState, onImage: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxHeight().width(72.dp)) {
        Column(
            Modifier.fillMaxHeight().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for (t in TOOLS) ToolButton(st, t, Modifier.width(68.dp).height(52.dp), onImage)
        }
    }
}

@Composable
private fun DockHeader(title: String, collapseLabel: String, onCollapse: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        IconButton(onClick = onCollapse) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, collapseLabel) }
    }
}

private fun toolTitle(st: EditorState): String = when {
    st.editingTextId != null || st.tool == Tool.TEXT || (st.tool == Tool.SELECT && st.selected is TextLayer) -> "Text"
    st.tool == Tool.DRAW -> "Draw"
    st.tool == Tool.SHAPES || (st.tool == Tool.SELECT && st.selected is ShapeLayer) -> "Shape"
    st.tool == Tool.ADJUST || (st.tool == Tool.SELECT && st.selected is ImageLayer) -> "Photo"
    st.tool == Tool.CANVAS -> "Canvas"
    st.selected is DrawingLayer -> "Drawing"
    else -> "Tool options"
}

/** The compact, collapsible options panel for the active tool / selected layer. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ContextPanel(st: EditorState, requestColor: (ColorRequest) -> Unit, docked: Boolean = false, onPickImages: () -> Unit) {
    val imeVisible = WindowInsets.isImeVisible
    // Keyboard dismissed (e.g. Back) while typing → offer the text tabs; other fields opening the
    // keyboard (font search, number entry) don't close them.
    var wasIme by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (wasIme && !imeVisible && st.editingTextId != null) st.textTabsWhileEditing = true
        wasIme = imeVisible
    }
    val sel = st.selected
    val kind: String? = when {
        st.editingTextId != null -> "text"
        st.tool == Tool.DRAW -> "draw"
        st.tool == Tool.CANVAS -> "canvas"
        st.tool == Tool.SHAPES -> "shape"
        st.tool == Tool.ADJUST -> "adjust"
        st.tool == Tool.TEXT -> "text"
        sel is TextLayer -> "text"
        sel is ShapeLayer -> "shape"
        sel is ImageLayer -> "adjust"
        sel is DrawingLayer -> "drawing"
        else -> null
    }
    Surface(
        color = if (docked) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().then(if (docked) Modifier.fillMaxHeight() else Modifier.animateContentSize()),
    ) {
        Column {
            if (st.editingTextId != null && !docked) TextEditBar(st)
            if (docked && kind == null) Hint("Select a layer, or pick a tool.")
            if (kind != null && st.editingTextId == null && !docked) {
                Row(
                    // Generous target (taller while collapsed) so a tap just above the chevron
                    // expands the panel instead of landing on the canvas.
                    Modifier.fillMaxWidth().height(if (st.panelCollapsed) 44.dp else 28.dp).clickable(onClickLabel = if (st.panelCollapsed) "Expand options" else "Collapse options") { st.panelCollapsed = !st.panelCollapsed },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (st.panelCollapsed) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
            // While typing, the text tabs show whenever the keyboard is down (selection is kept).
            AnimatedVisibility(visible = kind != null && (docked || !st.panelCollapsed) && (st.editingTextId == null || docked || st.textTabsWhileEditing), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                // Taller at large font scales so rows aren't cut, but never more than ~45% of the screen.
                val fs = androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceIn(1f, 1.4f)
                val maxPanel = minOf(300.dp * fs, androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.45f).coerceAtLeast(300.dp)
                Box(Modifier.fillMaxWidth().then(if (docked) Modifier.fillMaxHeight() else Modifier.heightIn(max = maxPanel)).padding(bottom = 6.dp)) {
                    when (kind) {
                        "text" -> TextPanel(st, requestColor, tall = docked)
                        "draw" -> DrawPanel(st, requestColor)
                        "shape" -> ShapePanel(st, requestColor)
                        "adjust" -> AdjustPanel(st, requestColor, onPickImages)
                        "canvas" -> CanvasPanel(st, requestColor)
                        "drawing" -> DrawingLayerPanel(st)
                    }
                }
            }
        }
    }
}

/** EditText subclass that reports selection changes. */
private class InputSink(context: android.content.Context) : android.widget.EditText(context) {
    var onSel: ((Int, Int) -> Unit)? = null
    /** Visual line [start, end) around a text offset, from the canvas layout (this view is 1dp wide). */
    var lineAt: ((Int) -> Pair<Int, Int>)? = null

    // Hardware Home/End (and Ctrl+Home/End) move the caret by the text's real lines; Shift extends.
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_MOVE_HOME || keyCode == android.view.KeyEvent.KEYCODE_MOVE_END) {
            val len = length()
            val home = keyCode == android.view.KeyEvent.KEYCODE_MOVE_HOME
            val focus = selectionEnd.coerceIn(0, len)
            val target = if (event.isCtrlPressed) (if (home) 0 else len)
                else lineAt?.invoke(focus)?.let { (a, b) -> if (home) a else b } ?: (if (home) 0 else len)
            if (event.isShiftPressed) setSelection(selectionStart.coerceIn(0, len), target.coerceIn(0, len)) else setSelection(target.coerceIn(0, len))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSel?.invoke(selStart, selEnd)
    }
}

/**
 * Invisible platform text field that receives keyboard input for on-canvas editing.
 * (A platform EditText avoids selection-handle popups; the caret and highlight are drawn
 * on the canvas instead.)
 */
@Composable
private fun HiddenTextInput(st: EditorState) {
    val id = st.editingTextId ?: return
    var syncing by remember { mutableStateOf(false) }
    val viewRef = remember { arrayOfNulls<android.view.View>(1) }
    LaunchedEffect(st.keyboardShowRequest) {
        if (st.keyboardShowRequest == 0) return@LaunchedEffect
        viewRef[0]?.let { v ->
            v.requestFocus()
            (v.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(v, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }
    LaunchedEffect(st.keyboardHideRequest) {
        if (st.keyboardHideRequest == 0) return@LaunchedEffect
        viewRef[0]?.let { v ->
            (v.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(v.windowToken, 0)
        }
    }
    // Hide the keyboard when editing ends, using the (still attached) root view's window token.
    val rootView = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(id) {
        onDispose {
            val imm = rootView.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(rootView.windowToken, 0)
        }
    }
    // The wrapper keeps the 1dp size even under a parent that forces min constraints.
    Box(Modifier.wrapContentSize(Alignment.TopStart)) {
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.requiredSize(1.dp).alpha(0f).semantics { contentDescription = "Text input" },
        factory = { ctx ->
            InputSink(ctx).also { viewRef[0] = it }.apply {
                background = null
                setTextColor(android.graphics.Color.TRANSPARENT)
                isCursorVisible = false
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
                isLongClickable = false
                setTextIsSelectable(false)
                setText(st.textValue.text)
                setSelection(st.textValue.selection.start.coerceIn(0, length()), st.textValue.selection.end.coerceIn(0, length()))
                fun push() {
                    if (syncing) return
                    val t = text?.toString() ?: ""
                    st.onTextValueChange(androidx.compose.ui.text.input.TextFieldValue(t, androidx.compose.ui.text.TextRange(selectionStart.coerceAtLeast(0), selectionEnd.coerceAtLeast(0))))
                }
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) { push() }
                })
                onSel = { _, _ -> push() }
                lineAt = { off -> st.lineBounds(off) }
                post {
                    requestFocus()
                    // A restored text selection opens without raising the keyboard (FORMAT.md §10.7).
                    if (st.suppressKeyboardOnce) { st.suppressKeyboardOnce = false; return@post }
                    val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }
        },
        update = { v ->
            val tv = st.textValue
            val cur = v.text?.toString() ?: ""
            if (cur != tv.text || v.selectionStart != tv.selection.start || v.selectionEnd != tv.selection.end) {
                syncing = true
                if (cur != tv.text) v.setText(tv.text)
                v.setSelection(tv.selection.start.coerceIn(0, v.length()), tv.selection.end.coerceIn(0, v.length()))
                syncing = false
            }
        },
        onRelease = { v ->
            v.clearFocus()
            val imm = v.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(v.windowToken, 0)
        },
    )
    }
    @Suppress("UNUSED_EXPRESSION") id
}
