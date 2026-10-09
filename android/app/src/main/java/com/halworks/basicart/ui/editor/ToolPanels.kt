package com.halworks.basicart.ui.editor

import android.graphics.Matrix
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowRightAlt
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.HorizontalRule
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material.icons.outlined.RoundedCorner
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.halworks.basicart.model.Adjust
import com.halworks.basicart.model.ArrowHeads
import com.halworks.basicart.model.Brush
import com.halworks.basicart.model.CropRect
import com.halworks.basicart.model.DrawingLayer
import com.halworks.basicart.model.ImageLayer
import com.halworks.basicart.model.Join
import com.halworks.basicart.model.ShapeKind
import com.halworks.basicart.model.ShapeLayer
import com.halworks.basicart.model.TRANSPARENT
import com.halworks.basicart.model.WHITE
import com.halworks.basicart.ui.LocalWorkspace
import com.halworks.basicart.ui.common.Checkerboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// ================================================================== Draw

// The Eraser leads the row and stays pinned (never scrolled off-screen while it's active).
private val BRUSHES = listOf(
    Brush.ERASER to "Eraser", Brush.PEN to "Pen", Brush.MARKER to "Marker", Brush.HIGHLIGHTER to "Highlighter",
    Brush.AIRBRUSH to "Airbrush", Brush.CALLIGRAPHY to "Calligraphy", Brush.PENCIL to "Pencil",
)

@Composable
fun DrawPanel(st: EditorState, requestColor: (ColorRequest) -> Unit) {
    Column(run { val vs = rememberScrollState(); Modifier.fadeEdgesVertical(vs).verticalScroll(vs) }.padding(top = 4.dp)) {
        // Seven equal tiles, each showing a real stroke of that brush. When they don't all fit,
        // the Eraser stays pinned at the start and the drawing brushes scroll beside it.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val fits = maxWidth >= (74 * BRUSHES.size + 16).dp
            if (fits) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BRUSHES.forEach { (b, label) -> BrushTile(st, b, label, Modifier.weight(1f)) }
                }
            } else {
                val scroll = rememberScrollState()
                val density = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    val i = BRUSHES.indexOfFirst { it.first == st.brush } - 1
                    if (i > 0) scroll.scrollTo(with(density) { (78.dp * (i - 1)).roundToPx() })
                }
                Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val (eb, el) = BRUSHES.first()
                    BrushTile(st, eb, el, Modifier.width(74.dp))
                    Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(44.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    Row(
                        Modifier.weight(1f).fadeEdges(scroll).horizontalScroll(scroll).padding(end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        BRUSHES.drop(1).forEach { (b, label) -> BrushTile(st, b, label, Modifier.width(74.dp)) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (st.brush != Brush.ERASER) {
                ColorWell("Color", st.brushColor) {
                    requestColor(ColorRequest("Brush color", st.brushColor, allowAlpha = false, onChange = { st.brushColor = it }, onDone = { st.brushColor = it }))
                }
            } else {
                val target = st.eraserTarget()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).weight(1f)) {
                    if (target != null) {
                        Text("Erasing from", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(target.base.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    } else {
                        Text("Select a layer to erase (use Layers)", style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            // live preview dot
            val dot = (st.brushSize / 2f).coerceIn(2f, 22f)
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                Box(Modifier.size((dot * 2).dp).clip(CircleShape).background(
                    if (st.brush == Brush.ERASER) MaterialTheme.colorScheme.outline else Color(st.brushColor).copy(alpha = st.brushOpacity),
                ))
            }
        }
        LabeledSlider("Size", st.brushSize, 1f..200f, { "${it.roundToInt()} px" }, editUnit = 1f, onDone = {}) { st.brushSize = it.roundToInt().toFloat().coerceAtLeast(1f) }
        LabeledSlider(if (st.brush == Brush.ERASER) "Strength" else "Opacity", st.brushOpacity, 0.05f..1f, ::fmtPct, onDone = {}) { st.brushOpacity = it }
        LabeledSlider("Smoothing", st.smoothing, 0f..1f, ::fmtPct, onDone = {}) { st.smoothing = it }
    }
}

/** Fades the scrolled-off edges of a horizontal scroller so it reads as "more this way". */
fun Modifier.fadeEdges(scroll: androidx.compose.foundation.ScrollState, width: androidx.compose.ui.unit.Dp = 24.dp) = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val w = width.toPx()
        if (scroll.value > 0) drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), 0f, w),
            size = androidx.compose.ui.geometry.Size(w, size.height), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
        if (scroll.value < scroll.maxValue) drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), size.width - w, size.width),
            topLeft = Offset(size.width - w, 0f), size = androidx.compose.ui.geometry.Size(w, size.height), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
    }

