import SwiftUI
import UIKit

extension RGBA {
    var swiftUI: Color { Color(.sRGB, red: red, green: green, blue: blue, opacity: alpha) }
    var uiColor: UIColor { UIColor(red: red, green: green, blue: blue, alpha: alpha) }

    var hsv: (h: Double, s: Double, v: Double) {
        let r = red, g = green, b = blue
        let mx = max(r, g, b), mn = min(r, g, b), d = mx - mn
        var h = 0.0
        if d > 0 {
            if mx == r { h = ((g - b) / d).truncatingRemainder(dividingBy: 6) }
            else if mx == g { h = (b - r) / d + 2 }
            else { h = (r - g) / d + 4 }
            h /= 6
            if h < 0 { h += 1 }
        }
        return (h, mx == 0 ? 0 : d / mx, mx)
    }

    init(h: Double, s: Double, v: Double, alpha: Double) {
        let i = floor(h * 6), f = h * 6 - i
        let p = v * (1 - s), q = v * (1 - f * s), t = v * (1 - (1 - f) * s)
        let (r, g, b): (Double, Double, Double)
        switch Int(i) % 6 {
        case 0: (r, g, b) = (v, t, p)
        case 1: (r, g, b) = (q, v, p)
        case 2: (r, g, b) = (p, v, t)
        case 3: (r, g, b) = (p, q, v)
        case 4: (r, g, b) = (t, p, v)
        default: (r, g, b) = (v, p, q)
        }
        self.init(red: r, green: g, blue: b, alpha: alpha)
    }

    var accessibilityName: String {
        let (h, s, v) = hsv
        if v < 0.15 { return "black" }
        if s < 0.12 { return v > 0.9 ? "white" : (v > 0.6 ? "light gray" : "gray") }
        let deg = h * 360
        var name: String
        switch deg {
        case ..<12, 348...: name = "red"
        case ..<40: name = "orange"
        case ..<68: name = "yellow"
        case ..<90: name = "lime"
        case ..<160: name = "green"
        case ..<190: name = "teal"
        case ..<205: name = "cyan"
        case ..<225: name = "sky blue"
        case ..<245: name = "blue"
        case ..<265: name = "indigo"
        case ..<295: name = "purple"
        case ..<330: name = "magenta"
        default: name = "pink"
        }
        if (name == "orange" || name == "red" || name == "yellow") && v < 0.55 { name = "brown" }
        if v < 0.4 && name != "brown" { name = "dark " + name }
        else if s < 0.35 && v > 0.8 { name = "light " + name }
        return name
    }
}

/// The canvas eyedropper: always the first item where colours are picked.
struct EyedropperButton: View {
    var action: () -> Void
    var body: some View {
        Button(action: action) {
            Image(systemName: "eyedropper.halffull")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Color.accentColor)
                .frame(width: 34, height: 34)
                .background(Circle().fill(Color.accentColor.opacity(0.14)))
                .overlay(Circle().strokeBorder(Color.accentColor.opacity(0.4), lineWidth: 1))
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Pick from canvas")
        .accessibilityIdentifier("eyedropperButton")
    }
}

struct ColorSwatch: View {
    var color: RGBA
    var size: CGFloat = 32
    var selected = false
    var body: some View {
        ZStack {
            Checkerboard(cell: size / 6).clipShape(Circle())
            Circle().fill(color.swiftUI)
            Circle().strokeBorder(Color.primary.opacity(0.18), lineWidth: 1)
            if selected {
                Circle().strokeBorder(Color.accentColor, lineWidth: 3).padding(-5)
            }
        }
        .frame(width: size, height: size)
    }
}

/// Compact inline palette: palette switcher + swatches + recents + custom button.
struct PaletteStrip: View {
    @Binding var color: RGBA
    var allowsAlpha = true
    var onEyedropper: (() -> Void)? = nil
    var onCommit: (RGBA) -> Void = { _ in }
    @EnvironmentObject var settings: AppSettings
    @AppStorage("paletteIndex") private var paletteIndex = 0
    @State private var showFull = false

