import CoreGraphics
import Foundation

/// Still-frame GIF89a encoder: median-cut palette (≤255 colours + 1 transparent
/// index), Floyd–Steinberg dithering, LZW compression. Pure Swift, deterministic.
enum GIFEncoder {

    static func encode(_ image: CGImage, dither: Bool = true) -> Data? {
        let w = image.width, h = image.height
        guard w > 0, h > 0, w <= 65535, h <= 65535 else { return nil }
        // Straight-alpha RGBA pixels.
        guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                  space: RGBA.sRGB, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue),
              let raw = ctx.data else { return nil }
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        let px = raw.bindMemory(to: UInt8.self, capacity: w * h * 4)
        let n = w * h
        var rgb = [Float](repeating: 0, count: n * 3)
        var transparent = [Bool](repeating: false, count: n)
        var anyTransparent = false
        for i in 0..<n {
            let a = Int(px[i * 4 + 3])
            if a < 128 { transparent[i] = true; anyTransparent = true; continue }
            for c in 0..<3 {
                let v = Int(px[i * 4 + c])
                rgb[i * 3 + c] = Float(min(255, (v * 255 + a / 2) / a))
            }
        }
        let maxColors = anyTransparent ? 255 : 256
        let palette = medianCut(rgb: rgb, transparent: transparent, maxColors: maxColors)
        var lookup = [Int16](repeating: -1, count: 32768)
        func nearest(_ r: Float, _ g: Float, _ b: Float) -> Int {
            let ri = Int(max(0, min(255, r))) >> 3, gi = Int(max(0, min(255, g))) >> 3, bi = Int(max(0, min(255, b))) >> 3
            let key = (ri << 10) | (gi << 5) | bi
            if lookup[key] >= 0 { return Int(lookup[key]) }
            let cr = Float(ri << 3 | 4), cg = Float(gi << 3 | 4), cb = Float(bi << 3 | 4)
            var best = 0, bestD = Float.greatestFiniteMagnitude
            for (k, p) in palette.enumerated() {
                let dr = cr - Float(p.0), dg = cg - Float(p.1), db = cb - Float(p.2)
                let d = dr * dr * 0.3 + dg * dg * 0.59 + db * db * 0.11
                if d < bestD { bestD = d; best = k }
            }
            lookup[key] = Int16(best)
            return best
        }
        let transIndex = palette.count
        var indices = [UInt8](repeating: 0, count: n)
        for y in 0..<h {
            for x in 0..<w {
                let i = y * w + x
                if transparent[i] { indices[i] = UInt8(transIndex); continue }
                let r = rgb[i * 3], g = rgb[i * 3 + 1], b = rgb[i * 3 + 2]
                let k = nearest(r, g, b)
                indices[i] = UInt8(k)
                guard dither else { continue }
                let er = r - Float(palette[k].0), eg = g - Float(palette[k].1), eb = b - Float(palette[k].2)
                func spread(_ dx: Int, _ dy: Int, _ f: Float) {
                    let xx = x + dx, yy = y + dy
                    guard xx >= 0, xx < w, yy < h else { return }
                    let j = yy * w + xx
                    if transparent[j] { return }
                    rgb[j * 3] += er * f; rgb[j * 3 + 1] += eg * f; rgb[j * 3 + 2] += eb * f
                }
                spread(1, 0, 7.0 / 16); spread(-1, 1, 3.0 / 16); spread(0, 1, 5.0 / 16); spread(1, 1, 1.0 / 16)
            }
        }
        let tableEntries = palette.count + (anyTransparent ? 1 : 0)
        var bits = 1
        while (1 << bits) < max(2, tableEntries) { bits += 1 }
        let tableSize = 1 << bits