/** Equal-width selectable tile: preview above a compact label. */
@Composable
fun PickTile(selected: Boolean, label: String, modifier: Modifier, content: @Composable () -> Unit, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .then(if (selected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)
            .clickable(onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label + if (selected) ", selected" else "" }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.height(26.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides fg) { content() }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1, softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Clip)
    }
}

@Composable
private fun BrushTile(st: EditorState, b: Brush, label: String, modifier: Modifier) {
    val selected = st.brush == b
    val ink = if (b == Brush.ERASER) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
    val inkArgb = android.graphics.Color.argb(255, (ink.red * 255).toInt(), (ink.green * 255).toInt(), (ink.blue * 255).toInt())
    val preview by produceState<android.graphics.Bitmap?>(null, b, inkArgb) {
        value = withContext(Dispatchers.Default) {
            val bmp = android.graphics.Bitmap.createBitmap(120, 52, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            val pts = FloatArray(16) { i -> if (i % 2 == 0) 14f + (i / 2) * 13f else 26f + 12f * kotlin.math.sin((i / 2) * 0.9f) }
            val stroke = com.halworks.basicart.model.Stroke(
                brush = if (b == Brush.ERASER) Brush.PEN else b, size = 9.0, color = inkArgb, opacity = if (b == Brush.ERASER) 0.5 else 1.0,
                points = com.halworks.basicart.model.FloatList(pts),
            )
            st.renderer.drawStroke(c, stroke)
            bmp
        }
    }
    PickTile(selected, label, modifier, content = {
        preview?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().height(26.dp)) }
    }, onClick = { st.brush = b })
}

@Composable
fun DrawingLayerPanel(st: EditorState) {
    val l = st.selected as? DrawingLayer ?: return
    Column {
        Hint("Drawing layer with ${l.strokes.size} stroke${if (l.strokes.size == 1) "" else "s"}. Pick Draw to add more.")
        LabeledSlider("Opacity", l.base.opacity.toFloat(), 0f..1f, ::fmtPct, onDone = st::endLive) { v ->
            st.updateSelected<DrawingLayer>(true) { it.copy(base = it.base.copy(opacity = Math.round(v * 100) / 100.0)) }
        }
    }
}

// ================================================================== Shapes

private val SHAPES: List<Triple<ShapeKind, String, ImageVector>> = listOf(
    Triple(ShapeKind.RECT, "Rectangle", Icons.Outlined.CropSquare),
    Triple(ShapeKind.ROUND_RECT, "Rounded", Icons.Outlined.CheckBoxOutlineBlank),
    Triple(ShapeKind.ELLIPSE, "Ellipse", Icons.Outlined.Circle),
    Triple(ShapeKind.LINE, "Line", Icons.Outlined.HorizontalRule),
    Triple(ShapeKind.ARROW, "Arrow", Icons.AutoMirrored.Outlined.ArrowRightAlt),
)

