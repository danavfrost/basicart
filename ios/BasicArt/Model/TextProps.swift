import Foundation

enum TextAlign: String, CaseIterable { case left, center, right, justify }
enum TextCase: String, CaseIterable { case none, upper, lower, title }
enum FillType: String, CaseIterable { case solid, linear, radial }
enum OutlineStyle: String, CaseIterable { case solid, double, glow }

struct GradientStop: Hashable {
    var offset: Double
    var color: RGBA
}

struct TextFill: Hashable {
    var type: FillType = .solid
    var color: RGBA = .black
    var angle: Double = 90
    var stops: [GradientStop] = [GradientStop(offset: 0, color: RGBA(hex: "#FFD23FFF")!),
                                 GradientStop(offset: 1, color: RGBA(hex: "#E0368CFF")!)]

    static let defaultFill = TextFill()

    /// Representative single color (for UI swatches / editing overlay).
    var primaryColor: RGBA { type == .solid ? color : (stops.first?.color ?? color) }

    static func == (a: TextFill, b: TextFill) -> Bool {
        guard a.type == b.type else { return false }
        switch a.type {
        case .solid: return a.color == b.color
        case .linear: return a.angle == b.angle && a.stops == b.stops
        case .radial: return a.stops == b.stops
        }
    }
    func hash(into h: inout Hasher) {
        h.combine(type)
        switch type {
        case .solid: h.combine(color)
        case .linear: h.combine(angle); h.combine(stops)
        case .radial: h.combine(stops)
        }
    }
}

struct TextOutline: Hashable {
    var enabled = false
    var style: OutlineStyle = .solid
    var color: RGBA = .black
    var width: Double = 0.06
    var color2: RGBA = .white
    var width2: Double = 0.06
    var glowRadius: Double = 0.3
    var join: LineJoin = .round
}

struct TextShadow: Hashable {
    var enabled = false
    var color: RGBA = RGBA(hex: "#00000080")!
    var blur: Double = 0.1
    var offsetX: Double = 0.05
    var offsetY: Double = 0.05
}

struct TextBackgroundBox: Hashable {
    var enabled = false
    var color: RGBA = RGBA(hex: "#00000099")!
    var padding: Double = 0.25
    var cornerRadius: Double = 0.15
}

struct StyleFlags: Hashable {
    var bold = false
    var italic = false
    var underline = false
    var strike = false

    enum Flag: CaseIterable { case bold, italic, underline, strike }

    subscript(flag: Flag) -> Bool {
        get {
            switch flag {
            case .bold: return bold
            case .italic: return italic
            case .underline: return underline
            case .strike: return strike
            }
        }
        set {
            switch flag {
            case .bold: bold = newValue
            case .italic: italic = newValue
            case .underline: underline = newValue
            case .strike: strike = newValue
            }
        }
    }
}

/// Per-range character styling overrides (§7.9). nil = inherit the layer value.
struct CharStyle: Hashable {
    var bold: Bool?
    var italic: Bool?
    var underline: Bool?
    var strike: Bool?
    var color: RGBA?
    var fontId: String?
    var weight: Int?
    var size: Double?

    var isEmpty: Bool { self == CharStyle() }

    subscript(flag: StyleFlags.Flag) -> Bool? {
        get {
            switch flag {
            case .bold: return bold
            case .italic: return italic
            case .underline: return underline
            case .strike: return strike
            }
        }
        set {
            switch flag {
            case .bold: bold = newValue
            case .italic: italic = newValue
            case .underline: underline = newValue
            case .strike: strike = newValue
            }
        }
    }

