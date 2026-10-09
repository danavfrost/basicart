package com.halworks.basicart.ui.editor

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.halworks.basicart.data.AppContainer
import com.halworks.basicart.data.ImageImport
import com.halworks.basicart.data.Projects
import com.halworks.basicart.model.Brush
import com.halworks.basicart.model.CanvasSpec
import com.halworks.basicart.model.Colors
import com.halworks.basicart.model.Document
import com.halworks.basicart.model.DrawingLayer
import com.halworks.basicart.model.History
import com.halworks.basicart.model.ImageLayer
import com.halworks.basicart.model.Layer
import com.halworks.basicart.model.LayerBase
import com.halworks.basicart.model.LayerOps
import com.halworks.basicart.model.LoadResult
import com.halworks.basicart.model.ShapeKind
import com.halworks.basicart.model.ShapeLayer
import com.halworks.basicart.model.ShapeStroke
import com.halworks.basicart.model.Span
import com.halworks.basicart.model.Spans
import com.halworks.basicart.model.StyleFlag
import com.halworks.basicart.model.StyleFlags
import com.halworks.basicart.model.TextLayer
import com.halworks.basicart.model.TextPreset
import com.halworks.basicart.model.TextPresets
import com.halworks.basicart.model.Transform
import com.halworks.basicart.model.WHITE
import com.halworks.basicart.model.BLACK
import com.halworks.basicart.model.Fill
import com.halworks.basicart.model.id
import com.halworks.basicart.model.withTransform
import com.halworks.basicart.model.layerStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

enum class Tool(val label: String) {
    SELECT("Select"), TEXT("Text"), IMAGE("Image"), DRAW("Draw"), SHAPES("Shapes"), ADJUST("Adjust"), CANVAS("Canvas")
}

/**
 * All editor state. The document is immutable; edits replace it. Undo/redo keeps
 * snapshots. Continuous edits (gestures, sliders, typing) are grouped into one undo
 * step with [live] … [endLive].
 */
class EditorState(val app: AppContainer, val projectId: String, private val uiScope: CoroutineScope) {
    val renderer = app.rendererFor(projectId)

    var doc by mutableStateOf<Document?>(null)
        private set
    var loadError by mutableStateOf<String?>(null)
    private val history = History<Document>(100)
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set

    var selectedId by mutableStateOf<String?>(null)
    var tool by mutableStateOf(Tool.SELECT)
    var showLayers by mutableStateOf(false)
    var panelCollapsed by mutableStateOf(false)
    /** Bumped when the view should fit the canvas to the screen. */
    var fitRequest by mutableIntStateOf(0)

    // text editing
    var editingTextId by mutableStateOf<String?>(null); private set
    var textValue by mutableStateOf(TextFieldValue()); private set
    /** Flags toggled with a collapsed caret, applied to the next typed text. */
    /** Style for the next typed text, set with a collapsed caret (typing attributes). */
    private var typingStyle by mutableStateOf<com.halworks.basicart.model.CharStyle?>(null)
    private var typingIdle: Job? = null

    // draw tool
    var brush by mutableStateOf(Brush.PEN)
    var brushSize by mutableFloatStateOf(12f)
    var brushOpacity by mutableFloatStateOf(1f)
    var brushColor by mutableIntStateOf(0xFFE53935.toInt())
    var smoothing by mutableFloatStateOf(0.5f)

    // shapes tool defaults
    var shapeKind by mutableStateOf(ShapeKind.RECT)

    /** When set, the next canvas tap picks a color and calls this. */
    var eyedropper by mutableStateOf<((Int) -> Unit)?>(null)


    val selected: Layer? get() = selectedId?.let { id -> doc?.layer(id) }

    // ------------------------------------------------------------------ load / save

    suspend fun load() {
        val r = withContext(app.saveDispatcher) { app.store.load(projectId) }
        when (r) {
            is LoadResult.Ok -> {
                // Empty text layers left by an interrupted edit are removed (FORMAT.md §7.1).
                val cleaned = r.doc.copy(layers = r.doc.layers.filterNot { it is TextLayer && it.text.isEmpty() })
                renderer.useCanvas(cleaned); doc = cleaned
                app.store.markStrokesSaved(r.doc)
                if (cleaned != r.doc) scheduleSave()
                restoreState(cleaned)
            }
            is LoadResult.NewerVersion -> loadError = "This project was made with a newer version of Basic Art. Update the app to open it."
            is LoadResult.Corrupt -> loadError = "Can't open this project."
        }
    }

