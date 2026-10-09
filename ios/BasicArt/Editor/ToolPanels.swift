import SwiftUI

// MARK: - Draw

extension BrushType {
    var shortName: String {
        switch self {
        case .pen: return "Pen"
        case .marker: return "Marker"
        case .highlighter: return "Highlight"
        case .airbrush: return "Airbrush"
        case .calligraphy: return "Callig."
        case .pencil: return "Pencil"
        case .eraser: return "Eraser"
        }
    }
    var symbol: String {
        switch self {
        case .pen: return "pencil.tip"
        case .marker: return "paintbrush.pointed.fill"
        case .highlighter: return "highlighter"
        case .airbrush: return "aqi.medium"
        case .calligraphy: return "pencil.and.outline"
        case .pencil: return "pencil"
        case .eraser: return "eraser"
        }
    }
}

struct DrawPanel: View {
    @ObservedObject var model: EditorModel

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 4) {
                ForEach(BrushType.allCases, id: \.self) { b in
                    let on = model.brush.brush == b
                    Button { model.brush.brush = b } label: {
                        VStack(spacing: 2) {
                            Image(systemName: b.symbol)
                                .font(.system(size: 17, weight: on ? .semibold : .regular))
                                .frame(height: 20)
                            Text(b.shortName)
                                .font(.system(size: 10, weight: .medium))
                                .lineLimit(1)
                                .minimumScaleFactor(0.85)
                        }
                            .frame(maxWidth: .infinity, minHeight: 50)
                            .foregroundStyle(on ? Color.white : Color.primary)
                            .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(on ? Color.accentColor : Color(uiColor: .tertiarySystemFill)))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(b.displayName)
                    .accessibilityAddTraits(on ? .isSelected : [])
                    .accessibilityIdentifier("brush-\(b.rawValue)")
                }
            }
            HStack(spacing: 12) {
                VStack(spacing: 0) {
                    LabeledSlider(label: "Size", value: Binding(get: { model.brush.size }, set: { model.brush.size = $0.rounded() }),
                                  range: 1...200, format: { "\(Int($0)) px" })
                    LabeledSlider(label: model.brush.brush == .eraser ? "Strength" : "Opacity",
                                  value: Binding(get: { model.brush.opacity * 100 }, set: { model.brush.opacity = $0 / 100 }),
                                  range: 1...100, format: { "\(Int($0))%" })
                }
                BrushPreview(brush: model.brush)
                    .frame(width: 56, height: 56)
            }
            if model.brush.brush == .eraser {
                if let t = model.eraserTarget {
                    PanelHint(symbol: "eraser", text: "Erasing from “\(t.name)”.")
                } else {
                    PanelHint(symbol: "hand.tap", text: "Select a layer to erase. Tap Layers to pick one.")
                }
            } else {
                PaletteStrip(color: Binding(get: { model.brush.color }, set: { model.brush.color = $0.withAlpha(1) }), allowsAlpha: false,
                             onEyedropper: { model.startEyedropper { c in model.brush.color = c.withAlpha(1) } })
            }
            HStack(spacing: 16) {
                Toggle("Smoothing", isOn: $model.brush.smoothing).font(.subheadline)
                if UIDevice.current.userInterfaceIdiom == .pad {
                    Toggle("Pencil pressure", isOn: $model.brush.usePressure).font(.subheadline)
                }
                Spacer(minLength: 0)
                Button {
                    model.select(nil)
                    _ = model.drawingTarget(create: true)
                } label: {
                    Label("New layer", systemImage: "plus.square").font(.subheadline.weight(.medium))
                        .frame(minHeight: 44).contentShape(Rectangle())
                }
            }
            .toggleStyle(.switch)
        }
        .padding(.horizontal, 16)
    }
}

struct BrushPreview: View {
    var brush: BrushState
    var body: some View {
        ZStack {
            Checkerboard(cell: 5)
            let d = min(52, max(3, brush.size * (brush.brush == .airbrush ? 0.5 : 1) / 3 + 3))
            Circle()
                .fill(brush.brush == .eraser ? Color.white : brush.color.swiftUI)
                .overlay(Circle().strokeBorder(Color.primary.opacity(brush.brush == .eraser ? 0.6 : 0), style: StrokeStyle(lineWidth: 1, dash: [3, 2])))
                .frame(width: d, height: d)
                .blur(radius: brush.brush == .airbrush ? d * 0.15 : 0)
                .opacity(brush.opacity * (brush.brush == .highlighter ? 0.4 : (brush.brush == .marker || brush.brush == .pencil ? 0.85 : 1)))
        }
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Color.primary.opacity(0.12)))
        .accessibilityLabel("Brush preview")
    }
}

