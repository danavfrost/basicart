import CoreGraphics
import CoreImage
import CoreText
import Foundation

struct RenderOptions {
    /// Cap for decoded image size (editing proxy). nil = full resolution (export).
    var imageMaxPixel: Int? = nil
    var drawBackground = true
    /// Layers not to draw (e.g. the text layer being edited on canvas).
    var hiddenLayerIDs: Set<String> = []
}

/// The one renderer used for the screen, thumbnails and export. Draws the
/// document model in canvas coordinates (y down) into any CGContext.
enum Renderer {

    // MARK: Entry points

    /// `ctx` must map canvas coordinates (y down) to device space.
    static func draw(_ doc: Document, in ctx: CGContext, assets: AssetProvider?, options: RenderOptions = RenderOptions()) {
        var doc = doc
        doc.syncTextLimits()
        let canvasRect = CGRect(x: 0, y: 0, width: doc.canvas.width, height: doc.canvas.height)
        ctx.saveGState()
        ctx.clip(to: canvasRect)
        ctx.setAllowsAntialiasing(true)
        ctx.setShouldAntialias(true)
        ctx.interpolationQuality = .high
        if options.drawBackground && doc.canvas.background.a > 0 {
            ctx.setFillColor(doc.canvas.background.cgColor)
            ctx.fill(canvasRect)
        }
        for layer in doc.layers where layer.visible && !options.hiddenLayerIDs.contains(layer.id) {
            drawLayer(layer, in: ctx, assets: assets, options: options)
        }
        ctx.restoreGState()
    }

    /// Renders the canvas into a new bitmap at `scale` (1 = canvas pixels).
    static func renderImage(_ doc: Document, scale: CGFloat, assets: AssetProvider?, options: RenderOptions = RenderOptions(), opaqueBackground: RGBA? = nil) -> CGImage? {
        let w = max(1, Int((CGFloat(doc.canvas.width) * scale).rounded()))
        let h = max(1, Int((CGFloat(doc.canvas.height) * scale).rounded()))
        return renderImage(doc, pixelWidth: w, pixelHeight: h, assets: assets, options: options, opaqueBackground: opaqueBackground)
    }

    static func renderImage(_ doc: Document, pixelWidth w: Int, pixelHeight h: Int, assets: AssetProvider?, options: RenderOptions = RenderOptions(), opaqueBackground: RGBA? = nil) -> CGImage? {
        guard let ctx = makeContext(width: w, height: h) else { return nil }
        if let bg = opaqueBackground {
            ctx.setFillColor(bg.withAlpha(1).cgColor)
            ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
        }
        ctx.translateBy(x: 0, y: CGFloat(h))
        ctx.scaleBy(x: 1, y: -1)
        ctx.scaleBy(x: CGFloat(w) / CGFloat(doc.canvas.width), y: CGFloat(h) / CGFloat(doc.canvas.height))
        draw(doc, in: ctx, assets: assets, options: options)
        return ctx.makeImage()
    }

    static func makeContext(width: Int, height: Int) -> CGContext? {
        CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                  space: RGBA.sRGB, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
    }

    // MARK: Layers

    static func drawLayer(_ layer: Layer, in ctx: CGContext, assets: AssetProvider?, options: RenderOptions) {
        let size = LayerGeometry.boxSize(of: layer)
        guard size.width > 0, size.height > 0, layer.opacity > 0 else { return }
        ctx.saveGState()
        ctx.concatenate(layer.transform.affine(boxSize: size))
        ctx.setAlpha(CGFloat(layer.opacity))
        let bounds = contentBounds(layer, size: size)
        if case .drawing = layer.content { ctx.clip(to: CGRect(origin: .zero, size: size)) }
        ctx.beginTransparencyLayer(in: bounds, auxiliaryInfo: nil)
        ctx.setAlpha(1)
        switch layer.content {
        case .image(let p): drawImage(p, size: size, in: ctx, assets: assets, options: options)
        case .shape(let p): drawShape(p, size: size, in: ctx)
        case .drawing(let p): drawDrawing(p, in: ctx)
        case .text(let t): drawText(t, in: ctx)
        }
        // Eraser mask (§9.1): destination-out inside the isolated group, not clipped.
        if layer.type != .drawing {
            for s in layer.mask { drawStroke(s, in: ctx) }
        }
        ctx.endTransparencyLayer()
        ctx.restoreGState()
    }

