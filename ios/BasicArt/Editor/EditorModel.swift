import Combine
import SwiftUI
import UIKit

enum EditorTool: String, CaseIterable, Identifiable {
    case select, text, image, draw, shapes, adjust, canvas
    var id: String { rawValue }
    var label: String {
        switch self {
        case .select: return "Select"
        case .text: return "Text"
        case .image: return "Image"
        case .draw: return "Draw"
        case .shapes: return "Shapes"
        case .adjust: return "Adjust"
        case .canvas: return "Canvas"
        }
    }
    var symbol: String {
        switch self {
        case .select: return "cursorarrow"
        case .text: return "textformat"
        case .image: return "photo.badge.plus"
        case .draw: return "paintbrush.pointed"
        case .shapes: return "square.on.circle"
        case .adjust: return "slider.horizontal.3"
        case .canvas: return "aspectratio"
        }
    }
}

/// Brush settings for the draw tool.
struct BrushState: Equatable {
    var brush: BrushType = .pen
    var size: Double = 12
    var opacity: Double = 1
    var color: RGBA = RGBA(hex: "#1E88E5FF")!
    var smoothing: Bool = true
    var usePressure: Bool = true
}

struct ShapeDefaults: Equatable {
    var kind: ShapeKind = .rect
    var fill = ShapeFill()
    var stroke = ShapeStroke(enabled: false, color: .black, width: 8, join: .miter)
}

/// Editor state for one open project: document, undo history, selection, tools, autosave.
@MainActor
final class EditorModel: ObservableObject {
    @Published private(set) var doc: Document
    @Published var selection: String?
    @Published var tool: EditorTool = .select { didSet { if tool != oldValue { toolChanged(from: oldValue) } } }
    @Published var panelCollapsed = false
    @Published var showLayers = false
    @Published var editingTextID: String?
    @Published var brush = BrushState()
    @Published var shapeDefaults = ShapeDefaults()
    @Published var contextMenuLayerID: String?
    @Published var toast: String?
    @Published var eyedropper: ((RGBA) -> Void)?
    @Published private(set) var canUndo = false
    @Published private(set) var canRedo = false
    /// Increments on every document change (cheap change signal for UIKit views).
    @Published private(set) var revision = 0
    /// Selected range in the on-canvas text editor (UTF-16), if editing.
    @Published var textSelection: NSRange?
    /// Full character style chosen with a collapsed caret; applied to what is typed next.
    @Published var typingStyle: CharStyle?
    /// Editing text with the keyboard hidden so the style panel can be used on the selection.
    @Published var textStyling = false
    /// True when the user zoomed/panned away from "fit"; drives the Fit button.
    @Published var viewportZoomed = false
    /// Docked side panel (iPad / landscape) collapsed state, persisted.
    @Published var sideCollapsed: Bool = UserDefaults.standard.bool(forKey: "sidePanelCollapsed") {
        didSet { if persistSide { UserDefaults.standard.set(sideCollapsed, forKey: "sidePanelCollapsed") } }
    }
    private var persistSide = true
    /// Phone landscape side column (defaults to shown).
    @Published var phoneSideCollapsed = UserDefaults.standard.bool(forKey: "phoneSidePanelCollapsed") {
        didSet { UserDefaults.standard.set(phoneSideCollapsed, forKey: "phoneSidePanelCollapsed") }
    }
    /// Applies the width-based default without persisting it (only user choices persist).
    func applySideDefault(width: CGFloat) {
        guard UserDefaults.standard.object(forKey: "sidePanelCollapsed") == nil else { return }
        persistSide = false
        sideCollapsed = width < 900
        persistSide = true
    }
    @Published var fitRequest = 0
    /// Bottom area a context panel can occupy (phone portrait); Fit keeps the canvas above it
    /// so panels opening later never cover it (and the canvas never has to jump).
    var fitReserve: CGFloat = 0

    private var history = UndoHistory<Document>(limit: 100)
    private var gestureSnapshot: Document?
    private var lastCoalesceKey: String?
    private var lastCoalesceTime = Date.distantPast

    let assets: AssetProvider
    let store: ProjectStore
    private let saver: ProjectSaver

    init(doc: Document, store: ProjectStore = .shared) {
        self.doc = doc
        self.store = store
        self.assets = AssetProvider(folder: store.folder(for: doc.id).appendingPathComponent("assets"))
        self.saver = ProjectSaver(store: store, initial: doc)
        prefetchImages()
        saver.stateProvider = { [weak self] in self?.currentEditorState() ?? EditorState() }
    }

