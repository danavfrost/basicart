package com.halworks.basicart.ui.editor

import androidx.compose.ui.graphics.graphicsLayer

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.halworks.basicart.model.Document
import com.halworks.basicart.model.DrawingLayer
import com.halworks.basicart.model.FloatList
import com.halworks.basicart.model.ImageLayer
import com.halworks.basicart.model.Layer
import com.halworks.basicart.model.ShapeLayer
import com.halworks.basicart.model.Stroke as BaStroke
import com.halworks.basicart.model.TextLayer
import com.halworks.basicart.model.Transform
import com.halworks.basicart.model.withTransform
import com.halworks.basicart.ui.LocalWorkspace
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/** Pan/zoom of the workspace: screen = canvas · zoom + pan. */
class ViewXf {
    var zoom by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)
    var fitted = false
    var fitZoom = 1f
    /** True once the user zoomed/panned by hand; then layout changes don't refit. */
    var userAdjusted = false
    /** View to return to when the keyboard (or a panel) that pushed the canvas up goes away. */
    var restorePan: Pair<Float, Int>? = null
    fun toCanvas(p: Offset) = Offset((p.x - panX) / zoom, (p.y - panY) / zoom)
    fun toScreen(x: Float, y: Float) = Offset(x * zoom + panX, y * zoom + panY)

    var lastWidth = 0

    /** Fits the canvas, leaving extra room at the top for selection handles and the rotate knob. */
    fun fit(doc: Document, size: IntSize, margin: Float) {
        if (size.width <= 0 || size.height <= 0) return
        val top = margin * 1.9f
        val bottom = margin * 1.2f
        val z = min((size.width - 2 * margin) / doc.canvas.width, (size.height - top - bottom) / doc.canvas.height).coerceAtLeast(0.01f)
        zoom = z; fitZoom = z
        panX = (size.width - doc.canvas.width * z) / 2f
        panY = top + (size.height - top - bottom - doc.canvas.height * z) / 2f
        fitted = true
        userAdjusted = false
        restorePan = null
        lastWidth = size.width
    }

    /** Keeps at least part of the canvas on screen (never lose it off-screen). */
    fun keepVisible(doc: Document, size: IntSize, minVisible: Float) {
        val cw = doc.canvas.width * zoom; val ch = doc.canvas.height * zoom
        val mx = min(minVisible, cw); val my = min(minVisible, ch)
        panX = panX.coerceIn(mx - cw, size.width - mx)
        panY = panY.coerceIn(my - ch, size.height - my)
    }
}

private enum class HandleKind { TL, TR, BR, BL, L, R, T, B, ROTATE }

private data class ContextMenu(val layerId: String, val at: Offset)

class LiveStroke(val layerId: String, val stroke: BaStroke)