    /// Local-space bounds of everything a layer can paint (for transparency layers).
    static func contentBounds(_ layer: Layer, size: CGSize) -> CGRect {
        let box = CGRect(origin: .zero, size: size)
        switch layer.content {
        case .image: return box.insetBy(dx: -2, dy: -2)
        case .drawing: return box
        case .shape(let p):
            let pad = max(p.stroke.width, 1) / 2 * 4 + 2 + (p.shape == .arrow ? max(12, 4 * p.stroke.width) : 0)
            return box.insetBy(dx: -pad, dy: -pad)
        case .text(let t):
            return textEffectBounds(t, layout: TextLayoutEngine.shared.layout(t))
        }
    }

    // MARK: Image

    static func imageContentTransform(_ p: ImageProps) -> CGAffineTransform {
        // crop space (0..cw, 0..ch) → box space, rotate90 clockwise then flips.
        let cw = p.crop.width, ch = p.crop.height
        var t: CGAffineTransform
        switch p.rotate90 % 4 {
        case 1: t = CGAffineTransform(a: 0, b: 1, c: -1, d: 0, tx: ch, ty: 0)      // (x,y) → (ch − y, x)
        case 2: t = CGAffineTransform(a: -1, b: 0, c: 0, d: -1, tx: cw, ty: ch)    // (cw − x, ch − y)
        case 3: t = CGAffineTransform(a: 0, b: -1, c: 1, d: 0, tx: 0, ty: cw)      // (y, cw − x)
        default: t = .identity
        }
        let box = p.boxSize
        if p.flipH { t = t.concatenating(CGAffineTransform(a: -1, b: 0, c: 0, d: 1, tx: box.width, ty: 0)) }
        if p.flipV { t = t.concatenating(CGAffineTransform(a: 1, b: 0, c: 0, d: -1, tx: 0, ty: box.height)) }
        return t
    }

    /// Natural (oriented) pixels → displayed box space.
    static func boxFromNatural(_ p: ImageProps) -> CGAffineTransform {
        CGAffineTransform(translationX: -p.crop.x, y: -p.crop.y).concatenating(imageContentTransform(p))
    }

    /// Remaps an image layer's mask from the old box geometry to the new one (§9.1).
    static func remapImageMask(_ mask: [Stroke], from old: ImageProps, to new: ImageProps) -> [Stroke] {
        let t = boxFromNatural(old).inverted().concatenating(boxFromNatural(new))
        return mask.map { s in var s = s; s.points = s.points.map { $0.applying(t) }; return s }
    }

    static func drawImage(_ p: ImageProps, size: CGSize, in ctx: CGContext, assets: AssetProvider?, options: RenderOptions) {
        let box = CGRect(origin: .zero, size: size)
        let radius = min(CGFloat(p.cornerRadius), min(size.width, size.height) / 2)
        let clipPath = CGPath(roundedRect: box, cornerWidth: radius, cornerHeight: radius, transform: nil)
        ctx.saveGState()
        ctx.addPath(clipPath)
        ctx.clip()
        if let img = assets?.adjustedImage(p.assetRef, maxPixel: options.imageMaxPixel, adjust: p.adjust) {
            ctx.concatenate(imageContentTransform(p))
            // Image pixels → natural px (proxies are uniformly scaled).
            let sx = CGFloat(p.naturalWidth) / CGFloat(img.width)
            let sy = CGFloat(p.naturalHeight) / CGFloat(img.height)
            let rect = CGRect(x: -p.crop.x, y: -p.crop.y, width: CGFloat(img.width) * sx, height: CGFloat(img.height) * sy)
            ctx.clip(to: CGRect(x: 0, y: 0, width: p.crop.width, height: p.crop.height))
            ctx.interpolationQuality = .high
            drawUpright(img, in: rect, ctx: ctx)
        } else {
            // Missing asset placeholder (§10.3).
            ctx.setFillColor(RGBA(r: 0xD0, g: 0xD0, b: 0xD0).cgColor)
            ctx.fill(box)
            ctx.setStrokeColor(RGBA(r: 0x9E, g: 0x9E, b: 0x9E).cgColor)
            ctx.setLineWidth(max(1, min(size.width, size.height) * 0.01))
            ctx.move(to: .zero); ctx.addLine(to: CGPoint(x: size.width, y: size.height))
            ctx.move(to: CGPoint(x: size.width, y: 0)); ctx.addLine(to: CGPoint(x: 0, y: size.height))
            ctx.strokePath()
        }
        ctx.restoreGState()
        if p.border.enabled && p.border.width > 0 {
            let bw = CGFloat(p.border.width)
            let inset = box.insetBy(dx: bw / 2, dy: bw / 2)
            if inset.width > 0, inset.height > 0 {
                let r = max(0, min(radius - bw / 2, min(inset.width, inset.height) / 2))
                ctx.addPath(CGPath(roundedRect: inset, cornerWidth: r, cornerHeight: r, transform: nil))
                ctx.setStrokeColor(p.border.color.cgColor)
                ctx.setLineWidth(bw)
                ctx.strokePath()
            }
        }
    }

