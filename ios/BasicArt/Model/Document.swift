import CoreGraphics
import Foundation

// MARK: - Document

struct Canvas: Hashable {
    var width: Int
    var height: Int
    var background: RGBA = .white

    static let minSide = 16
    static let maxSide = 8192
}

struct Document: Equatable {
    static let supportedFormatVersion = 1

    var id: String
    var name: String = "Untitled"
    var created: Date = Date()
    var modified: Date = Date()
    var generator: String = Document.generatorString
    var canvas: Canvas
    var layers: [Layer] = []

    static var generatorString: String {
        let v = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0.0"
        return "Basic Art iOS \(v)"
    }

    static func newID() -> String { UUID().uuidString.lowercased() }

    init(id: String = Document.newID(), name: String = "Untitled", canvas: Canvas) {
        self.id = id
        self.name = name
        self.canvas = canvas
    }

    func index(of layerID: String) -> Int? { layers.firstIndex { $0.id == layerID } }

    /// Sets each text layer's auto-width wrap limit from the canvas width and its scale.
    mutating func syncTextLimits() {
        for i in layers.indices {
            guard case .text(var t) = layers[i].content else { continue }
            let limit = 0.9 * Double(canvas.width) / max(0.01, layers[i].transform.scale)
            if t.autoWidthLimit != limit {
                t.autoWidthLimit = limit
                layers[i].content = .text(t)
            }
        }
    }
    func layer(_ id: String) -> Layer? { layers.first { $0.id == id } }

    /// Equality for "did the document change" ignores timestamps / generator.
    func contentEquals(_ other: Document) -> Bool {
        name == other.name && canvas == other.canvas && layers == other.layers
    }
}

// MARK: - Layer

struct Transform: Hashable {
    var x: Double
    var y: Double
    var scale: Double = 1
    var rotation: Double = 0 // degrees, clockwise

    var radians: Double { rotation * .pi / 180 }

    /// translate(x, y) → rotate(θ) → scale(s) → translate(−w/2, −h/2)
    func affine(boxSize: CGSize) -> CGAffineTransform {
        CGAffineTransform(translationX: x, y: y)
            .rotated(by: radians)
            .scaledBy(x: scale, y: scale)
            .translatedBy(x: -boxSize.width / 2, y: -boxSize.height / 2)
    }

    static func normalizeDegrees(_ d: Double) -> Double {
        guard d.isFinite else { return 0 }
        var r = d.truncatingRemainder(dividingBy: 360)
        if r < 0 { r += 360 }
        if r >= 360 { r = 0 }
        return r
    }
}

enum LayerType: String, CaseIterable {
    case image, text, drawing, shape

    var displayName: String {
        switch self {
        case .image: return "Image"
        case .text: return "Text"
        case .drawing: return "Drawing"
        case .shape: return "Shape"
        }
    }
}

enum LayerContent: Equatable {
    case image(ImageProps)
    case text(TextProps)
    case drawing(DrawingProps)
    case shape(ShapeProps)

    var type: LayerType {
        switch self {
        case .image: return .image
        case .text: return .text
        case .drawing: return .drawing
        case .shape: return .shape
        }
    }
}

struct Layer: Identifiable, Equatable {
    var id: String
    var name: String
    var visible: Bool = true
    var locked: Bool = false
    var opacity: Double = 1
    var blendMode: String = "normal"
    var transform: Transform
    var content: LayerContent
    /// Eraser mask (§9.1) for image/text/shape layers: eraser strokes in local space,
    /// stored in strokes/<id>.mask.json. Always empty for drawing layers.
    var mask: [Stroke] = []
    var maskFileState: StrokesFileState = .missing

    var type: LayerType { content.type }

    static func == (a: Layer, b: Layer) -> Bool {
        a.id == b.id && a.name == b.name && a.visible == b.visible && a.locked == b.locked &&
            a.opacity == b.opacity && a.transform == b.transform && a.content == b.content && a.mask == b.mask
    }

    var text: TextProps? {
        get { if case .text(let t) = content { return t }; return nil }
        set { if let t = newValue { content = .text(t) } }
    }
    var image: ImageProps? {
        get { if case .image(let t) = content { return t }; return nil }
        set { if let t = newValue { content = .image(t) } }
    }
    var drawing: DrawingProps? {
        get { if case .drawing(let t) = content { return t }; return nil }
        set { if let t = newValue { content = .drawing(t) } }
    }
    var shape: ShapeProps? {
        get { if case .shape(let t) = content { return t }; return nil }
        set { if let t = newValue { content = .shape(t) } }
    }