@Composable
fun EditorCanvas(st: EditorState, view: ViewXf, cache: LayerCache, snapping: Boolean, modifier: Modifier = Modifier) {
    val ws = LocalWorkspace.current
    val density = LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    var interacting by remember { mutableStateOf(false) }
    var guides by remember { mutableStateOf<List<Pair<Offset, Offset>>>(emptyList()) }
    var live by remember { mutableStateOf<LiveStroke?>(null) }
    var menu by remember { mutableStateOf<ContextMenu?>(null) }
    // Eyedropper: the document rendered at canvas resolution (as displayed) + loupe position.
    var eyeSample by remember { mutableStateOf<EyeSample?>(null) }
    var loupe by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(st.eyedropper != null) {
        loupe = null
        eyeSample = if (st.eyedropper == null) null else st.doc?.let { d ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val s = min(1f, 4096f / max(d.canvas.width, d.canvas.height))
                val w = max(1, (d.canvas.width * s).roundToInt()); val h = max(1, (d.canvas.height * s).roundToInt())
                EyeSample(st.renderer.renderBitmap(d, s, w, h), s, d.canvas.background)
            }
        }
    }
    val doc = st.doc ?: return
    val margin = with(density) { 24.dp.toPx() }

    // Hide the floating Fit button (bottom-end corner) while dragging or when it would sit on
    // top of the selected layer's handles.
    val fitBlocked = run {
        val sel = st.selectedId?.let { doc.layer(it) }
        if (sel == null || size.width <= 0) false else {
            val r = selectionRect(st, sel); screenMatrix(st, sel, view).mapRect(r)
            val pad = with(density) { 40.dp.toPx() }; val btn = with(density) { 68.dp.toPx() }
            r.inset(-pad, -pad)
            r.intersects(size.width - btn, size.height - btn, size.width.toFloat(), size.height.toFloat())
        }
    }
    SideEffect { st.fitHidden = interacting || fitBlocked }

    LaunchedEffect(st.fitRequest) {
        if (size.width > 0) st.doc?.let { view.fit(it, size, margin) }
    }
    LaunchedEffect(size) {
        val d = st.doc ?: return@LaunchedEffect
        if (size.width <= 0) return@LaunchedEffect
        // Fit only when opening a project or when the width changes (rotation / window resize).
        // Panel height changes keep the content exactly where it was.
        if (!view.fitted || size.width != view.lastWidth) {
            view.fit(d, size, margin)
            // Restore the saved view (zoom as a multiple of fit, centered on a canvas point).
            st.pendingView?.let { (mult, cx, cy) ->
                st.pendingView = null
                val z = (view.fitZoom * mult).toFloat()
                val px = size.width / 2f - cx.toFloat() * z; val py = size.height / 2f - cy.toFloat() * z
                val cw = d.canvas.width * z; val ch = d.canvas.height * z
                val visible = px < size.width && py < size.height && px + cw > 0 && py + ch > 0
                if (visible) { view.zoom = z; view.panX = px; view.panY = py; view.userAdjusted = true }
            }
            return@LaunchedEffect
        }
        view.keepVisible(d, size, margin * 3)
    }
    // Keep the selected / edited layer in view when panels or the keyboard cover it
    // (a minimal pan, never a re-fit).
    var lastHeight by remember { mutableStateOf(0) }
    LaunchedEffect(st.selectedId, st.editingTextId, size) {
        val prevH = lastHeight
        lastHeight = size.height
        // The keyboard/panel went away: put the canvas back where it was before it was pushed up.
        view.restorePan?.let { (py, h) ->
            if (size.height > prevH && size.height >= h - 1) { view.panY = py; view.restorePan = null }
        }
        val id = st.editingTextId ?: st.selectedId ?: return@LaunchedEffect
        val l = st.doc?.layer(id) ?: return@LaunchedEffect
        if (size.height <= 0) return@LaunchedEffect
        // Drawing layers cover the canvas; drawing must never move the view.
        if (l is DrawingLayer) return@LaunchedEffect
        val m = screenMatrix(st, l, view)
        val r = selectionRect(st, l); m.mapRect(r)
        val top = margin * 1.9f; val bottom = size.height - margin
        if (r.height() > bottom - top) return@LaunchedEffect
        val before = view.panY
        if (r.bottom > bottom) view.panY -= (r.bottom - bottom)
        else if (r.top < top) view.panY += (top - r.top)
        if (view.panY != before && size.height < prevH && view.restorePan == null) view.restorePan = before to prevH
    }
    DisposableEffect(Unit) { onDispose { cache.clear() } }
    DisposableEffect(st, view) {
        st.viewProvider = {
            if (size.width <= 0 || !view.fitted) null else Triple(
                (view.zoom / view.fitZoom).toDouble(),
                ((size.width / 2f - view.panX) / view.zoom).toDouble(),
                ((size.height / 2f - view.panY) / view.zoom).toDouble(),
            )
        }
        onDispose { st.viewProvider = null }
    }


    // System selection toolbar (Cut / Copy / Paste / Select all) for on-canvas text editing.
    val toolbar = androidx.compose.ui.platform.LocalTextToolbar.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    LaunchedEffect(size) {
        if (toolbar.status == androidx.compose.ui.platform.TextToolbarStatus.Shown) toolbar.hide()
    }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    val textUi = remember(st, view) {
        object : TextUi {
            override fun hideToolbar() = toolbar.hide()
            override fun haptic() = haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            override fun showToolbar() {
                val t = st.editingTextId?.let { st.doc?.layer(it) } as? TextLayer ?: return
                val tv = st.textValue
                val sel = tv.selection
                val lay = st.renderer.layout(t)
                val m = textLocalMatrix(st, t, view)
                val quads = if (sel.collapsed) listOf(lay.caretSegment(sel.start)) else lay.rangeQuads(sel.min, sel.max)
                if (quads.isEmpty()) return
                val all = RectF(quads[0][0], quads[0][1], quads[0][0], quads[0][1])
                for (q in quads) { var i = 0; while (i < q.size) { all.union(q[i], q[i + 1]); i += 2 } }
                m.mapRect(all)
                val rect = androidx.compose.ui.geometry.Rect(all.left + rootOffset.x, all.top + rootOffset.y, all.right + rootOffset.x, all.bottom + rootOffset.y)
                val text = tv.text
                fun replace(s: Int, e: Int, ins: String) {
                    val nt = text.substring(0, s) + ins + text.substring(e)
                    st.onTextValueChange(androidx.compose.ui.text.input.TextFieldValue(nt, androidx.compose.ui.text.TextRange(s + ins.length)))
                }
                toolbar.showMenu(
                    rect = rect,
                    onCopyRequested = if (sel.collapsed) null else {
                        { clipboard.setText(androidx.compose.ui.text.AnnotatedString(text.substring(sel.min, sel.max))); toolbar.hide() }
                    },
                    onPasteRequested = if (clipboard.hasText()) {
                        { replace(sel.min, sel.max, clipboard.getText()?.text ?: ""); toolbar.hide() }
                    } else null,
                    onCutRequested = if (sel.collapsed) null else {
                        { clipboard.setText(androidx.compose.ui.text.AnnotatedString(text.substring(sel.min, sel.max))); replace(sel.min, sel.max, ""); toolbar.hide() }
                    },
                    onSelectAllRequested = if (sel.min == 0 && sel.max == text.length) null else {
                        { st.setTextSelection(0, text.length); toolbar.hide(); showToolbar() }
                    },
                )
            }
        }
    }
    LaunchedEffect(st.editingTextId) { if (st.editingTextId == null) toolbar.hide() }
    LaunchedEffect(st.textValue.text) { toolbar.hide() }

    val checker = remember(ws) { checkerShader(with(density) { 8.dp.toPx() }, ws.checkerA.toArgb(), ws.checkerB.toArgb()) }
    val shadowPaint = remember(ws) { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ws.backdrop.toArgb(); setShadowLayer(with(density) { 10.dp.toPx() }, 0f, with(density) { 2.dp.toPx() }, ws.canvasShadow.toArgb()) } }

    Box(modifier.clipToBounds().onSizeChanged { size = it }.onGloballyPositioned { rootOffset = it.positionInRoot() }) {
        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Canvas, ${doc.layers.size} " + if (doc.layers.size == 1) "layer" else "layers" }
                .pointerInput(st, snapping) {
                    gestures(st, view, snapping, cache,
                        setInteracting = { interacting = it },
                        setGuides = { guides = it },
                        setLive = { live = it },
                        openMenu = { id, at -> menu = ContextMenu(id, at) },
                        fitView = { view.fit(st.doc!!, size, margin) },
                        textUi = textUi,
                        eye = object : EyeUi {
                            override fun move(p: Offset?) { loupe = p }
                            override fun sample(canvasX: Float, canvasY: Float): Int? = eyeSample?.at(canvasX, canvasY)
                        },
                    )
                },
        ) {
            drawRect(ws.backdrop)
            val z = view.zoom
            val cw = doc.canvas.width * z
            val ch = doc.canvas.height * z
            val left = view.panX; val top = view.panY
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                nc.drawRect(left, top, left + cw, top + ch, shadowPaint)
                nc.save()
                nc.clipRect(left, top, left + cw, top + ch)
                if (android.graphics.Color.alpha(doc.canvas.background) < 255) {
                    nc.drawPaint(Paint().apply { shader = checker })
                }
                nc.drawColor(doc.canvas.background)
                val ls = live
                for (l in doc.layers) {
                    if (!l.base.visible) continue
                    if (ls != null && l.base.id == ls.layerId) {
                        cache.drawWithStroke(nc, l, ls.stroke, z, view.panX, view.panY)
                    } else {
                        cache.draw(nc, l, z, view.panX, view.panY, interacting)
                    }
                }
                // First stroke of a drawing layer that isn't in the document yet.
                val pend = st.pendingDrawing
                if (ls != null && pend != null && ls.layerId == pend.base.id && doc.layer(pend.base.id) == null) {
                    cache.drawWithStroke(nc, pend, ls.stroke, z, view.panX, view.panY)
                }
                nc.restore()
            }
            cache.retain(doc.layers.map { it.base.id }.toSet() + listOfNotNull(st.pendingDrawing?.base?.id))

            // Selection + handles
            val sel = st.selected
            if (sel != null && st.tool != Tool.DRAW && st.eyedropper == null) {
                drawSelection(st, sel, view, ws.selection, st.editingTextId == sel.base.id, density.density)
            } else if (sel != null && st.tool == Tool.DRAW && st.brush == com.halworks.basicart.model.Brush.ERASER && live == null) {
                // Show which layer the eraser will cut (outline only, no handles).
                drawSelection(st, sel, view, ws.selection, true, density.density)
            }
            // Text caret / highlight
            val editing = st.editingTextId?.let { doc.layer(it) } as? TextLayer
            // (The blinking caret line itself lives in CaretOverlay, so blinking never redraws this.)
            if (editing != null) drawCaret(st, editing, view, ws.selection, caretOnly = false)
            // Eyedropper loupe: magnified pixel grid, crosshair, color preview + hex.
            val lp = loupe
            val es = eyeSample
            if (lp != null && es != null) {
                val cpt = view.toCanvas(lp)
                drawLoupe(lp, cpt, es, density.density)
            }
            // Snap guides
            for ((a, b) in guides) {
                drawLine(ws.guide, view.toScreen(a.x, a.y), view.toScreen(b.x, b.y), strokeWidth = 1.5f * density.density)
            }
        }
        if (st.editingTextId != null) CaretOverlay(st, view, ws.selection)
        menu?.let { m ->
            val l = doc.layer(m.layerId)
            Box(Modifier.offset { IntOffset(m.at.x.roundToInt(), m.at.y.roundToInt()) }) {
                DropdownMenu(expanded = l != null, onDismissRequest = { menu = null }) {
                    if (l is TextLayer) {
                        DropdownMenuItem(text = { Text("Edit text") }, onClick = { menu = null; st.startEditing(l.base.id) })
                        HorizontalDivider()
                    }
                    DropdownMenuItem(text = { Text("Move to top") }, onClick = { menu = null; st.reorder("top", m.layerId) })
                    DropdownMenuItem(text = { Text("Move up") }, onClick = { menu = null; st.reorder("up", m.layerId) })
                    DropdownMenuItem(text = { Text("Move down") }, onClick = { menu = null; st.reorder("down", m.layerId) })
                    DropdownMenuItem(text = { Text("Move to bottom") }, onClick = { menu = null; st.reorder("bottom", m.layerId) })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = null; st.duplicateLayer(m.layerId) })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = null; st.deleteLayer(m.layerId) })
                }
            }
        }
    }
}

