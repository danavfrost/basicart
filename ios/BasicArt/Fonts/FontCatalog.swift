import CoreText
import Foundation
import UIKit

struct FontFile: Hashable {
    var weight: Int
    var italic: Bool
    var styleName: String
    var path: String
}

struct FontFamily: Identifiable, Hashable {
    var id: String
    var family: String
    var category: String
    var group: String
    var files: [FontFile]
    var licenseName: String
    var licenseFile: String
    var copyright: String
    var author: String

    /// Non-italic files sorted by weight (the weight picker list).
    var uprightFiles: [FontFile] {
        files.filter { !$0.italic }.sorted { $0.weight < $1.weight }
    }

    var hasExtraWeights: Bool { !extraWeights.isEmpty }

    /// Weights offered in the weight picker: everything except Regular (the family row) and Bold (the B button).
    var extraWeights: [FontFile] {
        let regular = nearestUprightWeight(to: 400)
        return uprightFiles.filter { $0.weight != regular && $0.weight != 700 }
    }

    var categoryLabel: String {
        switch category {
        case "sans": return "Sans"
        case "serif": return "Serif"
        case "display": return "Display/Meme"
        case "handwriting": return "Handwriting/Script"
        case "mono": return "Monospace"
        case "fun": return "Fun/Retro"
        default: return category.capitalized
        }
    }

    /// Weight of the non-italic file nearest `w` (ties → lighter). Used when switching family.
    func nearestUprightWeight(to w: Int) -> Int {
        let pool = uprightFiles.isEmpty ? files : uprightFiles
        return pool.min { a, b in
            let da = abs(a.weight - w), db = abs(b.weight - w)
            return da != db ? da < db : a.weight < b.weight
        }?.weight ?? 400
    }
}

struct FontGroup: Identifiable, Hashable {
    var id: String
    var name: String
    var sampleFontId: String
}

/// The resolved face for one style run (§7.5).
struct ResolvedFace: Hashable {
    var file: FontFile
    var synthBold: Bool
    var synthItalic: Bool
}

/// Bundled font catalog (shared/fonts/fonts.json). Loaded lazily on first use;
/// font files are registered with Core Text lazily, one file at a time.
final class FontCatalog {
    static let shared = FontCatalog()

    private(set) var groups: [FontGroup] = []
    private(set) var fonts: [FontFamily] = []
    private var byID: [String: FontFamily] = [:]
    private let lock = NSLock()
    private var loaded = false
    private var descriptorCache: [String: CTFontDescriptor] = [:]   // "path|weight"
    private var fileDescriptors: [String: CTFontDescriptor] = [:]   // path
    private var registered = Set<String>()
    private var axesCache: [String: (min: Double, max: Double)?] = [:]

    let fontsRoot: URL? = Bundle.main.url(forResource: "fonts", withExtension: nil)

    init() {}

    func ensureLoaded() {
        lock.lock(); defer { lock.unlock() }
        guard !loaded else { return }
        loaded = true
        guard let root = fontsRoot,
              let data = try? Data(contentsOf: root.appendingPathComponent("fonts.json")),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
        for g in obj["groups"] as? [[String: Any]] ?? [] {
            groups.append(FontGroup(id: g["id"] as? String ?? "", name: g["name"] as? String ?? "",
                                    sampleFontId: g["sampleFontId"] as? String ?? "inter"))
        }
        for f in obj["fonts"] as? [[String: Any]] ?? [] {
            let files = (f["files"] as? [[String: Any]] ?? []).map {
                FontFile(weight: $0["weight"] as? Int ?? 400, italic: $0["italic"] as? Bool ?? false,
                         styleName: $0["styleName"] as? String ?? "Regular", path: $0["path"] as? String ?? "")
            }
            guard !files.isEmpty else { continue }
            let lic = f["license"] as? [String: Any] ?? [:]
            let fam = FontFamily(id: f["id"] as? String ?? "", family: f["family"] as? String ?? "",
                                 category: f["category"] as? String ?? "", group: f["group"] as? String ?? "",
                                 files: files, licenseName: lic["name"] as? String ?? "",
                                 licenseFile: lic["file"] as? String ?? "",
                                 copyright: f["copyright"] as? String ?? "", author: f["author"] as? String ?? "")
            fonts.append(fam)
            byID[fam.id] = fam
        }
    }

    var allGroups: [FontGroup] { ensureLoaded(); return groups }
    var allFonts: [FontFamily] { ensureLoaded(); return fonts }

    func fonts(inGroup id: String) -> [FontFamily] { allFonts.filter { $0.group == id } }

    /// Unknown ids resolve to "inter" (§7.1).
    func font(id: String) -> FontFamily {
        ensureLoaded()
        if let f = byID[id] { return f }
        if let f = byID["inter"] { return f }
        return fonts.first ?? FontFamily(id: "inter", family: "Inter", category: "sans", group: "", files: [FontFile(weight: 400, italic: false, styleName: "Regular", path: "")], licenseName: "", licenseFile: "", copyright: "", author: "")
    }