    /// A style with every field set from an effective style.
    init(_ e: EffectiveStyle) {
        bold = e.flags.bold; italic = e.flags.italic; underline = e.flags.underline; strike = e.flags.strike
        color = e.color; fontId = e.fontId; weight = e.weight; size = e.size
    }
    init(bold: Bool? = nil, italic: Bool? = nil, underline: Bool? = nil, strike: Bool? = nil,
         color: RGBA? = nil, fontId: String? = nil, weight: Int? = nil, size: Double? = nil) {
        self.bold = bold; self.italic = italic; self.underline = underline; self.strike = strike
        self.color = color; self.fontId = fontId; self.weight = weight; self.size = size
    }
}

/// The resolved style of one grapheme: layer values overridden by its span.
struct EffectiveStyle: Hashable {
    var flags: StyleFlags
    var color: RGBA?      // nil = the layer fill (solid or gradient)
    var fontId: String
    var weight: Int
    var size: Double
}

struct TextSpan: Hashable {
    var start: Int // UTF-16, inclusive
    var end: Int   // UTF-16, exclusive
    var style: CharStyle
}

struct TextProps: Hashable {
    var text: String
    var fontId: String = "inter"
    var fontSize: Double = 64
    var weight: Int = 400
    var bold = false
    var italic = false
    var underline = false
    var strike = false
    var spans: [TextSpan] = []
    var align: TextAlign = .center
    var letterSpacing: Double = 0
    var lineHeight: Double = 1.2
    var textCase: TextCase = .none
    var autoWidth = true
    var boxWidth: Double = 0
    var fill = TextFill()
    var outline = TextOutline()
    var shadow = TextShadow()
    var backgroundBox = TextBackgroundBox()
    var curve: Double = 0
    var skew: Double = 0
    /// Not stored: the auto-width wrap limit 0.9 × canvas.width / transform.scale (§7.3 step 5),
    /// kept in sync by `Document.syncTextLimits()`.
    var autoWidthLimit: Double = .infinity

    var layerFlags: StyleFlags {
        get { StyleFlags(bold: bold, italic: italic, underline: underline, strike: strike) }
        set { bold = newValue.bold; italic = newValue.italic; underline = newValue.underline; strike = newValue.strike }
    }

    /// Layer-level effective style (text outside every span).
    var layerStyle: EffectiveStyle {
        EffectiveStyle(flags: layerFlags, color: nil, fontId: fontId, weight: weight, size: fontSize)
    }

    func effective(_ s: CharStyle?) -> EffectiveStyle {
        guard let s else { return layerStyle }
        var e = layerStyle
        if let v = s.bold { e.flags.bold = v }
        if let v = s.italic { e.flags.italic = v }
        if let v = s.underline { e.flags.underline = v }
        if let v = s.strike { e.flags.strike = v }
        if let v = s.color { e.color = (fill.type == .solid && v == fill.color) ? nil : v }
        if let v = s.fontId { e.fontId = v }
        if let v = s.weight { e.weight = v }
        if let v = s.size { e.size = v }
        return e
    }

    /// Removes every field equal to the layer's current value (§7.9 normalization step 2).
    func stripped(_ s: CharStyle) -> CharStyle {
        var s = s
        if s.bold == bold { s.bold = nil }
        if s.italic == italic { s.italic = nil }
        if s.underline == underline { s.underline = nil }
        if s.strike == strike { s.strike = nil }
        if let c = s.color, fill.type == .solid, c == fill.color { s.color = nil }
        if s.fontId == fontId { s.fontId = nil }
        if s.weight == weight { s.weight = nil }
        if s.size == fontSize { s.size = nil }
        return s
    }

    /// Resets every style field to its §7.1 default (used when applying presets).
    mutating func resetStyle() {
        let d = TextProps(text: "")
        fontId = d.fontId; weight = d.weight
        bold = d.bold; italic = d.italic; underline = d.underline; strike = d.strike
        align = d.align; letterSpacing = d.letterSpacing; lineHeight = d.lineHeight
        textCase = d.textCase; fill = d.fill; outline = d.outline; shadow = d.shadow
        backgroundBox = d.backgroundBox; curve = d.curve; skew = d.skew
    }
}