private fun checkerShader(cell: Float, a: Int, b: Int): Shader {
    val n = cell.roundToInt().coerceAtLeast(2)
    val bmp = Bitmap.createBitmap(n * 2, n * 2, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    c.drawColor(a)
    val p = Paint().apply { color = b }
    c.drawRect(n.toFloat(), 0f, 2f * n, n.toFloat(), p)
    c.drawRect(0f, n.toFloat(), n.toFloat(), 2f * n, p)
    return BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
}

// ===================================================================== geometry

/** Local box (selection rectangle) of a layer: the layout box, or placed-glyph bounds for curved text. */
internal fun selectionRect(st: EditorState, l: Layer): RectF {
    val (w, h) = st.renderer.box(l)
    val r = RectF(0f, 0f, w, h)
    if (l is TextLayer) {
        val ink = st.renderer.layout(l).inkBounds
        // Curved text: the outline hugs the placed glyphs, not the (much wider) straight box.
        if (l.curve != 0.0) { if (!ink.isEmpty) return RectF(ink) }
        // Straight text: italic/script overhang stays inside the outline.
        else r.union(ink)
    }
    return r
}

/** Local bounds of a drawing layer's ink (eraser strokes excluded); null when it has none. */
internal fun strokeBounds(l: DrawingLayer): RectF? {
    var r: RectF? = null
    for (s in l.strokes) {
        if (s.brush == com.halworks.basicart.model.Brush.ERASER) continue
        val pad = (s.size / 2).toFloat()
        var i = 0
        while (i + 1 < s.points.size) {
            val x = s.points[i]; val y = s.points[i + 1]
            if (r == null) r = RectF(x - pad, y - pad, x + pad, y + pad) else r.union(RectF(x - pad, y - pad, x + pad, y + pad))
            i += 2
        }
    }
    return r
}

/** local → screen matrix. */
internal fun screenMatrix(st: EditorState, l: Layer, view: ViewXf): Matrix {
    val m = st.renderer.layerMatrix(l)
    m.postScale(view.zoom, view.zoom)
    m.postTranslate(view.panX, view.panY)
    return m
}

private fun mapPt(m: Matrix, x: Float, y: Float): Offset {
    val a = floatArrayOf(x, y); m.mapPoints(a); return Offset(a[0], a[1])
}

private fun handlesFor(l: Layer): List<HandleKind> = buildList {
    // Lines/arrows: end handles only (corner pairs would stack on each end).
    if (!(l is ShapeLayer && l.shape.isLinear)) addAll(listOf(HandleKind.TL, HandleKind.TR, HandleKind.BR, HandleKind.BL))
    when (l) {
        is TextLayer -> { add(HandleKind.L); add(HandleKind.R) }
        is ShapeLayer -> if (l.shape.isLinear) { add(HandleKind.L); add(HandleKind.R) } else addAll(listOf(HandleKind.L, HandleKind.R, HandleKind.T, HandleKind.B))
        else -> {}
    }
    add(HandleKind.ROTATE)
}

/**
 * Handles shown for a box of the given on-screen size: side handles disappear on small
 * boxes (they would cover the glyphs); corners and rotate always stay.
 */
/** Gap between content and its selection outline; text gets more so handles never sit on glyphs. */
private fun selectionPadDp(l: Layer): Float = if (l is TextLayer) 10f else 6f

private fun visibleHandles(l: Layer, wScreen: Float, hScreen: Float, dp: Float): List<HandleKind> =
    handlesFor(l).filter { k ->
        when (k) {
            HandleKind.L, HandleKind.R -> wScreen >= 88 * dp || (l is ShapeLayer && l.shape.isLinear)
            HandleKind.T, HandleKind.B -> hScreen >= 64 * dp
            else -> true
        }
    }

private fun handleLocal(kind: HandleKind, r: RectF, rotateOffsetLocal: Float): Offset = when (kind) {
    HandleKind.TL -> Offset(r.left, r.top)
    HandleKind.TR -> Offset(r.right, r.top)
    HandleKind.BR -> Offset(r.right, r.bottom)
    HandleKind.BL -> Offset(r.left, r.bottom)
    HandleKind.L -> Offset(r.left, r.centerY())
    HandleKind.R -> Offset(r.right, r.centerY())
    HandleKind.T -> Offset(r.centerX(), r.top)
    HandleKind.B -> Offset(r.centerX(), r.bottom)
    HandleKind.ROTATE -> Offset(r.centerX(), r.top - rotateOffsetLocal)
}

private fun DrawScope.drawSelection(st: EditorState, l: Layer, view: ViewXf, color: Color, editing: Boolean, dp: Float) {
    val m = screenMatrix(st, l, view)
    val r = selectionRect(st, l)
    val padL = selectionPadDp(l) * dp / (view.zoom * l.base.transform.scale.toFloat())
    r.inset(-padL, -padL)
    // line/arrow: show a box at least as tall as the handles
    if (l is ShapeLayer && l.shape.isLinear) {
        val minH = 16 * dp / (view.zoom * l.base.transform.scale.toFloat())
        if (r.height() < minH) r.inset(0f, -(minH - r.height()) / 2f)
    }
    val corners = listOf(mapPt(m, r.left, r.top), mapPt(m, r.right, r.top), mapPt(m, r.right, r.bottom), mapPt(m, r.left, r.bottom))
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(corners[0].x, corners[0].y); corners.drop(1).forEach { lineTo(it.x, it.y) }; close()
    }
    // Two-tone outline reads on light and dark artwork alike.
    drawPath(path, Color.White.copy(alpha = 0.9f), style = Stroke(3f * dp))
    drawPath(path, color, style = Stroke(1.25f * dp, pathEffect = if (l.base.locked) PathEffect.dashPathEffect(floatArrayOf(5 * dp, 4 * dp)) else null))
    if (l.base.locked || editing) return
    val scale = view.zoom * l.base.transform.scale.toFloat()
    val rotOff = 26 * dp / scale
    val wS = r.width() * scale; val hS = r.height() * scale
    val small = minOf(wS, hS) < 52 * dp
    fun knob(p: Offset, r: Float) {
        drawCircle(Color.Black.copy(alpha = 0.18f), r + 1.5f * dp, p + Offset(0f, 0.75f * dp))
        drawCircle(Color.White, r, p)
        drawCircle(color, r, p, style = Stroke(1.75f * dp))
    }
    for (k in visibleHandles(l, wS, hS, dp)) {
        val lp = handleLocal(k, r, 0f)
        val p = mapPt(m, lp.x, lp.y)
        when (k) {
            HandleKind.ROTATE -> {
                val ql = handleLocal(k, r, rotOff)
                val q = mapPt(m, ql.x, ql.y)
                val topMid = mapPt(m, r.centerX(), r.top)
                drawLine(Color.White.copy(alpha = 0.9f), topMid, q, strokeWidth = 3f * dp)
                drawLine(color, topMid, q, strokeWidth = 1.25f * dp)
                knob(q, 7.5f * dp)
                drawArc(color, -50f, 260f, false, topLeft = Offset(q.x - 3.5f * dp, q.y - 3.5f * dp),
                    size = androidx.compose.ui.geometry.Size(7 * dp, 7 * dp), style = Stroke(1.4f * dp, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
            HandleKind.L, HandleKind.R, HandleKind.T, HandleKind.B -> {
                // Side handles are small pills aligned with the edge.
                val horizontalEdge = k == HandleKind.T || k == HandleKind.B
                val ang = l.base.transform.rotation.toFloat() + if (horizontalEdge) 0f else 90f
                rotate(ang, p) {
                    val w = 14 * dp; val h = 5.5f * dp
                    drawRoundRect(Color.Black.copy(alpha = 0.18f), Offset(p.x - w / 2 - dp, p.y - h / 2 - dp + 0.75f * dp), androidx.compose.ui.geometry.Size(w + 2 * dp, h + 2 * dp), androidx.compose.ui.geometry.CornerRadius(h))
                    drawRoundRect(Color.White, Offset(p.x - w / 2, p.y - h / 2), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
                    drawRoundRect(color, Offset(p.x - w / 2, p.y - h / 2), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(h / 2), style = Stroke(1.5f * dp))
                }
            }
            else -> knob(p, if (small) 4.5f * dp else 6f * dp)
        }
    }
}

private fun textLocalMatrix(st: EditorState, t: TextLayer, view: ViewXf): Matrix {
    val m = screenMatrix(st, t, view)
    if (t.skew != 0.0) {
        val h = st.renderer.layout(t).h
        val sk = Matrix().apply { setSkew(-tan(Math.toRadians(t.skew)).toFloat(), 0f, 0f, h / 2f) }
        m.preConcat(sk)
    }
    return m
}

/**
 * The blinking caret on its own layer: the blink only changes the layer's alpha, so neither
 * this nor the (expensive) canvas below is redrawn twice a second while typing.
 */
@Composable
private fun CaretOverlay(st: EditorState, view: ViewXf, color: Color) {
    val blink = rememberInfiniteTransition(label = "caret")
    val caretAlpha = blink.animateFloat(
        1f, 0f,
        infiniteRepeatable(keyframes { durationMillis = 1000; 1f at 0; 1f at 499; 0f at 500; 0f at 999 }, RepeatMode.Restart),
        label = "caretAlpha",
    )
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = caretAlpha.value }) {
        val t = st.editingTextId?.let { st.doc?.layer(it) } as? TextLayer ?: return@Canvas
        drawCaret(st, t, view, color, caretOnly = true)
    }
}

private fun DrawScope.drawCaret(st: EditorState, t: TextLayer, view: ViewXf, color: Color, caretOnly: Boolean) {
    val lay = st.renderer.layout(t)
    val m = textLocalMatrix(st, t, view)
    val sel = st.textValue.selection
    if (sel.collapsed) {
        if (!caretOnly) return
        val c = lay.caretSegment(sel.start)
        val dx = c[2] - c[0]; val dy = c[3] - c[1]
        val a = mapPt(m, c[0] + dx * 0.1f, c[1] + dy * 0.1f)
        val b = mapPt(m, c[2] - dx * 0.1f, c[3] - dy * 0.1f)
        drawLine(color, a, b, strokeWidth = 2.5f * density)
    } else {
        if (caretOnly) return
        for (q in lay.rangeQuads(sel.min, sel.max)) {
            val pts = listOf(mapPt(m, q[0], q[1]), mapPt(m, q[2], q[3]), mapPt(m, q[4], q[5]), mapPt(m, q[6], q[7]))
            val p = androidx.compose.ui.graphics.Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
            drawPath(p, color.copy(alpha = 0.30f))
        }
        // Teardrop handles (Android style): pointed corner at the caret, knob below.
        val rr = 10 * density
        selectionHandles(st, t, view, density)?.forEach { (b, c) ->
            val drop = androidx.compose.ui.graphics.Path().apply {
                addOval(androidx.compose.ui.geometry.Rect(c, rr))
                val left = minOf(b.x, c.x); val right = maxOf(b.x, c.x)
                addRect(androidx.compose.ui.geometry.Rect(left, b.y, right, c.y))
            }
            drawPath(drop, Color.Black.copy(alpha = 0.15f), style = Stroke(2 * density))
            drawPath(drop, color)
        }
    }
}

// ===================================================================== gestures

private sealed interface Mode {
    data object Pending : Mode
    data object Done : Mode
    data class MoveLayer(val id: String, val start: Transform, val box: RectF) : Mode
    data class Handle(val kind: HandleKind, val start: Layer) : Mode
    data class PanView(val px: Float, val py: Float) : Mode
    data class Pinch(val layer: Layer?, val c0: Offset, val d0: Float, val a0: Float, val zoom0: Float, val pan0: Offset) : Mode
    class Draw(val layerId: String, val inv: Matrix, val pts: ArrayList<Float>, val pressure: ArrayList<Float>?, var sx: Float, var sy: Float, val toMask: Boolean) : Mode
    data class CreateBox(val start: Offset) : Mode
    /** Finger down on the edited text: tap = caret, double tap = word, long press = word + extend. */
    class TextPress(val id: String, val anchor: Int, var moved: Boolean = false, var long: Boolean = false, var wordA: Int = 0, var wordB: Int = 0) : Mode
    /** Dragging a selection handle (0 = start, 1 = end). */
    class SelHandle(val id: String, val which: Int, val anchorScreen: Offset) : Mode
}

private fun hitList(st: EditorState, view: ViewXf, screen: Offset, dp: Float): List<Layer> {
    val d = st.doc ?: return emptyList()
    val out = ArrayList<Layer>()
    for (l in d.layers.asReversed()) {
        if (!l.base.visible || l.base.locked) continue
        if (hits(st, view, l, screen, dp)) out.add(l)
    }
    return out
}

private fun hits(st: EditorState, view: ViewXf, l: Layer, screen: Offset, dp: Float, extraDp: Float = 0f): Boolean {
    val m = screenMatrix(st, l, view)
    val inv = Matrix(); if (!m.invert(inv)) return false
    val p = floatArrayOf(screen.x, screen.y); inv.mapPoints(p)
    // A drawing layer is only "there" where it has ink, not across the whole canvas.
    val r = if (l is DrawingLayer) strokeBounds(l) ?: return false else selectionRect(st, l)
    val s = view.zoom * l.base.transform.scale.toFloat()
    val minTouch = 24 * dp / s
    if (r.width() < minTouch) r.inset(-(minTouch - r.width()) / 2f, 0f)
    if (r.height() < minTouch) r.inset(0f, -(minTouch - r.height()) / 2f)
    r.inset(-extraDp * dp / s, -extraDp * dp / s)
    return r.contains(p[0], p[1])
}

private fun hitHandle(st: EditorState, view: ViewXf, l: Layer, screen: Offset, dp: Float): HandleKind? {
    if (l.base.locked) return null
    val m = screenMatrix(st, l, view)
    val r = selectionRect(st, l)
    val s = view.zoom * l.base.transform.scale.toFloat()
    r.inset(-selectionPadDp(l) * dp / s, -selectionPadDp(l) * dp / s)
    if (l is ShapeLayer && l.shape.isLinear && r.height() < 16 * dp / s) r.inset(0f, -(16 * dp / s - r.height()) / 2f)
    val rotOff = 26 * dp / s
    var best: HandleKind? = null
    var bestD = 24 * dp
    for (k in visibleHandles(l, r.width() * s, r.height() * s, dp)) {
        val lp = handleLocal(k, r, if (k == HandleKind.ROTATE) rotOff else 0f)
        val p = mapPt(m, lp.x, lp.y)
        val dd = hypot(p.x - screen.x, p.y - screen.y)
        if (dd < bestD) { bestD = dd; best = k }
    }
    return best
}

private var lastTapTime = 0L
private var lastTapPos = Offset.Zero
private var lastTapSelected: String? = null

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.gestures(
    st: EditorState, view: ViewXf, snapping: Boolean, cache: LayerCache,
    setInteracting: (Boolean) -> Unit,
    setGuides: (List<Pair<Offset, Offset>>) -> Unit,
    setLive: (LiveStroke?) -> Unit,
    openMenu: (String, Offset) -> Unit,
    fitView: () -> Unit,
    textUi: TextUi,
    eye: EyeUi,
) {
    val dp = density
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val doc = st.doc ?: return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        val start = down.position
        val downTime = down.uptimeMillis
        var mode: Mode = Mode.Pending
        val selected = st.selected

        // Eyedropper: the loupe follows the finger; lifting on the canvas picks, off it cancels.
        if (st.eyedropper != null) {
            eye.move(start)
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Main)
                val ch = ev.changes.firstOrNull() ?: break
                if (!ch.pressed) {
                    val c = view.toCanvas(ch.position)
                    val cb = st.eyedropper
                    val onCanvas = c.x in 0f..doc.canvas.width.toFloat() && c.y in 0f..doc.canvas.height.toFloat()
                    eye.move(null)
                    st.eyedropper = null
                    if (onCanvas && cb != null) cb(eye.sample(c.x, c.y) ?: sampleColor(st, doc, c.x, c.y))
                    break
                }
                eye.move(ch.position)
                ch.consume()
            }
            return@awaitEachGesture
        }
        // Decide the initial mode.
        if (st.eyedropper == null) {
            if (st.tool == Tool.DRAW) {
                val erasing = st.brush == com.halworks.basicart.model.Brush.ERASER
                // The eraser works on the selected layer of any type and never creates one.
                val target: Layer? = if (erasing) st.eraserTarget() else st.drawingTarget()
                if (target == null) {
                    st.hint = "Select a layer to erase"
                    mode = Mode.Done
                } else {
                    val m = screenMatrix(st, target, view)
                    val inv = Matrix(); m.invert(inv)
                    val p = floatArrayOf(start.x, start.y); inv.mapPoints(p)
                    val stylus = down.type == PointerType.Stylus
                    val toMask = erasing && target !is DrawingLayer
                    mode = Mode.Draw(target.base.id, inv, arrayListOf(p[0], p[1]), if (stylus) arrayListOf(down.pressure.coerceIn(0f, 1f)) else null, p[0], p[1], toMask)
                    setLive(LiveStroke(target.base.id, currentStroke(st, mode as Mode.Draw)))
                }
            } else if (selected != null && st.editingTextId != selected.base.id) {
                hitHandle(st, view, selected, start, dp)?.let { mode = Mode.Handle(it, selected); st.anchorTexts = false }
            }
            if (mode == Mode.Pending) {
                val editing = st.editingTextId?.let { doc.layer(it) } as? TextLayer
                if (editing != null) {
                    textUi.hideToolbar()
                    val h = hitSelectionHandle(st, editing, view, start, dp)
                    if (h != null) {
                        mode = Mode.SelHandle(editing.base.id, h.first, h.second)
                    } else if (hits(st, view, editing, start, dp)) {
                        mode = Mode.TextPress(editing.base.id, textOffsetAt(st, editing, view, start))
                    }
                }
            }
        }

        var longPressFired = false
        while (true) {
            val timeout = if (mode == Mode.Pending && !longPressFired) viewConfiguration.longPressTimeoutMillis - (System.currentTimeMillis() - (System.currentTimeMillis() - 0)) else Long.MAX_VALUE
            val md0 = mode
            val waitLong = mode == Mode.Pending || (md0 is Mode.TextPress && !md0.moved && !md0.long)
            val ev = if (waitLong) {
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { awaitPointerEvent(PointerEventPass.Main) }
            } else awaitPointerEvent(PointerEventPass.Main)
            @Suppress("UNUSED_VARIABLE") val unused = timeout
            if (ev == null && md0 is Mode.TextPress) {
                // Long press in the edited text: select the word; dragging then extends by words.
                val t = st.doc?.layer(md0.id) as? TextLayer
                if (t != null) {
                    val (a, b) = wordAt(t.text, md0.anchor)
                    md0.long = true; md0.wordA = a; md0.wordB = b
                    st.setTextSelection(a, b)
                    textUi.haptic()
                }
                continue
            }
            if (ev == null) {
                // Long press → context menu for the layer under the finger.
                longPressFired = true
                val hit = hitList(st, view, start, dp).firstOrNull()
                if (hit != null && st.editingTextId == null) {
                    st.selectedId = hit.base.id
                    openMenu(hit.base.id, start)
                }
                mode = Mode.Done
                continue
            }
            val pressed = ev.changes.filter { it.pressed }
            if (pressed.isEmpty()) {
                // Gesture end.
                when (val md = mode) {
                    Mode.Pending -> onTap(st, view, start, dp, ev.changes.first().uptimeMillis - downTime, fitView)
                    is Mode.Draw -> {
                        val s = currentStroke(st, md)
                        st.appendStroke(md.layerId, s, md.toMask)
                        st.doc?.layer(md.layerId)?.let { cache.adoptStroke(it) }
                        setLive(null)
                    }
                    is Mode.CreateBox -> finishCreateBox(st, view, md.start, ev.changes.first().position)
                    is Mode.TextPress -> {
                        if (!md.moved && !md.long) onTextTap(st, view, md, start, dp, textUi)
                        else textUi.showToolbar()
                    }
                    is Mode.SelHandle -> textUi.showToolbar()
                    else -> {}
                }
                st.pendingDrawing = null
                st.endLive()
                st.anchorTexts = true
                setInteracting(false)
                setGuides(emptyList())
                break
            }
            if (pressed.size >= 2 && mode !is Mode.Pinch && mode != Mode.Done) {
                if (mode is Mode.Draw) { setLive(null); st.pendingDrawing = null; st.endLive() }
                if (mode is Mode.CreateBox) setGuides(emptyList())
                val a = pressed[0].position; val b = pressed[1].position
                val c = (a + b) / 2f
                val sel = st.selected
                val layerTarget = if (sel != null && !sel.base.locked && st.tool != Tool.DRAW && st.editingTextId == null &&
                    hits(st, view, sel, c, dp, extraDp = 48f)) sel else null
                st.anchorTexts = false
                mode = Mode.Pinch(layerTarget, c, max(1f, (a - b).getDistance()), atan2(b.y - a.y, b.x - a.x), view.zoom, Offset(view.panX, view.panY))
                setInteracting(true)
                ev.changes.forEach { it.consume() }
                continue
            }
            val ch = pressed.first()
            val pos = ch.position
            when (val md = mode) {
                Mode.Pending -> {
                    if ((pos - start).getDistance() > slop) {
                        mode = startDrag(st, view, start, dp)
                        setInteracting(true)
                    }
                }
                is Mode.Draw -> {
                    for (h in ch.historical) addDrawPoint(st, md, h.position, ch.pressure)
                    addDrawPoint(st, md, pos, ch.pressure)
                    setLive(LiveStroke(md.layerId, currentStroke(st, md)))
                }
                is Mode.MoveLayer -> setGuides(moveLayer(st, view, md, start, pos, snapping))
                is Mode.Handle -> setGuides(dragHandle(st, view, md, start, pos, snapping))
                is Mode.PanView -> { view.userAdjusted = true; view.restorePan = null; view.panX = md.px + (pos.x - start.x); view.panY = md.py + (pos.y - start.y) }
                is Mode.Pinch -> {
                    if (pressed.size >= 2) pinch(st, view, md, pressed[0].position, pressed[1].position, snapping)
                }
                is Mode.CreateBox -> setGuides(listOf(view.toCanvas(md.start) to Offset(view.toCanvas(pos).x, view.toCanvas(md.start).y)))
                is Mode.TextPress -> {
                    val t = st.doc?.layer(md.id) as? TextLayer
                    if (t != null && (md.moved || md.long || (pos - start).getDistance() > slop)) {
                        md.moved = true
                        val off = textOffsetAt(st, t, view, pos)
                        if (md.long) st.setTextSelection(minOf(md.wordA, off), maxOf(md.wordB, off))
                        else st.setTextSelection(md.anchor, off)
                    }
                }
                is Mode.SelHandle -> {
                    val t = st.doc?.layer(md.id) as? TextLayer
                    if (t != null) {
                        val off = textOffsetAt(st, t, view, md.anchorScreen + (pos - start))
                        val sel = st.textValue.selection
                        val a = sel.min; val b = sel.max
                        if (md.which == 0) st.setTextSelection(minOf(off, b), maxOf(off, b))
                        else st.setTextSelection(minOf(a, off), maxOf(a, off))
                    }
                }
                Mode.Done -> {}
            }
            ch.consume()
        }
    }
}

