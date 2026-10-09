import SwiftUI

/// A compact labelled slider with a value readout, used across panels.
struct LabeledSlider: View {
    var label: String
    @Binding var value: Double
    var range: ClosedRange<Double>
    var step: Double? = nil
    var format: (Double) -> String = { String(format: "%.0f", $0) }
    var onEditingChanged: (Bool) -> Void = { _ in }

    var body: some View {
        HStack(spacing: 10) {
            Text(label)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .frame(minWidth: 76, alignment: .leading)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            Group {
                if let step {
                    Slider(value: $value, in: range, step: step, onEditingChanged: onEditingChanged)
                } else {
                    Slider(value: $value, in: range, onEditingChanged: onEditingChanged)
                }
            }
            .accessibilityLabel(label)
            .accessibilityValue(format(value))
            Text(format(value))
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(.secondary)
                .frame(minWidth: 44, alignment: .trailing)
        }
        .frame(minHeight: 40)
    }
}

/// Slider + editable numeric field.
struct SliderField: View {
    var label: String
    @Binding var value: Double
    var range: ClosedRange<Double>
    var unit: String = ""
    var decimals = 0
    /// Range accepted from the numeric field (defaults to the slider range).
    var fieldRange: ClosedRange<Double>? = nil
    /// Shows "Mixed" when the selection has several values.
    var mixed = false
    @State private var draft = ""
    @FocusState private var focused: Bool

    /// Shown text: the user's draft while editing, otherwise always the live value ("Mixed" = empty).
    private var text: Binding<String> {
        Binding(get: { focused ? draft : (mixed ? "" : fmt(value)) },
                set: { t in
                    draft = t
                    // Commit as you type whenever the entry is a valid number in range.
                    let r = fieldRange ?? range
                    if let v = Double(t.replacingOccurrences(of: ",", with: ".")), v >= r.lowerBound, v <= r.upperBound, v != value {
                        value = v
                    }
                })
    }

    var body: some View {
        HStack(spacing: 10) {
            Text(label)
                .font(.subheadline).foregroundStyle(.secondary)
                .frame(minWidth: 76, alignment: .leading)
            Slider(value: Binding(get: { value.clamped(range.lowerBound, range.upperBound) }, set: { value = $0 }), in: range)
                .accessibilityLabel(label)
                .accessibilityValue("\(fmt(value)) \(unit)")
            HStack(spacing: 2) {
                TextField(mixed ? "Mixed" : "", text: text)
                    .keyboardType(range.lowerBound < 0 ? .numbersAndPunctuation : (decimals > 0 ? .decimalPad : .numberPad))
                    .multilineTextAlignment(.trailing)
                    .font(.footnote.monospacedDigit())
                    .focused($focused)
                    .onSubmit(commit)
                    .accessibilityLabel("\(label) value")
                if !unit.isEmpty { Text(unit).font(.caption2).foregroundStyle(.secondary) }
            }
            .padding(.horizontal, 8)
            .frame(width: 70, height: 32)
            .background(RoundedRectangle(cornerRadius: 8).fill(Color(uiColor: .tertiarySystemFill)))
        }
        .frame(minHeight: 40)
        .onChange(of: focused) { f in if f { draft = mixed ? "" : fmt(value) } else { commit() } }
        .onDisappear { if focused { commit() } }
    }

    private func fmt(_ v: Double) -> String { String(format: "%.\(decimals)f", v) }
    private func commit() {
        let r = fieldRange ?? range
        if let v = Double(draft.replacingOccurrences(of: ",", with: ".")) { value = v.clamped(r.lowerBound, r.upperBound) }
    }
}

/// Segmented control built from SF Symbols or text, with ≥44 pt targets.
struct IconSegmented<T: Hashable>: View {
    var options: [(T, String, String)] // value, symbol or text, accessibility label
    @Binding var selection: T
    var useText = false

