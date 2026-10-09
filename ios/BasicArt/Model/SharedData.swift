import Foundation

struct TextPreset: Identifiable {
    var id: String
    var name: String
    var props: JSONObject

    func apply(to t: inout TextProps) { ProjectCodec.applyPreset(props, to: &t) }

    /// A preview TextProps (for drawing the preset chip).
    func preview(text: String) -> TextProps {
        var t = TextProps(text: text)
        apply(to: &t)
        return t
    }
}

enum TextPresets {
    static func load(from url: URL) -> [TextPreset] {
        guard let data = try? Data(contentsOf: url), let root = try? JSONValue.parse(data),
              let arr = root.objectValue?["presets"]?.arrayValue else { return [] }
        return arr.compactMap { v in
            guard let o = v.objectValue, let id = o["id"]?.stringValue, let props = o["props"]?.objectValue else { return nil }
            return TextPreset(id: id, name: o["name"]?.stringValue ?? id, props: props)
        }
    }

    static let all: [TextPreset] = {
        guard let url = Bundle.main.url(forResource: "text-presets", withExtension: "json") else { return [] }
        return load(from: url)
    }()
}

struct Palette: Identifiable, Hashable {
    var id: String
    var name: String
    var colors: [RGBA]
}

enum Palettes {
    static let all: [Palette] = {
        guard let url = Bundle.main.url(forResource: "palettes", withExtension: "json"),
              let data = try? Data(contentsOf: url), let root = try? JSONValue.parse(data),
              let arr = root.objectValue?["palettes"]?.arrayValue else { return [] }
        return arr.compactMap { v in
            guard let o = v.objectValue, let id = o["id"]?.stringValue else { return nil }
            let colors = (o["colors"]?.arrayValue ?? []).compactMap { $0.stringValue.flatMap(RGBA.init(hex:)) }
            return Palette(id: id, name: o["name"]?.stringValue ?? id, colors: colors)
        }
    }()
}