/** Rendered document used by the eyedropper (canvas resolution, capped at 4096 px). */
class EyeSample(val bmp: Bitmap, val scale: Float, val background: Int) {
    fun at(x: Float, y: Float): Int {
        val px = (x * scale).toInt().coerceIn(0, bmp.width - 1); val py = (y * scale).toInt().coerceIn(0, bmp.height - 1)
        val c = bmp.getPixel(px, py)
        return if (android.graphics.Color.alpha(c) == 0) background else c
    }
}

interface EyeUi {
    fun move(p: Offset?)
    fun sample(canvasX: Float, canvasY: Float): Int?
}

private fun DrawScope.drawLoupe(finger: Offset, canvasPt: Offset, es: EyeSample, dp: Float) {
    val radius = 58 * dp
    // Above the finger, flipped below near the top edge.
    val center = Offset(finger.x.coerceIn(radius + 8 * dp, size.width - radius - 8 * dp),
        if (finger.y - 110 * dp - radius > 0) finger.y - 110 * dp else finger.y + 110 * dp)
    val cells = 11
    val cx = (canvasPt.x * es.scale).toInt(); val cy = (canvasPt.y * es.scale).toInt()
    val cell = radius * 2 / cells
    drawIntoCanvas { c ->
        val nc = c.nativeCanvas
        val clip = android.graphics.Path().apply { addCircle(center.x, center.y, radius, android.graphics.Path.Direction.CW) }
        nc.save(); nc.clipPath(clip)
        nc.drawColor(android.graphics.Color.DKGRAY)
        val p = Paint()
        for (j in 0 until cells) for (i in 0 until cells) {
            val sx = cx + i - cells / 2; val sy = cy + j - cells / 2
            if (sx < 0 || sy < 0 || sx >= es.bmp.width || sy >= es.bmp.height) continue
            p.color = es.bmp.getPixel(sx, sy) or (0xFF shl 24)
            val l = center.x - radius + i * cell; val t = center.y - radius + j * cell
            nc.drawRect(l, t, l + cell, t + cell, p)
        }
        // pixel grid
        p.color = 0x33000000; p.strokeWidth = 1f
        for (k in 0..cells) {
            val o = -radius + k * cell
            nc.drawLine(center.x + o, center.y - radius, center.x + o, center.y + radius, p)
            nc.drawLine(center.x - radius, center.y + o, center.x + radius, center.y + o, p)
        }
        nc.restore()
    }
    // center cell crosshair
    val half = cell / 2
    drawRect(Color.White, Offset(center.x - half, center.y - half), androidx.compose.ui.geometry.Size(cell, cell), style = Stroke(2.5f * dp))
    drawRect(Color.Black, Offset(center.x - half - 1.5f * dp, center.y - half - 1.5f * dp), androidx.compose.ui.geometry.Size(cell + 3 * dp, cell + 3 * dp), style = Stroke(1f * dp))
    drawCircle(Color.White, radius, center, style = Stroke(3 * dp))
    drawCircle(Color.Black.copy(alpha = 0.35f), radius + 1.5f * dp, center, style = Stroke(1 * dp))
    // color chip + hex
    val color = es.at(canvasPt.x, canvasPt.y)
    val chipTop = center.y + radius + 8 * dp
    drawRoundRect(Color(0xE6202024), Offset(center.x - 56 * dp, chipTop), androidx.compose.ui.geometry.Size(112 * dp, 30 * dp), androidx.compose.ui.geometry.CornerRadius(15 * dp))
    drawCircle(Color(color), 9 * dp, Offset(center.x - 38 * dp, chipTop + 15 * dp))
    drawCircle(Color.White, 9 * dp, Offset(center.x - 38 * dp, chipTop + 15 * dp), style = Stroke(1.5f * dp))
    drawIntoCanvas { c ->
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE; textSize = 13 * dp; typeface = android.graphics.Typeface.MONOSPACE }
        c.nativeCanvas.drawText("#" + com.halworks.basicart.model.Colors.formatRgb(color), center.x - 24 * dp, chipTop + 20 * dp, tp)
    }
}