@Composable
fun ShapePanel(st: EditorState, requestColor: (ColorRequest) -> Unit) {
    val s = st.selected as? ShapeLayer
    Column(run { val vs = rememberScrollState(); Modifier.fadeEdgesVertical(vs).verticalScroll(vs) }.padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SHAPES.forEach { (k, label, icon) ->
                val on = (s?.shape ?: st.shapeKind) == k
                PickTile(
                    selected = on, label = label, modifier = Modifier.weight(1f),
                    content = { Icon(icon, null, Modifier.size(22.dp)) },
                    onClick = {
                        st.shapeKind = k
                        if (s != null && s.shape.isLinear == k.isLinear) st.updateSelected<ShapeLayer> {
                            // Auto-named layers follow the new kind ("Rectangle 2" → "Ellipse 2").
                            val oldBase = SHAPES.first { e -> e.first == it.shape }.second
                            val newBase = SHAPES.first { e -> e.first == k }.second
                            val n = it.base.name
                            val renamed = if (n.startsWith(oldBase) || n.startsWith("Rectangle") || n.startsWith("Rounded rectangle")) newBase + n.substringAfterLast(' ', "").let { suf -> if (suf.all(Char::isDigit) && suf.isNotEmpty()) " $suf" else "" } else n
                            it.copy(shape = k, base = it.base.copy(name = renamed))
                        }
                    },
                )
            }
        }
        if (s == null) {
            Hint("Tap the canvas to add a ${SHAPES.first { it.first == st.shapeKind }.second.lowercase()}, or drag to draw one.")
            PanelRow {
                ColorWell("Color", st.brushColor) {
                    requestColor(ColorRequest("Shape color", st.brushColor, onChange = { st.brushColor = it }, onDone = { st.brushColor = it }))
                }
            }
            return@Column
        }
        if (!s.shape.isLinear) {
            SwitchRow("Fill", s.fill.enabled, { v -> st.updateSelected<ShapeLayer> { it.copy(fill = it.fill.copy(enabled = v)) } }) {
                ColorWell("Color", s.fill.color) {
                    requestColor(ColorRequest("Fill color", s.fill.color, onChange = { c -> st.updateSelected<ShapeLayer>(true) { it.copy(fill = it.fill.copy(color = c, enabled = true)) } }, onDone = { st.endLive() }))
                }
            }
            SwitchRow("Stroke", s.stroke.enabled, { v -> st.updateSelected<ShapeLayer> { it.copy(stroke = it.stroke.copy(enabled = v)) } }) {
                ColorWell("Color", s.stroke.color) {
                    requestColor(ColorRequest("Stroke color", s.stroke.color, onChange = { c -> st.updateSelected<ShapeLayer>(true) { it.copy(stroke = it.stroke.copy(color = c, enabled = true)) } }, onDone = { st.endLive() }))
                }
            }
        } else {
            PanelRow {
                ColorWell("Color", s.stroke.color) {
                    requestColor(ColorRequest("Line color", s.stroke.color, onChange = { c -> st.updateSelected<ShapeLayer>(true) { it.copy(stroke = it.stroke.copy(color = c)) } }, onDone = { st.endLive() }))
                }
            }
        }
        LabeledSlider("Stroke width", s.stroke.width.toFloat(), 1f..max(100f, s.stroke.width.toFloat()), { "${it.roundToInt()} px" },
            enabled = s.shape.isLinear || s.stroke.enabled, onDone = st::endLive) { v ->
            st.updateSelected<ShapeLayer>(true) { it.copy(stroke = it.stroke.copy(width = v.roundToInt().toDouble()), height = if (it.shape.isLinear) max(1.0, v.roundToInt().toDouble()) else it.height) }
        }
        if (s.shape == ShapeKind.ROUND_RECT) {
            LabeledSlider("Corners", s.cornerRadius.toFloat(), 0f..(min(s.width, s.height) / 2).toFloat().coerceAtLeast(1f), { "${it.roundToInt()} px" }, onDone = st::endLive) { v ->
                st.updateSelected<ShapeLayer>(true) { it.copy(cornerRadius = v.roundToInt().toDouble()) }
            }
        }
        if (s.shape == ShapeKind.ARROW) {
            Segmented(ArrowHeads.entries, s.arrowHeads, { when (it) { ArrowHeads.END -> "End"; ArrowHeads.START -> "Start"; ArrowHeads.BOTH -> "Both" } }) { h ->
                st.updateSelected<ShapeLayer> { it.copy(arrowHeads = h) }
            }
        }
        if (!s.shape.isLinear && s.stroke.enabled) {
            Segmented(Join.entries, s.stroke.join, { if (it == Join.ROUND) "Round corners" else "Sharp corners" }) { j ->
                st.updateSelected<ShapeLayer> { it.copy(stroke = it.stroke.copy(join = j)) }
            }
        }
        LabeledSlider("Opacity", s.base.opacity.toFloat(), 0f..1f, ::fmtPct, onDone = st::endLive) { v ->
            st.updateSelected<ShapeLayer>(true) { it.copy(base = it.base.copy(opacity = Math.round(v * 100) / 100.0)) }
        }
    }
}

// ================================================================== Adjust (image)

