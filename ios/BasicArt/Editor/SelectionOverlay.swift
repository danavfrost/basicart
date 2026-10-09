import UIKit

enum HandleKind: Equatable {
    case corner(Int)
    case left, right, top, bottom
    case rotate
}

/// Draws the selection box, handles, snapping guides and creation rubber band.
final class SelectionOverlayView: UIView {
    weak var canvas: CanvasView?
    var guides: [CanvasView.Guide] = [] { didSet { if guides != oldValue { setNeedsDisplay() } } }
    var creationRect: CGRect?
    var creationIsLine = false
    var creationLine: (CGPoint, CGPoint)?
    var rotationReadout: (Double, Bool)? { didSet { setNeedsDisplay() } }

    private let handleRadius: CGFloat = 9
    private let hitRadius: CGFloat = 24
    private let rotateDistance: CGFloat = 34

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = false
        backgroundColor = .clear
        isUserInteractionEnabled = false
        contentMode = .redraw
    }
    required init?(coder: NSCoder) { fatalError() }

    private var accent: UIColor { tintColor ?? .systemBlue }

    struct Geometry {
        var corners: [CGPoint]      // view coords TL, TR, BR, BL
        var sides: [(HandleKind, CGPoint)]
        var rotate: CGPoint
        var rotateBase: CGPoint
    }

    func geometry(for layer: Layer) -> Geometry? {
        guard let canvas else { return nil }
        var size = LayerGeometry.boxSize(of: layer)
        var localRect = CGRect(origin: .zero, size: size)
        if case .text(let t) = layer.content, t.curve != 0 {
            localRect = TextLayoutEngine.shared.layout(t).inkBounds
        }
        if case .shape(let s) = layer.content, s.shape.isLinear {
            // Give lines a usable box height for handles.
            let pad = max(0, 14 / canvas.zoom / CGFloat(layer.transform.scale) - size.height / 2)
            localRect = localRect.insetBy(dx: 0, dy: -pad)
        }
        size = localRect.size
        let t = LayerGeometry.transform(of: layer).concatenating(canvas.viewTransform)
        // Outset the frame a little (in screen points) so handles don't cover the content edges.
        let screenScale = max(0.0001, canvas.zoom * CGFloat(layer.transform.scale))
        let pad = 7 / screenScale
        localRect = localRect.insetBy(dx: -pad, dy: (layer.shape?.shape.isLinear ?? false) ? 0 : -pad)
        let c = [CGPoint(x: localRect.minX, y: localRect.minY), CGPoint(x: localRect.maxX, y: localRect.minY),
                 CGPoint(x: localRect.maxX, y: localRect.maxY), CGPoint(x: localRect.minX, y: localRect.maxY)].map { $0.applying(t) }
        func mid(_ a: CGPoint, _ b: CGPoint) -> CGPoint { CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2) }
        var sides: [(HandleKind, CGPoint)] = []
        switch layer.content {
        case .text:
            sides = [(.left, mid(c[0], c[3])), (.right, mid(c[1], c[2]))]
        case .shape(let s):
            sides = [(.left, mid(c[0], c[3])), (.right, mid(c[1], c[2]))]
            if !s.shape.isLinear { sides += [(.top, mid(c[0], c[1])), (.bottom, mid(c[3], c[2]))] }
        default: break
        }
        let topMid = mid(c[0], c[1])
        let th = layer.transform.radians
        let up = CGVector(dx: sin(th), dy: -cos(th))
        let rot = CGPoint(x: topMid.x + up.dx * rotateDistance, y: topMid.y + up.dy * rotateDistance)
        return Geometry(corners: c, sides: sides, rotate: rot, rotateBase: topMid)
    }

    func handle(at p: CGPoint, for layer: Layer) -> HandleKind? {
        guard let g = geometry(for: layer) else { return nil }
        func near(_ q: CGPoint) -> CGFloat { hypot(q.x - p.x, q.y - p.y) }
        var best: (HandleKind, CGFloat)?
        let showCorners = !(layer.shape?.shape.isLinear ?? false)
        if near(g.rotate) < hitRadius { best = (.rotate, near(g.rotate)) }
        if showCorners {
            for (i, c) in g.corners.enumerated() where near(c) < hitRadius {
                if best == nil || near(c) < best!.1 { best = (.corner(i), near(c)) }
            }
        }
        for (k, q) in g.sides where near(q) < hitRadius {
            if best == nil || near(q) < best!.1 - 4 { best = (k, near(q)) }
        }
        // Tiny boxes: prefer moving when the touch is in the middle.
        return best?.0
    }

    override func draw(_ rect: CGRect) {
        guard let canvas, let ctx = UIGraphicsGetCurrentContext() else { return }
        let model = canvas.model
        // Guides
        if !guides.isEmpty {
            ctx.saveGState()
            ctx.setStrokeColor(UIColor.systemPink.cgColor)
            ctx.setLineWidth(1)
            let cr = canvas.canvasRectInView
            for g in guides {
                if g.vertical {
                    let x = canvas.toView(CGPoint(x: g.position, y: 0)).x
                    ctx.move(to: CGPoint(x: x, y: cr.minY - 2000)); ctx.addLine(to: CGPoint(x: x, y: cr.maxY + 2000))
                } else {
                    let y = canvas.toView(CGPoint(x: 0, y: g.position)).y
                    ctx.move(to: CGPoint(x: cr.minX - 2000, y: y)); ctx.addLine(to: CGPoint(x: cr.maxX + 2000, y: y))
                }
            }
            ctx.strokePath()
            ctx.restoreGState()
        }
        if let r = creationRect {
            ctx.saveGState()
            ctx.setStrokeColor(accent.cgColor)
            ctx.setLineWidth(1.5)
            ctx.setLineDash(phase: 0, lengths: [6, 4])
            if creationIsLine, let (a, b) = creationLine {
                ctx.move(to: canvas.toView(a)); ctx.addLine(to: canvas.toView(b))
                ctx.strokePath()
            } else {
                let a = canvas.toView(r.origin)
                let vr = CGRect(origin: a, size: CGSize(width: r.width * canvas.zoom, height: r.height * canvas.zoom))
                ctx.setFillColor(accent.withAlphaComponent(0.08).cgColor)
                ctx.fill(vr)
                ctx.stroke(vr)
            }
            ctx.restoreGState()
        }
        guard let layer = model.selectedLayer, model.editingTextID != layer.id || true,
              let g = geometry(for: layer) else { return }
        let editing = model.editingTextID == layer.id
        // Outline
        ctx.saveGState()
        ctx.setStrokeColor(accent.cgColor)
        ctx.setLineWidth(layer.locked ? 1 : 1.5)
        if layer.locked || !layer.visible || editing { ctx.setLineDash(phase: 0, lengths: [5, 4]) }
        ctx.addLines(between: g.corners)
        ctx.closePath()
        ctx.strokePath()
        ctx.restoreGState()
        if layer.locked {
            drawBadge(ctx, symbol: "lock.fill", at: g.corners[1])
            return
        }
        if editing || model.tool == .draw { return }
        // Rotate handle
        ctx.saveGState()
        ctx.setStrokeColor(accent.cgColor)
        ctx.setLineWidth(1.5)
        ctx.move(to: g.rotateBase); ctx.addLine(to: g.rotate)
        ctx.strokePath()
        ctx.restoreGState()
        drawHandle(ctx, at: g.rotate, radius: 13, symbol: "arrow.clockwise")
        if !(layer.shape?.shape.isLinear ?? false) {
            for c in g.corners { drawHandle(ctx, at: c, radius: handleRadius) }
        }
        for (k, q) in g.sides {
            let vertical = (k == .left || k == .right)
            drawPill(ctx, at: q, vertical: vertical, angle: CGFloat(layer.transform.radians))
        }
        if let (deg, snapped) = rotationReadout {
            let s = String(format: "%.0f°", deg) as NSString
            let attrs: [NSAttributedString.Key: Any] = [.font: UIFont.monospacedDigitSystemFont(ofSize: 13, weight: .semibold),
                                                        .foregroundColor: UIColor.white]
            let size = s.size(withAttributes: attrs)
            let r = CGRect(x: g.rotate.x - size.width / 2 - 8, y: g.rotate.y - 46, width: size.width + 16, height: 24)
            (snapped ? UIColor.systemPink : UIColor.black.withAlphaComponent(0.75)).setFill()
            UIBezierPath(roundedRect: r, cornerRadius: 12).fill()
            s.draw(at: CGPoint(x: r.minX + 8, y: r.minY + (24 - size.height) / 2), withAttributes: attrs)
        }
    }

    private func drawHandle(_ ctx: CGContext, at p: CGPoint, radius r: CGFloat, symbol: String? = nil) {
        ctx.saveGState()
        ctx.setShadow(offset: CGSize(width: 0, height: 1), blur: 3, color: UIColor.black.withAlphaComponent(0.3).cgColor)
        ctx.setFillColor(UIColor.white.cgColor)
        ctx.fillEllipse(in: CGRect(x: p.x - r, y: p.y - r, width: 2 * r, height: 2 * r))
        ctx.restoreGState()
        ctx.setStrokeColor(accent.cgColor)
        ctx.setLineWidth(2)
        ctx.strokeEllipse(in: CGRect(x: p.x - r, y: p.y - r, width: 2 * r, height: 2 * r))
        if let symbol, let img = UIImage(systemName: symbol, withConfiguration: UIImage.SymbolConfiguration(pointSize: 12, weight: .bold))?
            .withTintColor(accent, renderingMode: .alwaysOriginal) {
            img.draw(at: CGPoint(x: p.x - img.size.width / 2, y: p.y - img.size.height / 2))
        }
    }

    private func drawPill(_ ctx: CGContext, at p: CGPoint, vertical: Bool, angle: CGFloat) {
        ctx.saveGState()
        ctx.translateBy(x: p.x, y: p.y)
        ctx.rotate(by: angle)
        let r = vertical ? CGRect(x: -4, y: -11, width: 8, height: 22) : CGRect(x: -11, y: -4, width: 22, height: 8)
        let path = UIBezierPath(roundedRect: r, cornerRadius: 4)
        ctx.setShadow(offset: CGSize(width: 0, height: 1), blur: 3, color: UIColor.black.withAlphaComponent(0.3).cgColor)
        UIColor.white.setFill()
        path.fill()
        ctx.setShadow(offset: .zero, blur: 0, color: nil)
        accent.setStroke()
        path.lineWidth = 2
        path.stroke()
        ctx.restoreGState()
    }

    private func drawBadge(_ ctx: CGContext, symbol: String, at p: CGPoint) {
        let r: CGFloat = 12
        ctx.setFillColor(accent.cgColor)
        ctx.fillEllipse(in: CGRect(x: p.x - r, y: p.y - r, width: 2 * r, height: 2 * r))
        if let img = UIImage(systemName: symbol, withConfiguration: UIImage.SymbolConfiguration(pointSize: 11, weight: .bold))?
            .withTintColor(.white, renderingMode: .alwaysOriginal) {
            img.draw(at: CGPoint(x: p.x - img.size.width / 2, y: p.y - img.size.height / 2))
        }
    }
}
