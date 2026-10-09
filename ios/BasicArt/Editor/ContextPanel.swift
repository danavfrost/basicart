import SwiftUI

extension EditorModel {
    /// Binding into the selected text layer; edits with the same key coalesce into one undo step.
    func textBinding<V: Equatable>(_ kp: WritableKeyPath<TextProps, V>, _ key: String) -> Binding<V> {
        Binding(
            get: { self.selectedText?[keyPath: kp] ?? TextProps(text: "")[keyPath: kp] },
            set: { v in self.updateSelectedText(coalesce: key) { $0[keyPath: kp] = v } })
    }

    func layerBinding<V: Equatable>(_ kp: WritableKeyPath<Layer, V>, _ key: String, default def: V) -> Binding<V> {
        Binding(
            get: { self.selectedLayer?[keyPath: kp] ?? def },
            set: { v in if let id = self.selection { self.updateLayer(id, coalesce: key) { $0[keyPath: kp] = v } } })
    }

    func imageBinding<V: Equatable>(_ kp: WritableKeyPath<ImageProps, V>, _ key: String, default def: V) -> Binding<V> {
        Binding(
            get: { self.selectedLayer?.image?[keyPath: kp] ?? def },
            set: { v in
                guard let id = self.selection else { return }
                self.updateLayer(id, coalesce: key) { l in
                    guard var p = l.image else { return }
                    p[keyPath: kp] = v
                    l.image = p
                }
            })
    }

    func shapeBinding<V: Equatable>(_ kp: WritableKeyPath<ShapeProps, V>, _ key: String, defaultsKP: WritableKeyPath<ShapeDefaults, V>?) -> Binding<V> {
        Binding(
            get: {
                if let s = self.selectedLayer?.shape { return s[keyPath: kp] }
                if let d = defaultsKP { return self.shapeDefaults[keyPath: d] }
                return ShapeProps(shape: .rect, width: 1, height: 1)[keyPath: kp]
            },
            set: { v in
                if let id = self.selection, self.selectedLayer?.type == .shape {
                    self.updateLayer(id, coalesce: key) { l in
                        guard var p = l.shape else { return }
                        p[keyPath: kp] = v
                        l.shape = p
                    }
                }
                if let d = defaultsKP { self.shapeDefaults[keyPath: d] = v }
            })
    }

    /// Starts the canvas eyedropper; `apply` receives the picked colour.
    func startEyedropper(_ apply: @escaping (RGBA) -> Void) {
        endTextEditing()
        eyedropper = { c in apply(c) }
    }
}

/// Bottom context panel (phones, portrait). Collapsible.
struct ContextPanelHost: View {
    @ObservedObject var model: EditorModel
    var onEyedropper: () -> Void
    var onAddImage: () -> Void
    var maxHeight: CGFloat = 330