    func isKnown(_ id: String) -> Bool { ensureLoaded(); return byID[id] != nil }

    // MARK: §7.5 file selection

    static func resolve(_ fam: FontFamily, weight: Int, bold: Bool, italic: Bool) -> ResolvedFace {
        let w = min(900, max(100, weight))
        let italics = fam.files.filter { $0.italic }
        var synthItalic = false
        var pool: [FontFile]
        if italic && !italics.isEmpty {
            pool = italics
        } else {
            pool = fam.files.filter { !$0.italic }
            if pool.isEmpty { pool = fam.files }
            synthItalic = italic
        }
        let nearest = pool.min { a, b in
            let da = abs(a.weight - w), db = abs(b.weight - w)
            return da != db ? da < db : a.weight < b.weight
        }!
        guard bold else { return ResolvedFace(file: nearest, synthBold: false, synthItalic: synthItalic) }
        let t = max(700, w)
        let candidates = pool.filter { $0.weight >= 600 && $0.weight >= w }
        if let best = candidates.min(by: { a, b in
            let da = abs(a.weight - t), db = abs(b.weight - t)
            return da != db ? da < db : a.weight > b.weight
        }) {
            return ResolvedFace(file: best, synthBold: false, synthItalic: synthItalic)
        }
        return ResolvedFace(file: nearest, synthBold: true, synthItalic: synthItalic)
    }

    // MARK: Core Text fonts

    private func fileDescriptor(_ path: String) -> CTFontDescriptor? {
        if let d = fileDescriptors[path] { return d }
        guard let root = fontsRoot else { return nil }
        let url = root.appendingPathComponent(path)
        if !registered.contains(path) {
            registered.insert(path)
            CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
        }
        guard let arr = CTFontManagerCreateFontDescriptorsFromURL(url as CFURL) as? [CTFontDescriptor],
              let d = arr.first else { return nil }
        fileDescriptors[path] = d
        return d
    }

    static let wghtTag = 0x77676874 // 'wght'

    /// A descriptor for a file at a weight; variable fonts get their 'wght' axis set.
    func descriptor(for file: FontFile) -> CTFontDescriptor? {
        lock.lock(); defer { lock.unlock() }
        let key = "\(file.path)|\(file.weight)"
        if let d = descriptorCache[key] { return d }
        guard let base = fileDescriptor(file.path) else { return nil }
        var desc = base
        let axis: (min: Double, max: Double)?
        if let cached = axesCache[file.path] {
            axis = cached
        } else {
            let probe = CTFontCreateWithFontDescriptor(base, 12, nil)
            var found: (Double, Double)? = nil
            if let axes = CTFontCopyVariationAxes(probe) as? [[String: Any]] {
                for a in axes {
                    if let ident = a[kCTFontVariationAxisIdentifierKey as String] as? Int, ident == FontCatalog.wghtTag {
                        let mn = (a[kCTFontVariationAxisMinimumValueKey as String] as? Double) ?? 100
                        let mx = (a[kCTFontVariationAxisMaximumValueKey as String] as? Double) ?? 900
                        found = (mn, mx)
                    }
                }
            }
            axesCache[file.path] = found
            axis = found
        }
        // No automatic optical sizing: fonts with an `opsz` axis (e.g. Inter) keep its default,
        // like on Android (§7.3 step 3: only `wght` is ever set).
        var attrs: [CFString: Any] = [kCTFontOpticalSizeAttribute: "none"]
        if let axis {
            let v = min(axis.max, max(axis.min, Double(file.weight)))
            attrs[kCTFontVariationAttribute] = [FontCatalog.wghtTag: v]
        }
        desc = CTFontDescriptorCreateCopyWithAttributes(base, attrs as CFDictionary)
        descriptorCache[key] = desc
        return desc
    }

    /// CTFont for a resolved face at `size`. Synthesized italic is a 12° shear.
    func ctFont(_ face: ResolvedFace, size: CGFloat) -> CTFont {
        var matrix = CGAffineTransform.identity
        if face.synthItalic { matrix = CGAffineTransform(a: 1, b: 0, c: tan(12 * .pi / 180), d: 1, tx: 0, ty: 0) }
        if let d = descriptor(for: face.file) {
            return withUnsafePointer(to: &matrix) { CTFontCreateWithFontDescriptor(d, size, face.synthItalic ? $0 : nil) }
        }
        return withUnsafePointer(to: &matrix) { CTFontCreateWithName("Helvetica" as CFString, size, face.synthItalic ? $0 : nil) }
    }

    /// Convenience for UI: family rendered at a weight (no bold/italic).
    func uiFont(fontId: String, weight: Int, size: CGFloat) -> UIFont {
        let fam = font(id: fontId)
        let face = FontCatalog.resolve(fam, weight: weight, bold: false, italic: false)
        return ctFont(face, size: size) as UIFont
    }

    func licenseText(_ fam: FontFamily) -> String {
        guard let root = fontsRoot else { return "" }
        return (try? String(contentsOf: root.appendingPathComponent(fam.licenseFile), encoding: .utf8)) ?? ""
    }
}