@Composable
fun AdjustPanel(st: EditorState, requestColor: (ColorRequest) -> Unit, onPickImages: () -> Unit) {
    val img = st.selected as? ImageLayer
    if (img == null) {
        Column {
            Hint("Select a photo to crop, rotate, flip or adjust it.")
            PanelRow {
                FilledTonalButton(onClick = onPickImages, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)) {
                    Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add photos")
                }
            }
        }
        return
    }
    var cropping by remember { mutableStateOf(false) }
    Column(run { val vs = rememberScrollState(); Modifier.fadeEdgesVertical(vs).verticalScroll(vs) }.padding(top = 4.dp)) {
        Row(run { val sc = rememberScrollState(); Modifier.fadeEdges(sc).horizontalScroll(sc) }.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ActionButton(Icons.Outlined.Crop, "Crop") { cropping = true }
            ActionButton(Icons.Outlined.Rotate90DegreesCw, "Rotate") {
                // Rotate 90°: increment rotate90 and swap the flips (FORMAT.md §6).
                st.updateSelected<ImageLayer> { it.copy(rotate90 = (it.rotate90 + 1) % 4, flipH = it.flipV, flipV = it.flipH) }
            }
            ActionButton(Icons.Outlined.Flip, "Flip H") { st.updateSelected<ImageLayer> { it.copy(flipH = !it.flipH) } }
            ActionButton(Icons.Outlined.Flip, "Flip V", rotate = 90f) { st.updateSelected<ImageLayer> { it.copy(flipV = !it.flipV) } }
            ActionButton(Icons.Outlined.Restore, "Reset") { st.updateSelected<ImageLayer> { it.copy(adjust = Adjust()) } }
        }
        LabeledSlider("Opacity", img.base.opacity.toFloat(), 0f..1f, ::fmtPct, onDone = st::endLive) { v ->
            st.updateSelected<ImageLayer>(true) { it.copy(base = it.base.copy(opacity = Math.round(v * 100) / 100.0)) }
        }
        @Composable
        fun adj(label: String, v: Double, set: (Adjust, Double) -> Adjust) {
            LabeledSlider(label, v.toFloat(), -100f..100f, { (if (it > 0.5f) "+" else "") + it.roundToInt() }, onDone = st::endLive) { x ->
                st.updateSelected<ImageLayer>(true) { it.copy(adjust = set(it.adjust, x.roundToInt().toDouble())) }
            }
        }
        adj("Brightness", img.adjust.brightness) { a, v -> a.copy(brightness = v) }
        adj("Contrast", img.adjust.contrast) { a, v -> a.copy(contrast = v) }
        adj("Saturation", img.adjust.saturation) { a, v -> a.copy(saturation = v) }
        adj("Warmth", img.adjust.warmth) { a, v -> a.copy(warmth = v) }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        LabeledSlider("Corners", img.cornerRadius.toFloat(), 0f..(min(img.boxWidth, img.boxHeight) / 2).toFloat(), { "${it.roundToInt()} px" }, onDone = st::endLive) { v ->
            st.updateSelected<ImageLayer>(true) { it.copy(cornerRadius = v.roundToInt().toDouble()) }
        }
        SwitchRow("Border", img.border.enabled, { v ->
            st.updateSelected<ImageLayer> { it.copy(border = it.border.copy(enabled = v, width = if (it.border.width <= 0) max(4.0, (min(it.boxWidth, it.boxHeight) * 0.03).roundToInt().toDouble()) else it.border.width)) }
        }) {
            ColorWell("Color", img.border.color) {
                requestColor(ColorRequest("Border color", img.border.color, onChange = { c -> st.updateSelected<ImageLayer>(true) { it.copy(border = it.border.copy(color = c, enabled = true)) } }, onDone = { st.endLive() }))
            }
        }
        if (img.border.enabled) {
            LabeledSlider("Border width", img.border.width.toFloat(), 0f..(min(img.boxWidth, img.boxHeight) / 4).toFloat().coerceAtLeast(2f), { "${it.roundToInt()} px" }, onDone = st::endLive) { v ->
                st.updateSelected<ImageLayer>(true) { it.copy(border = it.border.copy(width = v.roundToInt().toDouble())) }
            }
        }
    }
    if (cropping) CropDialog(st, img, onDismiss = { cropping = false })
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, rotate: Float = 0f, onClick: () -> Unit) {
    Column(
        Modifier.width(72.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = label, onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp).rotate(rotate))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

// ------------------------------------------------------------------ crop

private val ASPECTS = listOf("Freeform" to 0f, "1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f, "16:9" to 16f / 9f, "9:16" to 9f / 16f)

@Composable
private fun CropDialog(st: EditorState, img: ImageLayer, onDismiss: () -> Unit) {
    val full = img.copy(crop = CropRect(0.0, 0.0, img.naturalWidth.toDouble(), img.naturalHeight.toDouble()))
    // natural px → displayed (rotated/flipped) px of the full image
    val toDisp = remember(img.rotate90, img.flipH, img.flipV) { st.renderer.imageMatrix(full, 1f) }
    val dispW = full.boxWidth.toFloat(); val dispH = full.boxHeight.toFloat()
    var rect by remember { mutableStateOf(RectF(img.crop.x.toFloat(), img.crop.y.toFloat(), (img.crop.x + img.crop.width).toFloat(), (img.crop.y + img.crop.height).toFloat()).also { toDisp.mapRect(it) }) }
    var aspect by remember { mutableStateOf(0f) }
    // The whole (uncropped) photo rendered exactly as on the canvas: adjustments and eraser mask
    // included (the mask remapped to the uncropped box, FORMAT.md §9.1).
    val preview by produceState<Pair<android.graphics.Bitmap, Float>?>(null) {
        value = withContext(Dispatchers.Default) {
            try {
                val k = min(1f, 2048f / maxOf(dispW, dispH))
                val shown = com.halworks.basicart.model.MaskGeometry.remapImage(img, full).let { l ->
                    l.copy(base = l.base.copy(transform = com.halworks.basicart.model.Transform(dispW / 2.0, dispH / 2.0), opacity = 1.0, visible = true))
                }
                val d = com.halworks.basicart.model.Document("crop", "", "", "", "",
                    com.halworks.basicart.model.CanvasSpec(dispW.toInt(), dispH.toInt(), 0), listOf(shown))
                val ow = maxOf(1, (dispW * k).toInt()); val oh = maxOf(1, (dispH * k).toInt())
                st.renderer.renderBitmap(d, k, ow, oh) to k
            } catch (e: Throwable) { null }
        }
    }
    fun applyAspect(a: Float) {
        aspect = a
        if (a <= 0f) return
        val cx = rect.centerX(); val cy = rect.centerY()
        var w = rect.width(); var h = w / a
        if (h > dispH) { h = dispH; w = h * a }
        if (w > dispW) { w = dispW; h = w / a }
        val l = (cx - w / 2).coerceIn(0f, dispW - w); val t = (cy - h / 2).coerceIn(0f, dispH - h)
        rect = RectF(l, t, l + w, t + h)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(color = Color(0xFF111114), modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) }
                    Text("Crop", style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Button(onClick = {
                        val inv = Matrix(); toDisp.invert(inv)
                        val nat = RectF(rect); inv.mapRect(nat)
                        val nx = nat.left.toDouble().coerceIn(0.0, img.naturalWidth - 1.0)
                        val ny = nat.top.toDouble().coerceIn(0.0, img.naturalHeight - 1.0)
                        val crop = CropRect(
                            Math.round(nx * 100) / 100.0, Math.round(ny * 100) / 100.0,
                            (Math.round(nat.width() * 100) / 100.0).coerceIn(1.0, img.naturalWidth - nx),
                            (Math.round(nat.height() * 100) / 100.0).coerceIn(1.0, img.naturalHeight - ny),
                        )
                        // Keep the kept region where it was on the canvas.
                        val old = RectF(img.crop.x.toFloat(), img.crop.y.toFloat(), (img.crop.x + img.crop.width).toFloat(), (img.crop.y + img.crop.height).toFloat())
                        toDisp.mapRect(old)
                        val dx = (rect.centerX() - old.centerX()).toDouble(); val dy = (rect.centerY() - old.centerY()).toDouble()
                        val t = img.base.transform
                        val r = Math.toRadians(t.rotation)
                        val cx = t.x + t.scale * (dx * cos(r) - dy * sin(r))
                        val cy = t.y + t.scale * (dx * sin(r) + dy * cos(r))
                        st.updateSelected<ImageLayer> { it.copy(crop = crop, base = it.base.copy(transform = t.copy(x = cx, y = cy))) }
                        onDismiss()
                    }) { Text("Apply") }
                }
                // Inset from the screen edges so corner drags never start a system back gesture,
                // and exclude the crop area from gesture navigation as well.
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 40.dp, vertical = 24.dp)) {
                    Canvas(
                        Modifier.fillMaxSize()
                            .systemGestureExclusion()
                            .semantics { contentDescription = "Crop area. Drag the corners to crop." }
                            .pointerInput(aspect) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    val s = min(size.width / dispW, size.height / dispH)
                                    val ox = (size.width - dispW * s) / 2f; val oy = (size.height - dispH * s) / 2f
                                    fun toImg(p: Offset) = Offset((p.x - ox) / s, (p.y - oy) / s)
                                    val p0 = toImg(down.position)
                                    val r0 = RectF(rect)
                                    val tol = 32f / s
                                    val nearL = abs(p0.x - r0.left) < tol; val nearR = abs(p0.x - r0.right) < tol
                                    val nearT = abs(p0.y - r0.top) < tol; val nearB = abs(p0.y - r0.bottom) < tol
                                    val corner = (nearL || nearR) && (nearT || nearB)
                                    val edge = !corner && (nearL || nearR || nearT || nearB) && aspect == 0f
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull { it.pressed } ?: break
                                        val p = toImg(ch.position)
                                        val dx = p.x - p0.x; val dy = p.y - p0.y
                                        val n = RectF(r0)
                                        val minS = 8f
                                        if (corner || edge) {
                                            if (nearL) n.left = (r0.left + dx).coerceIn(0f, r0.right - minS)
                                            if (nearR) n.right = (r0.right + dx).coerceIn(r0.left + minS, dispW)
                                            if (nearT) n.top = (r0.top + dy).coerceIn(0f, r0.bottom - minS)
                                            if (nearB) n.bottom = (r0.bottom + dy).coerceIn(r0.top + minS, dispH)
                                            if (aspect > 0f && corner) {
                                                val w = n.width(); val h = w / aspect
                                                if (nearT) n.top = n.bottom - h else n.bottom = n.top + h
                                                if (n.top < 0f || n.bottom > dispH) { n.set(rect) }
                                            }
                                        } else {
                                            val w = r0.width(); val h = r0.height()
                                            val l = (r0.left + dx).coerceIn(0f, dispW - w); val t = (r0.top + dy).coerceIn(0f, dispH - h)
                                            n.set(l, t, l + w, t + h)
                                        }
                                        rect = n
                                        ch.consume()
                                    }
                                }
                            },
                    ) {
                        val s = min(size.width / dispW, size.height / dispH)
                        val ox = (size.width - dispW * s) / 2f; val oy = (size.height - dispH * s) / 2f
                        drawIntoCanvas { c ->
                            preview?.let { (bmp, k) ->
                                val m = Matrix()
                                m.setScale(s / k, s / k); m.postTranslate(ox, oy)
                                c.nativeCanvas.drawBitmap(bmp, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                            }
                        }
                        val l = ox + rect.left * s; val t = oy + rect.top * s; val r = ox + rect.right * s; val b = oy + rect.bottom * s
                        val dim = Color.Black.copy(alpha = 0.55f)
                        drawRect(dim, Offset(ox, oy), androidx.compose.ui.geometry.Size(dispW * s, t - oy))
                        drawRect(dim, Offset(ox, b), androidx.compose.ui.geometry.Size(dispW * s, oy + dispH * s - b))
                        drawRect(dim, Offset(ox, t), androidx.compose.ui.geometry.Size(l - ox, b - t))
                        drawRect(dim, Offset(r, t), androidx.compose.ui.geometry.Size(ox + dispW * s - r, b - t))
                        drawRect(Color.White, Offset(l, t), androidx.compose.ui.geometry.Size(r - l, b - t), style = Stroke(2.dp.toPx()))
                        for (i in 1..2) {
                            drawLine(Color.White.copy(alpha = 0.4f), Offset(l + (r - l) * i / 3, t), Offset(l + (r - l) * i / 3, b))
                            drawLine(Color.White.copy(alpha = 0.4f), Offset(l, t + (b - t) * i / 3), Offset(r, t + (b - t) * i / 3))
                        }
                        val hl = 18.dp.toPx(); val sw = 4.dp.toPx()
                        for ((cx, cy) in listOf(l to t, r to t, l to b, r to b)) {
                            val sx = if (cx == l) 1 else -1; val sy = if (cy == t) 1 else -1
                            drawLine(Color.White, Offset(cx, cy), Offset(cx + sx * hl, cy), sw)
                            drawLine(Color.White, Offset(cx, cy), Offset(cx, cy + sy * hl), sw)
                        }
                    }
                }
                Row(run { val sc = rememberScrollState(); Modifier.fadeEdges(sc).horizontalScroll(sc) }.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val chipColors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                        containerColor = Color.White.copy(alpha = 0.10f), labelColor = Color.White,
                        selectedContainerColor = Color.White, selectedLabelColor = Color.Black,
                    )
                    val chipBorder = androidx.compose.material3.FilterChipDefaults.filterChipBorder(true, false, borderColor = Color.White.copy(alpha = 0.35f))
                    ASPECTS.forEach { (label, a) ->
                        FilterChip(selected = aspect == a, onClick = { applyAspect(a) }, label = { Text(label) }, colors = chipColors, border = chipBorder)
                    }
                    FilterChip(selected = false, onClick = { aspect = 0f; rect = RectF(0f, 0f, dispW, dispH) }, label = { Text("Reset") }, colors = chipColors, border = chipBorder)
                }
            }
        }
    }
}