    private var saveJob: Job? = null
    private var dirty = false

    private fun scheduleSave() {
        dirty = true
        saveJob?.cancel()
        saveJob = uiScope.launch {
            delay(1000)
            saveNow(thumb = true)
        }
    }

    /** Saves off the main thread; at most one save in flight (serial dispatcher). */
    /** Set by the canvas: (zoom as a multiple of fit, center x, center y) in canvas px. */
    var viewProvider: (() -> Triple<Double, Double, Double>?)? = null

    /** View to restore on open (zoom multiple, center x, center y), consumed by the canvas. */
    var pendingView: Triple<Double, Double, Double>? = null

    /** Restored text selection: open editing without raising the keyboard. */
    var suppressKeyboardOnce = false

    /** Snapshot of where the user is (FORMAT.md §10.7). */
    /** Where the user was just before opening the Export sheet (text editing ends there). */
    var stateBeforeExport: com.halworks.basicart.model.EditorStateDoc? = null

    fun editorStateDoc(): com.halworks.basicart.model.EditorStateDoc {
        val v = viewProvider?.invoke()
        val editing = editingTextId
        return com.halworks.basicart.model.EditorStateDoc(
            selectedLayerIds = listOfNotNull(selectedId),
            activeTool = tool.name.lowercase(),
            textTab = com.halworks.basicart.model.EditorStateDoc.TABS.getOrNull(textTab),
            zoom = v?.first?.coerceIn(0.1, 32.0), centerX = v?.second, centerY = v?.third,
            textSelection = editing?.let { com.halworks.basicart.model.EditorStateDoc.TextSel(it, textValue.selection.min, textValue.selection.max) },
        )
    }

    /** Applies a saved editor state after loading (invalid parts are simply ignored). */
    private fun restoreState(d: Document) {
        val s = app.store.readEditorState(projectId).validFor(d)
        s.activeTool?.let { name -> Tool.entries.firstOrNull { it.name.lowercase() == name }?.let { tool = it } }
        s.textTab?.let { textTab = com.halworks.basicart.model.EditorStateDoc.TABS.indexOf(it).coerceAtLeast(0) }
        selectedId = s.selectedLayerIds.firstOrNull()
        if (s.zoom != null && s.centerX != null && s.centerY != null) pendingView = Triple(s.zoom, s.centerX, s.centerY)
        s.textSelection?.let { ts ->
            suppressKeyboardOnce = true
            startEditing(ts.layerId)
            setTextSelection(ts.start, ts.end)
            textTabsWhileEditing = true
        }
    }

    private fun writeState(state: com.halworks.basicart.model.EditorStateDoc) {
        val json = state.write()
        app.appScope.launch(app.saveDispatcher) {
            try { app.store.writeEditorState(projectId, json) } catch (e: Exception) { android.util.Log.w("Editor", "State save failed", e) }
        }
    }

    /** Saves off the main thread; at most one save in flight (serial dispatcher). Always records the editor state. */
    fun saveNow(thumb: Boolean = true): Job {
        val d = doc ?: return Job().apply { complete() }
        val state = editorStateDoc()
        if (!dirty) { writeState(state); return Job().apply { complete() } }
        dirty = false
        return app.appScope.launch(app.saveDispatcher) {
            try {
                val saved = app.store.save(d)
                app.store.writeEditorState(projectId, state.write())
                if (thumb) Projects.writeThumb(app, renderer, saved)
            } catch (e: Exception) {
                android.util.Log.e("Editor", "Save failed", e)
                dirty = true
            }
        }
    }

    /** Editor closing: save if needed (state always), then drop unreferenced assets/strokes. */
    fun close() {
        val state = editorStateDoc()
        endLive()
        finishEditing()
        saveJob?.cancel()
        val d = doc ?: return
        val needsSave = dirty
        dirty = false
        val states = listOf(d)
        app.appScope.launch(app.saveDispatcher) {
            try {
                if (needsSave) {
                    val saved = app.store.save(d)
                    Projects.writeThumb(app, renderer, saved)
                }
                app.store.writeEditorState(projectId, state.write())
                app.store.collectGarbage(projectId, states)
            } catch (e: Exception) {
                android.util.Log.e("Editor", "Final save failed", e)
            }
        }
    }

