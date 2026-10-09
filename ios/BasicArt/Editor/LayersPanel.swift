import SwiftUI

struct LayersPanel: View {
    @ObservedObject var model: EditorModel
    var onRename: (String) -> Void
    var onClose: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Layers").font(.headline)
                Text("\(model.doc.layers.count)").font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
                Spacer()
                Button(action: onClose) {
                    Image(systemName: "xmark.circle.fill")
                        .symbolRenderingMode(.hierarchical)
                        .font(.title3)
                        .frame(width: 44, height: 44)
                }
                .foregroundStyle(.secondary)
                .accessibilityLabel("Close layers")
            }
            .padding(.leading, 16)
            .padding(.trailing, 4)
            if model.doc.layers.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "square.3.layers.3d").font(.title).foregroundStyle(.tertiary)
                    Text("No layers yet").font(.subheadline.weight(.semibold))
                    Text("Add text, a photo, a drawing or a shape with the tools below.")
                        .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                }
                .padding(24)
                .frame(maxWidth: .infinity)
                Spacer(minLength: 0)
            } else {
                List {
                    ForEach(model.doc.layers.reversed()) { layer in
                        LayerRow(model: model, layer: layer, selected: model.selection == layer.id, onRename: onRename)
                            .listRowInsets(EdgeInsets(top: 2, leading: 8, bottom: 2, trailing: 4))
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                    }
                    .onMove { from, to in
                        // List shows top → bottom; the document is bottom → top.
                        let n = model.doc.layers.count
                        guard let f = from.first else { return }
                        let id = model.doc.layers[n - 1 - f].id
                        let destDisplay = to > f ? to - 1 : to
                        model.moveLayer(id, toIndex: n - 1 - destDisplay)
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .accessibilityIdentifier("layersList")
            }
        }
    }
}

struct LayerRow: View {
    @ObservedObject var model: EditorModel
    let layer: Layer
    let selected: Bool
    var onRename: (String) -> Void

    var body: some View {
        HStack(spacing: 8) {
            LayerThumbnail(model: model, layer: layer)
                .frame(width: 38, height: 38)
            VStack(alignment: .leading, spacing: 1) {
                Text(layer.name).font(.subheadline.weight(selected ? .semibold : .regular))
                    .lineLimit(1).minimumScaleFactor(0.85).truncationMode(.middle)
                Text(layer.type.displayName).font(.caption2).foregroundStyle(.secondary)
            }
            .opacity(layer.visible ? 1 : 0.5)
            Spacer(minLength: 0)
            iconButton(layer.visible ? "eye" : "eye.slash", layer.visible ? "Hide \(layer.name)" : "Show \(layer.name)", dim: !layer.visible) {
                model.toggleVisible(layer.id)
            }
            iconButton(layer.locked ? "lock.fill" : "lock.open", layer.locked ? "Unlock \(layer.name)" : "Lock \(layer.name)", dim: false) {
                model.toggleLocked(layer.id)
            }
            Menu {
                LayerMenuItems(model: model, id: layer.id, includeEditText: true, onRename: onRename)
            } label: {
                Image(systemName: "ellipsis").font(.system(size: 15, weight: .semibold)).frame(width: 30, height: 44)
            }
            .tint(.primary)
            .accessibilityLabel("More options for \(layer.name)")
        }
        .padding(.leading, 5)
        .padding(.vertical, 2)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(selected ? Color.accentColor.opacity(0.15) : Color.clear)
        )
        .contentShape(Rectangle())
        .onTapGesture { model.select(layer.id) }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("\(layer.name), \(layer.type.displayName) layer\(selected ? ", selected" : "")\(layer.visible ? "" : ", hidden")\(layer.locked ? ", locked" : "")")
        .accessibilityAction(named: "Select") { model.select(layer.id) }
    }

    private func iconButton(_ symbol: String, _ label: String, dim: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(dim ? Color.secondary.opacity(0.7) : Color.primary.opacity(0.8))
                .frame(width: 30, height: 44)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

/// Small rendering of one layer, unrotated, fitted to the thumbnail.
struct LayerThumbnail: View {
    @ObservedObject var model: EditorModel
    let layer: Layer

    var body: some View {
        ZStack {
            Checkerboard(cell: 4)
            if let img = LayerThumbCache.shared.image(for: layer, assets: model.assets) {
                Image(uiImage: img).resizable().aspectRatio(contentMode: .fit).padding(3)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).strokeBorder(Color.primary.opacity(0.12)))
        .accessibilityHidden(true)
    }
}

final class LayerThumbCache {
    static let shared = LayerThumbCache()
    private var cache: [String: (Layer, UIImage)] = [:]

    func image(for layer: Layer, assets: AssetProvider) -> UIImage? {
        if let (l, img) = cache[layer.id], l == layer { return img }
        guard let img = render(layer, assets: assets) else { return nil }
        if cache.count > 200 { cache.removeAll() }
        cache[layer.id] = (layer, img)
        return img
    }

    private func render(_ layer: Layer, assets: AssetProvider) -> UIImage? {
        if case .text(var t) = layer.content {
            // A long line would be an unreadable speck: preview the style as "Aa".
            let first = Spans.effective(t).first ?? t.layerStyle
            t.text = "Aa"
            t.spans = []
            t.fontId = first.fontId; t.weight = first.weight; t.fontSize = 100
            t.layerFlags = first.flags
            if let c = first.color { t.fill = TextFill(type: .solid, color: c) }
            t.curve = 0; t.skew = 0; t.backgroundBox.enabled = false
            var l = layer
            l.content = .text(t)
            l.mask = []
            return renderLayer(l, assets: assets)
        }
        return renderLayer(layer, assets: assets)
    }

    private func renderLayer(_ layer: Layer, assets: AssetProvider) -> UIImage? {
        var l = layer
        l.visible = true
        l.opacity = 1
        var size = LayerGeometry.boxSize(of: l)
        var origin = CGPoint.zero
        if case .text(let t) = l.content {
            let r = Renderer.textEffectBounds(t, layout: TextLayoutEngine.shared.layout(t))
            origin = r.origin; size = r.size
        }
        if case .shape(let s) = l.content {
            let pad = max(s.stroke.width, 1)
            origin = CGPoint(x: -pad, y: -pad); size = CGSize(width: size.width + 2 * pad, height: size.height + 2 * pad)
        }
        guard size.width > 0, size.height > 0 else { return nil }
        let px: CGFloat = 96
        let s = px / max(size.width, size.height)
        let w = max(1, Int(size.width * s)), h = max(1, Int(size.height * s))
        guard let ctx = Renderer.makeContext(width: w, height: h) else { return nil }
        ctx.translateBy(x: 0, y: CGFloat(h)); ctx.scaleBy(x: 1, y: -1)
        ctx.scaleBy(x: s, y: s)
        ctx.translateBy(x: -origin.x, y: -origin.y)
        let box = LayerGeometry.boxSize(of: l)
        // Draw at local coordinates: set a transform placing the box at the origin.
        l.transform = Transform(x: Double(box.width / 2), y: Double(box.height / 2), scale: 1, rotation: 0)
        var opts = RenderOptions()
        opts.imageMaxPixel = 256
        Renderer.drawLayer(l, in: ctx, assets: assets, options: opts)
        return ctx.makeImage().map { UIImage(cgImage: $0) }
    }
}