// ================================================================== Canvas

@Composable
fun CanvasPanel(st: EditorState, requestColor: (ColorRequest) -> Unit) {
    val doc = st.doc ?: return
    var wText by remember(doc.canvas.width) { mutableStateOf(doc.canvas.width.toString()) }
    var hText by remember(doc.canvas.height) { mutableStateOf(doc.canvas.height.toString()) }
    var anchor by remember { mutableStateOf(4) }
    val w = wText.toIntOrNull(); val h = hText.toIntOrNull()
    val valid = w != null && h != null && w in 16..8192 && h in 16..8192
    val changed = valid && (w != doc.canvas.width || h != doc.canvas.height)
    Column(run { val vs = rememberScrollState(); Modifier.fadeEdgesVertical(vs).verticalScroll(vs) }.padding(top = 4.dp)) {
        Text("Background", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        val bg = doc.canvas.background
        com.halworks.basicart.ui.common.BackgroundChips(
            when (bg) { WHITE -> com.halworks.basicart.ui.common.BgKind.WHITE; TRANSPARENT -> com.halworks.basicart.ui.common.BgKind.TRANSPARENT; else -> com.halworks.basicart.ui.common.BgKind.COLOR },
            if (bg == WHITE || bg == TRANSPARENT) 0xFFFFD54F.toInt() else bg,
            Modifier.padding(horizontal = 16.dp),
        ) { k ->
            when (k) {
                com.halworks.basicart.ui.common.BgKind.WHITE -> st.setBackground(WHITE)
                com.halworks.basicart.ui.common.BgKind.TRANSPARENT -> st.setBackground(TRANSPARENT)
                else -> requestColor(ColorRequest("Background color", if (bg == WHITE || bg == TRANSPARENT) 0xFFFFD54F.toInt() else bg, onChange = { st.setBackground(it, true) }, onDone = { st.endLive() }))
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Text("Canvas size (layers are not scaled)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(wText, { wText = it.filter(Char::isDigit).take(4) }, label = { Text("Width") }, singleLine = true, isError = w == null || w !in 16..8192,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            OutlinedTextField(hText, { hText = it.filter(Char::isDigit).take(4) }, label = { Text("Height") }, singleLine = true, isError = h == null || h !in 16..8192,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            // 3×3 anchor grid
            Column(Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)).padding(2.dp).semantics { contentDescription = "Anchor" }) {
                for (r in 0..2) Row { for (c in 0..2) {
                    val i = r * 3 + c
                    Box(Modifier.size(22.dp).clickable(onClickLabel = "Anchor ${listOf("top", "middle", "bottom")[r]} ${listOf("left", "center", "right")[c]}") { anchor = i }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(if (anchor == i) 12.dp else 6.dp).clip(CircleShape).background(if (anchor == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline))
                    }
                } }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Button(enabled = changed, onClick = { st.resizeCanvas(w!!, h!!, (anchor % 3) / 2f, (anchor / 3) / 2f) }) { Text("Resize canvas") }
        }
    }
}