// MARK: - Shapes

extension ShapeKind {
    var article: String { self == .ellipse || self == .arrow ? "an" : "a" }
    var symbol: String {
        switch self {
        case .rect: return "rectangle"
        case .roundRect: return "app"
        case .ellipse: return "circle"
        case .line: return "line.diagonal"
        case .arrow: return "arrow.up.right"
        }
    }
}

struct ShapePanel: View {
    @ObservedObject var model: EditorModel
    var selectedShape: ShapeProps? { model.selectedLayer?.shape }
    var kind: ShapeKind { selectedShape?.shape ?? model.shapeDefaults.kind }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 4) {
                ForEach(ShapeKind.allCases, id: \.self) { k in
                    let on = kind == k
                    Button {
                        model.shapeDefaults.kind = k
                        if let id = model.selection, var s = selectedShape {
                            if s.shape.isLinear != k.isLinear {
                                if k.isLinear { s.stroke.enabled = true; s.height = max(1, s.stroke.width) } else { s.height = max(s.height, s.width * 0.6) }
                            }
                            s.shape = k
                            model.updateLayer(id) { $0.shape = s; if $0.name.hasPrefix(selectedShape!.shape.displayName) { $0.name = model.doc.nextLayerName(k.displayName) } }
                        }
                    } label: {
                        Image(systemName: k.symbol)
                            .font(.system(size: 18, weight: .medium))
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .foregroundStyle(on ? Color.white : Color.primary)
                            .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(on ? Color.accentColor : Color(uiColor: .tertiarySystemFill)))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(k.displayName)
                    .accessibilityAddTraits(on ? .isSelected : [])
                }
            }
            if selectedShape == nil {
                HStack {
                    Text("Drag on the canvas to draw \(kind.article) \(kind.displayName.lowercased()).").font(.footnote).foregroundStyle(.secondary)
                    Spacer()
                    Button { model.addShape(kind) } label: { Label("Add", systemImage: "plus").font(.subheadline.weight(.semibold)) }
                        .buttonStyle(.borderedProminent).buttonBorderShape(.capsule)
                        .accessibilityIdentifier("addShape")
                }
                .frame(minHeight: 44)
            }
            if !kind.isLinear {
                HStack {
                    Toggle(isOn: model.shapeBinding(\.fill.enabled, "fillOn", defaultsKP: \.fill.enabled)) { Text("Fill").font(.subheadline.weight(.semibold)) }
                        .fixedSize()
                    Spacer(minLength: 12)
                    if (selectedShape?.fill.enabled ?? model.shapeDefaults.fill.enabled) {
                        ColorButton(title: "", color: selectedShape?.fill.color ?? model.shapeDefaults.fill.color,
                                    onChange: { c in model.shapeBinding(\.fill.color, "fillColor", defaultsKP: \.fill.color).wrappedValue = c },
                                    onEyedropper: { model.startEyedropper { c in model.shapeBinding(\.fill.color, "fillColor", defaultsKP: \.fill.color).wrappedValue = c } })
                            .frame(maxWidth: 210)
                    }
                }
            }
            HStack {
                if kind.isLinear {
                    Text("Line").font(.subheadline.weight(.semibold))
                } else {
                    Toggle(isOn: model.shapeBinding(\.stroke.enabled, "strokeOn", defaultsKP: \.stroke.enabled)) { Text("Stroke").font(.subheadline.weight(.semibold)) }
                        .fixedSize()
                }
                Spacer(minLength: 12)
                if kind.isLinear || (selectedShape?.stroke.enabled ?? model.shapeDefaults.stroke.enabled) {
                    ColorButton(title: "", color: selectedShape?.stroke.color ?? model.shapeDefaults.stroke.color,
                                onChange: { c in model.shapeBinding(\.stroke.color, "strokeColor", defaultsKP: \.stroke.color).wrappedValue = c },
                                onEyedropper: { model.startEyedropper { c in model.shapeBinding(\.stroke.color, "strokeColor", defaultsKP: \.stroke.color).wrappedValue = c } })
                        .frame(maxWidth: 210)
                }
            }
            if kind.isLinear || (selectedShape?.stroke.enabled ?? model.shapeDefaults.stroke.enabled) {
                LabeledSlider(label: "Width", value: Binding(
                    get: { selectedShape?.stroke.width ?? model.shapeDefaults.stroke.width },
                    set: { v in
                        model.shapeBinding(\.stroke.width, "strokeW", defaultsKP: \.stroke.width).wrappedValue = v.rounded()
                        if kind.isLinear, let id = model.selection, model.selectedLayer?.type == .shape {
                            model.updateLayer(id, coalesce: "strokeW") { l in if var s = l.shape { s.height = max(1, s.stroke.width); l.shape = s } }
                        }
                    }), range: kind.isLinear ? 1...120 : 0...120, format: { "\(Int($0)) px" })
                if !kind.isLinear {
                    HStack {
                        Text("Corners").font(.subheadline).foregroundStyle(.secondary)
                        IconSegmented(options: [(LineJoin.miter, "Sharp", "Sharp corners"), (.round, "Round", "Round corners")],
                                      selection: model.shapeBinding(\.stroke.join, "join", defaultsKP: \.stroke.join), useText: true)
                    }
                }
            }
            if kind == .roundRect, selectedShape != nil {
                LabeledSlider(label: "Radius", value: model.shapeBinding(\.cornerRadius, "radius", defaultsKP: nil),
                              range: 0...max(10, min(selectedShape!.width, selectedShape!.height) / 2), format: { "\(Int($0)) px" })
            }
            if kind == .arrow, selectedShape != nil {
                IconSegmented(options: [(ArrowHeads.end, "arrow.right", "Arrow at end"), (.start, "arrow.left", "Arrow at start"), (.both, "arrow.left.and.right", "Arrows at both ends")],
                              selection: model.shapeBinding(\.arrowHeads, "heads", defaultsKP: nil))
            }
            if let l = model.selectedLayer, l.type == .shape {
                LabeledSlider(label: "Opacity", value: Binding(
                    get: { (model.selectedLayer?.opacity ?? 1) * 100 },
                    set: { v in model.updateLayer(l.id, coalesce: "opacity") { $0.opacity = v / 100 } }),
                              range: 0...100, format: { "\(Int($0))%" })
            }
        }
        .padding(.horizontal, 16)
    }
}