    var palette: Palette? { Palettes.all.isEmpty ? nil : Palettes.all[min(paletteIndex, Palettes.all.count - 1)] }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Menu {
                    ForEach(Array(Palettes.all.enumerated()), id: \.offset) { i, p in
                        Button(p.name) { paletteIndex = i }
                    }
                } label: {
                    HStack(spacing: 4) {
                        Text(palette?.name ?? "Palette").font(.subheadline.weight(.semibold))
                        Image(systemName: "chevron.up.chevron.down").font(.caption2.weight(.bold))
                    }
                    .frame(minHeight: 36)
                }
                .accessibilityLabel("Palette: \(palette?.name ?? "")")
                Spacer()
                Button { showFull = true } label: {
                    HStack(spacing: 6) {
                        ColorSwatch(color: color, size: 22)
                        Text("Custom").font(.subheadline)
                    }
                    .frame(minHeight: 40)
                }
                .accessibilityLabel("Custom color")
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    if let onEyedropper {
                        EyedropperButton(action: onEyedropper)
                        Divider().frame(height: 28)
                    }
                    if !settings.recentColors.isEmpty {
                        ForEach(settings.recentColors.prefix(6), id: \.self) { c in swatchButton(c) }
                        Divider().frame(height: 28)
                    }
                    ForEach(palette?.colors ?? [], id: \.self) { c in swatchButton(c) }
                }
                .padding(.vertical, 6)
                .padding(.horizontal, 4)
            }
        }
        .sheet(isPresented: $showFull) {
            ColorPickerSheet(title: "Color", color: color, allowsAlpha: allowsAlpha) { c in
                color = c
                onCommit(c)
            }
        }
    }

    private func swatchButton(_ c: RGBA) -> some View {
        Button {
            var nc = c
            if allowsAlpha && c.a == 255 { nc.a = color.a == 0 ? 255 : color.a }
            color = nc
            settings.noteColorUsed(c)
            onCommit(nc)
        } label: {
            ColorSwatch(color: c, size: 34, selected: c.hexRGB == color.hexRGB)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(c.accessibilityName)
    }
}

/// Full colour picker (§9): palettes, recents, HSV, HEX, RGB, alpha, My colors.
struct ColorPickerSheet: View {
    var title: String
    var color: RGBA
    var allowsAlpha = true
    var onEyedropper: (() -> Void)? = nil
    var onChange: (RGBA) -> Void

    @EnvironmentObject var settings: AppSettings
    @Environment(\.dismiss) private var dismiss
    @State private var h = 0.0
    @State private var s = 0.0
    @State private var v = 0.0
    @State private var a = 1.0
    @State private var hexText = ""
    @State private var paletteIndex = 0
    @State private var started = false