    // ------------------------------------------------------------------ edits

    private var txBefore: Document? = null

    private fun applyDoc(d: Document) {
        renderer.useCanvas(d)
        doc = d
        scheduleSave()
        if (selectedId != null && d.layer(selectedId!!) == null) selectedId = null
        if (editingTextId != null && d.layer(editingTextId!!) == null) { editingTextId = null }
    }

    /** [start, end) of the laid-out line holding [offset] in the text being edited. */
    fun lineBounds(offset: Int): Pair<Int, Int> {
        val t = editingTextId?.let { doc?.layer(it) } as? TextLayer ?: return 0 to 0
        val gl = renderer.layout(t).glyphs.filter { !it.isNewline }
        if (gl.isEmpty()) return 0 to 0
        // The glyph at the caret (or the one just before it at a line end).
        val g = gl.firstOrNull { offset >= it.srcStart && offset < it.srcEnd }
            ?: gl.lastOrNull { it.srcEnd <= offset } ?: gl.first()
        val onLine = gl.filter { it.line == g.line }
        val start = onLine.minOf { it.srcStart }
        var end = onLine.maxOf { it.srcEnd }
        // A wrapped line ends before its trailing space so End lands where the line visibly ends.
        if (onLine.last().isSpace && gl.any { it.line > g.line }) end = onLine.last().srcStart
        return start to end
    }

    /** The floating Fit button hides while a gesture runs or when it would cover the selection's handles. */
    var fitHidden by mutableStateOf(false)

    /** Off while a scale/pinch gesture runs (those scale about the center). */
    var anchorTexts = true

    /**
     * Text box housekeeping after every edit (specs §8.1). Auto-width boxes wrap by themselves
     * at 0.9 × canvas width (FORMAT.md §7.3 step 5); here:
     * 2. Height changes keep the top edge for boxes in the top part of the canvas and the bottom
     *    edge for boxes in the bottom part (others stay centered), so captions never grow off.
     * 3. Growth never pushes a box further outside the canvas than it already was.
     */
    private fun anchorTextHeights(before: Document, after: Document): Document {
        if (!anchorTexts || before.layers === after.layers) return after
        val W = after.canvas.width.toDouble(); val H = after.canvas.height.toDouble()
        var changed = false
        val layers = after.layers.map { l0 ->
            if (l0 !is TextLayer) return@map l0
            val old = before.layer(l0.id) as? TextLayer
            if (old === l0) return@map l0
            val l: TextLayer = l0
            val t = l.base.transform
            val sc = if (t.scale > 0) t.scale else 1.0
            if (old == null || old.base.transform != t) return@map l
            val (w0, h0) = renderer.box(old).let { it.first.toDouble() to it.second.toDouble() }
            val (w1, h1) = renderer.box(l).let { it.first.toDouble() to it.second.toDouble() }
            if (kotlin.math.abs(h1 - h0) < 0.5 && kotlin.math.abs(w1 - w0) < 0.5) return@map l
            val r = Math.toRadians(t.rotation)
            val cs = kotlin.math.cos(r); val sn = kotlin.math.sin(r)
            var x = t.x; var y = t.y
            // 2. Vertical anchoring.
            if (kotlin.math.abs(h1 - h0) >= 0.5) {
                val half0 = h0 / 2 * sc
                val sign = when {
                    t.y - half0 < H * 0.3 -> 1.0
                    t.y + half0 > H * 0.7 -> -1.0
                    else -> 0.0
                }
                val d = (h1 - h0) / 2 * sc * sign
                x -= sn * d; y += cs * d
            }
            // 3. Clamp growth inside the canvas (axis-aligned bounds of the rotated box).
            fun overflow(cx: Double, cy: Double, w: Double, h: Double): DoubleArray {
                val hx = (kotlin.math.abs(cs) * w + kotlin.math.abs(sn) * h) / 2 * sc
                val hy = (kotlin.math.abs(sn) * w + kotlin.math.abs(cs) * h) / 2 * sc
                return doubleArrayOf(max(0.0, hx - cx), max(0.0, cx + hx - W), max(0.0, hy - cy), max(0.0, cy + hy - H), hx, hy)
            }
            val o0 = overflow(t.x, t.y, w0, h0)
            val o1 = overflow(x, y, w1, h1)
            if (o1[4] * 2 <= W) {
                val dl = o1[0] - o0[0]; val dr = o1[1] - o0[1]
                if (dl > 0 && dr <= 0) x += dl else if (dr > 0 && dl <= 0) x -= dr
            }
            if (o1[5] * 2 <= H) {
                val dt = o1[2] - o0[2]; val db = o1[3] - o0[3]
                if (dt > 0 && db <= 0) y += dt else if (db > 0 && dt <= 0) y -= db
            }
            if (x == t.x && y == t.y) return@map l
            changed = true
            l.withTransform(t.copy(x = x, y = y))
        }
        return if (changed) after.copy(layers = layers) else after
    }