    /// Box size for non-text layers (text needs layout; use `LayerGeometry.boxSize`).
    var storedBoxSize: CGSize? {
        switch content {
        case .image(let p): return p.boxSize
        case .drawing(let p): return CGSize(width: p.width, height: p.height)
        case .shape(let p): return CGSize(width: p.width, height: p.height)
        case .text: return nil
        }
    }
}

// MARK: - Image

struct CropRect: Hashable {
    var x: Double
    var y: Double
    var width: Double
    var height: Double
    var cgRect: CGRect { CGRect(x: x, y: y, width: width, height: height) }
}

struct ImageAdjust: Hashable {
    var brightness: Double = 0
    var contrast: Double = 0
    var saturation: Double = 0
    var warmth: Double = 0
    var isIdentity: Bool { brightness == 0 && contrast == 0 && saturation == 0 && warmth == 0 }
}

struct ImageBorder: Hashable {
    var enabled: Bool = false
    var width: Double = 0
    var color: RGBA = .white
}

struct ImageProps: Hashable {
    var assetRef: String
    var naturalWidth: Int
    var naturalHeight: Int
    var crop: CropRect
    var rotate90: Int = 0
    var flipH: Bool = false
    var flipV: Bool = false
    var adjust = ImageAdjust()
    var cornerRadius: Double = 0
    var border = ImageBorder()

    init(assetRef: String, naturalWidth: Int, naturalHeight: Int) {
        self.assetRef = assetRef
        self.naturalWidth = naturalWidth
        self.naturalHeight = naturalHeight
        self.crop = CropRect(x: 0, y: 0, width: Double(naturalWidth), height: Double(naturalHeight))
    }

    var boxSize: CGSize {
        rotate90 % 2 == 1 ? CGSize(width: crop.height, height: crop.width)
                          : CGSize(width: crop.width, height: crop.height)
    }

    /// True when crop/rotate/flip differ (the box ↔ natural mapping changed).
    func geometryDiffers(from o: ImageProps) -> Bool {
        crop != o.crop || rotate90 != o.rotate90 || flipH != o.flipH || flipV != o.flipV
    }
}

// MARK: - Shape

enum ShapeKind: String, CaseIterable {
    case rect, roundRect, ellipse, line, arrow
    var isLinear: Bool { self == .line || self == .arrow }
    var displayName: String {
        switch self {
        case .rect: return "Rectangle"
        case .roundRect: return "Rounded"
        case .ellipse: return "Ellipse"
        case .line: return "Line"
        case .arrow: return "Arrow"
        }
    }
}

enum LineJoin: String, CaseIterable { case round, miter }
enum ArrowHeads: String, CaseIterable { case end, start, both }

struct ShapeFill: Hashable {
    var enabled: Bool = true
    var color: RGBA = RGBA(hex: "#3478F6FF")!
}

struct ShapeStroke: Hashable {
    var enabled: Bool = false
    var color: RGBA = .black
    var width: Double = 8
    var join: LineJoin = .miter
}

struct ShapeProps: Hashable {
    var shape: ShapeKind
    var width: Double
    var height: Double
    var fill = ShapeFill()
    var stroke = ShapeStroke()
    var cornerRadius: Double = 24
    var arrowHeads: ArrowHeads = .end
}

// MARK: - Drawing

enum BrushType: String, CaseIterable {
    case pen, marker, highlighter, airbrush, calligraphy, pencil, eraser

    var displayName: String {
        switch self {
        case .pen: return "Pen"
        case .marker: return "Marker"
        case .highlighter: return "Highlighter"
        case .airbrush: return "Airbrush"
        case .calligraphy: return "Calligraphy"
        case .pencil: return "Pencil"
        case .eraser: return "Eraser"
        }
    }
}

struct Stroke: Hashable {
    var brush: BrushType
    var size: Double = 12
    var color: RGBA = .black
    var opacity: Double = 1
    var points: [CGPoint]
    var pressure: [Double]? = nil
}

enum StrokesFileState: Hashable {
    case ok, missing, invalid
}

struct DrawingProps: Hashable {
    var width: Int
    var height: Int
    var strokes: [Stroke] = []
    /// Load state of strokes/<id>.json (not part of project.json).
    var fileState: StrokesFileState = .ok

    static func == (a: DrawingProps, b: DrawingProps) -> Bool {
        a.width == b.width && a.height == b.height && a.strokes == b.strokes
    }
    func hash(into h: inout Hasher) {
        h.combine(width); h.combine(height); h.combine(strokes.count)
    }
}