    /// Draws a CGImage upright in a y-down user space.
    static func drawUpright(_ img: CGImage, in rect: CGRect, ctx: CGContext) {
        ctx.saveGState()
        ctx.translateBy(x: rect.minX, y: rect.maxY)
        ctx.scaleBy(x: 1, y: -1)
        ctx.draw(img, in: CGRect(origin: .zero, size: rect.size))
        ctx.restoreGState()
    }

    // MARK: Shape

    static func shapePath(_ p: ShapeProps) -> CGPath? {
        let w = CGFloat(p.width), h = CGFloat(p.height)
        switch p.shape {
        case .rect: return CGPath(rect: CGRect(x: 0, y: 0, width: w, height: h), transform: nil)
        case .roundRect:
            let r = min(CGFloat(p.cornerRadius), min(w, h) / 2)
            return CGPath(roundedRect: CGRect(x: 0, y: 0, width: w, height: h), cornerWidth: r, cornerHeight: r, transform: nil)
        case .ellipse: return CGPath(ellipseIn: CGRect(x: 0, y: 0, width: w, height: h), transform: nil)
        case .line, .arrow: return nil
        }
    }

    static func drawShape(_ p: ShapeProps, size: CGSize, in ctx: CGContext) {
        let sw = CGFloat(p.stroke.width)
        ctx.setLineJoin(p.stroke.join == .round ? .round : .miter)
        ctx.setMiterLimit(4)
        if let path = shapePath(p) {
            if p.fill.enabled {
                ctx.addPath(path)
                ctx.setFillColor(p.fill.color.cgColor)
                ctx.fillPath()
            }
            if p.stroke.enabled && sw > 0 {
                ctx.addPath(path)
                ctx.setStrokeColor(p.stroke.color.cgColor)
                ctx.setLineWidth(sw)
                ctx.strokePath()
            }
            return
        }
        let w = CGFloat(p.width), h = size.height
        let y = h / 2
        ctx.setStrokeColor(p.stroke.color.cgColor)
        ctx.setFillColor(p.stroke.color.cgColor)
        ctx.setLineWidth(max(sw, 0.0001))
        if p.shape == .line {
            ctx.setLineCap(.round)
            ctx.move(to: CGPoint(x: 0, y: y)); ctx.addLine(to: CGPoint(x: w, y: y))
            if sw > 0 { ctx.strokePath() }
            return
        }
        let both = p.arrowHeads == .both
        let atStart = p.arrowHeads != .end, atEnd = p.arrowHeads != .start
        let headLen = min(max(12, 4 * sw), both ? w / 2 : w)
        let headW = 0.9 * headLen
        let x0 = atStart ? headLen : 0, x1 = atEnd ? w - headLen : w
        ctx.setLineCap(.butt)
        if x1 > x0 && sw > 0 {
            ctx.move(to: CGPoint(x: x0, y: y)); ctx.addLine(to: CGPoint(x: x1, y: y))
            ctx.strokePath()
        }
        if atEnd {
            ctx.move(to: CGPoint(x: w, y: y))
            ctx.addLine(to: CGPoint(x: w - headLen, y: y - headW / 2))
            ctx.addLine(to: CGPoint(x: w - headLen, y: y + headW / 2))
            ctx.closePath(); ctx.fillPath()
        }
        if atStart {
            ctx.move(to: CGPoint(x: 0, y: y))
            ctx.addLine(to: CGPoint(x: headLen, y: y - headW / 2))
            ctx.addLine(to: CGPoint(x: headLen, y: y + headW / 2))
            ctx.closePath(); ctx.fillPath()
        }
    }

    // MARK: Drawing (strokes)

    static func drawDrawing(_ p: DrawingProps, in ctx: CGContext) {
        for s in p.strokes { drawStroke(s, in: ctx) }
    }

    static func effectiveAlpha(_ s: Stroke) -> CGFloat {
        let o = CGFloat(s.opacity)
        switch s.brush {
        case .marker, .pencil: return o * 0.85
        case .highlighter: return o * 0.40
        default: return o
        }
    }

