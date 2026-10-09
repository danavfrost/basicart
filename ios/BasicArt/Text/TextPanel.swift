import SwiftUI

private struct FontBrowserHeightKey: EnvironmentKey { static let defaultValue: CGFloat = 236 }
extension EnvironmentValues {
    /// Height of the font browser list (taller in the docked side column).
    var fontBrowserHeight: CGFloat {
        get { self[FontBrowserHeightKey.self] }
        set { self[FontBrowserHeightKey.self] = newValue }
    }
}

enum TextTab: String, CaseIterable, Identifiable {
    case font, style, color, outline, shadow, effects
    var id: String { rawValue }
    var label: String { rawValue.capitalized }
    var symbol: String {
        switch self {
        case .font: return "textformat"
        case .style: return "bold.italic.underline"
        case .color: return "paintpalette"
        case .outline: return "a.square"
        case .shadow: return "shadow"
        case .effects: return "sparkles"
        }
    }
}

struct TextPanel: View {
    @ObservedObject var model: EditorModel
    @AppStorage("textTab") private var tab: TextTab = .font
    @Environment(\.horizontalSizeClass) private var hSize
    @Environment(\.fontBrowserHeight) private var browserHeight

    var body: some View {
        VStack(spacing: 8) {
            HStack(spacing: 2) {
                ForEach(TextTab.allCases) { t in
                    let on = t == tab
                    Button {
                        withAnimation(.snappy(duration: 0.2)) { tab = t }
                    } label: {
                        VStack(spacing: 2) {
                            Image(systemName: t.symbol)
                                .font(.system(size: hSize == .regular ? 17 : 15, weight: on ? .semibold : .regular))
                                .frame(height: 18)
                            Text(t.label)
                                .font(.system(size: hSize == .regular ? 13 : 12, weight: on ? .bold : .medium))
                                .lineLimit(1)
                                .fixedSize()
                        }
                        .frame(maxWidth: .infinity, minHeight: 46)
                        .foregroundStyle(on ? Color.white : Color.primary.opacity(0.8))
                        .background(RoundedRectangle(cornerRadius: 11, style: .continuous).fill(on ? Color.accentColor : Color.clear))
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(t.label)
                    .accessibilityAddTraits(on ? .isSelected : [])
                    .accessibilityIdentifier("textTab-\(t.rawValue)")
                }
            }
            .padding(3)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color(uiColor: .tertiarySystemFill)))
            .padding(.horizontal, 12)
            Group {
                switch tab {
                case .font: FontBrowser(model: model).frame(height: browserHeight)
                case .style: StyleTab(model: model)
                case .color: ColorTab(model: model)
                case .outline: OutlineTab(model: model)
                case .shadow: ShadowTab(model: model)
                case .effects: EffectsTab(model: model)
                }
            }
            .padding(.horizontal, 16)
        }
    }
}

struct TextHintPanel: View {
    @ObservedObject var model: EditorModel
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            PanelHint(symbol: "hand.tap", text: "Tap the canvas to add text, or drag to make a text box. Or start from a look:")
            PresetRow(model: model)
        }
        .padding(.horizontal, 16)
    }
}

// MARK: - Style

struct StyleTab: View {
    @ObservedObject var model: EditorModel

    var body: some View {
        VStack(spacing: 6) {
            HStack(spacing: 8) {
                HStack(spacing: 6) {
                    styleButton(.bold, "B", "Bold")
                    styleButton(.italic, "I", "Italic")
                    styleButton(.underline, "U", "Underline")
                    styleButton(.strike, "S", "Strikethrough")
                }
                IconSegmented(options: [(TextAlign.left, "text.alignleft", "Align left"), (.center, "text.aligncenter", "Align center"),
                                        (.right, "text.alignright", "Align right"), (.justify, "text.justify", "Justify")],
                              selection: model.textBinding(\.align, "align"))
            }
            SliderField(label: "Size", value: Binding(
                get: { model.uniform(\.size) ?? model.styleSummary.first?.size ?? 64 },
                set: { v in model.setTextSize(v) }), range: 4...400, unit: "px",
                        fieldRange: 4...2000, mixed: model.uniform(\.size) == nil)
            LabeledSlider(label: "Spacing", value: model.textBinding(\.letterSpacing, "ls"), range: -0.2...1, format: { String(format: "%.2f", $0) })
            LabeledSlider(label: "Line height", value: model.textBinding(\.lineHeight, "lh"), range: 0.5...3, format: { String(format: "%.2f", $0) })
            IconSegmented(options: [(TextCase.none, "Aa", "Normal case"), (.upper, "AA", "All caps"),
                                    (.lower, "aa", "Lowercase"), (.title, "Title", "Title case")],
                          selection: model.textBinding(\.textCase, "case"), useText: true)
        }
    }