    var current: RGBA { RGBA(h: h, s: s, v: v, alpha: allowsAlpha ? a : 1) }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    HStack(spacing: 14) {
                        if let onEyedropper {
                            EyedropperButton {
                                dismiss()
                                onEyedropper()
                            }
                        }
                        ZStack {
                            Checkerboard(cell: 8)
                            current.swiftUI
                        }
                        .frame(width: 64, height: 64)
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Color.primary.opacity(0.15)))
                        .accessibilityLabel("Current color \(current.accessibilityName)")
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Text("#").foregroundStyle(.secondary)
                                TextField("RRGGBB", text: $hexText)
                                    .font(.body.monospaced())
                                    .textInputAutocapitalization(.characters)
                                    .autocorrectionDisabled()
                                    .onSubmit(applyHex)
                                    .accessibilityLabel("Hex color")
                            }
                            .padding(.horizontal, 10).frame(height: 40)
                            .background(RoundedRectangle(cornerRadius: 9).fill(Color(uiColor: .tertiarySystemFill)))
                            HStack(spacing: 6) {
                                rgbField("R", \.r)
                                rgbField("G", \.g)
                                rgbField("B", \.b)
                            }
                        }
                    }

                    SVSquare(h: h, s: $s, v: $v)
                        .frame(height: 200)
                        .accessibilityLabel("Saturation and brightness")
                        .accessibilityValue("Saturation \(Int(s * 100)) percent, brightness \(Int(v * 100)) percent")
                    HueBar(h: $h).frame(height: 32)
                    if allowsAlpha {
                        AlphaBar(color: RGBA(h: h, s: s, v: v, alpha: 1), a: $a).frame(height: 32)
                    }

                    if !settings.recentColors.isEmpty {
                        section("Recent") { swatchGrid(settings.recentColors) }
                    }
                    section("My colors") {
                        HStack(alignment: .top) {
                            if settings.myColors.isEmpty {
                                Text("Save colors you use often.").font(.footnote).foregroundStyle(.secondary)
                            } else {
                                swatchGrid(settings.myColors, removable: true)
                            }
                            Spacer()
                            Button {
                                if !settings.myColors.contains(current) { settings.myColors.insert(current, at: 0) }
                            } label: {
                                Label("Save", systemImage: "plus.circle.fill").font(.subheadline.weight(.semibold))
                                    .frame(minHeight: 44)
                            }
                            .accessibilityLabel("Save current color to My colors")
                        }
                    }
                    section("Palettes") {
                        Picker("Palette", selection: $paletteIndex) {
                            ForEach(Array(Palettes.all.enumerated()), id: \.offset) { i, p in Text(p.name).tag(i) }
                        }
                        .pickerStyle(.menu)
                        if !Palettes.all.isEmpty { swatchGrid(Palettes.all[min(paletteIndex, Palettes.all.count - 1)].colors) }
                    }
                }
                .padding(16)
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        settings.noteColorUsed(current)
                        dismiss()
                    }.fontWeight(.semibold)
                }
            }
        }
        .presentationDetents([.fraction(0.72), .large])
        .onAppear {
            let hsv = color.hsv
            h = hsv.h; s = hsv.s; v = hsv.v; a = color.alpha
            hexText = color.hexRGB
            DispatchQueue.main.async { started = true }
        }
        .onChange(of: h) { _ in changed() }
        .onChange(of: s) { _ in changed() }
        .onChange(of: v) { _ in changed() }
        .onChange(of: a) { _ in changed() }
    }

    private func changed() {
        guard started else { return }
        hexText = current.hexRGB
        onChange(current)
    }

    private func applyHex() {
        let t = hexText.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "#", with: "")
        if let c = RGBA(hex: "#" + t) { set(c) } else { hexText = current.hexRGB }
    }

    private func set(_ c: RGBA) {
        let hsv = c.hsv
        if hsv.s > 0 { h = hsv.h }
        s = hsv.s; v = hsv.v
        if allowsAlpha { a = c.alpha }
    }

    private func rgbField(_ label: String, _ kp: WritableKeyPath<RGBA, UInt8>) -> some View {
        let binding = Binding<String>(
            get: { "\(current[keyPath: kp])" },
            set: { t in
                guard let n = Int(t) else { return }
                var c = current
                c[keyPath: kp] = UInt8(max(0, min(255, n)))
                set(c)
            })
        return HStack(spacing: 2) {
            Text(label).font(.caption2.weight(.bold)).foregroundStyle(.secondary)
            TextField(label, text: binding).keyboardType(.numberPad).font(.callout.monospacedDigit())
                .accessibilityLabel("\(label) value")
        }
        .padding(.horizontal, 6).frame(height: 36)
        .background(RoundedRectangle(cornerRadius: 8).fill(Color(uiColor: .tertiarySystemFill)))
    }

    private func section<C: View>(_ title: String, @ViewBuilder content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.footnote.weight(.semibold)).foregroundStyle(.secondary).textCase(.uppercase)
            content()
        }
    }

    private func swatchGrid(_ colors: [RGBA], removable: Bool = false) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 44), spacing: 4)], alignment: .leading, spacing: 4) {
            ForEach(colors, id: \.self) { c in
                Button { set(c) } label: {
                    ColorSwatch(color: c, size: 34, selected: c == current).frame(width: 44, height: 44).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(c.accessibilityName)
                .contextMenu {
                    if removable {
                        Button(role: .destructive) { settings.myColors.removeAll { $0 == c } } label: {
                            Label("Remove from My colors", systemImage: "trash")
                        }
                    }
                }
            }
        }
    }
}