// MARK: - Adjust (image)

struct AdjustPanel: View {
    @ObservedObject var model: EditorModel
    @State private var showCrop = false
    var img: ImageProps? { model.selectedLayer?.image }

    var body: some View {
        if let img, let id = model.selection {
            VStack(alignment: .leading, spacing: 2) {
                // Equal-width actions: nothing is clipped or hidden behind a scroll.
                HStack(spacing: 6) {
                    StackedButton(title: "Crop", symbol: "crop") { showCrop = true }
                        .accessibilityIdentifier("cropButton")
                    StackedButton(title: "Rotate", symbol: "rotate.right") {
                        model.updateLayer(id) { l in
                            guard var p = l.image else { return }
                            p.rotate90 = (p.rotate90 + 1) % 4
                            swap(&p.flipH, &p.flipV)
                            l.image = p
                        }
                    }
                    StackedButton(title: "Flip H", symbol: "arrow.left.and.right.righttriangle.left.righttriangle.right") {
                        model.updateLayer(id) { l in l.image?.flipH.toggle() }
                    }
                    .accessibilityLabel("Flip horizontal")
                    StackedButton(title: "Flip V", symbol: "arrow.up.and.down.righttriangle.up.righttriangle.down") {
                        model.updateLayer(id) { l in l.image?.flipV.toggle() }
                    }
                    .accessibilityLabel("Flip vertical")
                    StackedButton(title: "Reset", symbol: "arrow.counterclockwise") {
                        model.updateLayer(id) { l in l.image?.adjust = ImageAdjust() }
                    }
                    .disabled(img.adjust.isIdentity)
                    .accessibilityLabel("Reset adjustments")
                }
                LabeledSlider(label: "Opacity", value: Binding(get: { (model.selectedLayer?.opacity ?? 1) * 100 },
                                                               set: { v in model.updateLayer(id, coalesce: "opacity") { $0.opacity = v / 100 } }),
                              range: 0...100, format: { "\(Int($0))%" })
                adjustSlider("Brightness", \.brightness)
                adjustSlider("Contrast", \.contrast)
                adjustSlider("Saturation", \.saturation)
                adjustSlider("Warmth", \.warmth)
                LabeledSlider(label: "Corners", value: model.imageBinding(\.cornerRadius, "corner", default: 0),
                              range: 0...max(1, min(img.boxSize.width, img.boxSize.height) / 2), format: { "\(Int($0)) px" })
                HStack {
                    Toggle(isOn: model.imageBinding(\.border.enabled, "borderOn", default: false)) {
                        Label("Border", systemImage: "square.dashed").font(.subheadline.weight(.semibold))
                    }
                    Spacer(minLength: 12)
                    if img.border.enabled {
                        ColorButton(title: "", color: img.border.color, onChange: { c in model.imageBinding(\.border.color, "borderColor", default: .white).wrappedValue = c },
                                    onEyedropper: { model.startEyedropper { c in model.imageBinding(\.border.color, "borderColor", default: .white).wrappedValue = c } })
                            .frame(maxWidth: 210)
                    }
                }
                if img.border.enabled {
                    LabeledSlider(label: "Border", value: model.imageBinding(\.border.width, "borderW", default: 0),
                                  range: 0...max(2, min(img.boxSize.width, img.boxSize.height) / 4), format: { "\(Int($0)) px" })
                }
            }
            .padding(.horizontal, 16)
            .sheet(isPresented: $showCrop) {
                CropSheet(model: model, layerID: id)
            }
        }
    }

