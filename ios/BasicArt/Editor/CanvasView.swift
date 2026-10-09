import Combine
import SwiftUI
import UIKit

/// The editor canvas: renders the document with the shared Renderer at screen
/// resolution, and owns all canvas gestures (select/move/scale/rotate, zoom/pan,
/// drawing, text and shape creation, on-canvas text editing).
final class CanvasView: UIView, UIGestureRecognizerDelegate {
    let model: EditorModel
    private var bag = Set<AnyCancellable>()

    // Viewport: view = canvas * zoom + offset
    private(set) var zoom: CGFloat = 1
    private(set) var offset: CGPoint = .zero
    private var userAdjustedViewport = false
    private var lastBoundsSize: CGSize = .zero

    let overlay = SelectionOverlayView()
    let textEditor = CanvasTextEditor()

    // Render cache of the layers below/above the selected layer.
    private var cache: RenderCacheEntry?

    // Gesture state
    private enum Drag {
        case none
        case move(id: String, start: Transform, startPoint: CGPoint)
        case corner(id: String, start: Layer, startDist: CGFloat)
        case side(id: String, start: Layer, handle: HandleKind, startPoint: CGPoint)
        case rotate(id: String, start: Transform, startAngle: CGFloat)
        case panCanvas(startOffset: CGPoint)
        case createBox(start: CGPoint, current: CGPoint)
    }
    private var drag: Drag = .none
    private var twoFingerLayer: (id: String, start: Layer)?
    private var pinchStartZoom: CGFloat = 1
    private var pinchAnchorCanvas: CGPoint = .zero
    private var lastTap: (time: TimeInterval, point: CGPoint, id: String?)?
    private var liveStroke: Stroke?
    private var liveStrokeLayerID: String?
    private var strokeSmoothed: CGPoint?

    private var tapGR: UITapGestureRecognizer!
    private var panGR: UIPanGestureRecognizer!
    private var pinchGR: UIPinchGestureRecognizer!
    private var rotateGR: UIRotationGestureRecognizer!
    private var twoPanGR: UIPanGestureRecognizer!
    private var longGR: UILongPressGestureRecognizer!
    private var strokeGR: StrokeGestureRecognizer!
    private var loupeGR: UILongPressGestureRecognizer!
    private let loupe = LoupeView()

    var imageMaxPixel: Int { UIDevice.current.userInterfaceIdiom == .pad ? 3072 : 2048 }