/** UI hooks the gesture code needs for text selection (toolbar, haptics). */
interface TextUi {
    fun showToolbar()
    fun hideToolbar()
    fun haptic()
}

private var lastTextTapTime = 0L
private var lastTextTapPos = Offset.Zero

/** Tap inside the edited text: caret; double tap: word; tap on the caret again: Paste/Select all toolbar. */
private fun onTextTap(st: EditorState, view: ViewXf, md: Mode.TextPress, pos: Offset, dp: Float, ui: TextUi) {
    val t = st.doc?.layer(md.id) as? TextLayer ?: return
    val now = System.currentTimeMillis()
    val isDouble = now - lastTextTapTime < 400 && (pos - lastTextTapPos).getDistance() < 48 * dp
    lastTextTapTime = if (isDouble) 0L else now
    lastTextTapPos = pos
    if (isDouble) {
        val (a, b) = wordAt(t.text, md.anchor)
        st.setTextSelection(a, b)
        ui.showToolbar()
        return
    }
    val sel = st.textValue.selection
    if (sel.collapsed && sel.start == md.anchor) { ui.showToolbar(); st.textTabsWhileEditing = false; st.keyboardShowRequest++; return }
    st.setTextSelection(md.anchor, md.anchor)
    // a plain tap in the text brings the keyboard back
    st.textTabsWhileEditing = false
    st.keyboardShowRequest++
}