    private func adjustSlider(_ label: String, _ kp: WritableKeyPath<ImageAdjust, Double>) -> some View {
        LabeledSlider(label: label, value: model.imageBinding((\ImageProps.adjust).appending(path: kp), "adj-\(label)", default: 0),
                      range: -100...100, step: 1, format: { $0 > 0 ? "+\(Int($0))" : "\(Int($0))" })
    }
}

// MARK: - Canvas

struct CanvasPanel: View {
    @ObservedObject var model: EditorModel
    @State private var width = 0
    @State private var height = 0
    @State private var anchor = UnitPoint.center
    @State private var lock = false

    var bg: RGBA { model.doc.canvas.background }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .bottom, spacing: 10) {
                PixelField(label: "Width", value: $width) { new, old in
                    if lock, old > 0 { height = Int((Double(new) * Double(height) / Double(old)).rounded()).clamped(Canvas.minSide, Canvas.maxSide) }
                }
                Button { lock.toggle() } label: {
                    Image(systemName: lock ? "lock.fill" : "lock.open").frame(width: 36, height: 44)
                }
                .accessibilityLabel(lock ? "Aspect ratio locked" : "Aspect ratio unlocked")
                PixelField(label: "Height", value: $height) { new, old in
                    if lock, old > 0 { width = Int((Double(new) * Double(width) / Double(old)).rounded()).clamped(Canvas.minSide, Canvas.maxSide) }
                }
                AnchorGrid(anchor: $anchor)
            }
            HStack {
                Text("Resizing keeps layers where they are, relative to the anchor.").font(.caption).foregroundStyle(.secondary)
                Spacer()
                Button("Resize") { model.resizeCanvas(width: width, height: height, anchor: anchor) }
                    .buttonStyle(.borderedProminent).buttonBorderShape(.capsule)
                    .disabled(width == model.doc.canvas.width && height == model.doc.canvas.height)
                    .accessibilityIdentifier("resizeCanvas")
            }
            IconSegmented(options: [(BackgroundChoice.white, "White", "White background"), (.transparent, "Transparent", "Transparent background"),
                                    (.color, "Color", "Colored background")],
                          selection: Binding(get: {
                              bg == .white ? .white : (bg.a == 0 ? .transparent : .color)
                          }, set: { v in
                              switch v {
                              case .white: model.setBackground(.white)
                              case .transparent: model.setBackground(.clear)
                              case .color: model.setBackground(bg == .white || bg.a == 0 ? RGBA(hex: "#FFD23FFF")! : bg)
                              }
                          }), useText: true)
            if bg != .white && bg.a > 0 {
                PaletteStrip(color: Binding(get: { bg }, set: { model.setBackground($0, coalesce: "bg") }),
                             onEyedropper: { model.startEyedropper { c in model.setBackground(c) } })
            }
        }
        .padding(.horizontal, 16)
        .onAppear { width = model.doc.canvas.width; height = model.doc.canvas.height }
        .onChange(of: model.doc.canvas) { c in width = c.width; height = c.height }
    }
}

struct AnchorGrid: View {
    @Binding var anchor: UnitPoint
    var body: some View {
        VStack(spacing: 2) {
            ForEach(0..<3) { r in
                HStack(spacing: 2) {
                    ForEach(0..<3) { c in
                        let p = UnitPoint(x: Double(c) / 2, y: Double(r) / 2)
                        Button { anchor = p } label: {
                            RoundedRectangle(cornerRadius: 3)
                                .fill(anchor == p ? Color.accentColor : Color(uiColor: .tertiarySystemFill))
                                .frame(width: 22, height: 22)
                                .frame(width: 44, height: 44)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Anchor \(["top", "middle", "bottom"][r]) \(["left", "center", "right"][c])")
                        .accessibilityAddTraits(anchor == p ? .isSelected : [])
                    }
                }
            }
        }
        .padding(4)
        .background(RoundedRectangle(cornerRadius: 8).strokeBorder(Color.primary.opacity(0.12)))
    }
}