    private func styleButton(_ flag: StyleFlags.Flag, _ letter: String, _ name: String) -> some View {
        let on = model.isStyleActive(flag)
        return Button { model.toggleStyle(flag) } label: {
            Text(AttributedString(StyleButtonTitle.make(letter: letter, flag: flag, size: 18)))
                .foregroundStyle(on ? Color.white : Color.primary)
                .frame(width: 44, height: 44)
                .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(on ? Color.accentColor : Color(uiColor: .tertiarySystemFill)))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(name)
        .accessibilityAddTraits(on ? .isSelected : [])
        .accessibilityIdentifier("style-\(name)")
    }
}

// MARK: - Color

struct ColorTab: View {
    @ObservedObject var model: EditorModel

    var fill: TextFill { model.selectedText?.fill ?? TextFill() }
    /// The uniform effective solid colour of the target (nil when mixed or gradient-filled).
    var selectionColor: RGBA? {
        guard let c = model.uniform({ $0.color }) else { return nil }
        if let c { return c }
        return fill.type == .solid ? fill.color : nil
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if model.styleRange != nil || model.caretMode {
                // Character colour for the selection (or what you type next).
                HStack {
                    Text(model.caretMode ? "Color for new text" : "Selection color")
                        .font(.footnote.weight(.semibold)).foregroundStyle(.secondary).textCase(.uppercase)
                    Spacer()
                    if selectionColor == nil { Text("Mixed").font(.footnote).foregroundStyle(.secondary) }
                }
                PaletteStrip(color: Binding(get: { selectionColor ?? .clear }, set: { c in model.setTextColor(c.withAlpha(max(c.alpha, 0.01))) }),
                             onEyedropper: { model.startEyedropper { c in model.setTextColor(c) } })
                Divider().padding(.vertical, 2)
                Text("Whole text").font(.footnote.weight(.semibold)).foregroundStyle(.secondary).textCase(.uppercase)
            }
            IconSegmented(options: [(FillType.solid, "Solid", "Solid color"), (.linear, "Linear", "Linear gradient"), (.radial, "Radial", "Radial gradient")],
                          selection: Binding(get: { fill.type }, set: { v in
                              guard let t = model.selectedText else { return }
                              var f = t.fill
                              if v != .solid && f.type == .solid {
                                  // Seed a gradient from the current colour.
                                  let c = f.color
                                  let (h, sat, b) = c.hsv
                                  if sat < 0.25 || b < 0.25 {
                                      // Black/white/grey text: start from a pleasant default gradient.
                                      f.stops = [GradientStop(offset: 0, color: RGBA(hex: "#FFD23FFF")!),
                                                 GradientStop(offset: 1, color: RGBA(hex: "#E0368CFF")!)]
                                  } else {
                                      f.stops = [GradientStop(offset: 0, color: c),
                                                 GradientStop(offset: 1, color: RGBA(h: (h + 0.12).truncatingRemainder(dividingBy: 1), s: max(0.5, sat), v: max(0.6, b), alpha: c.alpha))]
                                  }
                              }
                              f.type = v
                              model.setTextFill(f, coalesce: nil)
                          }), useText: true)
            if fill.type == .solid {
                if model.styleRange == nil && !model.caretMode {
                    PaletteStrip(color: Binding(get: { selectionColor ?? fill.color }, set: { c in model.setTextColor(c) }),
                                 onEyedropper: { model.startEyedropper { c in model.setTextColor(c) } })
                    if selectionColor == nil {
                        Text("Some words have their own color — picking a color here recolors all text.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                } else {
                    PaletteStrip(color: Binding(get: { fill.color }, set: { c in
                        var f = fill; f.type = .solid; f.color = c
                        model.setTextFill(f)
                    }), onEyedropper: { model.startEyedropper { c in
                        var f = fill; f.type = .solid; f.color = c
                        model.setTextFill(f)
                    } })
                }
            } else {
                GradientEditor(model: model)
            }
            LabeledSlider(label: "Opacity", value: Binding(
                get: { (model.selectedLayer?.opacity ?? 1) * 100 },
                set: { v in if let id = model.selection { model.updateLayer(id, coalesce: "opacity") { $0.opacity = v / 100 } } }),
                          range: 0...100, format: { "\(Int($0))%" })
        }
    }
}

struct GradientEditor: View {
    @ObservedObject var model: EditorModel
    var fill: TextFill { model.selectedText?.fill ?? TextFill() }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 10) {
                LinearGradient(stops: fill.stops.map { .init(color: $0.color.swiftUI, location: $0.offset) },
                               startPoint: .leading, endPoint: .trailing)
                    .frame(height: 28)
                    .clipShape(Capsule())
                    .overlay(Capsule().strokeBorder(Color.primary.opacity(0.15)))
                    .accessibilityHidden(true)
                ForEach(fill.stops.indices, id: \.self) { i in
                    ColorButtonCompact(color: fill.stops[i].color, label: "Gradient color \(i + 1)", onChange: { c in
                        model.updateSelectedText(coalesce: "stop\(i)") { t in if i < t.fill.stops.count { t.fill.stops[i].color = c } }
                    }, onEyedropper: {
                        model.startEyedropper { c in model.updateSelectedText { t in if i < t.fill.stops.count { t.fill.stops[i].color = c } } }
                    })
                }
                if fill.stops.count < 3 {
                    Button {
                        model.updateSelectedText { t in
                            let a = t.fill.stops.first!.color, b = t.fill.stops.last!.color
                            let mid = RGBA(red: (a.red + b.red) / 2, green: (a.green + b.green) / 2, blue: (a.blue + b.blue) / 2, alpha: (a.alpha + b.alpha) / 2)
                            t.fill.stops.insert(GradientStop(offset: 0.5, color: mid), at: 1)
                        }
                    } label: { Image(systemName: "plus.circle").font(.title3).frame(width: 44, height: 44) }
                    .accessibilityLabel("Add middle color")
                } else {
                    Button {
                        model.updateSelectedText { t in if t.fill.stops.count > 2 { t.fill.stops.remove(at: 1) } }
                    } label: { Image(systemName: "minus.circle").font(.title3).frame(width: 44, height: 44) }
                    .accessibilityLabel("Remove middle color")
                }
            }
            if fill.type == .linear {
                LabeledSlider(label: "Angle", value: Binding(get: { fill.angle }, set: { v in model.updateSelectedText(coalesce: "gradAngle") { $0.fill.angle = v } }),
                              range: 0...360, step: 1, format: { "\(Int($0))°" })
            }
        }
    }
}