    /// Draws one stroke as an isolated unit (no self-overlap darkening).
    static func drawStroke(_ s: Stroke, in ctx: CGContext) {
        guard !s.points.isEmpty else { return }
        ctx.saveGState()
        ctx.setAlpha(effectiveAlpha(s))
        if s.brush == .eraser { ctx.setBlendMode(.destinationOut) }
        let color = s.brush == .eraser ? RGBA.black.cgColor : s.color.withAlpha(1).cgColor
        let bounds = strokeBounds(s)
        if s.brush == .airbrush {
            let sigma = CGFloat(s.size) * 0.25
            Blur.drawBlurred(in: ctx, localRect: bounds, sigma: sigma, key: nil) { c in
                strokeGeometry(s, color: color, in: c)
            }
        } else {
            ctx.beginTransparencyLayer(in: bounds, auxiliaryInfo: nil)
            strokeGeometry(s, color: color, in: ctx)
            if s.brush == .pencil { applyPencilGrain(in: ctx, bounds: bounds) }
            ctx.endTransparencyLayer()
        }
        ctx.restoreGState()
    }

    static func strokeBounds(_ s: Stroke) -> CGRect {
        var r = CGRect.null
        for p in s.points { r = r.union(CGRect(origin: p, size: .zero)) }
        let pad = CGFloat(s.size) * (s.brush == .airbrush ? 1.5 : 1) + 2
        return r.insetBy(dx: -pad, dy: -pad)
    }

    static func strokeWidth(_ s: Stroke) -> CGFloat {
        switch s.brush {
        case .airbrush: return CGFloat(s.size) * 0.5
        case .pencil: return CGFloat(s.size) * 0.6
        default: return CGFloat(s.size)
        }
    }

    // MARK: Pencil grain (§9.2, bit-exact)

    /// The 32×32 grain alpha values, row-major (x = i % 32, y = i / 32).
    static let pencilGrain: [UInt8] = {
        var seed: UInt64 = 1
        var out = [UInt8](repeating: 0, count: 1024)
        for i in 0..<1024 {
            seed = (seed &* 1103515245 &+ 12345) % (1 << 31)
            let v = Int((seed >> 16) & 0xFF)
            out[i] = UInt8((11475 + 55 * v) / 100)
        }
        return out
    }()

    /// White tile with alpha = G, rows stored bottom-up so that drawing it in a y-down
    /// context puts row 0 at local y = 0.
    static let pencilGrainTile: CGImage = {
        var px = [UInt8](repeating: 0, count: 32 * 32 * 4)
        for y in 0..<32 {
            for x in 0..<32 {
                let a = pencilGrain[y * 32 + x]
                let o = ((31 - y) * 32 + x) * 4
                px[o] = a; px[o + 1] = a; px[o + 2] = a; px[o + 3] = a // premultiplied white
            }
        }
        let provider = CGDataProvider(data: Data(px) as CFData)!
        return CGImage(width: 32, height: 32, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: 128, space: RGBA.sRGB,
                       bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)!
    }()

    /// Multiplies the current (stroke) buffer's alpha by the grain, tiled from local (0,0).
    static func applyPencilGrain(in ctx: CGContext, bounds: CGRect) {
        ctx.saveGState()
        ctx.setBlendMode(.destinationIn)
        ctx.interpolationQuality = .none
        ctx.clip(to: bounds)
        ctx.draw(pencilGrainTile, in: CGRect(x: 0, y: 0, width: 32, height: 32), byTiling: true)
        ctx.restoreGState()
    }

    /// The path rule: moveTo p0; quadTo(p_i, mid(p_i, p_i+1)); lineTo p_n−1.
    static func strokePath(_ pts: [CGPoint]) -> CGPath {
        let path = CGMutablePath()
        path.move(to: pts[0])
        if pts.count > 2 {
            for i in 1...(pts.count - 2) {
                let mid = CGPoint(x: (pts[i].x + pts[i + 1].x) / 2, y: (pts[i].y + pts[i + 1].y) / 2)
                path.addQuadCurve(to: mid, control: pts[i])
            }
        }
        if pts.count > 1 { path.addLine(to: pts[pts.count - 1]) }
        return path
    }