    var body: some View {
        HStack(spacing: 4) {
            ForEach(options.indices, id: \.self) { i in
                let (v, sym, label) = options[i]
                let on = v == selection
                Button { selection = v } label: {
                    Group {
                        if useText { Text(sym).font(.subheadline.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.7) }
                        else { Image(systemName: sym).font(.system(size: 16, weight: .medium)) }
                    }
                    .frame(maxWidth: .infinity, minHeight: 36)
                    .foregroundStyle(on ? Color.white : Color.primary)
                    .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(on ? Color.accentColor : Color.clear))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(label)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(3)
        .frame(minHeight: 44)
        .background(RoundedRectangle(cornerRadius: 11, style: .continuous).fill(Color(uiColor: .tertiarySystemFill)))
    }
}

struct PanelToggleRow: View {
    var title: String
    var symbol: String
    @Binding var isOn: Bool
    var body: some View {
        Toggle(isOn: $isOn) {
            Label(title, systemImage: symbol).font(.subheadline.weight(.semibold))
        }
        .frame(minHeight: 44)
    }
}

/// A color button that opens the full picker; shows the current color.
struct ColorButton: View {
    var title: String
    var color: RGBA
    var allowsAlpha = true
    var onChange: (RGBA) -> Void
    var onEyedropper: (() -> Void)? = nil
    @State private var show = false

    var body: some View {
        HStack(spacing: 6) {
            if let onEyedropper { EyedropperButton(action: onEyedropper) }
            button
        }
    }

    private var button: some View {
        Button { show = true } label: {
            HStack(spacing: 8) {
                Text(title).font(.subheadline).foregroundStyle(.secondary)
                Spacer(minLength: 4)
                if !title.isEmpty { Text(color.hexRGB).font(.caption.monospaced()).foregroundStyle(.secondary) }
                ColorSwatch(color: color, size: 28)
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(title.isEmpty ? "Color" : title): \(color.accessibilityName)")
        .sheet(isPresented: $show) {
            ColorPickerSheet(title: title, color: color, allowsAlpha: allowsAlpha, onEyedropper: onEyedropper, onChange: onChange)
        }
    }
}

struct PanelHint: View {
    var symbol: String
    var text: String
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbol).font(.title3).foregroundStyle(Color.accentColor).frame(width: 32)
            Text(text).font(.subheadline).foregroundStyle(.secondary)
            Spacer(minLength: 0)
        }
        .padding(.vertical, 8)
    }
}

struct PillButton: View {
    var title: String
    var symbol: String
    var role: ButtonRole? = nil
    var action: () -> Void
    var body: some View {
        Button(role: role, action: action) {
            Label(title, systemImage: symbol)
                .font(.subheadline.weight(.medium))
                .lineLimit(1)
                .fixedSize()
                .padding(.horizontal, 12)
                .frame(minHeight: 40)
                .background(Capsule().fill(Color(uiColor: .tertiarySystemFill)))
                .foregroundStyle(role == .destructive ? Color.red : Color.primary)
        }
        .buttonStyle(.plain)
        .frame(minHeight: 44)
    }
}

/// Scrolls its content only when it is taller than `maxHeight`; otherwise sizes to fit.
struct AdaptiveScroll<Content: View>: View {
    var maxHeight: CGFloat
    @ViewBuilder var content: () -> Content
    @State private var contentHeight: CGFloat = 0

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.vertical, showsIndicators: contentHeight > maxHeight) {
                VStack(spacing: 0) {
                    Color.clear.frame(height: 0).id("panelTop")
                    content()
                    if contentHeight > maxHeight { Color.clear.frame(height: 16) }
                }
                .background(GeometryReader { g in
                    Color.clear.preference(key: HeightKey.self, value: g.size.height)
                })
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
                withAnimation(.snappy) { proxy.scrollTo("panelTop", anchor: .top) }
            }
        }
        .frame(height: min(maxHeight, max(1, contentHeight)))
        .onPreferenceChange(HeightKey.self) { contentHeight = $0 }
        .scrollDisabled(contentHeight <= maxHeight)
    }
}

private struct HeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

/// Icon over a short label, equal width in a row (≥ 44 pt tall).
struct StackedButton: View {
    var title: String
    var symbol: String
    var action: () -> Void
    @Environment(\.isEnabled) private var enabled
    var body: some View {
        Button(action: action) {
            VStack(spacing: 3) {
                Image(systemName: symbol).font(.system(size: 17, weight: .medium)).frame(height: 20)
                Text(title).font(.caption.weight(.medium)).lineLimit(1).minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity, minHeight: 52)
            .foregroundStyle(enabled ? Color.primary : Color.secondary.opacity(0.6))
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color(uiColor: .tertiarySystemFill)))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