struct ColorButtonCompact: View {
    var color: RGBA
    var label: String
    var onChange: (RGBA) -> Void
    var onEyedropper: (() -> Void)? = nil
    @State private var show = false
    var body: some View {
        Button { show = true } label: { ColorSwatch(color: color, size: 30).frame(width: 44, height: 44) }
            .buttonStyle(.plain)
            .accessibilityLabel("\(label), \(color.accessibilityName)")
            .sheet(isPresented: $show) { ColorPickerSheet(title: label, color: color, onEyedropper: onEyedropper, onChange: onChange) }
    }
}

// MARK: - Outline

struct OutlineTab: View {
    @ObservedObject var model: EditorModel
    var o: TextOutline { model.selectedText?.outline ?? TextOutline() }
    var fs: Double { model.selectedText?.fontSize ?? 64 }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                PanelToggleRow(title: "Outline", symbol: "character.textbox", isOn: model.textBinding(\.outline.enabled, "outlineOn"))
            }
            if o.enabled {
                IconSegmented(options: [(OutlineStyle.solid, "Solid", "Solid outline"), (.double, "Double", "Double outline"), (.glow, "Glow", "Glow")],
                              selection: model.textBinding(\.outline.style, "outlineStyle"), useText: true)
                ColorButton(title: o.style == .glow ? "Glow color" : (o.style == .double ? "Inner color" : "Color"), color: o.color,
                            onChange: { c in model.updateSelectedText(coalesce: "oc") { $0.outline.color = c } },
                            onEyedropper: { model.startEyedropper { c in model.updateSelectedText { $0.outline.color = c } } })
                LabeledSlider(label: "Thickness", value: Binding(get: { o.width * fs }, set: { v in model.updateSelectedText(coalesce: "ow") { $0.outline.width = (v / fs).clamped(0, 0.5) } }),
                              range: 0...(0.5 * fs), format: { String(format: "%.0f px", $0) })
                if o.style == .double {
                    ColorButton(title: "Outer color", color: o.color2, onChange: { c in model.updateSelectedText(coalesce: "oc2") { $0.outline.color2 = c } },
                                onEyedropper: { model.startEyedropper { c in model.updateSelectedText { $0.outline.color2 = c } } })
                    LabeledSlider(label: "Outer", value: Binding(get: { o.width2 * fs }, set: { v in model.updateSelectedText(coalesce: "ow2") { $0.outline.width2 = (v / fs).clamped(0, 0.5) } }),
                                  range: 0...(0.5 * fs), format: { String(format: "%.0f px", $0) })
                }
                if o.style == .glow {
                    LabeledSlider(label: "Radius", value: Binding(get: { o.glowRadius * fs }, set: { v in model.updateSelectedText(coalesce: "og") { $0.outline.glowRadius = (v / fs).clamped(0, 2) } }),
                                  range: 0...(2 * fs), format: { String(format: "%.0f px", $0) })
                }
                if o.style != .glow || o.width > 0 {
                    HStack {
                        Text("Corners").font(.subheadline).foregroundStyle(.secondary)
                        IconSegmented(options: [(LineJoin.round, "Round", "Round corners"), (.miter, "Sharp", "Sharp corners")],
                                      selection: model.textBinding(\.outline.join, "join"), useText: true)
                    }
                }
            }
        }
    }
}