    /// Flattens the path rule into a polyline with per-vertex pressure.
    static func flatten(_ pts: [CGPoint], pressure: [Double]?) -> [(CGPoint, CGFloat)] {
        func pr(_ i: Int) -> CGFloat { CGFloat(pressure?[i] ?? 1) }
        var out: [(CGPoint, CGFloat)] = [(pts[0], pr(0))]
        guard pts.count > 1 else { return out }
        var cur = pts[0], curP = pr(0)
        if pts.count > 2 {
            for i in 1...(pts.count - 2) {
                let mid = CGPoint(x: (pts[i].x + pts[i + 1].x) / 2, y: (pts[i].y + pts[i + 1].y) / 2)
                let midP = (pr(i) + pr(i + 1)) / 2
                let len = hypot(pts[i].x - cur.x, pts[i].y - cur.y) + hypot(mid.x - pts[i].x, mid.y - pts[i].y)
                let steps = max(2, min(24, Int(len / 3)))
                for k in 1...steps {
                    let t = CGFloat(k) / CGFloat(steps)
                    let a = (1 - t) * (1 - t), b = 2 * (1 - t) * t, c = t * t
                    let p = CGPoint(x: a * cur.x + b * pts[i].x + c * mid.x, y: a * cur.y + b * pts[i].y + c * mid.y)
                    let pp = t < 0.5 ? curP + (pr(i) - curP) * (t * 2) : pr(i) + (midP - pr(i)) * ((t - 0.5) * 2)
                    out.append((p, pp))
                }
                cur = mid; curP = midP
            }
        }
        out.append((pts[pts.count - 1], pr(pts.count - 1)))
        return out
    }

    static func strokeGeometry(_ s: Stroke, color: CGColor, in ctx: CGContext) {
        let width = strokeWidth(s)
        ctx.setFillColor(color)
        ctx.setStrokeColor(color)
        if s.points.count == 1 {
            let p = s.points[0]
            let w = width * (s.pressure.map { 0.25 + 0.75 * CGFloat($0[0]) } ?? 1)
            ctx.fillEllipse(in: CGRect(x: p.x - w / 2, y: p.y - w / 2, width: w, height: w))
            return
        }
        if s.brush == .calligraphy {
            let poly = flatten(s.points, pressure: s.pressure)
            let nib = CGVector(dx: cos(CGFloat.pi / 4), dy: sin(CGFloat.pi / 4))
            for i in 1..<poly.count {
                let a = poly[i - 1].0, b = poly[i].0
                let dx = b.x - a.x, dy = b.y - a.y
                let len = hypot(dx, dy)
                guard len > 0.0001 else { continue }
                let beta = atan2(dy, dx)
                let pw = s.pressure == nil ? 1 : (0.25 + 0.75 * (poly[i - 1].1 + poly[i].1) / 2)
                let size = CGFloat(s.size) * pw
                let sinv = abs(sin(beta - .pi / 4))
                if sinv >= 0.15 {
                    let h = CGVector(dx: nib.dx * size / 2, dy: nib.dy * size / 2)
                    ctx.move(to: CGPoint(x: a.x - h.dx, y: a.y - h.dy))
                    ctx.addLine(to: CGPoint(x: a.x + h.dx, y: a.y + h.dy))
                    ctx.addLine(to: CGPoint(x: b.x + h.dx, y: b.y + h.dy))
                    ctx.addLine(to: CGPoint(x: b.x - h.dx, y: b.y - h.dy))
                    ctx.closePath()
                } else {
                    let hw = size * 0.15 / 2
                    let nx = -dy / len * hw, ny = dx / len * hw
                    ctx.move(to: CGPoint(x: a.x + nx, y: a.y + ny))
                    ctx.addLine(to: CGPoint(x: b.x + nx, y: b.y + ny))
                    ctx.addLine(to: CGPoint(x: b.x - nx, y: b.y - ny))
                    ctx.addLine(to: CGPoint(x: a.x - nx, y: a.y - ny))
                    ctx.closePath()
                }
            }
            ctx.fillPath(using: .winding)
            return
        }
        let cap: CGLineCap
        switch s.brush {
        case .marker: cap = .square
        case .highlighter: cap = .butt
        default: cap = .round
        }
        ctx.setLineCap(cap)
        ctx.setLineJoin(.round)
        if let pressure = s.pressure, pressure.count == s.points.count {
            let poly = flatten(s.points, pressure: pressure)
            ctx.setLineCap(.round)
            for i in 1..<poly.count {
                let w = width * (0.25 + 0.75 * (poly[i - 1].1 + poly[i].1) / 2)
                ctx.setLineWidth(w)
                ctx.move(to: poly[i - 1].0)
                ctx.addLine(to: poly[i].0)
                ctx.strokePath()
            }
            return
        }
        ctx.setLineWidth(width)
        ctx.addPath(strokePath(s.points))
        ctx.strokePath()
    }

