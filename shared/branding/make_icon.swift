// Basic Art app icon generator (macOS, Swift — no third-party deps).
// Run from the repo root:  swift shared/branding/make_icon.swift
// Glyphs come from the bundled Inter (OFL, shared/fonts/inter) at weight 700,
// so the icon is redistributable on every platform.
//
// Outputs (all 1024×1024, written next to this script):
//   app-icon-1024.png            full icon (background + foreground), opaque
//   icon-background-1024.png     layer 1: diagonal gradient
//   icon-foreground-1024.png     layer 2: "Aa" + wave on transparent (same placement as the full icon)
//   icon-foreground-adaptive-1024.png  layer 2 scaled to 66% (Android adaptive-icon safe zone, 72/108 dp)
//   icon.svg                     vector version: gradient + glyph outlines + wave as separate groups
import CoreGraphics
import CoreText
import Foundation
import ImageIO
import UniformTypeIdentifiers

let S: CGFloat = 1024
let here = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
let fontURL = here.appendingPathComponent("../fonts/inter/inter-variable.ttf").standardizedFileURL
let sRGB = CGColorSpace(name: CGColorSpace.sRGB)!

// Design constants (y-up coordinates, origin bottom-left, like Core Graphics).
let gradTop = (r: 0.20, g: 0.47, b: 0.96)      // #3378F5 at top-left
let gradBottom = (r: 0.55, g: 0.25, b: 0.90)   // #8C40E6 at bottom-right
let waveColor = (r: 1.0, g: 0.82, b: 0.25)     // #FFD140
let waveWidth: CGFloat = 70
let waveStart = CGPoint(x: 190, y: 250), waveEnd = CGPoint(x: 840, y: 300)
let waveC1 = CGPoint(x: 400, y: 120), waveC2 = CGPoint(x: 620, y: 420)
let textSize: CGFloat = 560
let textBaselineLift: CGFloat = 400 - 40      // glyph-bounds bottom sits at y = 360

func interBold(_ size: CGFloat) -> CTFont {
    let descs = CTFontManagerCreateFontDescriptorsFromURL(fontURL as CFURL) as! [CTFontDescriptor]
    let d = CTFontDescriptorCreateCopyWithAttributes(descs[0], [kCTFontVariationAttribute: [0x77676874: 700]] as CFDictionary)
    return CTFontCreateWithFontDescriptor(d, size, nil)
}

/// "Aa" glyph outlines placed in icon space.
func textPath() -> CGPath {
    let font = interBold(textSize)
    let line = CTLineCreateWithAttributedString(NSAttributedString(string: "Aa", attributes: [NSAttributedString.Key(kCTFontAttributeName as String): font]))
    let b = CTLineGetBoundsWithOptions(line, .useGlyphPathBounds)
    let origin = CGPoint(x: (S - b.width) / 2 - b.minX, y: textBaselineLift - b.minY)
    let path = CGMutablePath()
    for run in CTLineGetGlyphRuns(line) as! [CTRun] {
        let n = CTRunGetGlyphCount(run)
        var glyphs = [CGGlyph](repeating: 0, count: n), pos = [CGPoint](repeating: .zero, count: n)
        CTRunGetGlyphs(run, CFRange(), &glyphs); CTRunGetPositions(run, CFRange(), &pos)
        for i in 0..<n {
            var t = CGAffineTransform(translationX: origin.x + pos[i].x, y: origin.y + pos[i].y)
            if let g = CTFontCreatePathForGlyph(font, glyphs[i], &t) { path.addPath(g) }
        }
    }
    return path
}

func wavePath() -> CGPath {
    let p = CGMutablePath()
    p.move(to: waveStart); p.addCurve(to: waveEnd, control1: waveC1, control2: waveC2)
    return p
}

/// Opaque contexts produce PNGs without an alpha channel (required for the App Store icon).
func context(opaque: Bool = false) -> CGContext {
    CGContext(data: nil, width: Int(S), height: Int(S), bitsPerComponent: 8, bytesPerRow: 0, space: sRGB,
              bitmapInfo: (opaque ? CGImageAlphaInfo.noneSkipLast : CGImageAlphaInfo.premultipliedLast).rawValue)!
}

func drawBackground(_ c: CGContext) {
    let g = CGGradient(colorsSpace: sRGB, colors: [CGColor(red: gradTop.r, green: gradTop.g, blue: gradTop.b, alpha: 1),
                                                   CGColor(red: gradBottom.r, green: gradBottom.g, blue: gradBottom.b, alpha: 1)] as CFArray, locations: [0, 1])!
    c.drawLinearGradient(g, start: CGPoint(x: 0, y: S), end: CGPoint(x: S, y: 0), options: [])
}

func drawForeground(_ c: CGContext) {
    c.setLineCap(.round)
    c.setStrokeColor(CGColor(red: waveColor.r, green: waveColor.g, blue: waveColor.b, alpha: 1))
    c.setLineWidth(waveWidth)
    c.addPath(wavePath()); c.strokePath()
    c.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
    c.addPath(textPath()); c.fillPath()
}

func save(_ c: CGContext, _ name: String) {
    let url = here.appendingPathComponent(name)
    let d = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(d, c.makeImage()!, nil); CGImageDestinationFinalize(d)
    print("wrote \(name)")
}

var c = context(opaque: true); drawBackground(c); drawForeground(c); save(c, "app-icon-1024.png")
c = context(opaque: true); drawBackground(c); save(c, "icon-background-1024.png")
c = context(); drawForeground(c); save(c, "icon-foreground-1024.png")
c = context(); c.translateBy(x: S / 2, y: S / 2); c.scaleBy(x: 0.66, y: 0.66); c.translateBy(x: -S / 2, y: -S / 2); drawForeground(c)
save(c, "icon-foreground-adaptive-1024.png")

// SVG (y-down): flip y.
func svgPath(_ p: CGPath) -> String {
    var s = ""
    func f(_ v: CGFloat) -> String { String(format: "%.2f", v) }
    func pt(_ q: CGPoint) -> String { "\(f(q.x)) \(f(S - q.y))" }
    p.applyWithBlock { e in
        let a = e.pointee.points
        switch e.pointee.type {
        case .moveToPoint: s += "M\(pt(a[0]))"
        case .addLineToPoint: s += "L\(pt(a[0]))"
        case .addQuadCurveToPoint: s += "Q\(pt(a[0])) \(pt(a[1]))"
        case .addCurveToPoint: s += "C\(pt(a[0])) \(pt(a[1])) \(pt(a[2]))"
        case .closeSubpath: s += "Z"
        @unknown default: break
        }
    }
    return s
}
func hex(_ c: (r: Double, g: Double, b: Double)) -> String {
    String(format: "#%02X%02X%02X", Int((c.r * 255).rounded()), Int((c.g * 255).rounded()), Int((c.b * 255).rounded()))
}
let svg = """
<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1024" y2="1024" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="\(hex(gradTop))"/>
      <stop offset="1" stop-color="\(hex(gradBottom))"/>
    </linearGradient>
  </defs>
  <g id="background"><rect width="1024" height="1024" fill="url(#bg)"/></g>
  <g id="foreground">
    <path id="wave" d="\(svgPath(wavePath()))" fill="none" stroke="\(hex(waveColor))" stroke-width="\(Int(waveWidth))" stroke-linecap="round"/>
    <path id="letters" d="\(svgPath(textPath()))" fill="#FFFFFF" fill-rule="nonzero"/>
  </g>
</svg>

"""
try! svg.write(to: here.appendingPathComponent("icon.svg"), atomically: true, encoding: .utf8)
print("wrote icon.svg")