    /** One undoable edit. */
    fun commit(f: (Document) -> Document) {
        endLive()
        val before = doc ?: return
        val after = anchorTextHeights(before, f(before))
        if (after == before) return
        history.push(before)
        applyDoc(after)
        refreshUndo()
    }

    /** Part of a continuous edit; one undo step until [endLive]. */
    fun live(f: (Document) -> Document) {
        val before = doc ?: return
        if (txBefore == null) txBefore = before
        val after = anchorTextHeights(before, f(before))
        if (after != before) applyDoc(after)
    }

    fun endLive() {
        val b = txBefore ?: return
        txBefore = null
        if (doc != b) { history.push(b); refreshUndo() }
    }

    fun undo() {
        endLive()
        if (editingTextId != null) finishEditing()
        val cur = doc ?: return
        val prev = history.undo(cur) ?: return
        applyDoc(prev); refreshUndo()
    }

    fun redo() {
        endLive()
        if (editingTextId != null) finishEditing()
        val cur = doc ?: return
        val next = history.redo(cur) ?: return
        applyDoc(next); refreshUndo()
    }

    private fun refreshUndo() { canUndo = history.canUndo; canRedo = history.canRedo }

    fun updateLayer(id: String, liveEdit: Boolean = false, f: (Layer) -> Layer) {
        val g: (Document) -> Document = { d ->
            d.layer(id)?.let { old ->
                var new = f(old)
                // Keep an image's eraser mask on the same pixels when crop/rotate/flip change (§9.1).
                if (old is ImageLayer && new is ImageLayer) new = com.halworks.basicart.model.MaskGeometry.remapImage(old, new)
                d.replaceLayer(new)
            } ?: d
        }
        if (liveEdit) live(g) else commit(g)
    }

    inline fun <reified T : Layer> updateSelected(liveEdit: Boolean = false, crossinline f: (T) -> T) {
        val id = selectedId ?: return
        updateLayer(id, liveEdit) { l -> if (l is T) f(l) else l }
    }

    // ------------------------------------------------------------------ layer ops

    fun newId() = UUID.randomUUID().toString()

    private fun uniqueName(base: String): String {
        val names = doc?.layers?.map { it.base.name }?.toSet() ?: emptySet()
        var i = 1
        while ("$base $i" in names) i++
        return "$base $i"
    }

    fun addLayer(l: Layer, select: Boolean = true) {
        commit { d -> d.copy(layers = LayerOps.insertAbove(d.layers, l, null)) }
        if (select) selectedId = l.id
    }

    fun deleteLayer(id: String) {
        if (editingTextId == id) { editingTextId = null }
        commit { d -> d.copy(layers = LayerOps.remove(d.layers, id)) }
        if (selectedId == id) selectedId = null
    }

    fun duplicateLayer(id: String) {
        val d = doc ?: return
        val l = d.layer(id) ?: return
        val off = max(12.0, min(d.canvas.width, d.canvas.height) * 0.03)
        val t = l.base.transform
        val copyName = (l.base.name + " copy").take(100)
        val nl = l.withBase(l.base.copy(id = newId(), name = copyName, transform = t.copy(x = t.x + off, y = t.y + off)))
        val nl2 = if (nl is DrawingLayer) nl.copy(strokesState = com.halworks.basicart.model.StrokesState.OK) else nl
        commit { dd -> dd.copy(layers = LayerOps.insertAbove(dd.layers, nl2, id)) }
        selectedId = nl2.id
    }

    fun reorder(op: String, id: String) {
        commit { d ->
            d.copy(
                layers = when (op) {
                    "top" -> LayerOps.toTop(d.layers, id)
                    "up" -> LayerOps.moveUp(d.layers, id)
                    "down" -> LayerOps.moveDown(d.layers, id)
                    "bottom" -> LayerOps.toBottom(d.layers, id)
                    else -> d.layers
                },
            )
        }
    }