    // MARK: Text

    static func textEffectBounds(_ t: TextProps, layout: TextLayoutResult) -> CGRect {
        let fs = CGFloat(t.fontSize)
        var r = layout.inkBounds.union(CGRect(origin: .zero, size: layout.size))
        let synth = layout.groups.map(\.boldStroke).max() ?? 0
        var pad: CGFloat = 0.06 * fs + synth
        if t.outline.enabled {
            switch t.outline.style {
            case .solid: pad = max(pad, CGFloat(t.outline.width) * fs + 0.04 * fs)
            case .double: pad = max(pad, CGFloat(t.outline.width + t.outline.width2) * fs + 0.04 * fs)
            case .glow: pad = max(pad, CGFloat(t.outline.width) * fs + 1.5 * CGFloat(t.outline.glowRadius) * fs + 0.04 * fs)
            }
        }
        r = r.insetBy(dx: -pad, dy: -pad)
        if t.shadow.enabled {
            let blurPad = 1.5 * CGFloat(t.shadow.blur) * fs
            let sh = r.offsetBy(dx: CGFloat(t.shadow.offsetX) * fs, dy: CGFloat(t.shadow.offsetY) * fs).insetBy(dx: -blurPad, dy: -blurPad)
            r = r.union(sh)
        }
        if t.backgroundBox.enabled && t.curve == 0 {
            let p = CGFloat(t.backgroundBox.padding) * fs
            r = r.union(CGRect(origin: .zero, size: layout.size).insetBy(dx: -p, dy: -p))
        }
        if t.skew != 0 {
            let k = tan(CGFloat(t.skew) * .pi / 180)
            let h = layout.size.height
            let sk = CGAffineTransform(a: 1, b: 0, c: -k, d: 1, tx: k * h / 2, ty: 0)
            r = r.applying(sk)
        }
        return r.insetBy(dx: -2, dy: -2)
    }

    static func drawText(_ t: TextProps, in ctx: CGContext) {
        let layout = TextLayoutEngine.shared.layout(t)
        let fs = CGFloat(t.fontSize)
        let size = layout.size
        ctx.saveGState()
        if t.skew != 0 {
            let k = tan(CGFloat(t.skew) * .pi / 180)
            ctx.concatenate(CGAffineTransform(a: 1, b: 0, c: -k, d: 1, tx: k * size.height / 2, ty: 0))
        }
        // 1. Background box
        if t.backgroundBox.enabled && t.curve == 0 {
            let p = CGFloat(t.backgroundBox.padding) * fs
            let rect = CGRect(origin: .zero, size: size).insetBy(dx: -p, dy: -p)
            let r = min(CGFloat(t.backgroundBox.cornerRadius) * fs, min(rect.width, rect.height) / 2)
            ctx.addPath(CGPath(roundedRect: rect, cornerWidth: r, cornerHeight: r, transform: nil))
            ctx.setFillColor(t.backgroundBox.color.cgColor)
            ctx.fillPath()
        }
        let bodyBounds = textEffectBounds(TextProps(text: t.text, fontId: t.fontId, fontSize: t.fontSize, weight: t.weight,
                                                    outline: t.outline), layout: layout)
        // 2. Shadow: silhouette of steps 3–5, tinted, blurred, offset.
        if t.shadow.enabled && t.shadow.color.a > 0 {
            let sigma = CGFloat(t.shadow.blur) * fs / 2
            ctx.saveGState()
            ctx.translateBy(x: CGFloat(t.shadow.offsetX) * fs, y: CGFloat(t.shadow.offsetY) * fs)
            let color = t.shadow.color.cgColor
            let draw: (CGContext) -> Void = { c in
                c.beginTransparencyLayer(auxiliaryInfo: nil)
                drawTextBody(t, layout: layout, in: c)
                c.setBlendMode(.sourceIn)
                c.setFillColor(color)
                c.fill(bodyBounds)
                c.endTransparencyLayer()
            }
            if sigma < 0.01 {
                ctx.beginTransparencyLayer(in: bodyBounds, auxiliaryInfo: nil)
                draw(ctx)
                ctx.endTransparencyLayer()
            } else {
                var shadowKeyProps = t
                shadowKeyProps.backgroundBox = TextBackgroundBox()
                shadowKeyProps.skew = 0
                Blur.drawBlurred(in: ctx, localRect: bodyBounds, sigma: sigma, key: BlurKey(props: shadowKeyProps, kind: 1), draw: draw)
            }
            ctx.restoreGState()
        }
        // 3–5
        drawTextBody(t, layout: layout, in: ctx, bodyBounds: bodyBounds)
        ctx.restoreGState()
    }