    /// Decodes editing proxies off the main thread so the first canvas draw is quick.
    func prefetchImages() {
        let refs = Set(doc.layers.compactMap { $0.image?.assetRef })
        let assets = self.assets
        let maxPixel = UIDevice.current.userInterfaceIdiom == .pad ? 3072 : 2048
        DispatchQueue.global(qos: .userInitiated).async {
            for r in refs { _ = assets.image(r, maxPixel: maxPixel) }
            DispatchQueue.main.async { [weak self] in self?.revision &+= 1 }
        }
    }

    // MARK: Document mutation

    /// Applies an undoable edit. Edits with the same `coalesce` key within 1.2 s
    /// (e.g. slider drags) collapse into one undo step.
    func apply(_ coalesce: String? = nil, _ body: (inout Document) -> Void) {
        var next = doc
        body(&next)
        next.syncTextLimits()
        guard next != doc else { return }
        EditorModel.remapImageMasks(from: doc, to: &next)
        // §8.1: auto-width text never runs off the canvas.
        for i in next.layers.indices where next.layers[i].text?.autoWidth == true {
            // Only when the text itself grew (moving a layer is never clamped).
            if let old = doc.layer(next.layers[i].id), old.text != next.layers[i].text,
               LayerGeometry.boxSize(of: next.layers[i]).width > LayerGeometry.boxSize(of: old).width {
                EditorModel.fitAutoWidth(&next.layers[i], canvas: next.canvas)
            }
        }
        if gestureSnapshot == nil {
            let now = Date()
            if coalesce == nil || coalesce != lastCoalesceKey || now.timeIntervalSince(lastCoalesceTime) > 1.2 {
                history.record(doc)
            }
            lastCoalesceKey = coalesce
            lastCoalesceTime = now
        }
        commit(next)
    }

    /// Keeps image eraser masks attached when crop/rotate/flip change (§9.1).
    static func remapImageMasks(from old: Document, to next: inout Document) {
        for i in next.layers.indices where !next.layers[i].mask.isEmpty {
            guard let np = next.layers[i].image, let prev = old.layer(next.layers[i].id), let op = prev.image,
                  np.geometryDiffers(from: op), prev.mask == next.layers[i].mask else { continue }
            next.layers[i].mask = Renderer.remapImageMask(prev.mask, from: op, to: np)
        }
    }

    /// Begin a continuous gesture: one undo step for the whole gesture.
    func beginGesture() {
        guard gestureSnapshot == nil else { return }
        gestureSnapshot = doc
        lastCoalesceKey = nil
    }

    func endGesture() {
        guard let snap = gestureSnapshot else { return }
        gestureSnapshot = nil
        if snap != doc { history.record(snap); updateUndoFlags() }
    }

    func cancelGesture() {
        guard let snap = gestureSnapshot else { return }
        gestureSnapshot = nil
        commit(snap)
    }

    private func commit(_ next: Document) {
        doc = next
        revision &+= 1
        if let s = selection, doc.index(of: s) == nil { selection = nil }
        updateUndoFlags()
        saver.scheduleSave(doc)
    }

    private func updateUndoFlags() {
        canUndo = history.canUndo
        canRedo = history.canRedo
    }

    func undo() {
        endTextEditing()
        guard let prev = history.undo(current: doc) else { return }
        lastCoalesceKey = nil
        commit(prev)
    }

    func redo() {
        endTextEditing()
        guard let next = history.redo(current: doc) else { return }
        lastCoalesceKey = nil
        commit(next)
    }

    // MARK: Convenience accessors

    var selectedLayer: Layer? { selection.flatMap { doc.layer($0) } }

    func updateLayer(_ id: String, coalesce: String? = nil, _ body: (inout Layer) -> Void) {
        apply(coalesce) { d in d.updateLayer(id, body) }
    }

    func updateText(_ id: String, coalesce: String? = nil, _ body: (inout TextProps) -> Void) {
        updateLayer(id, coalesce: coalesce) { l in
            guard var t = l.text else { return }
            body(&t)
            l.text = t
        }
    }

    func updateSelectedText(coalesce: String? = nil, _ body: (inout TextProps) -> Void) {
        guard let id = selection else { return }
        updateText(id, coalesce: coalesce, body)
    }