/** Screen positions of the two teardrop handles: (caret bottom point, handle center). */
internal fun selectionHandles(st: EditorState, t: TextLayer, view: ViewXf, dp: Float): List<Pair<Offset, Offset>>? {
    val sel = st.textValue.selection
    if (sel.collapsed) return null
    val lay = st.renderer.layout(t)
    val m = textLocalMatrix(st, t, view)
    val r = 10 * dp
    return listOf(sel.min to -1f, sel.max to 1f).map { (off, side) ->
        // At the caret's foot, along the curve for curved text; the knob hangs below in screen space.
        val c = lay.caretSegment(off)
        val b = mapPt(m, c[2] - (c[2] - c[0]) * 0.1f, c[3] - (c[3] - c[1]) * 0.1f)
        b to Offset(b.x + side * r, b.y + r)
    }
}

private fun hitSelectionHandle(st: EditorState, t: TextLayer, view: ViewXf, p: Offset, dp: Float): Pair<Int, Offset>? {
    val hs = selectionHandles(st, t, view, dp) ?: return null
    var best = -1; var bestD = 20 * dp
    hs.forEachIndexed { i, (_, c) -> val d = (c - p).getDistance(); if (d < bestD) { bestD = d; best = i } }
    if (best < 0) return null
    // Drag the caret point, not the knob, so the selection follows the finger precisely.
    val lay = st.renderer.layout(t)
    val sel = st.textValue.selection
    val c = lay.caretSegment(if (best == 0) sel.min else sel.max)
    val m = textLocalMatrix(st, t, view)
    // Up the caret (toward its top) in screen space, so dragging follows the line even on a curve.
    val up = mapPt(m, c[0], c[1]) - mapPt(m, c[2], c[3])
    val anchor = hs[best].first + up * 0.35f
    return best to anchor
}

private fun textOffsetAt(st: EditorState, t: TextLayer, view: ViewXf, screen: Offset): Int {
    val m = textLocalMatrix(st, t, view)
    val inv = Matrix(); m.invert(inv)
    val p = floatArrayOf(screen.x, screen.y); inv.mapPoints(p)
    return st.renderer.layout(t).offsetAt(p[0], p[1], t.text.length)
}