    /// Steps 3–5 of §7.8 (outer ring / glow, outline ring, fill). Effects are in em of the
    /// layer fontSize; synthesized bold uses each run's own size; span colours override the fill.
    static func drawTextBody(_ t: TextProps, layout: TextLayoutResult, in ctx: CGContext, bodyBounds: CGRect? = nil) {
        let fs = CGFloat(t.fontSize)
        let groups = layout.groups
        func strokeShape(in c: CGContext, width: CGFloat, color: CGColor, join: LineJoin) {
            guard width > 0 else { return }
            c.setStrokeColor(color)
            c.setLineJoin(join == .round ? .round : .miter)
            c.setMiterLimit(4)
            c.setLineCap(.butt)
            for g in groups {
                c.setLineWidth(width + g.boldStroke)
                c.addPath(g.path)
                c.strokePath()
            }
        }
        func fillGroup(_ g: TextShapeGroup, in c: CGContext, color: CGColor) {
            c.setFillColor(color)
            c.addPath(g.path)
            c.fillPath()
            if g.boldStroke > 0 {
                c.setStrokeColor(color)
                c.setLineJoin(.round)
                c.setLineWidth(g.boldStroke)
                c.addPath(g.path)
                c.strokePath()
            }
        }
        let o = t.outline
        let bounds = bodyBounds ?? textEffectBounds(t, layout: layout)
        if o.enabled {
            switch o.style {
            case .double:
                strokeShape(in: ctx, width: 2 * CGFloat(o.width + o.width2) * fs, color: o.color2.cgColor, join: o.join)
                strokeShape(in: ctx, width: 2 * CGFloat(o.width) * fs, color: o.color.cgColor, join: o.join)
            case .solid:
                strokeShape(in: ctx, width: 2 * CGFloat(o.width) * fs, color: o.color.cgColor, join: o.join)
            case .glow:
                let sigma = CGFloat(o.glowRadius) * fs / 2
                var key = t
                key.fill = TextFill(); key.shadow = TextShadow(); key.backgroundBox = TextBackgroundBox(); key.skew = 0
                key.spans = key.spans.map { var s = $0; s.style.color = nil; return s }
                Blur.drawBlurred(in: ctx, localRect: bounds, sigma: sigma, key: sigma > 0.01 ? BlurKey(props: key, kind: 2) : nil) { c in
                    for g in groups { fillGroup(g, in: c, color: o.color.cgColor) }
                    strokeShape(in: c, width: 2 * CGFloat(o.width) * fs, color: o.color.cgColor, join: o.join)
                }
            }
        }
        // 5. Fill: layer fill for uncoloured runs, span colours for the rest.
        let plain = groups.filter { $0.color == nil }
        if !plain.isEmpty {
            switch t.fill.type {
            case .solid:
                for g in plain { fillGroup(g, in: ctx, color: t.fill.color.cgColor) }
            case .linear, .radial:
                ctx.beginTransparencyLayer(in: bounds, auxiliaryInfo: nil)
                for g in plain { fillGroup(g, in: ctx, color: RGBA.black.cgColor) }
                ctx.setBlendMode(.sourceIn)
                drawGradient(t.fill, size: layout.size, in: ctx, cover: bounds)
                ctx.endTransparencyLayer()
            }
        }
        for g in groups { if let c = g.color { fillGroup(g, in: ctx, color: c.cgColor) } }
        // Bitmap glyphs (emoji) draw their own colours.
        if layout.hasBitmapGlyphs {
            for g in layout.glyphs where g.path == nil {
                ctx.saveGState()
                ctx.concatenate(g.transform)
                var glyph = g.glyph
                var pos = CGPoint.zero
                CTFontDrawGlyphs(g.font, &glyph, &pos, 1, ctx)
                ctx.restoreGState()
            }
        }
    }

    static func makeGradient(_ stops: [GradientStop]) -> CGGradient? {
        let sorted = stops.sorted { $0.offset < $1.offset }
        let colors = sorted.map { $0.color.cgColor } as CFArray
        var locs = sorted.map { CGFloat($0.offset) }
        return CGGradient(colorsSpace: RGBA.sRGB, colors: colors, locations: &locs)
    }