    var body: some View {
        if ContextPanelContent.hasContent(model) && (model.editingTextID == nil || model.textStyling) {
            VStack(spacing: 0) {
                Button {
                    model.panelCollapsed.toggle()
                } label: {
                    VStack(spacing: 0) {
                        Capsule().fill(Color.secondary.opacity(0.45)).frame(width: 36, height: 5)
                            .padding(.top, 7)
                        if model.panelCollapsed {
                            HStack(spacing: 6) {
                                Image(systemName: "chevron.up").font(.caption.weight(.bold))
                                Text(ContextPanelContent.title(model)).font(.footnote.weight(.semibold))
                            }
                            .foregroundStyle(.secondary)
                            .padding(.vertical, 6)
                        }
                    }
                    .frame(maxWidth: .infinity, minHeight: model.panelCollapsed ? 44 : 20)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(model.panelCollapsed ? "Show options" : "Hide options")
                if !model.panelCollapsed {
                    AdaptiveScroll(maxHeight: maxHeight) {
                        ContextPanelContent(model: model, onEyedropper: onEyedropper, onAddImage: onAddImage)
                            .padding(.bottom, 6)
                    }
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .background(
                UnevenTop(radius: 18)
                    .fill(Color(uiColor: .secondarySystemGroupedBackground))
                    .shadow(color: .black.opacity(0.10), radius: 10, y: -2)
                    .ignoresSafeArea(edges: .bottom)
            )
            .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }
}

struct UnevenTop: Shape {
    var radius: CGFloat
    func path(in r: CGRect) -> Path {
        Path(UIBezierPath(roundedRect: r, byRoundingCorners: [.topLeft, .topRight],
                          cornerRadii: CGSize(width: radius, height: radius)).cgPath)
    }
}

struct ContextPanelContent: View {
    @ObservedObject var model: EditorModel
    var onEyedropper: () -> Void
    var onAddImage: () -> Void

    enum Kind { case none, text, textHint, draw, shapes, adjust, adjustHint, canvas, layer }

    static func kind(_ m: EditorModel) -> Kind {
        switch m.tool {
        case .draw: return .draw
        case .shapes: return .shapes
        case .canvas: return .canvas
        case .adjust: return m.selectedLayer?.type == .image ? .adjust : .adjustHint
        case .text: return m.selectedLayer?.type == .text ? .text : .textHint
        case .select, .image:
            guard let l = m.selectedLayer else { return .none }
            switch l.type {
            case .text: return .text
            case .shape: return .shapes
            case .image, .drawing: return .layer
            }
        }
    }

    static func hasContent(_ m: EditorModel) -> Bool { kind(m) != .none }

    static func title(_ m: EditorModel) -> String {
        switch kind(m) {
        case .text, .textHint: return "Text options"
        case .draw: return "Brush"
        case .shapes: return "Shape"
        case .adjust, .adjustHint: return "Adjust"
        case .canvas: return "Canvas"
        case .layer: return m.selectedLayer?.name ?? "Layer"
        case .none: return ""
        }
    }

    var body: some View {
        Group {
            switch Self.kind(model) {
            case .none: EmptyView()
            case .text: TextPanel(model: model)
            case .textHint: TextHintPanel(model: model)
            case .draw: DrawPanel(model: model)
            case .shapes: ShapePanel(model: model)
            case .adjust: AdjustPanel(model: model)
            case .adjustHint:
                PanelHint(symbol: "photo", text: "Select a photo layer to crop, rotate, flip or adjust its colors.")
                    .padding(.horizontal, 16)
            case .canvas: CanvasPanel(model: model)
            case .layer: LayerQuickPanel(model: model)
            }
        }
        .frame(maxWidth: 720)
        .frame(maxWidth: .infinity)
    }
}

/// Opacity + common actions for image / drawing layers in the Select tool.
struct LayerQuickPanel: View {
    @ObservedObject var model: EditorModel
    var body: some View {
        if let l = model.selectedLayer {
            VStack(alignment: .leading, spacing: 6) {
                LabeledSlider(label: "Opacity", value: Binding(
                    get: { (model.selectedLayer?.opacity ?? 1) * 100 },
                    set: { v in model.updateLayer(l.id, coalesce: "opacity") { $0.opacity = v / 100 } }),
                              range: 0...100, format: { "\(Int($0))%" })
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        if l.type == .image {
                            PillButton(title: "Adjust", symbol: "slider.horizontal.3") { model.tool = .adjust }
                        }
                        if l.type == .drawing {
                            PillButton(title: "Draw", symbol: "paintbrush.pointed") { model.tool = .draw }
                        }
                        PillButton(title: "Duplicate", symbol: "plus.square.on.square") { model.duplicateLayer(l.id) }
                        PillButton(title: "To top", symbol: "square.3.layers.3d.top.filled") { model.moveToTop(l.id) }
                        PillButton(title: "To bottom", symbol: "square.3.layers.3d.bottom.filled") { model.moveToBottom(l.id) }
                        PillButton(title: "Delete", symbol: "trash", role: .destructive) { model.deleteLayer(l.id) }
                    }
                }
            }
            .padding(.horizontal, 16)
        }
    }
}