        var out = Data()
        out.append(contentsOf: Array("GIF89a".utf8))
        func u16(_ v: Int) { out.append(UInt8(v & 0xFF)); out.append(UInt8((v >> 8) & 0xFF)) }
        u16(w); u16(h)
        out.append(0x80 | UInt8((bits - 1) << 4) | UInt8(bits - 1)) // global table, color res, size
        out.append(UInt8(anyTransparent ? transIndex : 0)) // background index
        out.append(0)
        for k in 0..<tableSize {
            if k < palette.count { out.append(palette[k].0); out.append(palette[k].1); out.append(palette[k].2) }
            else { out.append(0); out.append(0); out.append(0) }
        }
        if anyTransparent {
            out.append(contentsOf: [0x21, 0xF9, 0x04, 0x01, 0x00, 0x00, UInt8(transIndex), 0x00])
        }
        out.append(0x2C); u16(0); u16(0); u16(w); u16(h); out.append(0)
        let minCode = max(2, bits)
        out.append(UInt8(minCode))
        let lzw = lzwEncode(indices, minCodeSize: minCode)
        var p = 0
        while p < lzw.count {
            let len = min(255, lzw.count - p)
            out.append(UInt8(len))
            out.append(contentsOf: lzw[p..<(p + len)])
            p += len
        }
        out.append(0)
        out.append(0x3B)
        return out
    }

    // MARK: Median cut

    private static func medianCut(rgb: [Float], transparent: [Bool], maxColors: Int) -> [(UInt8, UInt8, UInt8)] {
        // Histogram at 5 bits/channel keeps this fast for big images.
        var hist = [Int: Int]()
        let n = transparent.count
        let step = max(1, n / 400_000)
        var i = 0
        while i < n {
            if !transparent[i] {
                let r = Int(rgb[i * 3]) >> 3, g = Int(rgb[i * 3 + 1]) >> 3, b = Int(rgb[i * 3 + 2]) >> 3
                hist[(r << 10) | (g << 5) | b, default: 0] += 1
            }
            i += step
        }
        if hist.isEmpty { return [(0, 0, 0)] }
        struct Entry { var r: Int; var g: Int; var b: Int; var count: Int }
        var entries = hist.map { Entry(r: ($0.key >> 10) & 31, g: ($0.key >> 5) & 31, b: $0.key & 31, count: $0.value) }
        if entries.count <= maxColors {
            return entries.map { (UInt8($0.r << 3 | 4), UInt8($0.g << 3 | 4), UInt8($0.b << 3 | 4)) }
        }
        var boxes: [ArraySlice<Entry>] = [entries[...]]
        while boxes.count < maxColors {
            // Split the box with the largest range × population.
            var bestIdx = -1, bestScore = 0, bestAxis = 0
            for (k, box) in boxes.enumerated() where box.count > 1 {
                var lo = [31, 31, 31], hi = [0, 0, 0], pop = 0
                for e in box {
                    lo[0] = min(lo[0], e.r); hi[0] = max(hi[0], e.r)
                    lo[1] = min(lo[1], e.g); hi[1] = max(hi[1], e.g)
                    lo[2] = min(lo[2], e.b); hi[2] = max(hi[2], e.b)
                    pop += e.count
                }
                let ranges = [hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2]]
                let axis = ranges.firstIndex(of: ranges.max()!)!
                let score = ranges[axis] * Int(Double(pop).squareRoot() + 1)
                if score > bestScore { bestScore = score; bestIdx = k; bestAxis = axis }
            }
            if bestIdx < 0 { break }
            var arr = Array(boxes[bestIdx])
            arr.sort { a, b in
                switch bestAxis { case 0: return a.r < b.r; case 1: return a.g < b.g; default: return a.b < b.b }
            }
            let total = arr.reduce(0) { $0 + $1.count }
            var acc = 0, cut = 1
            for (k, e) in arr.enumerated() { acc += e.count; if acc >= total / 2 { cut = max(1, min(arr.count - 1, k + 1)); break } }
            boxes[bestIdx] = arr[0..<cut]
            boxes.append(arr[cut...])
        }
        entries.removeAll()
        return boxes.map { box in
            var r = 0, g = 0, b = 0, c = 0
            for e in box { r += e.r * e.count; g += e.g * e.count; b += e.b * e.count; c += e.count }
            c = max(1, c)
            return (UInt8(min(255, (r * 8 + c * 4) / c)), UInt8(min(255, (g * 8 + c * 4) / c)), UInt8(min(255, (b * 8 + c * 4) / c)))
        }
    }

    // MARK: LZW

    private static func lzwEncode(_ data: [UInt8], minCodeSize: Int) -> [UInt8] {
        let clear = 1 << minCodeSize, eoi = clear + 1
        var codeSize = minCodeSize + 1
        var next = eoi + 1
        var dict = [Int: Int]() // (prefix << 8 | byte) → code
        dict.reserveCapacity(4096)
        var out = [UInt8]()
        out.reserveCapacity(data.count / 2)
        var bitBuf = 0, bitCount = 0
        func emit(_ code: Int) {
            bitBuf |= code << bitCount
            bitCount += codeSize
            while bitCount >= 8 { out.append(UInt8(bitBuf & 0xFF)); bitBuf >>= 8; bitCount -= 8 }
        }
        emit(clear)
        guard !data.isEmpty else { emit(eoi); if bitCount > 0 { out.append(UInt8(bitBuf & 0xFF)) }; return out }
        var prefix = Int(data[0])
        for k in 1..<data.count {
            let c = Int(data[k])
            let key = (prefix << 8) | c
            if let code = dict[key] { prefix = code; continue }
            emit(prefix)
            if next < 4096 {
                dict[key] = next
                next += 1
                if next > (1 << codeSize) && codeSize < 12 { codeSize += 1 }
            } else {
                emit(clear)
                dict.removeAll(keepingCapacity: true)
                codeSize = minCodeSize + 1
                next = eoi + 1
            }
            prefix = c
        }
        emit(prefix)
        emit(eoi)
        if bitCount > 0 { out.append(UInt8(bitBuf & 0xFF)) }
        return out
    }
}