    static func drawGradient(_ f: TextFill, size: CGSize, in ctx: CGContext, cover: CGRect) {
        guard let g = makeGradient(f.stops) else { return }
        let w = size.width, h = size.height
        ctx.saveGState()
        ctx.clip(to: cover)
        if f.type == .linear {
            let a = CGFloat(f.angle) * .pi / 180
            let d = CGVector(dx: cos(a), dy: sin(a))
            let L = abs(w * cos(a)) + abs(h * sin(a))
            let c = CGPoint(x: w / 2, y: h / 2)
            ctx.drawLinearGradient(g, start: CGPoint(x: c.x - d.dx * L / 2, y: c.y - d.dy * L / 2),
                                   end: CGPoint(x: c.x + d.dx * L / 2, y: c.y + d.dy * L / 2),
                                   options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
        } else {
            let c = CGPoint(x: w / 2, y: h / 2)
            ctx.drawRadialGradient(g, startCenter: c, startRadius: 0, endCenter: c, endRadius: sqrt(w * w + h * h) / 2,
                                   options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
        }
        ctx.restoreGState()
    }
}

// MARK: - Blur

struct BlurKey: Hashable {
    var props: TextProps
    var kind: Int
}

enum Blur {
    static let ciContext = ImageAdjuster.ciContext
    private static var cache: [String: (CGImage, CGRect)] = [:]
    private static var order: [String] = []
    private static let lock = NSLock()

    /// Rasterizes `draw` (in local space) at device resolution, applies a Gaussian
    /// blur with σ = `sigma` local units, and composites the result into ctx.
    static func drawBlurred(in ctx: CGContext, localRect: CGRect, sigma: CGFloat, key: BlurKey?, draw: (CGContext) -> Void) {
        let m = ctx.ctm
        var scale = sqrt(abs(m.a * m.d - m.b * m.c))
        if scale <= 0 || !scale.isFinite { scale = 1 }
        let pad = sigma * 3
        var rect = localRect.insetBy(dx: -pad, dy: -pad).integral
        // Limit to the visible clip to keep big zooms cheap.
        let clip = ctx.boundingBoxOfClipPath
        if !clip.isNull && !clip.isInfinite && clip.width < 1e8 {
            let vis = clip.insetBy(dx: -pad, dy: -pad)
            if key == nil { rect = rect.intersection(vis) }
        }
        guard !rect.isNull, rect.width > 0, rect.height > 0 else { return }
        let maxSide: CGFloat = 4096
        scale = min(scale, maxSide / max(rect.width, rect.height))
        let qScale = (scale * 8).rounded(.up) / 8
        let cacheKey: String? = key.map { "\($0.hashValue)|\(qScale)|\(rect.minX),\(rect.minY),\(rect.width),\(rect.height)|\(sigma)" }
        if let ck = cacheKey {
            lock.lock()
            let hit = cache[ck]
            lock.unlock()
            if let (img, r) = hit {
                Renderer.drawUpright(img, in: r, ctx: ctx)
                return
            }
        }
        let pw = max(1, Int((rect.width * qScale).rounded(.up)))
        let ph = max(1, Int((rect.height * qScale).rounded(.up)))
        guard let off = Renderer.makeContext(width: pw, height: ph) else { return }
        off.translateBy(x: 0, y: CGFloat(ph))
        off.scaleBy(x: 1, y: -1)
        off.scaleBy(x: CGFloat(pw) / rect.width, y: CGFloat(ph) / rect.height)
        off.translateBy(x: -rect.minX, y: -rect.minY)
        draw(off)
        guard let raw = off.makeImage() else { return }
        var result = raw
        let pxSigma = sigma * CGFloat(pw) / rect.width
        if pxSigma > 0.05 {
            let ci = CIImage(cgImage: raw)
            let blurred = ci.clampedToExtent().applyingGaussianBlur(sigma: Double(pxSigma)).cropped(to: ci.extent)
            // Clamp-to-extent would smear edges; the padding (3σ) keeps edges transparent.
            if let out = ciContext.createCGImage(blurred, from: ci.extent, format: .RGBA8, colorSpace: RGBA.sRGB) {
                result = out
            }
        }
        Renderer.drawUpright(result, in: rect, ctx: ctx)
        if let ck = cacheKey {
            lock.lock()
            cache[ck] = (result, rect)
            order.append(ck)
            if order.count > 48 { cache[order.removeFirst()] = nil }
            lock.unlock()
        }
    }

    static func clearCache() {
        lock.lock(); cache.removeAll(); order.removeAll(); lock.unlock()
    }
}
