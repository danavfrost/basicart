import SwiftUI

/// Crop in the displayed orientation; stored in natural (oriented, unrotated) space (§6).
struct CropSheet: View {
    @ObservedObject var model: EditorModel
    let layerID: String
    @Environment(\.dismiss) private var dismiss
    @State private var rect: CGRect = .zero          // displayed-image px
    @State private var full: CGSize = .zero          // displayed full image size
    @State private var preview: UIImage?
    @State private var aspect: Double? = nil
    @State private var dragStart: CGRect?

    private let aspects: [(String, Double?)] = [("Freeform", nil), ("1:1", 1), ("4:3", 4.0 / 3), ("3:4", 3.0 / 4), ("16:9", 16.0 / 9), ("9:16", 9.0 / 16)]

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                GeometryReader { geo in
                    let fit = full.width > 0 ? min(geo.size.width / full.width, geo.size.height / full.height) : 1
                    let shown = CGSize(width: full.width * fit, height: full.height * fit)
                    let origin = CGPoint(x: (geo.size.width - shown.width) / 2, y: (geo.size.height - shown.height) / 2)
                    let vr = CGRect(x: origin.x + rect.minX * fit, y: origin.y + rect.minY * fit, width: rect.width * fit, height: rect.height * fit)
                    ZStack(alignment: .topLeading) {
                        if let preview {
                            Image(uiImage: preview).resizable()
                                .frame(width: shown.width, height: shown.height)
                                .background(Checkerboard(cell: 8))
                                .offset(x: origin.x, y: origin.y)
                        }
                        // Dim outside the crop.
                        Path { p in
                            p.addRect(CGRect(origin: origin, size: shown))
                            p.addRect(vr)
                        }
                        .fill(Color.black.opacity(0.55), style: FillStyle(eoFill: true))
                        .allowsHitTesting(false)
                        // Thirds grid + border
                        Path { p in
                            for i in 1...2 {
                                let x = vr.minX + vr.width * CGFloat(i) / 3, y = vr.minY + vr.height * CGFloat(i) / 3
                                p.move(to: CGPoint(x: x, y: vr.minY)); p.addLine(to: CGPoint(x: x, y: vr.maxY))
                                p.move(to: CGPoint(x: vr.minX, y: y)); p.addLine(to: CGPoint(x: vr.maxX, y: y))
                            }
                        }
                        .stroke(Color.white.opacity(0.45), lineWidth: 0.5)
                        .allowsHitTesting(false)
                        Rectangle().stroke(Color.white, lineWidth: 2)
                            .frame(width: vr.width, height: vr.height)
                            .offset(x: vr.minX, y: vr.minY)
                            .contentShape(Rectangle())
                            .gesture(DragGesture().onChanged { g in
                                if dragStart == nil { dragStart = rect }
                                var r = dragStart!
                                r.origin.x = (r.minX + g.translation.width / fit).clamped(0, full.width - r.width)
                                r.origin.y = (r.minY + g.translation.height / fit).clamped(0, full.height - r.height)
                                rect = r
                            }.onEnded { _ in dragStart = nil })
                            .accessibilityLabel("Crop area")
                        ForEach(0..<4, id: \.self) { i in
                            let pt = [CGPoint(x: vr.minX, y: vr.minY), CGPoint(x: vr.maxX, y: vr.minY),
                                      CGPoint(x: vr.maxX, y: vr.maxY), CGPoint(x: vr.minX, y: vr.maxY)][i]
                            CornerMark(index: i)
                                .frame(width: 44, height: 44)
                                .contentShape(Rectangle())
                                .position(pt)
                                .gesture(DragGesture().onChanged { g in
                                    if dragStart == nil { dragStart = rect }
                                    rect = resized(dragStart!, corner: i, by: CGSize(width: g.translation.width / fit, height: g.translation.height / fit))
                                }.onEnded { _ in dragStart = nil })
                                .accessibilityLabel("Crop corner")
                        }
                    }
                }
                .padding(.horizontal, 20)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(aspects.indices, id: \.self) { i in
                            Chip(title: aspects[i].0, selected: aspect == aspects[i].1) {
                                aspect = aspects[i].1
                                if let a = aspect { rect = fitAspect(rect, a) }
                            }
                        }

                    }
                    .padding(.horizontal, 20)
                }
                Button {
                    withAnimation(.snappy) { rect = CGRect(origin: .zero, size: full); aspect = nil }
                } label: {
                    Label("Reset crop", systemImage: "arrow.counterclockwise").font(.subheadline.weight(.medium))
                        .padding(.horizontal, 14).frame(minHeight: 44)
                }
                .accessibilityIdentifier("cropReset")
            }
            .padding(.vertical, 12)
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle("Crop")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Done") { apply(); dismiss() }.fontWeight(.semibold) }
            }
        }
        .onAppear(perform: setup)
    }

    private func fullProps() -> ImageProps? {
        guard var p = model.doc.layer(layerID)?.image else { return nil }
        p.crop = CropRect(x: 0, y: 0, width: Double(p.naturalWidth), height: Double(p.naturalHeight))
        return p
    }

    private func setup() {
        guard let cur = model.doc.layer(layerID)?.image, let fp = fullProps() else { return }
        full = fp.boxSize
        let t = Renderer.imageContentTransform(fp)
        rect = cur.crop.cgRect.applying(t).standardized
        // Preview: rotated/flipped, adjusted, uncropped.
        let maxSide: CGFloat = 1400
        let s = min(1, maxSide / max(full.width, full.height))
        if let ctx = Renderer.makeContext(width: max(1, Int(full.width * s)), height: max(1, Int(full.height * s))) {
            ctx.translateBy(x: 0, y: full.height * s); ctx.scaleBy(x: 1, y: -1); ctx.scaleBy(x: s, y: s)
            var o = RenderOptions(); o.imageMaxPixel = 2048
            var p = fp; p.cornerRadius = 0; p.border.enabled = false
            Renderer.drawImage(p, size: full, in: ctx, assets: model.assets, options: o)
            preview = ctx.makeImage().map { UIImage(cgImage: $0) }
        }
    }

    private func resized(_ r: CGRect, corner: Int, by d: CGSize) -> CGRect {
        var minX = r.minX, minY = r.minY, maxX = r.maxX, maxY = r.maxY
        let minSide: CGFloat = max(1, min(full.width, full.height) * 0.02)
        switch corner {
        case 0: minX += d.width; minY += d.height
        case 1: maxX += d.width; minY += d.height
        case 2: maxX += d.width; maxY += d.height
        default: minX += d.width; maxY += d.height
        }
        minX = minX.clamped(0, maxX - minSide); maxX = maxX.clamped(minX + minSide, full.width)
        minY = minY.clamped(0, maxY - minSide); maxY = maxY.clamped(minY + minSide, full.height)
        var out = CGRect(x: minX, y: minY, width: maxX - minX, height: maxY - minY)
        if let a = aspect {
            // Adjust height to match the aspect, anchored at the opposite corner.
            var h = out.width / CGFloat(a)
            var w = out.width
            let maxH = (corner == 0 || corner == 1) ? r.maxY : full.height - out.minY
            if h > maxH { h = maxH; w = h * CGFloat(a) }
            let maxW = (corner == 0 || corner == 3) ? r.maxX : full.width - out.minX
            if w > maxW { w = maxW; h = w / CGFloat(a) }
            let x = (corner == 0 || corner == 3) ? r.maxX - w : out.minX
            let y = (corner == 0 || corner == 1) ? r.maxY - h : out.minY
            out = CGRect(x: x, y: y, width: w, height: h)
        }
        return out
    }

    private func fitAspect(_ r: CGRect, _ a: Double) -> CGRect {
        var w = r.width, h = r.width / CGFloat(a)
        if h > full.height { h = full.height; w = h * CGFloat(a) }
        if w > full.width { w = full.width; h = w / CGFloat(a) }
        let cx = r.midX.clamped(w / 2, full.width - w / 2), cy = r.midY.clamped(h / 2, full.height - h / 2)
        return CGRect(x: cx - w / 2, y: cy - h / 2, width: w, height: h)
    }

    private func apply() {
        guard let layer = model.doc.layer(layerID), let cur = layer.image, let fp = fullProps() else { return }
        let t = Renderer.imageContentTransform(fp)
        var nat = rect.applying(t.inverted()).standardized
        nat.origin.x = nat.minX.clamped(0, CGFloat(cur.naturalWidth) - 1)
        nat.origin.y = nat.minY.clamped(0, CGFloat(cur.naturalHeight) - 1)
        nat.size.width = nat.width.clamped(1, CGFloat(cur.naturalWidth) - nat.minX)
        nat.size.height = nat.height.clamped(1, CGFloat(cur.naturalHeight) - nat.minY)
        let oldDisplayed = cur.crop.cgRect.applying(t).standardized
        // Keep the visible content in place: new box centre in old local coordinates.
        let newCenterLocal = CGPoint(x: rect.midX - oldDisplayed.minX, y: rect.midY - oldDisplayed.minY)
        let c = newCenterLocal.applying(layer.transform.affine(boxSize: cur.boxSize))
        model.updateLayer(layerID) { l in
            guard var p = l.image else { return }
            p.crop = CropRect(x: round4(Double(nat.minX)), y: round4(Double(nat.minY)), width: round4(Double(nat.width)), height: round4(Double(nat.height)))
            l.image = p
            l.transform.x = Double(c.x)
            l.transform.y = Double(c.y)
        }
    }
}

private struct CornerMark: View {
    let index: Int
    var body: some View {
        Path { p in
            let L: CGFloat = 16
            let c = CGPoint(x: 22, y: 22)
            let sx: CGFloat = (index == 0 || index == 3) ? 1 : -1
            let sy: CGFloat = (index == 0 || index == 1) ? 1 : -1
            p.move(to: CGPoint(x: c.x, y: c.y + sy * L))
            p.addLine(to: c)
            p.addLine(to: CGPoint(x: c.x + sx * L, y: c.y))
        }
        .stroke(Color.white, style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round))
        .shadow(color: .black.opacity(0.4), radius: 2)
    }
}
