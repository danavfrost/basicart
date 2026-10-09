import Foundation

/// A minimal, strict JSON value used for reading and writing the project format.
/// Reading goes through JSONSerialization (fast, battle tested) and is converted
/// to this enum so booleans and numbers stay distinct. Writing uses our own
/// serializer so number formatting is deterministic on every iOS version.
indirect enum JSONValue: Equatable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object(JSONObject)

    static func parse(_ data: Data) throws -> JSONValue {
        let any = try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        return JSONValue(any: any)
    }

    init(any: Any) {
        switch any {
        case let n as NSNumber:
            if CFGetTypeID(n) == CFBooleanGetTypeID() {
                self = .bool(n.boolValue)
            } else {
                self = .number(n.doubleValue)
            }
        case let s as String: self = .string(s)
        case let a as [Any]: self = .array(a.map { JSONValue(any: $0) })
        case let d as [String: Any]:
            var o = JSONObject()
            for k in d.keys.sorted() { o[k] = JSONValue(any: d[k]!) }
            self = .object(o)
        default: self = .null
        }
    }

    var objectValue: JSONObject? { if case .object(let o) = self { return o }; return nil }
    var arrayValue: [JSONValue]? { if case .array(let a) = self { return a }; return nil }
    var stringValue: String? { if case .string(let s) = self { return s }; return nil }
    var doubleValue: Double? { if case .number(let n) = self { return n }; return nil }
    var boolValue: Bool? { if case .bool(let b) = self { return b }; return nil }
    var intValue: Int? {
        if case .number(let n) = self, n.isFinite, n.rounded() == n, abs(n) < 1e15 { return Int(n) }
        return nil
    }

    // MARK: Writing

    func serialized(pretty: Bool = true) -> Data {
        var out = ""
        out.reserveCapacity(4096)
        write(into: &out, indent: 0, pretty: pretty)
        out.append("\n")
        return Data(out.utf8)
    }

    private func write(into out: inout String, indent: Int, pretty: Bool) {
        switch self {
        case .null: out += "null"
        case .bool(let b): out += b ? "true" : "false"
        case .number(let n): out += JSONValue.format(n)
        case .string(let s): JSONValue.writeString(s, into: &out)
        case .array(let a):
            if a.isEmpty { out += "[]"; return }
            // Flat numeric arrays (stroke points) stay on one line to keep files small.
            let flat = a.allSatisfy { if case .number = $0 { return true }; return false }
            if flat || !pretty {
                out += "["
                for (i, v) in a.enumerated() {
                    if i > 0 { out += pretty ? ", " : "," }
                    v.write(into: &out, indent: indent, pretty: pretty)
                }
                out += "]"
                return
            }
            out += "[\n"
            for (i, v) in a.enumerated() {
                out += String(repeating: " ", count: indent + 2)
                v.write(into: &out, indent: indent + 2, pretty: pretty)
                out += i < a.count - 1 ? ",\n" : "\n"
            }
            out += String(repeating: " ", count: indent) + "]"
        case .object(let o):
            if o.keys.isEmpty { out += "{}"; return }
            out += pretty ? "{\n" : "{"
            for (i, k) in o.keys.enumerated() {
                if pretty { out += String(repeating: " ", count: indent + 2) }
                JSONValue.writeString(k, into: &out)
                out += pretty ? ": " : ":"
                o[k]!.write(into: &out, indent: indent + 2, pretty: pretty)
                if i < o.keys.count - 1 { out += "," }
                if pretty { out += "\n" }
            }
            if pretty { out += String(repeating: " ", count: indent) }
            out += "}"
        }
    }

    static func format(_ n: Double) -> String {
        guard n.isFinite else { return "0" }
        if n == n.rounded(), abs(n) < 1e15 { return String(Int64(n)) }
        // Shortest round-trip representation; avoid exponent notation.
        var s = "\(n)"
        if s.contains("e") || s.contains("E") {
            s = String(format: "%.6f", n)
            while s.hasSuffix("0") { s.removeLast() }
            if s.hasSuffix(".") { s.removeLast() }
        }
        return s
    }

    private static func writeString(_ s: String, into out: inout String) {
        out += "\""
        for u in s.unicodeScalars {
            switch u {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if u.value < 0x20 {
                    out += String(format: "\\u%04x", u.value)
                } else {
                    out.unicodeScalars.append(u)
                }
            }
        }
        out += "\""
    }
}

/// Ordered JSON object (insertion order is kept for readable output).
struct JSONObject: Equatable {
    private(set) var keys: [String] = []
    private var dict: [String: JSONValue] = [:]

    init() {}
    init(_ pairs: [(String, JSONValue)]) {
        for (k, v) in pairs { self[k] = v }
    }

    subscript(key: String) -> JSONValue? {
        get { dict[key] }
        set {
            if let v = newValue {
                if dict[key] == nil { keys.append(key) }
                dict[key] = v
            } else if dict[key] != nil {
                dict[key] = nil
                keys.removeAll { $0 == key }
            }
        }
    }

    static func == (a: JSONObject, b: JSONObject) -> Bool { a.dict == b.dict }
}

/// Rounding helpers for writers (§1: at most 4 decimals; stroke points 2).
@inline(__always) func round4(_ v: Double) -> Double { (v * 10000).rounded() / 10000 }
@inline(__always) func round2(_ v: Double) -> Double { (v * 100).rounded() / 100 }

extension JSONValue {
    static func num(_ v: Double) -> JSONValue { .number(round4(v)) }
    static func int(_ v: Int) -> JSONValue { .number(Double(v)) }
}