private fun startDrag(st: EditorState, view: ViewXf, start: Offset, dp: Float): Mode {
    if (st.editingTextId != null) return Mode.PanView(view.panX, view.panY)
    val sel = st.selected
    val target = if (sel != null && !sel.base.locked && sel.base.visible && hits(st, view, sel, start, dp)) sel
    else hitList(st, view, start, dp).firstOrNull()
    val move = when (st.tool) {
        Tool.TEXT -> target is TextLayer
        Tool.SHAPES -> target is ShapeLayer
        else -> target != null
    }
    if (move && target != null) {
        st.selectedId = target.base.id
        return Mode.MoveLayer(target.base.id, target.base.transform, selectionRect(st, target))
    }
    if (st.tool == Tool.TEXT || st.tool == Tool.SHAPES) return Mode.CreateBox(start)
    return Mode.PanView(view.panX, view.panY)
}

private fun onTap(st: EditorState, view: ViewXf, pos: Offset, dp: Float, @Suppress("UNUSED_PARAMETER") dur: Long, fitView: () -> Unit) {
    val doc = st.doc ?: return
    val now = System.currentTimeMillis()
    val isDouble = now - lastTapTime < 350 && (pos - lastTapPos).getDistance() < 40 * dp
    lastTapTime = if (isDouble) 0L else now

    st.eyedropper?.let { cb ->
        val c = view.toCanvas(pos)
        cb(sampleColor(st, doc, c.x, c.y))
        st.eyedropper = null
        return
    }
    val editing = st.editingTextId?.let { doc.layer(it) } as? TextLayer
    if (editing != null) {
        if (hits(st, view, editing, pos, dp)) {
            val off = textOffsetAt(st, editing, view, pos)
            if (isDouble) {
                val (a, b) = wordAt(editing.text, off)
                st.setTextSelection(a, b)
            } else st.setTextSelection(off, off)
            lastTapPos = pos
            return
        }
        st.finishEditing()
        lastTapPos = pos
        if (st.tool == Tool.TEXT) { st.selectedId = null; return }
    }
    val hits = hitList(st, view, pos, dp)
    if (isDouble) {
        val t = hits.firstOrNull { it.base.id == st.selectedId } ?: hits.firstOrNull()
        if (t is TextLayer) { st.startEditing(t.base.id); return }
        if (t == null) { fitView(); return }
    }
    val sameSpot = (pos - lastTapPos).getDistance() < 12 * dp
    lastTapPos = pos
    val cp = view.toCanvas(pos)
    val onCanvas = cp.x in 0f..doc.canvas.width.toFloat() && cp.y in 0f..doc.canvas.height.toFloat()
    if (hits.isEmpty()) {
        if (!onCanvas) { st.selectedId = null; return }
        when (st.tool) {
            Tool.TEXT -> { val c = view.toCanvas(pos); st.createText(c.x.toDouble(), c.y.toDouble()) }
            Tool.SHAPES -> { val c = view.toCanvas(pos); st.addShape(st.shapeKind, c.x.toDouble(), c.y.toDouble()) }
            else -> st.selectedId = null
        }
        return
    }
    if (st.tool == Tool.TEXT) {
        when (val action = TapRules.textTool(st.selectedId, hits.filterIsInstance<TextLayer>().map { it.base.id }, onCanvas)) {
            is TextToolTap.Edit -> {
                st.startEditing(action.id)
                (st.doc?.layer(action.id) as? TextLayer)?.let { t -> val off = textOffsetAt(st, t, view, pos); st.setTextSelection(off, off) }
            }
            is TextToolTap.Select -> st.selectedId = action.id
            TextToolTap.Create -> { val c = view.toCanvas(pos); st.createText(c.x.toDouble(), c.y.toDouble()) }
            TextToolTap.Deselect -> st.selectedId = null
        }
        return
    }
    // Select tool: tapping again on the same spot cycles to the next layer beneath.
    val next = TapRules.selectTool(st.selectedId, hits.map { it.base.id }, sameSpot && st.tool == Tool.SELECT, lastTapSelected)
    st.selectedId = next
    lastTapSelected = next
}

private fun wordAt(text: String, off: Int): Pair<Int, Int> {
    if (text.isEmpty()) return 0 to 0
    var a = off.coerceIn(0, text.length); var b = a
    while (a > 0 && !text[a - 1].isWhitespace()) a--
    while (b < text.length && !text[b].isWhitespace()) b++
    return a to b
}

private fun sampleColor(st: EditorState, doc: Document, x: Float, y: Float): Int {
    val s = min(1f, 1024f / max(doc.canvas.width, doc.canvas.height))
    val w = max(1, (doc.canvas.width * s).roundToInt()); val h = max(1, (doc.canvas.height * s).roundToInt())
    val bmp = st.renderer.renderBitmap(doc, s, w, h)
    val px = (x * s).roundToInt().coerceIn(0, w - 1); val py = (y * s).roundToInt().coerceIn(0, h - 1)
    val c = bmp.getPixel(px, py)
    bmp.recycle()
    return if (android.graphics.Color.alpha(c) == 0) doc.canvas.background else c
}

// ------------------------------------------------------------------ transforms

private fun layerAabb(st: EditorState, l: Layer): RectF {
    val r = selectionRect(st, l)
    val m = st.renderer.layerMatrix(l)
    m.mapRect(r)
    return r
}

private fun moveLayer(st: EditorState, view: ViewXf, md: Mode.MoveLayer, start: Offset, pos: Offset, snapping: Boolean): List<Pair<Offset, Offset>> {
    val doc = st.doc ?: return emptyList()
    val l = doc.layer(md.id) ?: return emptyList()
    var nx = md.start.x + (pos.x - start.x) / view.zoom
    var ny = md.start.y + (pos.y - start.y) / view.zoom
    val guides = ArrayList<Pair<Offset, Offset>>()
    if (snapping) {
        val thr = 8 * 2.75f / view.zoom
        val moved = l.withTransform(md.start.copy(x = nx, y = ny))
        val bb = layerAabb(st, moved)
        val W = doc.canvas.width.toFloat(); val H = doc.canvas.height.toFloat()
        // x: center, left edge, right edge
        val xs = listOf(bb.centerX() to W / 2f, bb.left to 0f, bb.right to W)
        xs.minByOrNull { abs(it.first - it.second) }?.let { (v, target) ->
            if (abs(v - target) < thr) { nx += target - v; guides.add(Offset(target, 0f) to Offset(target, H)) }
        }
        val ys = listOf(bb.centerY() to H / 2f, bb.top to 0f, bb.bottom to H)
        ys.minByOrNull { abs(it.first - it.second) }?.let { (v, target) ->
            if (abs(v - target) < thr) { ny += target - v; guides.add(Offset(0f, target) to Offset(W, target)) }
        }
    }
    st.updateLayer(md.id, liveEdit = true) { it.withTransform(md.start.copy(x = nx, y = ny)) }
    return guides
}

private fun snapAngle(a: Double, snapping: Boolean): Double {
    var r = ((a % 360) + 360) % 360
    if (snapping) {
        val nearest = Math.round(r / 45.0) * 45.0
        if (abs(r - nearest) < 4.0) r = nearest % 360
    }
    return r
}