    fun moveLayer(from: Int, to: Int) = commit { d -> d.copy(layers = LayerOps.move(d.layers, from, to)) }

    fun setVisible(id: String, v: Boolean) = updateLayer(id) { it.withBase(it.base.copy(visible = v)) }
    fun setLocked(id: String, v: Boolean) = updateLayer(id) { it.withBase(it.base.copy(locked = v)) }
    fun rename(id: String, n: String) = updateLayer(id) { it.withBase(it.base.copy(name = n.take(100))) }
    fun renameProject(n: String) = commit { it.copy(name = n.trim().take(100).ifEmpty { "Untitled" }) }

    /** Merge down = rasterizing is out of scope; merging two drawing layers concatenates strokes. */
    fun canMergeDown(id: String): Boolean {
        val d = doc ?: return false
        val i = d.indexOf(id)
        if (i <= 0) return false
        val a = d.layers[i]; val b = d.layers[i - 1]
        return a is DrawingLayer && b is DrawingLayer && a.base.transform == b.base.transform &&
            a.width == b.width && a.height == b.height && a.base.opacity == 1.0 && b.base.opacity == 1.0
    }

    fun mergeDown(id: String) {
        if (!canMergeDown(id)) return
        commit { d ->
            val i = d.indexOf(id)
            val a = d.layers[i] as DrawingLayer; val b = d.layers[i - 1] as DrawingLayer
            val merged = b.copy(strokes = b.strokes + a.strokes)
            d.copy(layers = d.layers.toMutableList().also { it[i - 1] = merged; it.removeAt(i) })
        }
        selectedId = doc?.layers?.getOrNull((doc?.indexOf(id) ?: 1) - 1)?.id
    }

    // ------------------------------------------------------------------ text

    fun defaultTextLayer(cx: Double, cy: Double, autoWidth: Boolean = true, boxWidth: Double = 0.0): TextLayer {
        val d = doc!!
        val fs = max(16.0, min(d.canvas.width, d.canvas.height) * 0.08).let { kotlin.math.round(it) }
        val bg = d.canvas.background
        val dark = Colors.alpha(bg) > 128 && luminance(bg) < 0.45
        return TextLayer(
            base = LayerBase(newId(), uniqueName("Text"), transform = Transform(cx, cy)),
            text = "",
            fontSize = fs,
            fill = Fill.Solid(if (dark) WHITE else BLACK),
            autoWidth = autoWidth,
            boxWidth = boxWidth,
        )
    }