    var selectedText: TextProps? { selectedLayer?.text }

    func rename(_ name: String) {
        let t = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(100))
        guard !t.isEmpty else { return }
        apply { $0.name = t }
    }

    // MARK: Layer commands

    func select(_ id: String?) {
        if editingTextID != nil && editingTextID != id { endTextEditing() }
        selection = id
    }

    func addLayer(_ layer: Layer, select: Bool = true) {
        apply { $0.layers.append(layer) }
        if select { selection = layer.id }
    }

    func deleteLayer(_ id: String) {
        if editingTextID == id { editingTextID = nil }
        apply { _ = $0.removeLayer(id) }
        if selection == id { selection = nil }
    }

    func duplicateLayer(_ id: String) {
        var newID: String?
        apply { newID = $0.duplicateLayer(id) }
        if let newID { selection = newID }
    }

    func moveUp(_ id: String) { apply { $0.moveLayerUp(id) } }
    func moveDown(_ id: String) { apply { $0.moveLayerDown(id) } }
    func moveToTop(_ id: String) { apply { $0.moveLayerToTop(id) } }
    func moveToBottom(_ id: String) { apply { $0.moveLayerToBottom(id) } }
    func moveLayer(_ id: String, toIndex i: Int) { apply { $0.moveLayer(id, toIndex: i) } }
    func toggleVisible(_ id: String) { updateLayer(id) { $0.visible.toggle() } }
    func toggleLocked(_ id: String) { updateLayer(id) { $0.locked.toggle() } }
    func renameLayer(_ id: String, _ name: String) {
        let t = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(100))
        guard !t.isEmpty else { return }
        updateLayer(id) { $0.name = t }
    }

    /// Merges a layer into the one below it as a drawing? Not supported in v1 (optional in spec).

    // MARK: Text

    var canvasCenter: CGPoint { CGPoint(x: Double(doc.canvas.width) / 2, y: Double(doc.canvas.height) / 2) }

    func defaultFontSize() -> Double {
        (Double(min(doc.canvas.width, doc.canvas.height)) / 10).rounded().clamped(16, 400)
    }

    @discardableResult
    func addText(at p: CGPoint? = nil, boxWidth: Double? = nil, text: String = "", props: TextProps? = nil, edit: Bool = true) -> String {
        var t = props ?? TextProps(text: text)
        if props == nil {
            t.fontSize = defaultFontSize()
            t.fill = TextFill(type: .solid, color: doc.canvas.background.isDark ? .white : .black)
        }
        t.text = text
        if let boxWidth { t.autoWidth = false; t.boxWidth = max(1, boxWidth) }
        let pos = p ?? canvasCenter
        let layer = Layer(id: Document.newID(), name: doc.nextLayerName("Text"),
                          transform: Transform(x: pos.x, y: pos.y), content: .text(t))
        addLayer(layer)
        if tool == .text { /* stay */ }
        if edit { editingTextID = layer.id }
        return layer.id
    }

    func beginTextEditing(_ id: String) {
        guard doc.layer(id)?.type == .text else { return }
        selection = id
        typingStyle = nil
        editingTextID = id
    }

    func endTextEditing() {
        guard let id = editingTextID else { return }
        textStyling = false
        editingTextID = nil
        textSelection = nil
        typingStyle = nil
        if let t = doc.layer(id)?.text, t.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            // Empty text layers are removed when editing ends (merged into the creation step).
            apply("text-edit-\(id)") { _ = $0.removeLayer(id) }
            if selection == id { selection = nil }
        }
    }

    /// Text edit from the on-canvas editor: replace UTF-16 range.
    func replaceText(_ id: String, range: NSRange, with s: String) {
        let flags = typingStyle
        updateLayer(id, coalesce: "text-edit-\(id)") { l in
            guard var t = l.text else { return }
            let before = LayerGeometry.boxSize(of: l)
            Spans.replace(in: &t, range: range.location..<(range.location + range.length), with: s,
                          typing: (s as NSString).length > 0 ? flags : nil)
            l.text = t
            // Keep the anchored edge still while typing: top (or bottom) edge,
            // left/right edge for left/right aligned auto-width text.
            let after = LayerGeometry.boxSize(of: l)
            let sc = l.transform.scale, th = l.transform.radians
            var dx = 0.0
            if t.autoWidth {
                switch t.align {
                case .left, .justify: dx = Double(after.width - before.width) / 2
                case .right: dx = -Double(after.width - before.width) / 2
                case .center: dx = 0
                }
            }
            // Text in the lower half of the canvas (e.g. meme bottom text) grows upward.
            let anchorBottom = l.transform.y > Double(doc.canvas.height) / 2
            let dy = Double(after.height - before.height) / 2 * (anchorBottom ? -1 : 1)
            l.transform.x += (dx * cos(th) - dy * sin(th)) * sc
            l.transform.y += (dx * sin(th) + dy * cos(th)) * sc
            EditorModel.fitAutoWidth(&l, canvas: doc.canvas)
        }
    }

    /// §8.1: an auto-width box grows up to the canvas width minus 5% each side, then wraps.
    nonisolated static func fitAutoWidth(_ l: inout Layer, canvas: Canvas) {
        // The wrap at 0.9 × canvas width comes from layout (§7.3 step 5); here we only keep
        // a growing auto-width box inside the 5 % margins (unrotated boxes).
        guard l.text?.autoWidth == true, l.transform.rotation == 0 else { return }
        let W = Double(canvas.width), margin = W * 0.05
        let w = Double(LayerGeometry.boxSize(of: l).width) * max(0.01, l.transform.scale)
        if w <= W - 2 * margin { l.transform.x = min(max(l.transform.x, margin + w / 2), W - margin - w / 2) }
    }

    /// B / I / U / S: applies to the highlighted range while editing, else whole text.
    // MARK: Character styling (§8 selection rule)

    /// The text layer character styling targets (being edited, else selected).
    var styleTargetID: String? {
        let id = editingTextID ?? selection
        return id.flatMap { doc.layer($0)?.type == .text ? $0 : nil }
    }

    /// Partial selection while editing (nil = whole box).
    var styleRange: Range<Int>? {
        guard editingTextID != nil, let sel = textSelection, sel.length > 0 else { return nil }
        return sel.location..<(sel.location + sel.length)
    }

    /// Collapsed caret while editing non-empty text: styling goes to typing attributes.
    var caretMode: Bool {
        guard editingTextID != nil, let sel = textSelection, sel.length == 0,
              let t = styleTargetID.flatMap({ doc.layer($0)?.text }) else { return false }
        return !t.text.isEmpty
    }

    func effectiveStyle(at p: Int, in t: TextProps) -> EffectiveStyle {
        let eff = Spans.effective(t)
        if eff.isEmpty { return t.layerStyle }
        return eff[p > 0 ? min(p - 1, eff.count - 1) : 0]
    }

    private func modifyTyping(_ body: (inout CharStyle) -> Void) {
        guard let id = styleTargetID, let t = doc.layer(id)?.text, let sel = textSelection else { return }
        var st = typingStyle ?? CharStyle(effectiveStyle(at: sel.location, in: t))
        body(&st)
        typingStyle = st
    }

    /// Effective styles the controls reflect: typing style, the selection, or the whole box.
    var styleSummary: [EffectiveStyle] {
        guard let id = styleTargetID, let t = doc.layer(id)?.text else { return [] }
        if caretMode, let sel = textSelection {
            if let ts = typingStyle { return [t.effective(ts)] }
            return [effectiveStyle(at: sel.location, in: t)]
        }
        return Spans.styles(t, in: styleRange)
    }

    /// The shared value of a style field across the target, or nil when mixed.
    func uniform<V: Equatable>(_ f: (EffectiveStyle) -> V) -> V? {
        let all = styleSummary.map(f)
        guard let first = all.first else { return nil }
        return all.allSatisfy { $0 == first } ? first : nil
    }

    func toggleStyle(_ flag: StyleFlags.Flag) {
        guard let id = styleTargetID else { return }
        if caretMode {
            let on = isStyleActive(flag)
            modifyTyping { $0[flag] = !on }
            return
        }
        let r = styleRange
        updateText(id) { Spans.toggle(flag, in: &$0, selection: r) }
    }

    func isStyleActive(_ flag: StyleFlags.Flag) -> Bool {
        let all = styleSummary
        return !all.isEmpty && all.allSatisfy { $0.flags[flag] }
    }

    /// Solid colour: span colour on a selection, layer fill otherwise.
    func setTextColor(_ c: RGBA, coalesce: String? = "textColor") {
        guard let id = styleTargetID else { return }
        if caretMode { modifyTyping { $0.color = c }; return }
        let r = styleRange
        updateText(id, coalesce: coalesce) { Spans.setColor(c, in: &$0, selection: r) }
    }

    /// Gradients always apply to the whole layer (span colours cleared).
    func setTextFill(_ f: TextFill, coalesce: String? = "textFill") {
        guard let id = styleTargetID else { return }
        updateText(id, coalesce: coalesce) { Spans.setLayerFill(f, in: &$0) }
    }

    func setTextSize(_ v: Double, coalesce: String? = "textSize") {
        guard let id = styleTargetID else { return }
        if caretMode { modifyTyping { $0.size = round4(v.clamped(4, 2000)) }; return }
        let r = styleRange
        updateText(id, coalesce: coalesce) { t in
            if r == nil {
                // Whole box: keep a fixed box proportional, then set the layer size.
                let ratio = v / t.fontSize
                if !t.autoWidth { t.boxWidth = max(1, t.boxWidth * ratio) }
            }
            Spans.setSize(v, in: &t, selection: r)
        }
    }

    func setTextFont(_ fam: FontFamily, weight: Int? = nil) {
        guard let id = styleTargetID else { return }
        if caretMode {
            let cur = uniform(\.weight) ?? 400
            modifyTyping { $0.fontId = fam.id; $0.weight = weight ?? fam.nearestUprightWeight(to: cur) }
            return
        }
        let r = styleRange
        updateText(id) { Spans.setFont(fam, weight: weight, in: &$0, selection: r) }
    }

    /// Presets restyle the selected text box (no prompts, no moving). With no text box
    /// selected, one new text box in that style is created, like the Text tool.
    func applyPreset(_ p: TextPreset) {
        if let id = selection, doc.layer(id)?.type == .text {
            updateText(id) { p.apply(to: &$0) }
            return
        }
        var t = TextProps(text: "")
        t.fontSize = defaultFontSize()
        p.apply(to: &t)
        addText(props: t)
    }

    // MARK: Images

    func addImages(_ images: [ImportedImage]) {
        guard !images.isEmpty else { return }
        let W = Double(doc.canvas.width), H = Double(doc.canvas.height)
        var newLayers: [Layer] = []
        for (i, img) in images.enumerated() {
            guard let ref = try? store.storeAsset(img.data, ext: img.ext, projectID: doc.id) else { continue }
            let p = ImageProps(assetRef: ref, naturalWidth: img.naturalWidth, naturalHeight: img.naturalHeight)
            let fit = min(W / Double(img.naturalWidth), H / Double(img.naturalHeight))
            let scale = (images.count > 1 ? fit * 0.6 : fit).clamped(0.01, 100)
            let off = images.count > 1 ? Double(i - (images.count - 1) / 2) * min(W, H) * 0.08 : 0
            let name = images.count > 1 ? doc.nextLayerName("Photo") + (i > 0 ? "" : "") : doc.nextLayerName("Photo")
            newLayers.append(Layer(id: Document.newID(), name: name,
                                   transform: Transform(x: W / 2 + off, y: H / 2 + off, scale: scale, rotation: 0),
                                   content: .image(p)))
        }
        // Unique names
        var named = doc
        for i in newLayers.indices {
            newLayers[i].name = named.nextLayerName("Photo")
            named.layers.append(newLayers[i])
        }
        apply { $0.layers.append(contentsOf: newLayers) }
        selection = newLayers.last?.id
        prefetchImages()
    }

    // MARK: Shapes

    func addShape(_ kind: ShapeKind, rect: CGRect? = nil) {
        let W = Double(doc.canvas.width), H = Double(doc.canvas.height)
        let side = min(W, H) * 0.4
        var p = ShapeProps(shape: kind, width: side, height: kind.isLinear ? 1 : side * (kind == .ellipse ? 1 : 0.7))
        p.fill = shapeDefaults.fill
        p.stroke = shapeDefaults.stroke
        p.cornerRadius = (side * 0.12).rounded()
        if kind.isLinear {
            p.stroke.enabled = true
            p.stroke.width = max(4, (min(W, H) / 100).rounded())
            if p.stroke.color.a == 0 { p.stroke.color = .black }
            p.height = max(p.stroke.width, 1)
        }
        var t = Transform(x: W / 2, y: H / 2)
        if let r = rect {
            if kind.isLinear {
                let len = max(1, hypot(r.width, r.height))
                p.width = len
                t = Transform(x: r.midX, y: r.midY, scale: 1, rotation: Transform.normalizeDegrees(atan2(r.height, r.width) * 180 / .pi))
            } else {
                p.width = max(1, abs(r.width)); p.height = max(1, abs(r.height))
                t = Transform(x: r.midX, y: r.midY)
            }
        }
        let layer = Layer(id: Document.newID(), name: doc.nextLayerName(kind.displayName), transform: t, content: .shape(p))
        addLayer(layer)
    }

    // MARK: Drawing

    /// The drawing layer strokes go into (selected drawing layer, else a new one).
    func drawingTarget(create: Bool) -> String? {
        if let s = selectedLayer, s.type == .drawing, !s.locked { return s.id }
        guard create else { return nil }
        let W = doc.canvas.width, H = doc.canvas.height
        let layer = Layer(id: Document.newID(), name: doc.nextLayerName("Drawing"),
                          transform: Transform(x: Double(W) / 2, y: Double(H) / 2),
                          content: .drawing(DrawingProps(width: W, height: H)))
        apply { $0.layers.append(layer) }
        selection = layer.id
        return layer.id
    }

    /// Eraser strokes on image/text/shape layers go to the layer's mask (§9.1).
    func addMaskStroke(_ stroke: Stroke, to layerID: String) {
        updateLayer(layerID) { $0.mask.append(stroke) }
    }

    /// The layer the eraser works on: the selected, visible, unlocked layer (any type).
    var eraserTarget: Layer? {
        guard let l = selectedLayer, l.visible, !l.locked else { return nil }
        return l
    }

    /// Merge down (v1): only two drawing layers with equal transforms, upper at opacity 1.
    func canMergeDown(_ id: String) -> Bool {
        guard let i = doc.index(of: id), i > 0 else { return false }
        let up = doc.layers[i], low = doc.layers[i - 1]
        return up.type == .drawing && low.type == .drawing && up.transform == low.transform && up.opacity == 1
            && up.drawing?.width == low.drawing?.width && up.drawing?.height == low.drawing?.height
    }

    func mergeDown(_ id: String) {
        guard canMergeDown(id), let i = doc.index(of: id) else { return }
        let lowID = doc.layers[i - 1].id
        apply { d in
            guard let up = d.layers[i].drawing, var low = d.layers[i - 1].drawing else { return }
            low.strokes.append(contentsOf: up.strokes)
            d.layers[i - 1].content = .drawing(low)
            d.layers.remove(at: i)
        }
        selection = lowID
    }

    func addStroke(_ stroke: Stroke, to layerID: String) {
        updateLayer(layerID) { l in
            guard var d = l.drawing else { return }
            d.strokes.append(stroke)
            l.drawing = d
        }
    }

    // MARK: Canvas

    func resizeCanvas(width: Int, height: Int, anchor: UnitPoint) {
        let w = width.clamped(Canvas.minSide, Canvas.maxSide), h = height.clamped(Canvas.minSide, Canvas.maxSide)
        let dx = Double(w - doc.canvas.width) * anchor.x
        let dy = Double(h - doc.canvas.height) * anchor.y
        apply { d in
            d.canvas.width = w; d.canvas.height = h
            for i in d.layers.indices {
                d.layers[i].transform.x += dx
                d.layers[i].transform.y += dy
            }
        }
    }

    func setBackground(_ c: RGBA, coalesce: String? = nil) { apply(coalesce) { $0.canvas.background = c } }

    // MARK: Tools

    private func toolChanged(from old: EditorTool) {
        if old == .text || editingTextID != nil { endTextEditing() }
        panelCollapsed = false
        switch tool {
        case .draw:
            break // keep the selection: the eraser works on the selected layer of any type
        case .adjust:
            if selectedLayer?.type != .image {
                selection = doc.layers.last(where: { $0.type == .image && !$0.locked })?.id
            }
        default: break
        }
    }

    // MARK: Hit testing

    /// Topmost visible, unlocked layer under `p` (canvas coords). `after` cycles beneath.
    func hitLayer(at p: CGPoint, slop: CGFloat, cycleFrom current: String? = nil) -> String? {
        let hits = doc.layers.reversed().filter { $0.visible && !$0.locked && LayerGeometry.contains($0, point: p, slop: slop) }
        guard !hits.isEmpty else { return nil }
        if let current, let i = hits.firstIndex(where: { $0.id == current }) {
            return hits[(i + 1) % hits.count].id
        }
        return hits.first?.id
    }

    // MARK: Saving

    func flush(completion: (() -> Void)? = nil) { saver.flush(doc, completion: completion) }

    // MARK: Editor state (§10.7)

    /// Current zoom (× fit) and canvas centre, provided by the canvas view.
    var viewStateProvider: (() -> (zoom: Double, cx: Double, cy: Double))?
    /// State restored on open; consumed by the canvas (view) and the editor (rest).
    var pendingState: EditorState?

    func currentEditorState() -> EditorState {
        var s = EditorState()
        if let sel = selection { s.selectedLayerIds = [sel] }
        s.activeTool = tool
        s.textTab = TextTab(rawValue: UserDefaults.standard.string(forKey: "textTab") ?? "") ?? .font
        if let v = viewStateProvider?() { s.zoom = v.zoom; s.centerX = v.cx; s.centerY = v.cy }
        if let id = editingTextID, let sel = textSelection {
            s.textSelection = (id, sel.location, sel.location + sel.length)
        }
        return s
    }

    /// Applies selection, tool, tab and text selection (the view is applied by the canvas).
    func restore(_ s: EditorState) {
        if let t = s.textTab { UserDefaults.standard.set(t.rawValue, forKey: "textTab") }
        if let t = s.activeTool, t != .image { tool = t }
        if let id = s.selectedLayerIds.first(where: { doc.layer($0) != nil }) { selection = id }
        if let (id, range) = s.validTextSelection(in: doc) {
            // Restore the selection without raising the keyboard (style panel mode).
            selection = id
            textStyling = true
            textSelection = range
            editingTextID = id
        }
    }

    func close(completion: @escaping () -> Void) {
        let state = currentEditorState()   // before editing ends, so the text selection is kept
        endTextEditing()
        let all = [doc] + history.allStates
        let id = doc.id
        let store = self.store
        saver.flush(doc, state: state) {
            DispatchQueue.global(qos: .utility).async {
                store.collectGarbage(projectID: id, keeping: all)
                DispatchQueue.main.async { completion() }
            }
        }
    }

    func showToast(_ s: String) {
        toast = s
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.4) { [weak self] in
            if self?.toast == s { self?.toast = nil }
        }
    }
}