private fun scaleLayer(st: EditorState, l: Layer, start: Layer, f: Double): Layer = when (start) {
    is TextLayer -> {
        val fs = (start.fontSize * f).coerceIn(4.0, 2000.0)
        val k = fs / start.fontSize
        var t = (l as TextLayer).copy(
            fontSize = fs,
            boxWidth = if (start.autoWidth) start.boxWidth else (start.boxWidth * k).coerceAtLeast(1.0),
            spans = com.halworks.basicart.model.Spans.scaleSizes(start.spans, k), // span sizes scale too (§7.9)
        )
        if (start.base.mask.isNotEmpty()) {
            // The mask follows corner/pinch scaling about the box center (§9.1).
            val (w0, h0) = st.renderer.box(start)
            val (w1, h1) = st.renderer.box(t)
            t = t.copy(base = t.base.copy(mask = com.halworks.basicart.model.MaskGeometry.scaleText(start.base.mask, k, w0 / 2.0, h0 / 2.0, w1 / 2.0, h1 / 2.0)))
        }
        t
    }
    else -> l.withTransform(l.base.transform.copy(scale = (start.base.transform.scale * f).coerceIn(0.01, 100.0)))
}

private fun dragHandle(st: EditorState, view: ViewXf, md: Mode.Handle, start: Offset, pos: Offset, snapping: Boolean): List<Pair<Offset, Offset>> {
    val l0 = md.start
    val t0 = l0.base.transform
    val center = view.toScreen(t0.x.toFloat(), t0.y.toFloat())
    when (md.kind) {
        HandleKind.ROTATE -> {
            val a0 = atan2(start.y - center.y, start.x - center.x)
            val a1 = atan2(pos.y - center.y, pos.x - center.x)
            val rot = snapAngle(t0.rotation + Math.toDegrees((a1 - a0).toDouble()), snapping)
            st.updateLayer(l0.base.id, true) { it.withTransform(it.base.transform.copy(rotation = rot)) }
            return emptyList()
        }
        HandleKind.TL, HandleKind.TR, HandleKind.BR, HandleKind.BL -> {
            val d0 = max(1f, (start - center).getDistance())
            val f = ((pos - center).getDistance() / d0).toDouble().coerceAtLeast(0.02)
            st.updateLayer(l0.base.id, true) { scaleLayer(st, it, l0, f) }
            return emptyList()
        }
        else -> {
            // Side handles: stretch along a local axis, opposite side anchored.
            val rad = Math.toRadians(t0.rotation)
            val ax = Offset(cos(rad).toFloat(), sin(rad).toFloat())
            val ay = Offset(-sin(rad).toFloat(), cos(rad).toFloat())
            val dv = pos - start
            val s = (view.zoom * t0.scale).toFloat()
            val horizontal = md.kind == HandleKind.L || md.kind == HandleKind.R
            val sign = if (md.kind == HandleKind.R || md.kind == HandleKind.B) 1f else -1f
            val dLocal = (if (horizontal) dv.x * ax.x + dv.y * ax.y else dv.x * ay.x + dv.y * ay.y) / s * sign
            val (w0, h0) = st.renderer.box(l0)
            val size0 = if (horizontal) w0 else h0
            val minSize = 4f
            val newSize = max(minSize, size0 + dLocal)
            val shift = (newSize - size0) / 2f * sign * t0.scale.toFloat()
            val axis = if (horizontal) ax else ay
            val nt = t0.copy(x = t0.x + axis.x * shift, y = t0.y + axis.y * shift)
            st.updateLayer(l0.base.id, true) { l ->
                when (l) {
                    is TextLayer -> l.copy(autoWidth = false, boxWidth = max(1.0, newSize.toDouble()), base = l.base.copy(transform = nt))
                    is ShapeLayer -> if (horizontal) l.copy(width = newSize.toDouble(), base = l.base.copy(transform = nt))
                    else l.copy(height = newSize.toDouble(), base = l.base.copy(transform = nt))
                    else -> l
                }
            }
            return emptyList()
        }
    }
}

private fun pinch(st: EditorState, view: ViewXf, md: Mode.Pinch, a: Offset, b: Offset, snapping: Boolean) {
    val c = (a + b) / 2f
    val d = max(1f, (a - b).getDistance())
    val f = d / md.d0
    val l = md.layer
    if (l == null) {
        view.userAdjusted = true
        view.restorePan = null
        val z = (md.zoom0 * f).coerceIn(view.fitZoom * 0.2f, max(view.fitZoom * 40f, 8f))
        // keep the canvas point under the initial centroid under the current centroid
        val cx = (md.c0.x - md.pan0.x) / md.zoom0
        val cy = (md.c0.y - md.pan0.y) / md.zoom0
        view.zoom = z
        view.panX = c.x - cx * z
        view.panY = c.y - cy * z
        return
    }
    val ang = atan2(b.y - a.y, b.x - a.x)
    val t0 = l.base.transform
    val rot = snapAngle(t0.rotation + Math.toDegrees((ang - md.a0).toDouble()), snapping)
    val nx = t0.x + (c.x - md.c0.x) / view.zoom
    val ny = t0.y + (c.y - md.c0.y) / view.zoom
    st.updateLayer(l.base.id, true) { cur ->
        scaleLayer(st, cur, l, f.toDouble()).let { it.withTransform(it.base.transform.copy(x = nx, y = ny, rotation = rot)) }
    }
}

private fun finishCreateBox(st: EditorState, view: ViewXf, s: Offset, e: Offset) {
    val a = view.toCanvas(s); val b = view.toCanvas(e)
    val w = abs(b.x - a.x).toDouble(); val h = abs(b.y - a.y).toDouble()
    if (st.tool == Tool.TEXT) {
        val tmp = st.defaultTextLayer(0.0, 0.0)
        val lh = tmp.fontSize * tmp.lineHeight
        if (w < 20) { st.createText(a.x.toDouble(), a.y.toDouble()); return }
        st.createText((a.x + b.x) / 2.0, min(a.y, b.y) + lh / 2, autoWidth = false, boxWidth = w)
    } else if (st.tool == Tool.SHAPES) {
        val kind = st.shapeKind
        if (w < 8 && h < 8) { st.addShape(kind, a.x.toDouble(), a.y.toDouble()); return }
        if (kind.isLinear) {
            val len = hypot(w, h)
            st.addShape(kind, (a.x + b.x) / 2.0, (a.y + b.y) / 2.0, w = max(8.0, len))
            val ang = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()))
            st.selectedId?.let { id -> st.updateLayer(id, true) { it.withTransform(it.base.transform.copy(rotation = ((ang % 360) + 360) % 360)) } }
            st.endLive()
        } else st.addShape(kind, (a.x + b.x) / 2.0, (a.y + b.y) / 2.0, max(4.0, w), max(4.0, h))
    }
}

// ------------------------------------------------------------------ drawing

private fun addDrawPoint(st: EditorState, md: Mode.Draw, screen: Offset, pressure: Float) {
    val p = floatArrayOf(screen.x, screen.y); md.inv.mapPoints(p)
    // Exponential stabilizer: the more smoothing, the more the pen lags behind the finger.
    val k = 1f - st.smoothing * 0.85f
    md.sx += (p[0] - md.sx) * k
    md.sy += (p[1] - md.sy) * k
    val n = md.pts.size
    val lx = md.pts[n - 2]; val ly = md.pts[n - 1]
    val minDist = max(0.75f, st.brushSize * 0.04f)
    if (hypot(md.sx - lx, md.sy - ly) >= minDist) {
        md.pts.add(md.sx); md.pts.add(md.sy)
        md.pressure?.add(pressure.coerceIn(0f, 1f))
    }
}

private fun currentStroke(st: EditorState, md: Mode.Draw): BaStroke {
    val pts = md.pts.toFloatArray()
    return BaStroke(
        brush = st.brush,
        size = st.brushSize.toDouble(),
        color = st.brushColor,
        opacity = st.brushOpacity.toDouble(),
        points = FloatList(pts),
        pressure = md.pressure?.takeIf { it.size == pts.size / 2 }?.let { FloatList(it.toFloatArray()) },
    )
}

@Suppress("unused") private fun unusedImports(c: PointerInputChange, i: ImageLayer) = Unit