// MARK: - Shadow

struct ShadowTab: View {
    @ObservedObject var model: EditorModel
    var s: TextShadow { model.selectedText?.shadow ?? TextShadow() }
    var fs: Double { model.selectedText?.fontSize ?? 64 }
    var distance: Double { hypot(s.offsetX, s.offsetY) * fs }
    var angle: Double { Transform.normalizeDegrees(atan2(s.offsetY, s.offsetX) * 180 / .pi) }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            PanelToggleRow(title: "Shadow", symbol: "shadow", isOn: model.textBinding(\.shadow.enabled, "shadowOn"))
            if s.enabled {
                ColorButton(title: "Color", color: s.color.withAlpha(1), allowsAlpha: false,
                            onChange: { c in model.updateSelectedText(coalesce: "sc") { $0.shadow.color = c.withAlpha($0.shadow.color.alpha) } },
                            onEyedropper: { model.startEyedropper { c in model.updateSelectedText { $0.shadow.color = c.withAlpha($0.shadow.color.alpha) } } })
                LabeledSlider(label: "Opacity", value: Binding(get: { s.color.alpha * 100 }, set: { v in model.updateSelectedText(coalesce: "sa") { $0.shadow.color = $0.shadow.color.withAlpha(v / 100) } }),
                              range: 0...100, format: { "\(Int($0))%" })
                LabeledSlider(label: "Blur", value: Binding(get: { s.blur * fs }, set: { v in model.updateSelectedText(coalesce: "sb") { $0.shadow.blur = (v / fs).clamped(0, 2) } }),
                              range: 0...(1 * fs), format: { String(format: "%.0f px", $0) })
                LabeledSlider(label: "Distance", value: Binding(get: { distance }, set: { v in setPolar(distance: v, angle: angle) }),
                              range: 0...(0.6 * fs), format: { String(format: "%.0f px", $0) })
                LabeledSlider(label: "Angle", value: Binding(get: { angle }, set: { v in setPolar(distance: distance, angle: v) }),
                              range: 0...359, step: 1, format: { "\(Int($0))°" })
            }
        }
    }

    private func setPolar(distance d: Double, angle a: Double) {
        let r = a * .pi / 180
        model.updateSelectedText(coalesce: "spolar") { t in
            t.shadow.offsetX = (d * cos(r) / fs).clamped(-2, 2)
            t.shadow.offsetY = (d * sin(r) / fs).clamped(-2, 2)
        }
    }
}

// MARK: - Effects