    init(model: EditorModel) {
        self.model = model
        super.init(frame: .zero)
        backgroundColor = .clear
        isOpaque = false
        contentMode = .redraw
        isMultipleTouchEnabled = true
        overlay.canvas = self
        addSubview(overlay)
        textEditor.attach(to: self)
        setupGestures()
        isAccessibilityElement = false
        pageElement.accessibilityLabel = "Canvas"
        pageElement.accessibilityTraits = .allowsDirectInteraction
        pageElement.accessibilityIdentifier = "canvasPage"

        model.$revision.sink { [weak self] _ in
            DispatchQueue.main.async { self?.documentChanged() }
        }.store(in: &bag)
        model.$selection.sink { [weak self] _ in DispatchQueue.main.async { self?.refresh() } }.store(in: &bag)
        model.$editingTextID.sink { [weak self] id in DispatchQueue.main.async { self?.editingChanged(id) } }.store(in: &bag)
        model.$tool.sink { [weak self] _ in DispatchQueue.main.async { self?.toolChanged() } }.store(in: &bag)
        model.viewStateProvider = { [weak self] in
            guard let self, self.bounds.width > 0 else { return (1, Double(self?.canvasSize.width ?? 0) / 2, Double(self?.canvasSize.height ?? 0) / 2) }
            let c = self.toCanvas(CGPoint(x: self.bounds.midX, y: self.bounds.midY))
            return (Double(self.zoom / max(0.0001, self.fitZoom())), Double(c.x), Double(c.y))
        }
        model.$fitRequest.dropFirst().sink { [weak self] _ in DispatchQueue.main.async { self?.fitToScreen(animated: true) } }.store(in: &bag)
        NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)
            .sink { [weak self] n in self?.keyboardChanged(n) }.store(in: &bag)
        NotificationCenter.default.publisher(for: UIApplication.didReceiveMemoryWarningNotification)
            .sink { [weak self] _ in self?.cache = nil; self?.model.assets.purge(); Blur.clearCache() }.store(in: &bag)
    }

    required init?(coder: NSCoder) { fatalError() }

    // MARK: Viewport

    var canvasSize: CGSize { CGSize(width: model.doc.canvas.width, height: model.doc.canvas.height) }
    var viewTransform: CGAffineTransform { CGAffineTransform(a: zoom, b: 0, c: 0, d: zoom, tx: offset.x, ty: offset.y) }
    func toView(_ p: CGPoint) -> CGPoint { CGPoint(x: p.x * zoom + offset.x, y: p.y * zoom + offset.y) }
    func toCanvas(_ p: CGPoint) -> CGPoint { CGPoint(x: (p.x - offset.x) / zoom, y: (p.y - offset.y) / zoom) }
    var canvasRectInView: CGRect { CGRect(origin: offset, size: CGSize(width: canvasSize.width * zoom, height: canvasSize.height * zoom)) }

    /// Zoom that fits the canvas (same rule as Fit).
    func fitZoom() -> CGFloat {
        let side: CGFloat = bounds.width > 500 ? 36 : 24
        let w = bounds.width - 2 * side, h = bounds.height - 58 - 30
        guard w > 10, h > 10 else { return 1 }
        return max(0.0005, min(w / canvasSize.width, h / canvasSize.height))
    }

    /// Restores a saved view (§10.7): zoom as a multiple of fit, centred on (cx, cy).
    private func applyPendingView() -> Bool {
        guard let st = model.pendingState, let z = st.zoom, let cx = st.centerX, let cy = st.centerY else { return false }
        model.pendingState?.zoom = nil
        let zoomValue = fitZoom() * CGFloat(z)
        let o = CGPoint(x: bounds.midX - CGFloat(cx) * zoomValue, y: bounds.midY - CGFloat(cy) * zoomValue)
        let rect = CGRect(origin: o, size: CGSize(width: canvasSize.width * zoomValue, height: canvasSize.height * zoomValue))
        guard rect.intersects(bounds) else { return false } // would show nothing: re-fit instead
        userAdjustedViewport = abs(z - 1) > 0.001
        setViewport(zoom: zoomValue, offset: o)
        return true
    }

    func fitToScreen(animated: Bool = false) {
        guard bounds.width > 0, bounds.height > 0 else { return }
        // Leave room for selection handles and the rotate knob above the canvas top.
        let side: CGFloat = bounds.width > 500 ? 36 : 24
        maxHeightSeen = max(maxHeightSeen, bounds.height)
        let reserve = max(0, model.fitReserve - (maxHeightSeen - bounds.height))
        let avail = CGRect(x: side, y: 58, width: bounds.width - 2 * side, height: bounds.height - 58 - 30 - reserve)
        guard avail.width > 10, avail.height > 10 else { return }
        let z = max(0.0005, min(avail.width / canvasSize.width, avail.height / canvasSize.height))
        let newOffset = CGPoint(x: avail.midX - canvasSize.width * z / 2, y: avail.midY - canvasSize.height * z / 2)
        userAdjustedViewport = false
        setViewport(zoom: z, offset: newOffset, animated: animated)
    }

    private func setViewport(zoom z: CGFloat, offset o: CGPoint, animated: Bool = false) {
        if animated {
            let startZ = zoom, startO = offset
            let link = ViewportAnimator(duration: 0.25) { [weak self] t in
                guard let self else { return }
                let e = 1 - pow(1 - t, 3)
                self.zoom = startZ + (z - startZ) * e
                self.offset = CGPoint(x: startO.x + (o.x - startO.x) * e, y: startO.y + (o.y - startO.y) * e)
                self.viewportChanged()
            }
            link.start()
        } else {
            zoom = z
            offset = o
            viewportChanged()
        }
    }

    private var lastEditorZoom: CGFloat = 0
    private var maxHeightSeen: CGFloat = 0

    private func viewportChanged() {
        if model.viewportZoomed != userAdjustedViewport { model.viewportZoomed = userAdjustedViewport }
        cache = nil
        setNeedsDisplay()
        overlay.setNeedsDisplay()
        if abs(zoom - lastEditorZoom) > 0.0001 {
            // Font sizes in the on-canvas editor depend on zoom: restyle, not just move.
            lastEditorZoom = zoom
            textEditor.documentChanged()
        } else {
            textEditor.layoutEditor()
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        overlay.frame = bounds
        if bounds.size != lastBoundsSize {
            let old = lastBoundsSize
            lastBoundsSize = bounds.size
            // Only a first layout or a width change (rotation, side panel) re-fits.
            // Height-only changes (panels growing/shrinking, tabs) never move the canvas.
            if abs(bounds.width - old.width) > 0.5 { maxHeightSeen = bounds.height }
            if old == .zero, applyPendingView() {
                // restored saved view
            } else if old == .zero || (abs(bounds.width - old.width) > 0.5 && !userAdjustedViewport) {
                fitToScreen()
                if model.editingTextID != nil { DispatchQueue.main.async { self.ensureEditingVisible() } }
            } else if abs(bounds.width - old.width) > 0.5 {
                offset.x += (bounds.width - old.width) / 2
                viewportChanged()
            } else if Date() < settleFitUntil, !userAdjustedViewport, model.editingTextID == nil {
                // Typing just ended and the tool bar / keyboard space is coming back: the
                // settle-fit started on the taller typing layout, so fit again to the final size
                // (QA N7: otherwise the canvas stays ~half a keyboard too low).
                fitToScreen(animated: true)
            } else {
                viewportChanged()
            }
        }
    }

    // MARK: Model observation

    private func documentChanged() {
        if lastCanvasSize != canvasSize { lastCanvasSize = canvasSize; if !userAdjustedViewport { fitToScreen() } }
        refresh()
        textEditor.documentChanged()
    }
    private var lastCanvasSize: CGSize = .zero

    func refresh() {
        setNeedsDisplay()
        overlay.setNeedsDisplay()
    }

    private var keyboardAdjusted = false
    /// Until then, height-only layout changes re-fit (the editor is settling after typing).
    private var settleFitUntil = Date.distantPast

    private func editingChanged(_ id: String?) {
        if id == nil && keyboardAdjusted {
            keyboardAdjusted = false
            userAdjustedViewport = false
            settleFitUntil = Date().addingTimeInterval(1.0)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { self.fitToScreen(animated: true) }
        }
        textEditor.setEditing(id)
        refresh()
    }

    private func toolChanged() {
        strokeGR.isEnabled = model.tool == .draw
        refresh()
    }

    // MARK: Drawing

    var hiddenLayerIDs: Set<String> {
        var s = Set<String>()
        if let e = model.editingTextID { s.insert(e) }
        return s
    }

    /// The document as currently shown (incl. the in-progress stroke).
    var displayDoc: Document {
        guard let stroke = liveStroke, let lid = liveStrokeLayerID, let i = model.doc.index(of: lid) else { return model.doc }
        var d = model.doc
        if case .drawing(var p) = d.layers[i].content {
            p.strokes.append(stroke)
            d.layers[i].content = .drawing(p)
        } else {
            d.layers[i].mask.append(stroke)
        }
        return d
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        let doc = displayDoc
        let cr = canvasRectInView
        // Drop shadow + transparency checkerboard (workspace UI, not canvas content).
        ctx.saveGState()
        ctx.setShadow(offset: CGSize(width: 0, height: 2), blur: 14, color: UIColor.black.withAlphaComponent(0.22).cgColor)
        ctx.setFillColor(UIColor.white.cgColor)
        ctx.fill(cr)
        ctx.restoreGState()
        if doc.canvas.background.a < 255 {
            ctx.saveGState()
            ctx.clip(to: cr)
            ctx.setFillColor(UIColor(patternImage: CheckerTile.image(cell: 8)).cgColor)
            ctx.fill(cr.intersection(bounds))
            ctx.restoreGState()
        }
        var opts = RenderOptions()
        opts.imageMaxPixel = imageMaxPixel
        opts.hiddenLayerIDs = hiddenLayerIDs
        if let lid = liveStrokeLayerID, let stroke = liveStroke, let idx = model.doc.index(of: lid) {
            // Fast path while drawing: cached layers below/above, cached existing strokes,
            // and only the live stroke rendered per frame.
            let base = model.doc
            let entry = cachedEntry(doc: base, focusIndex: idx, opts: opts)
            if let below = entry.below { Renderer.drawUpright(below, in: bounds, ctx: ctx) }
            let l = base.layers[idx]
            if l.visible {
                let existing = liveLayerImage(base.layers[idx], opts: opts)
                ctx.saveGState()
                ctx.clip(to: cr)
                ctx.setAlpha(CGFloat(l.opacity))
                ctx.beginTransparencyLayer(auxiliaryInfo: nil)
                ctx.setAlpha(1)
                if let existing { Renderer.drawUpright(existing, in: bounds, ctx: ctx) }
                ctx.concatenate(viewTransform)
                ctx.concatenate(LayerGeometry.transform(of: l))
                if l.type == .drawing { ctx.clip(to: CGRect(origin: .zero, size: LayerGeometry.boxSize(of: l))) }
                Renderer.drawStroke(stroke, in: ctx)
                ctx.endTransparencyLayer()
                ctx.restoreGState()
            }
            if let above = entry.above { Renderer.drawUpright(above, in: bounds, ctx: ctx) }
            return
        }
        let focusID = model.selection
        if let focusID, let idx = doc.index(of: focusID), doc.layers.count > 1 {
            let entry = cachedEntry(doc: doc, focusIndex: idx, opts: opts)
            if let below = entry.below { Renderer.drawUpright(below, in: bounds, ctx: ctx) }
            ctx.saveGState()
            ctx.clip(to: cr)
            ctx.concatenate(viewTransform)
            let l = doc.layers[idx]
            if l.visible && !opts.hiddenLayerIDs.contains(l.id) {
                Renderer.drawLayer(l, in: ctx, assets: model.assets, options: opts)
            }
            ctx.restoreGState()
            if let above = entry.above { Renderer.drawUpright(above, in: bounds, ctx: ctx) }
        } else {
            ctx.saveGState()
            ctx.concatenate(viewTransform)
            Renderer.draw(doc, in: ctx, assets: model.assets, options: opts)
            ctx.restoreGState()
        }
    }

    private var liveLayerCache: (layer: Layer, size: CGSize, zoom: CGFloat, offset: CGPoint, image: CGImage?)?

    /// The drawing layer's existing strokes at full opacity, rendered once per stroke session.
    private func liveLayerImage(_ layer: Layer, opts: RenderOptions) -> CGImage? {
        if let c = liveLayerCache, c.layer == layer, c.size == bounds.size, c.zoom == zoom, c.offset == offset { return c.image }
        let scale = window?.screen.scale ?? UIScreen.main.scale
        let pw = Int(bounds.width * scale), ph = Int(bounds.height * scale)
        var img: CGImage? = nil
        if pw > 0, ph > 0, let c = Renderer.makeContext(width: pw, height: ph) {
            c.translateBy(x: 0, y: CGFloat(ph)); c.scaleBy(x: 1, y: -1)
            c.scaleBy(x: scale, y: scale)
            c.concatenate(viewTransform)
            c.clip(to: CGRect(origin: .zero, size: canvasSize))
            var l = layer
            l.opacity = 1
            Renderer.drawLayer(l, in: c, assets: model.assets, options: opts)
            img = c.makeImage()
        }
        liveLayerCache = (layer, bounds.size, zoom, offset, img)
        return img
    }

    private struct RenderCacheEntry {
        var below: CGImage?
        var above: CGImage?
        var belowLayers: ArraySlice<Layer>
        var aboveLayers: ArraySlice<Layer>
        var background: RGBA
        var canvas: CGSize
        var hidden: Set<String>
        var size: CGSize
    }

    private func cachedEntry(doc: Document, focusIndex idx: Int, opts: RenderOptions) -> RenderCacheEntry {
        let belowLayers = doc.layers[..<idx]
        let aboveLayers = doc.layers[(idx + 1)...]
        if let c = cache, c.size == bounds.size, c.background == doc.canvas.background, c.canvas == canvasSize,
           c.hidden == opts.hiddenLayerIDs, c.belowLayers == belowLayers, c.aboveLayers == aboveLayers {
            return c
        }
        func render(_ layers: ArraySlice<Layer>, background: Bool) -> CGImage? {
            if layers.isEmpty && (!background || doc.canvas.background.a == 0) { return nil }
            let scale = window?.screen.scale ?? UIScreen.main.scale
            let pw = Int(bounds.width * scale), ph = Int(bounds.height * scale)
            guard pw > 0, ph > 0, let c = Renderer.makeContext(width: pw, height: ph) else { return nil }
            c.translateBy(x: 0, y: CGFloat(ph)); c.scaleBy(x: 1, y: -1)
            c.scaleBy(x: scale, y: scale)
            c.concatenate(viewTransform)
            var d = doc
            d.layers = Array(layers)
            var o = opts
            o.drawBackground = background
            Renderer.draw(d, in: c, assets: model.assets, options: o)
            return c.makeImage()
        }
        let entry = RenderCacheEntry(below: render(belowLayers, background: true), above: render(aboveLayers, background: false),
                                     belowLayers: belowLayers, aboveLayers: aboveLayers, background: doc.canvas.background,
                                     canvas: canvasSize, hidden: opts.hiddenLayerIDs, size: bounds.size)
        cache = entry
        return entry
    }

    // MARK: Gestures setup

    private func setupGestures() {
        tapGR = UITapGestureRecognizer(target: self, action: #selector(onTap(_:)))
        panGR = UIPanGestureRecognizer(target: self, action: #selector(onPan(_:)))
        panGR.maximumNumberOfTouches = 1
        pinchGR = UIPinchGestureRecognizer(target: self, action: #selector(onPinch(_:)))
        rotateGR = UIRotationGestureRecognizer(target: self, action: #selector(onRotate(_:)))
        twoPanGR = UIPanGestureRecognizer(target: self, action: #selector(onTwoPan(_:)))
        twoPanGR.minimumNumberOfTouches = 2
        longGR = UILongPressGestureRecognizer(target: self, action: #selector(onLongPress(_:)))
        longGR.minimumPressDuration = 0.45
        strokeGR = StrokeGestureRecognizer(target: self, action: #selector(onStroke(_:)))
        strokeGR.isEnabled = false
        loupeGR = UILongPressGestureRecognizer(target: self, action: #selector(onLoupe(_:)))
        loupeGR.minimumPressDuration = 0
        loupeGR.allowableMovement = .greatestFiniteMagnitude
        for g in [tapGR, panGR, pinchGR, rotateGR, twoPanGR, longGR, strokeGR, loupeGR] as [UIGestureRecognizer] {
            g.delegate = self
            addGestureRecognizer(g)
        }
    }

    /// While editing text, touches on the text view (and its selection handles / loupe) belong to it.
    func gestureRecognizer(_ g: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        guard model.editingTextID != nil, let v = touch.view else { return true }
        if v === textEditor.textView || v.isDescendant(of: textEditor.textView) { return false }
        // Selection handles can sit just outside the text view's bounds.
        let p = touch.location(in: textEditor.textView)
        if textEditor.textView.bounds.insetBy(dx: -24, dy: -24).contains(p) { return false }
        return true
    }

    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith o: UIGestureRecognizer) -> Bool {
        let two: Set<UIGestureRecognizer> = [pinchGR, rotateGR, twoPanGR]
        if two.contains(g) && two.contains(o) { return true }
        if g === strokeGR || o === strokeGR { return two.contains(g) || two.contains(o) }
        return false
    }

    override func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
        // Eyedropper mode: only the loupe gesture runs.
        if model.eyedropper != nil { return g === loupeGR }
        if g === loupeGR { return false }
        if model.tool == .draw && (g === panGR || g === longGR) { return false }
        if g === longGR {
            let p = toCanvas(g.location(in: self))
            return model.hitLayer(at: p, slop: 8 / zoom) != nil
        }
        return true
    }

    // MARK: Tap

    @objc private func onTap(_ g: UITapGestureRecognizer) {
        dismissPanelKeyboard()
        let v = g.location(in: self)
        let p = toCanvas(v)
        let now = Date.timeIntervalSinceReferenceDate
        let isDouble = lastTap.map { now - $0.time < 0.32 && hypot($0.point.x - v.x, $0.point.y - v.y) < 30 } ?? false
        if model.tool == .draw {
            if isDouble { fitToScreen(animated: true) }
            lastTap = (now, v, nil)
            return
        }
        if model.editingTextID != nil {
            // Tap outside the text being edited ends editing.
            if let id = model.editingTextID, let l = model.doc.layer(id), LayerGeometry.contains(l, point: p, slop: 12 / zoom) {
                return
            }
            model.endTextEditing()
            lastTap = (now, v, nil)
            return
        }
        if model.tool == .text {
            // Text tool (PM rule): tap the selected text → edit with the caret at the tap;
            // tap another text layer → select it; tap anywhere else on the canvas → new text. No cycling.
            if let sel = model.selectedLayer, sel.type == .text, sel.visible, !sel.locked,
               LayerGeometry.contains(sel, point: p, slop: 12 / zoom) {
                textEditor.pendingCaretPoint = v
                model.beginTextEditing(sel.id)
            } else if let hit = model.doc.layers.reversed().first(where: {
                $0.type == .text && $0.visible && !$0.locked && LayerGeometry.contains($0, point: p, slop: 10 / zoom)
            }) {
                model.select(hit.id)
            } else if CGRect(origin: .zero, size: canvasSize).contains(p) {
                model.addText(at: p)
            } else {
                model.select(nil) // taps outside the canvas never create invisible text
            }
            lastTap = (now, v, nil)
            return
        }
        if isDouble, let last = lastTap {
            lastTap = nil
            if let id = last.id ?? model.hitLayer(at: p, slop: 10 / zoom), model.doc.layer(id)?.type == .text {
                textEditor.pendingCaretPoint = v
                model.beginTextEditing(id)
            } else if last.id == nil {
                fitToScreen(animated: true)
            }
            return
        }
        let current = (lastTap.map { hypot($0.point.x - v.x, $0.point.y - v.y) < 20 } ?? false) ? model.selection : nil
        let hit = model.hitLayer(at: p, slop: 10 / zoom, cycleFrom: current)
        if model.tool == .text {
            if let hit, model.doc.layer(hit)?.type == .text {
                if model.selection == hit { model.beginTextEditing(hit) } else { model.select(hit) }
            } else if CGRect(origin: .zero, size: canvasSize).contains(p) {
                model.addText(at: p)
            } else {
                model.select(nil) // taps outside the canvas never create invisible text
            }
            lastTap = (now, v, hit)
            return
        }
        model.select(hit)
        lastTap = (now, v, hit)
    }

    // MARK: Long press

    @objc private func onLongPress(_ g: UILongPressGestureRecognizer) {
        guard g.state == .began else { return }
        let p = toCanvas(g.location(in: self))
        guard let id = model.hitLayer(at: p, slop: 8 / zoom) else { return }
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        model.select(id)
        model.contextMenuLayerID = id
        cancelDrag()
    }

    private func cancelDrag() {
        if case .none = drag { return }
        drag = .none
        model.endGesture()
        overlay.guides = []
        overlay.creationRect = nil
        refresh()
    }

    // MARK: One-finger pan

    @objc private func onPan(_ g: UIPanGestureRecognizer) {
        let v = g.location(in: self)
        switch g.state {
        case .began:
            let start = CGPoint(x: v.x - g.translation(in: self).x, y: v.y - g.translation(in: self).y)
            beginDrag(at: start)
            updateDrag(to: v)
        case .changed:
            updateDrag(to: v)
        case .ended, .cancelled, .failed:
            endDrag(at: v, cancelled: g.state != .ended)
        default: break
        }
    }

    private func beginHandleDrag(_ h: HandleKind, _ sel: Layer, _ v: CGPoint, _ p: CGPoint) {
        model.beginGesture()
        switch h {
        case .rotate:
            let c = toView(CGPoint(x: sel.transform.x, y: sel.transform.y))
            drag = .rotate(id: sel.id, start: sel.transform, startAngle: atan2(v.y - c.y, v.x - c.x))
        case .corner:
            let c = toView(CGPoint(x: sel.transform.x, y: sel.transform.y))
            drag = .corner(id: sel.id, start: sel, startDist: max(1, hypot(v.x - c.x, v.y - c.y)))
        default:
            drag = .side(id: sel.id, start: sel, handle: h, startPoint: p)
        }
    }

    private func beginDrag(at v: CGPoint) {
        dismissPanelKeyboard()
        let p = toCanvas(v)
        if model.editingTextID != nil {
            drag = .panCanvas(startOffset: offset)
            return
        }
        // Shapes tool: a drag always draws a new shape, unless it starts on a handle of the selected shape.
        if model.tool == .shapes {
            if let sel = model.selectedLayer, sel.type == .shape, !sel.locked, let h = overlay.handle(at: v, for: sel) {
                beginHandleDrag(h, sel, v, p)
                return
            }
            drag = .createBox(start: p, current: p)
            return
        }
        // Handles of the selected layer first.
        if let sel = model.selectedLayer, !sel.locked, model.tool != .draw, let h = overlay.handle(at: v, for: sel) {
            beginHandleDrag(h, sel, v, p)
            return
        }
        let hit: String?
        if let sel = model.selectedLayer, !sel.locked, sel.visible, LayerGeometry.contains(sel, point: p, slop: 10 / zoom) {
            hit = sel.id
        } else {
            hit = model.hitLayer(at: p, slop: 6 / zoom)
        }
        let creating = (model.tool == .text || model.tool == .shapes)
        if let hit {
            // In creation tools, dragging on a different layer creates (only the selected layer moves),
            // except text layers in the Text tool, which select and move.
            let isText = model.doc.layer(hit)?.type == .text
            if creating && hit != model.selection && !(model.tool == .text && isText) {
                drag = .createBox(start: p, current: p)
                return
            }
            model.select(hit)
            guard let l = model.doc.layer(hit) else { return }
            model.beginGesture()
            drag = .move(id: hit, start: l.transform, startPoint: p)
            return
        }
        if creating {
            drag = .createBox(start: p, current: p)
            return
        }
        drag = .panCanvas(startOffset: offset)
    }

    private func updateDrag(to v: CGPoint) {
        let p = toCanvas(v)
        let snapping = AppSettings.shared.snapping
        switch drag {
        case .none: break
        case .panCanvas(let startOffset):
            guard let g = panGR else { return }
            let t = g.translation(in: self)
            userAdjustedViewport = true
            setViewport(zoom: zoom, offset: CGPoint(x: startOffset.x + t.x, y: startOffset.y + t.y))
        case .move(let id, let start, let startPoint):
            var t = start
            t.x += p.x - startPoint.x
            t.y += p.y - startPoint.y
            var guides: [Guide] = []
            if snapping, let l = model.doc.layer(id) {
                var moved = l
                moved.transform = t
                (t, guides) = snapMove(moved)
            }
            overlay.guides = guides
            model.updateLayer(id) { $0.transform = t }
        case .rotate(let id, let start, let startAngle):
            let c = toView(CGPoint(x: start.x, y: start.y))
            let a = atan2(v.y - c.y, v.x - c.x)
            var deg = start.rotation + Double((a - startAngle) * 180 / .pi)
            deg = Transform.normalizeDegrees(deg)
            var snapped = false
            if snapping {
                let nearest = (deg / 45).rounded() * 45
                if abs(nearest - deg) < 4 { deg = Transform.normalizeDegrees(nearest); snapped = true }
            }
            overlay.rotationReadout = (deg, snapped)
            model.updateLayer(id) { $0.transform.rotation = deg }
        case .corner(let id, let start, let startDist):
            let c = toView(CGPoint(x: start.transform.x, y: start.transform.y))
            let f = Double(max(0.01, hypot(v.x - c.x, v.y - c.y) / startDist))
            model.updateLayer(id) { l in applyScale(f, from: start, to: &l) }
        case .side(let id, let start, let handle, let startPoint):
            model.updateLayer(id) { l in applySide(handle, from: start, startPoint: startPoint, current: p, to: &l) }
        case .createBox(let start, _):
            drag = .createBox(start: start, current: p)
            overlay.creationRect = CGRect(x: min(start.x, p.x), y: min(start.y, p.y), width: abs(p.x - start.x), height: abs(p.y - start.y))
            overlay.creationIsLine = model.tool == .shapes && model.shapeDefaults.kind.isLinear
            overlay.creationLine = (start, p)
            overlay.setNeedsDisplay()
        }
    }

    private func endDrag(at v: CGPoint, cancelled: Bool) {
        switch drag {
        case .createBox(let start, let cur):
            overlay.creationRect = nil
            overlay.setNeedsDisplay()
            if !cancelled {
                let r = CGRect(x: min(start.x, cur.x), y: min(start.y, cur.y), width: abs(cur.x - start.x), height: abs(cur.y - start.y))
                if model.tool == .text {
                    let w = Double(r.width)
                    if w * Double(zoom) > 24 {
                        let fs = model.defaultFontSize()
                        let lh = fs * 1.2
                        model.addText(at: CGPoint(x: r.midX, y: r.minY + lh / 2), boxWidth: w)
                    } else {
                        model.addText(at: start)
                    }
                } else if model.tool == .shapes {
                    if model.shapeDefaults.kind.isLinear {
                        if hypot(cur.x - start.x, cur.y - start.y) * zoom > 12 {
                            model.addShape(model.shapeDefaults.kind, rect: CGRect(x: start.x, y: start.y, width: cur.x - start.x, height: cur.y - start.y))
                        }
                    } else if r.width * zoom > 12 && r.height * zoom > 12 {
                        model.addShape(model.shapeDefaults.kind, rect: r)
                    }
                }
            }
        case .none, .panCanvas: break
        default:
            model.endGesture()
        }
        drag = .none
        overlay.guides = []
        overlay.rotationReadout = nil
        refresh()
    }

    // MARK: Transform math

    private func applyScale(_ f: Double, from start: Layer, to l: inout Layer) {
        if case .text(var t) = start.content {
            t.fontSize = (t.fontSize * f).clamped(4, 2000)
            let ratio = t.fontSize / (start.text!.fontSize)
            if !t.autoWidth { t.boxWidth = max(1, t.boxWidth * ratio) }
            Spans.scaleSizes(&t, by: ratio)   // §7.9: span sizes follow corner/pinch scaling
            l.content = .text(t)
            if !start.mask.isEmpty {
                // §9.1: the mask follows corner/pinch scaling about the box centre.
                let c0 = LayerGeometry.boxSize(of: start), c1 = LayerGeometry.boxSize(of: l)
                let k = CGFloat(ratio)
                l.mask = start.mask.map { s in
                    var s = s
                    s.points = s.points.map { CGPoint(x: c1.width / 2 + k * ($0.x - c0.width / 2), y: c1.height / 2 + k * ($0.y - c0.height / 2)) }
                    s.size = s.size * Double(k)
                    return s
                }
            }
        } else {
            l.transform.scale = (start.transform.scale * f).clamped(0.01, 100)
        }
    }

    private func applySide(_ h: HandleKind, from start: Layer, startPoint: CGPoint, current: CGPoint, to l: inout Layer) {
        let th = start.transform.radians
        let ux = CGVector(dx: cos(th), dy: sin(th)), uy = CGVector(dx: -sin(th), dy: cos(th))
        let d = CGVector(dx: current.x - startPoint.x, dy: current.y - startPoint.y)
        let s = CGFloat(start.transform.scale)
        let along: CGFloat
        let axis: CGVector
        let sign: CGFloat
        switch h {
        case .left: axis = ux; sign = -1
        case .right: axis = ux; sign = 1
        case .top: axis = uy; sign = -1
        case .bottom: axis = uy; sign = 1
        default: return
        }
        along = (d.dx * axis.dx + d.dy * axis.dy) * sign / s
        let size0 = LayerGeometry.boxSize(of: start)
        var applied: CGFloat = 0
        switch start.content {
        case .text(var t):
            let newW = max(t.fontSize * 0.5, Double(size0.width + along))
            applied = CGFloat(newW) - size0.width
            t.autoWidth = false
            t.boxWidth = newW
            l.content = .text(t)
        case .shape(var sp):
            if h == .left || h == .right {
                let nw = max(1, sp.width + Double(along)); applied = CGFloat(nw - sp.width); sp.width = nw
            } else {
                let nh = max(1, sp.height + Double(along)); applied = CGFloat(nh - sp.height); sp.height = nh
            }
            l.content = .shape(sp)
        default: return
        }
        // Keep the opposite edge fixed: centre moves by half the growth along the axis.
        let shift = applied * s / 2 * sign
        l.transform.x = start.transform.x + Double(axis.dx * shift)
        l.transform.y = start.transform.y + Double(axis.dy * shift)
        if case .text = start.content {
            // Text height may change with wrapping; keep the top edge fixed.
            let newH = LayerGeometry.boxSize(of: l).height
            let dh = (newH - size0.height) * s / 2
            l.transform.x += Double(uy.dx * dh)
            l.transform.y += Double(uy.dy * dh)
        }
    }

    struct Guide: Equatable { var vertical: Bool; var position: CGFloat }

    private func snapMove(_ l: Layer) -> (Transform, [Guide]) {
        var t = l.transform
        let corners = LayerGeometry.corners(of: l)
        let minX = corners.map(\.x).min()!, maxX = corners.map(\.x).max()!
        let minY = corners.map(\.y).min()!, maxY = corners.map(\.y).max()!
        let W = canvasSize.width, H = canvasSize.height
        let thr = 7 / zoom
        var guides: [Guide] = []
        let xc: [(CGFloat, CGFloat)] = [(CGFloat(t.x), W / 2), (minX, 0), (maxX, W), (minX, W / 2), (maxX, W / 2)]
        if let best = xc.map({ ($0.1 - $0.0, $0.1) }).filter({ abs($0.0) < thr }).min(by: { abs($0.0) < abs($1.0) }) {
            t.x += Double(best.0); guides.append(Guide(vertical: true, position: best.1))
        }
        let yc: [(CGFloat, CGFloat)] = [(CGFloat(t.y), H / 2), (minY, 0), (maxY, H), (minY, H / 2), (maxY, H / 2)]
        if let best = yc.map({ ($0.1 - $0.0, $0.1) }).filter({ abs($0.0) < thr }).min(by: { abs($0.0) < abs($1.0) }) {
            t.y += Double(best.0); guides.append(Guide(vertical: false, position: best.1))
        }
        if !guides.isEmpty && overlay.guides.isEmpty { UISelectionFeedbackGenerator().selectionChanged() }
        return (t, guides)
    }

    // MARK: Two fingers

    @objc private func onPinch(_ g: UIPinchGestureRecognizer) { handleTwoFinger(g) }
    @objc private func onRotate(_ g: UIRotationGestureRecognizer) { handleTwoFinger(g) }
    @objc private func onTwoPan(_ g: UIPanGestureRecognizer) { handleTwoFinger(g) }

    private var twoActive = 0
    private var twoStart: (zoom: CGFloat, offset: CGPoint, centroid: CGPoint)?

    private func handleTwoFinger(_ g: UIGestureRecognizer) {
        switch g.state {
        case .began:
            if twoActive == 0 { beginTwo(g) }
            twoActive += 1
        case .changed:
            updateTwo()
        case .ended, .cancelled, .failed:
            twoActive = max(0, twoActive - 1)
            if twoActive == 0 { endTwo() }
        default: break
        }
    }

    private func beginTwo(_ g: UIGestureRecognizer) {
        if case .none = drag {} else { cancelDrag() }
        if liveStroke != nil { cancelStroke() }
        let c = g.location(in: self)
        pinchGR.scale = 1; rotateGR.rotation = 0; twoPanGR.setTranslation(.zero, in: self)
        if model.editingTextID == nil, model.tool != .draw, let sel = model.selectedLayer, !sel.locked,
           LayerGeometry.contains(sel, point: toCanvas(c), slop: 40 / zoom) {
            twoFingerLayer = (sel.id, sel)
            model.beginGesture()
        } else {
            twoFingerLayer = nil
            twoStart = (zoom, offset, c)
        }
    }

    private func updateTwo() {
        let scale = pinchGR.state == .began || pinchGR.state == .changed ? pinchGR.scale : 1
        let rot = rotateGR.state == .began || rotateGR.state == .changed ? rotateGR.rotation : 0
        let tr = twoPanGR.state == .began || twoPanGR.state == .changed ? twoPanGR.translation(in: self) : .zero
        if let (id, start) = twoFingerLayer {
            model.updateLayer(id) { l in
                applyScale(Double(scale), from: start, to: &l)
                var deg = Transform.normalizeDegrees(start.transform.rotation + Double(rot) * 180 / .pi)
                if AppSettings.shared.snapping {
                    let n = (deg / 45).rounded() * 45
                    if abs(n - deg) < 3 { deg = Transform.normalizeDegrees(n) }
                }
                l.transform.rotation = deg
                l.transform.x = start.transform.x + Double(tr.x / zoom)
                l.transform.y = start.transform.y + Double(tr.y / zoom)
            }
        } else if let s = twoStart {
            let z = (s.zoom * scale).clamped(minZoom, 64)
            // Keep the canvas point under the starting centroid under the moving centroid.
            let anchor = CGPoint(x: (s.centroid.x - s.offset.x) / s.zoom, y: (s.centroid.y - s.offset.y) / s.zoom)
            let c = CGPoint(x: s.centroid.x + tr.x, y: s.centroid.y + tr.y)
            userAdjustedViewport = true
            setViewport(zoom: z, offset: CGPoint(x: c.x - anchor.x * z, y: c.y - anchor.y * z))
        }
    }

    private var minZoom: CGFloat {
        let fit = min(bounds.width / canvasSize.width, bounds.height / canvasSize.height)
        return max(0.0005, fit * 0.25)
    }

    private func endTwo() {
        if twoFingerLayer != nil { model.endGesture() }
        twoFingerLayer = nil
        twoStart = nil
        refresh()
    }

    // MARK: Drawing strokes

    @objc private func onStroke(_ g: StrokeGestureRecognizer) {
        switch g.state {
        case .began:
            let b = model.brush
            let target: String?
            if b.brush == .eraser {
                // The eraser works on the selected layer of any type and never creates one.
                target = model.eraserTarget?.id
                if target == nil {
                    model.showToast("Select a layer to erase")
                    g.state = .cancelled
                    return
                }
            } else {
                target = model.drawingTarget(create: true)
            }
            guard let id = target, let l = model.doc.layer(id) else { g.state = .cancelled; return }
            liveStrokeLayerID = id
            let localSize = (b.size / max(0.01, l.transform.scale)).clamped(1, 200)
            liveStroke = Stroke(brush: b.brush, size: localSize, color: b.color, opacity: b.opacity, points: [], pressure: nil)
            strokeSmoothed = nil
            addStrokeSamples(g.samples, layer: l)
            g.samples.removeAll()
        case .changed:
            guard let id = liveStrokeLayerID, let l = model.doc.layer(id) else { return }
            addStrokeSamples(g.samples, layer: l)
            g.samples.removeAll()
            setNeedsDisplay()
        case .ended:
            guard let id = liveStrokeLayerID, let l = model.doc.layer(id), var s = liveStroke else { cancelStroke(); return }
            addStrokeSamples(g.samples, layer: l, final: true)
            g.samples.removeAll()
            s = liveStroke ?? s
            liveStroke = nil
            liveStrokeLayerID = nil
            if !s.points.isEmpty {
                if l.type == .drawing { model.addStroke(s, to: id) } else { model.addMaskStroke(s, to: id) }
            }
            setNeedsDisplay()
        default:
            cancelStroke()
        }
    }

    private func cancelStroke() {
        liveStroke = nil
        liveStrokeLayerID = nil
        setNeedsDisplay()
    }

    private func addStrokeSamples(_ samples: [StrokeGestureRecognizer.Sample], layer: Layer, final: Bool = false) {
        guard var s = liveStroke else { return }
        let inv = LayerGeometry.transform(of: layer).inverted()
        let smoothing = model.brush.smoothing
        let minDist = max(0.4, 1.2 / zoom / CGFloat(layer.transform.scale))
        var pressures = s.pressure ?? []
        var anyPressure = s.pressure != nil
        for (i, sm) in samples.enumerated() {
            let raw = toCanvas(sm.location).applying(inv)
            var pt = raw
            if smoothing, let prev = strokeSmoothed {
                let k: CGFloat = 0.42
                pt = CGPoint(x: prev.x + (raw.x - prev.x) * k, y: prev.y + (raw.y - prev.y) * k)
            }
            let isLast = final && i == samples.count - 1
            if let last = s.points.last, hypot(pt.x - last.x, pt.y - last.y) < minDist, !isLast { strokeSmoothed = pt; continue }
            strokeSmoothed = pt
            if let p = sm.pressure, model.brush.usePressure {
                if !anyPressure { anyPressure = true; pressures = Array(repeating: 1, count: s.points.count) }
                pressures.append(Double(p))
            } else if anyPressure {
                pressures.append(pressures.last ?? 1)
            }
            s.points.append(isLast && smoothing ? raw : pt)
        }
        if final, smoothing, let last = samples.last {
            let raw = toCanvas(last.location).applying(inv)
            if let lp = s.points.last, hypot(raw.x - lp.x, raw.y - lp.y) > 0.5 {
                s.points.append(raw)
                if anyPressure { pressures.append(pressures.last ?? 1) }
            }
        }
        s.pressure = anyPressure ? pressures.map { round4($0) } : nil
        s.points = s.points.map { CGPoint(x: round2($0.x), y: round2($0.y)) }
        liveStroke = s
    }

    // MARK: Eyedropper

    // MARK: Eyedropper loupe

    /// Drag shows a magnifier (pixel grid, crosshair, live colour + hex); lift on the canvas to pick,
    /// lift (or tap) off the canvas to cancel. Samples the rendered canvas at canvas resolution.
    @objc private func onLoupe(_ g: UILongPressGestureRecognizer) {
        let v = g.location(in: self)
        let inside = canvasRectInView.contains(v)
        switch g.state {
        case .began, .changed:
            guard inside || g.state == .changed else { loupe.removeFromSuperview(); return }
            if loupe.superview == nil { addSubview(loupe) }
            let c = pickColor(at: v)
            loupe.update(grid: pixelGrid(at: v, n: 9), color: c)
            var center = CGPoint(x: v.x, y: v.y - 96)
            if center.y < 70 { center.y = v.y + 96 }
            loupe.center = CGPoint(x: min(max(center.x, 64), bounds.width - 64), y: center.y)
            loupe.isHidden = !inside
        case .ended:
            loupe.removeFromSuperview()
            guard let pick = model.eyedropper else { return }
            model.eyedropper = nil
            if inside, let c = pickColor(at: v) {
                pick(c)
                AppSettings.shared.noteColorUsed(c)
                UISelectionFeedbackGenerator().selectionChanged()
            }
        default:
            loupe.removeFromSuperview()
            model.eyedropper = nil
        }
    }

    /// An n×n image of rendered canvas pixels centred on the touch (canvas resolution).
    func pixelGrid(at v: CGPoint, n: Int) -> CGImage? {
        let p = toCanvas(v)
        let x0 = floor(p.x) - CGFloat(n / 2), y0 = floor(p.y) - CGFloat(n / 2)
        guard let ctx = Renderer.makeContext(width: n, height: n) else { return nil }
        ctx.setFillColor(UIColor(white: 0.85, alpha: 1).cgColor)
        ctx.fill(CGRect(x: 0, y: 0, width: n, height: n))
        ctx.translateBy(x: 0, y: CGFloat(n)); ctx.scaleBy(x: 1, y: -1)
        ctx.translateBy(x: -x0, y: -y0)
        var opts = RenderOptions(); opts.imageMaxPixel = imageMaxPixel
        Renderer.draw(model.doc, in: ctx, assets: model.assets, options: opts)
        return ctx.makeImage()
    }

    func pickColor(at v: CGPoint) -> RGBA? {
        let p = toCanvas(v)
        guard p.x >= 0, p.y >= 0, p.x < canvasSize.width, p.y < canvasSize.height else { return nil }
        guard let ctx = Renderer.makeContext(width: 1, height: 1) else { return nil }
        ctx.translateBy(x: 0, y: 1); ctx.scaleBy(x: 1, y: -1)
        ctx.translateBy(x: -p.x, y: -p.y)
        var opts = RenderOptions(); opts.imageMaxPixel = imageMaxPixel
        Renderer.draw(model.doc, in: ctx, assets: model.assets, options: opts)
        guard let data = ctx.data else { return nil }
        let px = data.bindMemory(to: UInt8.self, capacity: 4)
        let a = px[3]
        guard a > 0 else { return RGBA.clear }
        func un(_ v: UInt8) -> UInt8 { UInt8(min(255, (Int(v) * 255 + Int(a) / 2) / Int(a))) }
        return RGBA(r: un(px[0]), g: un(px[1]), b: un(px[2]), a: a)
    }

    // MARK: Keyboard

    private var keyboardFrame: CGRect = .zero

    private func keyboardChanged(_ n: Notification) {
        guard let f = n.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect, let w = window else { return }
        keyboardFrame = convert(f, from: w.screen.coordinateSpace)
        ensureEditingVisible()
        // A panel field's keyboard went away: settle the canvas back into view.
        let hidden = keyboardFrame.intersection(bounds).height < 1
        if hidden, model.editingTextID == nil, !userAdjustedViewport {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { [weak self] in
                guard let self, !self.userAdjustedViewport, self.model.editingTextID == nil else { return }
                self.fitToScreen(animated: true)
            }
        }
    }

    /// Tapping or dragging the canvas ends typing in a panel field (which commits it).
    private func dismissPanelKeyboard() {
        if model.editingTextID == nil { window?.endEditing(true) }
    }

    func ensureEditingVisible() {
        guard let id = model.editingTextID, let l = model.doc.layer(id) else { return }
        var visible = bounds
        let kb = keyboardFrame.intersection(bounds)
        if !kb.isNull && kb.height > 0 { visible.size.height = kb.minY - bounds.minY }
        visible = visible.insetBy(dx: 12, dy: 12)
        guard visible.height > 40 else { return }
        let pts = LayerGeometry.corners(of: l).map { toView($0) }
        let r = CGRect(x: pts.map(\.x).min()!, y: pts.map(\.y).min()!, width: 0, height: 0)
            .union(CGRect(x: pts.map(\.x).max()!, y: pts.map(\.y).max()!, width: 0, height: 0))
        var dy: CGFloat = 0
        if r.maxY > visible.maxY { dy = visible.maxY - r.maxY }
        if r.minY + dy < visible.minY { dy = visible.minY - r.minY }
        var dx: CGFloat = 0
        if r.width < visible.width {
            if r.maxX > visible.maxX { dx = visible.maxX - r.maxX }
            if r.minX + dx < visible.minX { dx = visible.minX - r.minX }
        }
        if abs(dx) > 0.5 || abs(dy) > 0.5 {
            if !userAdjustedViewport { keyboardAdjusted = true }
            setViewport(zoom: zoom, offset: CGPoint(x: offset.x + dx, y: offset.y + dy), animated: true)
        }
    }

    // MARK: Accessibility

    /// The canvas page as one VoiceOver element (frame follows zoom/pan).
    private lazy var pageElement = CanvasPageElement(accessibilityContainer: self)

    override var accessibilityElements: [Any]? {
        get {
            pageElement.canvas = self
            pageElement.accessibilityFrameInContainerSpace = canvasRectInView.intersection(bounds)
            // The on-canvas text editor stays reachable (VoiceOver and UI tests).
            return [pageElement, textEditor.textView]
        }
        set {}
    }

    var accessibilitySummary: String {
        let n = model.doc.layers.count
        var s = "\(model.doc.canvas.width) by \(model.doc.canvas.height) pixels, \(n) layer\(n == 1 ? "" : "s")"
        if let l = model.selectedLayer { s += ". Selected: \(l.name)" }
        return s
    }
}

final class CanvasPageElement: UIAccessibilityElement {
    weak var canvas: CanvasView?
    override var accessibilityValue: String? {
        get { canvas?.accessibilitySummary }
        set {}
    }
}

/// Collects touch samples for drawing; begins immediately, cancels if a second
/// finger lands early (so pinch-zoom wins).
final class StrokeGestureRecognizer: UIGestureRecognizer {
    struct Sample { var location: CGPoint; var pressure: CGFloat? }
    var samples: [Sample] = []
    private var trackedTouch: UITouch?
    private var startTime: TimeInterval = 0
    private var count = 0

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        if trackedTouch != nil || touches.count > 1 {
            if state == .began || state == .changed, Date.timeIntervalSinceReferenceDate - startTime < 0.25 || count < 6 {
                state = .cancelled
            } else if trackedTouch == nil {
                state = .failed
            }
            return
        }
        guard let t = touches.first else { return }
        trackedTouch = t
        startTime = Date.timeIntervalSinceReferenceDate
        count = 0
        samples = [sample(t)]
        state = .began
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let t = trackedTouch, touches.contains(t) else { return }
        for c in event.coalescedTouches(for: t) ?? [t] { samples.append(sample(c)); count += 1 }
        state = .changed
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let t = trackedTouch, touches.contains(t) else { return }
        samples.append(sample(t))
        state = .ended
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) {
        state = .cancelled
    }

    override func reset() {
        trackedTouch = nil
        samples = []
        count = 0
    }

    private func sample(_ t: UITouch) -> Sample {
        let p = t.location(in: view)
        var pressure: CGFloat? = nil
        if t.type == .pencil, t.maximumPossibleForce > 0 { pressure = min(1, max(0, t.force / t.maximumPossibleForce)) }
        return Sample(location: p, pressure: pressure)
    }
}

/// Small display-link animator for viewport transitions.
final class ViewportAnimator: NSObject {
    private var link: CADisplayLink?
    private let duration: Double
    private let step: (CGFloat) -> Void
    private var startTime: CFTimeInterval = 0
    private static var active: ViewportAnimator?

    init(duration: Double, step: @escaping (CGFloat) -> Void) {
        self.duration = duration
        self.step = step
    }

    func start() {
        ViewportAnimator.active?.stop()
        ViewportAnimator.active = self
        startTime = CACurrentMediaTime()
        link = CADisplayLink(target: self, selector: #selector(tick))
        link?.add(to: .main, forMode: .common)
    }

    @objc private func tick() {
        let t = min(1, (CACurrentMediaTime() - startTime) / duration)
        step(CGFloat(t))
        if t >= 1 { stop() }
    }

    func stop() {
        link?.invalidate()
        link = nil
        if ViewportAnimator.active === self { ViewportAnimator.active = nil }
    }
}

/// SwiftUI wrapper.
struct CanvasRepresentable: UIViewRepresentable {
    let model: EditorModel
    var onCreate: (CanvasView) -> Void = { _ in }
    func makeUIView(context: Context) -> CanvasView {
        let v = CanvasView(model: model)
        onCreate(v)
        return v
    }
    func updateUIView(_ v: CanvasView, context: Context) {}
}

/// Magnifier shown while using the eyedropper.
final class LoupeView: UIView {
    private let pixels = UIImageView()
    private let grid = CAShapeLayer()
    private let cross = CAShapeLayer()
    private let label = UILabel()
    private let swatch = UIView()
    private let size: CGFloat = 112

    override init(frame: CGRect) {
        super.init(frame: CGRect(x: 0, y: 0, width: 112, height: 150))
        isUserInteractionEnabled = false
        let ring = UIView(frame: CGRect(x: 0, y: 0, width: size, height: size))
        ring.layer.cornerRadius = size / 2
        ring.clipsToBounds = true
        ring.layer.borderWidth = 3
        ring.layer.borderColor = UIColor.white.cgColor
        pixels.frame = ring.bounds
        pixels.layer.magnificationFilter = .nearest
        ring.addSubview(pixels)
        let step = size / 9
        let gp = UIBezierPath()
        for i in 1..<9 {
            gp.move(to: CGPoint(x: CGFloat(i) * step, y: 0)); gp.addLine(to: CGPoint(x: CGFloat(i) * step, y: size))
            gp.move(to: CGPoint(x: 0, y: CGFloat(i) * step)); gp.addLine(to: CGPoint(x: size, y: CGFloat(i) * step))
        }
        grid.path = gp.cgPath
        grid.strokeColor = UIColor.black.withAlphaComponent(0.18).cgColor
        grid.lineWidth = 0.5
        ring.layer.addSublayer(grid)
        cross.path = UIBezierPath(rect: CGRect(x: 4 * step, y: 4 * step, width: step, height: step)).cgPath
        cross.fillColor = UIColor.clear.cgColor
        cross.strokeColor = UIColor.white.cgColor
        cross.lineWidth = 2
        cross.shadowOpacity = 0.6; cross.shadowRadius = 1; cross.shadowOffset = .zero
        ring.layer.addSublayer(cross)
        addSubview(ring)
        layer.shadowColor = UIColor.black.cgColor
        layer.shadowOpacity = 0.3
        layer.shadowRadius = 8
        layer.shadowOffset = CGSize(width: 0, height: 3)
        let pill = UIView(frame: CGRect(x: 6, y: size + 8, width: size - 12, height: 28))
        pill.backgroundColor = UIColor.systemBackground.withAlphaComponent(0.95)
        pill.layer.cornerRadius = 14
        swatch.frame = CGRect(x: 6, y: 5, width: 18, height: 18)
        swatch.layer.cornerRadius = 9
        swatch.layer.borderWidth = 1
        swatch.layer.borderColor = UIColor.label.withAlphaComponent(0.2).cgColor
        label.frame = CGRect(x: 28, y: 0, width: pill.bounds.width - 32, height: 28)
        label.font = .monospacedSystemFont(ofSize: 12, weight: .semibold)
        label.textColor = .label
        pill.addSubview(swatch)
        pill.addSubview(label)
        addSubview(pill)
        accessibilityElementsHidden = true
    }
    required init?(coder: NSCoder) { fatalError() }

    func update(grid image: CGImage?, color: RGBA?) {
        pixels.image = image.map { UIImage(cgImage: $0) }
        swatch.backgroundColor = color?.uiColor ?? .clear
        label.text = color.map { "#" + $0.hexRGB } ?? "—"
    }
}