struct SVSquare: View {
    var h: Double
    @Binding var s: Double
    @Binding var v: Double
    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color(hue: h, saturation: 1, brightness: 1)
                LinearGradient(colors: [.white, .white.opacity(0)], startPoint: .leading, endPoint: .trailing)
                LinearGradient(colors: [.black.opacity(0), .black], startPoint: .top, endPoint: .bottom)
                Circle()
                    .strokeBorder(.white, lineWidth: 3)
                    .background(Circle().fill(Color(hue: h, saturation: s, brightness: v)))
                    .shadow(radius: 2)
                    .frame(width: 28, height: 28)
                    .position(x: s * geo.size.width, y: (1 - v) * geo.size.height)
            }
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { g in
                s = min(1, max(0, g.location.x / geo.size.width))
                v = min(1, max(0, 1 - g.location.y / geo.size.height))
            })
        }
        .accessibilityElement()
        .accessibilityAdjustableAction { dir in
            v = min(1, max(0, v + (dir == .increment ? 0.05 : -0.05)))
        }
    }
}

struct HueBar: View {
    @Binding var h: Double
    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                LinearGradient(colors: stride(from: 0.0, through: 1.0, by: 1.0 / 12).map { Color(hue: $0, saturation: 1, brightness: 1) },
                               startPoint: .leading, endPoint: .trailing)
                    .clipShape(Capsule())
                Circle().strokeBorder(.white, lineWidth: 3)
                    .background(Circle().fill(Color(hue: h, saturation: 1, brightness: 1)))
                    .shadow(radius: 2)
                    .frame(width: geo.size.height, height: geo.size.height)
                    .offset(x: h * (geo.size.width - geo.size.height))
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { g in
                h = min(1, max(0, (g.location.x - geo.size.height / 2) / (geo.size.width - geo.size.height)))
            })
        }
        .accessibilityElement()
        .accessibilityLabel("Hue")
        .accessibilityValue("\(Int(h * 360)) degrees")
        .accessibilityAdjustableAction { dir in h = min(1, max(0, h + (dir == .increment ? 1.0 / 36 : -1.0 / 36))) }
    }
}

struct AlphaBar: View {
    var color: RGBA
    @Binding var a: Double
    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Checkerboard(cell: 6).clipShape(Capsule())
                LinearGradient(colors: [color.swiftUI.opacity(0), color.swiftUI], startPoint: .leading, endPoint: .trailing)
                    .clipShape(Capsule())
                Circle().strokeBorder(.white, lineWidth: 3)
                    .background(Circle().fill(color.swiftUI.opacity(a)))
                    .shadow(radius: 2)
                    .frame(width: geo.size.height, height: geo.size.height)
                    .offset(x: a * (geo.size.width - geo.size.height))
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { g in
                a = min(1, max(0, (g.location.x - geo.size.height / 2) / (geo.size.width - geo.size.height)))
            })
        }
        .accessibilityElement()
        .accessibilityLabel("Opacity")
        .accessibilityValue("\(Int(a * 100)) percent")
        .accessibilityAdjustableAction { dir in a = min(1, max(0, a + (dir == .increment ? 0.05 : -0.05))) }
    }
}