/// Debounced, coalesced, off-main-thread autosave (+ thumbnail).
final class ProjectSaver {
    private let store: ProjectStore
    private let queue = DispatchQueue(label: "project.save", qos: .userInitiated)
    private var pending: Document?
    private var work: DispatchWorkItem?
    private var written: [String: [Stroke]] = [:]
    private var lastSaved: Document

    init(store: ProjectStore, initial: Document) {
        self.store = store
        self.lastSaved = initial
        for l in initial.layers {
            if case .drawing(let d) = l.content, d.fileState == .ok { written[l.id] = d.strokes }
        }
    }

    func scheduleSave(_ doc: Document) {
        work?.cancel()
        let item = DispatchWorkItem { [weak self] in self?.save(doc) }
        work = item
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0, execute: item)
    }

    func flush(_ doc: Document, state: EditorState? = nil, completion: (() -> Void)? = nil) {
        work?.cancel()
        work = nil
        save(doc, completion: completion, state: state)
    }

    /// Editor state (§10.7), captured on the main thread at save time.
    var stateProvider: (() -> EditorState)?

    private func save(_ doc: Document, completion: (() -> Void)? = nil, state override: EditorState? = nil) {
        let changed = !doc.contentEquals(lastSaved)
        lastSaved = doc
        let state = override ?? stateProvider?()
        queue.async { [self] in
            if changed {
                var d = doc
                d.modified = Date()
                d.generator = Document.generatorString
                do {
                    try store.save(d, writtenStrokes: &written)
                    if let png = Thumbnailer.png(for: d, store: store) { store.writeThumbnail(png, id: d.id) }
                } catch {
                    NSLog("Basic Art: save failed: \(error)")
                }
            }
            if let state { store.saveEditorState(state, id: doc.id) }
            if let completion { DispatchQueue.main.async(execute: completion) }
        }
    }
}

extension Comparable {
    func clamped(_ lo: Self, _ hi: Self) -> Self { min(hi, max(lo, self)) }
}

extension RGBA {
    var isDark: Bool { a > 128 && (0.299 * red + 0.587 * green + 0.114 * blue) < 0.5 }
}