struct EffectsTab: View {
    @ObservedObject var model: EditorModel
    var t: TextProps { model.selectedText ?? TextProps(text: "") }
    var fs: Double { t.fontSize }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Presets").font(.footnote.weight(.semibold)).foregroundStyle(.secondary).textCase(.uppercase)
            PresetRow(model: model)
            PanelToggleRow(title: "Background box", symbol: "rectangle.fill", isOn: model.textBinding(\.backgroundBox.enabled, "boxOn"))
                .disabled(t.curve != 0)
            if t.curve != 0 {
                Text("The background box isn't available while text is curved.").font(.caption).foregroundStyle(.secondary)
            }
            if t.backgroundBox.enabled && t.curve == 0 {
                ColorButton(title: "Box color", color: t.backgroundBox.color.withAlpha(1), allowsAlpha: false,
                            onChange: { c in model.updateSelectedText(coalesce: "bc") { $0.backgroundBox.color = c.withAlpha($0.backgroundBox.color.alpha) } },
                            onEyedropper: { model.startEyedropper { c in model.updateSelectedText { $0.backgroundBox.color = c.withAlpha($0.backgroundBox.color.alpha) } } })
                LabeledSlider(label: "Box opacity", value: Binding(get: { t.backgroundBox.color.alpha * 100 }, set: { v in model.updateSelectedText(coalesce: "ba") { $0.backgroundBox.color = $0.backgroundBox.color.withAlpha(v / 100) } }),
                              range: 0...100, format: { "\(Int($0))%" })
                LabeledSlider(label: "Padding", value: Binding(get: { t.backgroundBox.padding * fs }, set: { v in model.updateSelectedText(coalesce: "bp") { $0.backgroundBox.padding = max(0, v / fs) } }),
                              range: 0...(1.5 * fs), format: { String(format: "%.0f px", $0) })
                LabeledSlider(label: "Corners", value: Binding(get: { t.backgroundBox.cornerRadius * fs }, set: { v in model.updateSelectedText(coalesce: "br") { $0.backgroundBox.cornerRadius = max(0, v / fs) } }),
                              range: 0...(1 * fs), format: { String(format: "%.0f px", $0) })
            }
            LabeledSlider(label: "Curve", value: model.textBinding(\.curve, "curve"), range: -100...100, step: 1,
                          format: { $0 == 0 ? "None" : "\(Int($0))" })
            SliderField(label: "Rotation", value: Binding(
                get: { let r = model.selectedLayer?.transform.rotation ?? 0; return r > 180 ? r - 360 : r },
                set: { v in if let id = model.selection { model.updateLayer(id, coalesce: "rot") { $0.transform.rotation = Transform.normalizeDegrees(v.rounded()) } } }),
                        range: -180...180, unit: "°")
            LabeledSlider(label: "Slant", value: model.textBinding(\.skew, "skew"), range: -45...45, step: 1, format: { "\(Int($0))°" })
        }
    }
}

// MARK: - Presets

struct PresetRow: View {
    @ObservedObject var model: EditorModel
    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(TextPresets.all) { p in
                    Button { model.applyPreset(p) } label: {
                        VStack(spacing: 4) {
                            PresetPreview(preset: p)
                                .frame(width: 88, height: 54)
                                .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color(white: 0.55)))
                                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                            Text(p.name).font(.caption.weight(.medium)).foregroundStyle(.primary).lineLimit(1)
                        }
                    }
                    .buttonStyle(TileButtonStyle())
                    .accessibilityLabel("Preset: \(p.name)")
                    .accessibilityIdentifier("preset-\(p.id)")
                }
            }
            .padding(.vertical, 4)
        }
    }
}

struct PresetPreview: View {
    let preset: TextPreset
    var body: some View {
        if let img = PresetPreviewCache.image(preset) {
            Image(uiImage: img).resizable().aspectRatio(contentMode: .fit).padding(6)
        }
    }
}

enum PresetPreviewCache {
    private static var cache: [String: UIImage] = [:]
    static func image(_ p: TextPreset) -> UIImage? {
        if let c = cache[p.id] { return c }
        var t = p.preview(text: p.id == "classic-meme" ? "MEME" : "Aa")
        t.fontSize = 100
        let layout = TextLayoutEngine.shared.layout(t)
        let r = layout.inkBounds.insetBy(dx: -14, dy: -14)
        let scale: CGFloat = 1.2
        guard let ctx = Renderer.makeContext(width: Int(r.width * scale), height: Int(r.height * scale)) else { return nil }
        ctx.translateBy(x: 0, y: r.height * scale); ctx.scaleBy(x: 1, y: -1)
        ctx.scaleBy(x: scale, y: scale)
        ctx.translateBy(x: -r.minX, y: -r.minY)
        Renderer.drawText(t, in: ctx)
        guard let cg = ctx.makeImage() else { return nil }
        let img = UIImage(cgImage: cg)
        cache[p.id] = img
        return img
    }
}