    private fun luminance(c: Int): Double {
        val r = ((c shr 16) and 0xFF) / 255.0; val g = ((c shr 8) and 0xFF) / 255.0; val b = (c and 0xFF) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** Creates an empty text layer and starts editing it (one undo step with the typing). */
    fun createText(cx: Double, cy: Double, autoWidth: Boolean = true, boxWidth: Double = 0.0, preset: TextPreset? = null, text: String = "") {
        finishEditing()
        var t = defaultTextLayer(cx, cy, autoWidth, boxWidth).copy(text = text)
        if (preset != null) t = TextPresets.apply(t, preset)
        commit { d -> d.copy(layers = d.layers + t) }
        selectedId = t.id
        startEditing(t.id, selectAll = text.isNotEmpty())
    }

    fun startEditing(id: String, selectAll: Boolean = false) {
        val t = doc?.layer(id) as? TextLayer ?: return
        if (t.base.locked) return
        if (editingTextId != null && editingTextId != id) finishEditing()
        editingTextId = id
        textTabsWhileEditing = false
        selectedId = id
        tool = Tool.TEXT
        textValue = TextFieldValue(t.text, if (selectAll) TextRange(0, t.text.length) else TextRange(t.text.length))
        typingStyle = null
    }

    fun finishEditing() {
        val id = editingTextId ?: return
        typingIdle?.cancel()
        endLive()
        editingTextId = null
        val t = doc?.layer(id) as? TextLayer
        if (t != null && t.text.isEmpty()) {
            // Apps delete empty text layers when editing ends (FORMAT.md §7.1).
            commit { d -> d.copy(layers = LayerOps.remove(d.layers, id)) }
            if (selectedId == id) selectedId = null
        }
    }

    fun onTextValueChange(v: TextFieldValue) {
        val id = editingTextId ?: return
        val t = doc?.layer(id) as? TextLayer ?: return
        val old = textValue
        if (v.text != old.text) {
            val ls = t.layerStyle()
            var spans = Spans.applyEdit(old.text, v.text, ls, t.spans)
            // Typing over a selection keeps the selection's look: the replacement takes the style
            // of the selection's first character (unless typing attributes were set).
            val sel = old.selection
            val pending = typingStyle ?: if (!sel.collapsed && old.text.isNotEmpty())
                Spans.styleAt(old.text, ls, t.spans, (sel.min + 1).coerceAtMost(old.text.length)) else null
            var pre = 0
            val minLen = minOf(old.text.length, v.text.length)
            while (pre < minLen && old.text[pre] == v.text[pre]) pre++
            var suf = 0
            while (suf < minLen - pre && old.text[old.text.length - 1 - suf] == v.text[v.text.length - 1 - suf]) suf++
            val insEnd = v.text.length - suf
            if (pending != null && insEnd > pre) {
                // Restyle the inserted range with the typing attributes.
                val full = com.halworks.basicart.model.SpanStyle(pending.bold, pending.italic, pending.underline, pending.strike,
                    pending.color, pending.fontId, pending.weight, pending.size)
                spans = Spans.normalize(v.text, ls, spans + Span(pre, insEnd, full))
            }
            live { d -> d.replaceLayer(t.copy(text = v.text, spans = spans)) }
            typingIdle?.cancel()
            typingIdle = uiScope.launch { delay(1200); endLive() }
        } else if (v.selection != old.selection) {
            typingStyle = null
        }
        textValue = v
    }

    fun setTextSelection(s: Int, e: Int) {
        val len = textValue.text.length
        textValue = textValue.copy(selection = TextRange(s.coerceIn(0, len), e.coerceIn(0, len)))
        typingStyle = null
    }

    // ------------------------------------------------------------------ selection rule (specs §8.2)

    /** Where character styling goes right now. */
    sealed interface StyleTarget {
        data object Whole : StyleTarget
        data class Range(val s: Int, val e: Int) : StyleTarget
        data class Caret(val pos: Int) : StyleTarget
    }

    fun styleTarget(t: TextLayer): StyleTarget {
        if (editingTextId != t.id) return StyleTarget.Whole
        val sel = textValue.selection
        return when {
            !sel.collapsed && !(sel.min == 0 && sel.max >= t.text.length) -> StyleTarget.Range(sel.min, sel.max)
            sel.collapsed && t.text.isNotEmpty() -> StyleTarget.Caret(sel.start)
            else -> StyleTarget.Whole
        }
    }

    /** Effective styles under the current target (one entry per grapheme; caret → the typing style). */
    fun targetStyles(t: TextLayer): List<com.halworks.basicart.model.CharStyle> {
        val ls = t.layerStyle()
        return when (val tg = styleTarget(t)) {
            StyleTarget.Whole -> Spans.stylesIn(t.text, ls, t.spans, 0, t.text.length)
            is StyleTarget.Range -> Spans.stylesIn(t.text, ls, t.spans, tg.s, tg.e)
            is StyleTarget.Caret -> listOf(typingStyle ?: Spans.styleAt(t.text, ls, t.spans, tg.pos))
        }.ifEmpty { listOf(Spans.styleAt(t.text, ls, t.spans, 0)) }
    }

    /**
     * Applies one character-styling change by the selection rule: selection → spans;
     * caret → typing style; otherwise the whole box (layer field, cleared from spans).
     */
    fun applyCharStyle(
        liveEdit: Boolean = false,
        caret: (com.halworks.basicart.model.CharStyle) -> com.halworks.basicart.model.CharStyle,
        apply: (TextLayer, Int, Int) -> TextLayer,
    ) {
        val t = selected as? TextLayer ?: return
        when (val tg = styleTarget(t)) {
            is StyleTarget.Caret -> typingStyle = caret(typingStyle ?: Spans.styleAt(t.text, t.layerStyle(), t.spans, tg.pos))
            is StyleTarget.Range -> updateLayer(t.id, liveEdit) { l -> apply(l as TextLayer, tg.s, tg.e) }
            StyleTarget.Whole -> updateLayer(t.id, liveEdit) { l -> apply(l as TextLayer, 0, 0) }
        }
    }

    fun setTextColor(c: Int, liveEdit: Boolean = false) =
        applyCharStyle(liveEdit, { it.copy(color = c) }) { l, s, e -> com.halworks.basicart.model.CharStyling.setColor(l, s, e, c) }

    fun setTextSize(z: Double, liveEdit: Boolean = false) =
        applyCharStyle(liveEdit, { it.copy(size = z) }) { l, s, e -> com.halworks.basicart.model.CharStyling.setSize(l, s, e, z) }

    /** Family switch: weight = the new family's file nearest the current effective weight. */
    fun setTextFont(fontId: String, explicitWeight: Int? = null) {
        val t = selected as? TextLayer ?: return
        val fam = app.fonts.family(fontId)
        val curW = targetStyles(t).first().weight
        val w = explicitWeight ?: com.halworks.basicart.model.FontSelect.nearestWeight(fam.files, curW)
        applyCharStyle(false, { it.copy(fontId = fontId, weight = w) }) { l, s, e -> com.halworks.basicart.model.CharStyling.setFont(l, s, e, fontId, w) }
    }

    /** Effective state of a style flag for the current target (for the B/I/U/S buttons). */
    fun flagActive(t: TextLayer, flag: StyleFlag): Boolean = targetStyles(t).all { it.get(flag) }

    /** B/I/U/S by the selection rule. */
    fun toggleFlag(flag: StyleFlag) {
        val t = selected as? TextLayer ?: return
        when (val tg = styleTarget(t)) {
            is StyleTarget.Caret -> {
                val cur = typingStyle ?: Spans.styleAt(t.text, t.layerStyle(), t.spans, tg.pos)
                typingStyle = when (flag) {
                    StyleFlag.BOLD -> cur.copy(bold = !cur.bold); StyleFlag.ITALIC -> cur.copy(italic = !cur.italic)
                    StyleFlag.UNDERLINE -> cur.copy(underline = !cur.underline); StyleFlag.STRIKE -> cur.copy(strike = !cur.strike)
                }
            }
            is StyleTarget.Range -> {
                val r = Spans.toggle(t.text, t, flag, tg.s, tg.e)
                commit { d -> d.replaceLayer(t.copy(flags = r.layerFlags, spans = r.spans)) }
            }
            StyleTarget.Whole -> {
                val r = Spans.toggle(t.text, t, flag, 0, 0)
                commit { d -> d.replaceLayer(t.copy(flags = r.layerFlags, spans = r.spans)) }
            }
        }
    }

    fun applyPreset(p: TextPreset) {
        val t = selected as? TextLayer
        if (t == null) {
            val d = doc ?: return
            createText(d.canvas.width / 2.0, d.canvas.height / 2.0, preset = p, text = "Your text")
            return
        }
        commit { d -> d.replaceLayer(TextPresets.apply(t, p)) }
    }

    // ------------------------------------------------------------------ images

    suspend fun importImages(context: Context, uris: List<Uri>): Int {
        val d0 = doc ?: return 0
        val imported = withContext(kotlinx.coroutines.Dispatchers.IO) {
            uris.mapNotNull { u -> try { ImageImport.import(context, app.store, projectId, u) } catch (e: Exception) { null } }
        }
        if (imported.isEmpty()) return 0
        val cw = d0.canvas.width.toDouble(); val ch = d0.canvas.height.toDouble()
        val n = imported.size
        val step = min(cw, ch) * 0.05
        val layers = imported.mapIndexed { i, img ->
            val s = min(1.0, min(cw * 0.9 / img.naturalWidth, ch * 0.9 / img.naturalHeight)) * if (n > 1) 0.75 else 1.0
            val off = (i - (n - 1) / 2.0) * step
            ImageLayer(
                LayerBase(newId(), uniqueName("Photo").let { if (i == 0) it else "Photo ${i + 1}" }, transform = Transform(cw / 2 + off, ch / 2 + off, s.coerceIn(0.01, 100.0), 0.0)),
                img.assetRef, img.naturalWidth, img.naturalHeight,
            )
        }
        commit { d -> d.copy(layers = d.layers + layers) }
        selectedId = layers.last().id
        return layers.size
    }

    // ------------------------------------------------------------------ shapes / drawing

    fun addShape(kind: ShapeKind, cx: Double, cy: Double, w: Double? = null, h: Double? = null) {
        val d = doc ?: return
        val base = min(d.canvas.width, d.canvas.height) * 0.3
        val sw = max(4.0, kotlin.math.round(base / 30))
        val shape = when (kind) {
            ShapeKind.LINE, ShapeKind.ARROW -> ShapeLayer(
                LayerBase(newId(), uniqueName(if (kind == ShapeKind.LINE) "Line" else "Arrow"), transform = Transform(cx, cy)),
                kind, w ?: (base * 1.3), sw, stroke = ShapeStroke(true, brushColor, sw, com.halworks.basicart.model.Join.ROUND),
            )
            else -> ShapeLayer(
                LayerBase(newId(), uniqueName(when (kind) { ShapeKind.ELLIPSE -> "Ellipse"; ShapeKind.ROUND_RECT -> "Rounded rectangle"; else -> "Rectangle" }), transform = Transform(cx, cy)),
                kind, w ?: base, h ?: base,
                fill = com.halworks.basicart.model.ShapeFill(true, brushColor),
                stroke = ShapeStroke(false, BLACK, sw),
                cornerRadius = kotlin.math.round(base * 0.12),
            )
        }
        addLayer(shape)
    }

    /** The drawing layer strokes go to: the selected one, else a new one (created in the same undo step). */
    /**
     * A new drawing layer waiting for its first stroke. It joins the document only when a stroke
     * is committed, so a cancelled touch never leaves an empty "Drawing" layer behind.
     */
    var pendingDrawing by mutableStateOf<DrawingLayer?>(null)

    fun drawingTarget(): DrawingLayer {
        (selected as? DrawingLayer)?.takeIf { !it.base.locked && it.base.visible }?.let { return it }
        val d = doc!!
        val nl = DrawingLayer(
            LayerBase(newId(), uniqueName("Drawing"), transform = Transform(d.canvas.width / 2.0, d.canvas.height / 2.0)),
            d.canvas.width, d.canvas.height,
        )
        pendingDrawing = nl
        return nl
    }

    fun appendStroke(layerId: String, s: com.halworks.basicart.model.Stroke, toMask: Boolean = false) {
        val pend = pendingDrawing
        if (pend != null && pend.id == layerId && doc?.layer(layerId) == null) {
            pendingDrawing = null
            commit { d -> d.copy(layers = d.layers + pend.copy(strokes = listOf(s))) }
            selectedId = pend.id
            return
        }
        live { d ->
            val l = d.layer(layerId) ?: return@live d
            if (toMask) d.replaceLayer(l.withBase(l.base.copy(mask = l.base.mask + s)))
            else (l as? DrawingLayer)?.let { d.replaceLayer(it.copy(strokes = it.strokes + s)) } ?: d
        }
        endLive()
    }

    /** Layer the eraser works on: the selected, visible, unlocked layer of any type (never created). */
    fun eraserTarget(): Layer? = selected?.takeIf { it.base.visible && !it.base.locked }

    /** While editing: show the text tabs (keyboard down) instead of just the B/I/U/S bar. */
    var textTabsWhileEditing by mutableStateOf(false)

    /** Open text-options tab (kept when re-selecting layers). */
    var textTab by mutableIntStateOf(0)

    /** Bumped to show / hide the soft keyboard while editing (selection is kept). */
    var keyboardShowRequest by mutableIntStateOf(0)
    var keyboardHideRequest by mutableIntStateOf(0)

    /** One-off message for the snackbar. */
    var hint by mutableStateOf<String?>(null)

    // ------------------------------------------------------------------ canvas

    fun resizeCanvas(w: Int, h: Int, ax: Float, ay: Float) {
        commit { d ->
            val dx = (w - d.canvas.width) * ax.toDouble()
            val dy = (h - d.canvas.height) * ay.toDouble()
            d.copy(
                canvas = d.canvas.copy(width = w.coerceIn(16, 8192), height = h.coerceIn(16, 8192)),
                layers = d.layers.map { l -> l.withTransform(l.base.transform.copy(x = l.base.transform.x + dx, y = l.base.transform.y + dy)) },
            )
        }
        fitRequest++
    }

    fun setBackground(c: Int, liveEdit: Boolean = false) {
        val f: (Document) -> Document = { d -> d.copy(canvas = CanvasSpec(d.canvas.width, d.canvas.height, c)) }
        if (liveEdit) live(f) else commit(f)
    }
}
