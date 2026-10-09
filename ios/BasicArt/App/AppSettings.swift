import SwiftUI
import UIKit

enum AppTheme: String, CaseIterable, Identifiable {
    case system, light, dark
    var id: String { rawValue }
    var label: String {
        switch self {
        case .system: return "System"
        case .light: return "Light"
        case .dark: return "Dark"
        }
    }
    var colorScheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }
    var uiStyle: UIUserInterfaceStyle {
        switch self {
        case .system: return .unspecified
        case .light: return .light
        case .dark: return .dark
        }
    }
}

enum ExportFormat: String, CaseIterable, Identifiable {
    case png, jpeg, gif
    var id: String { rawValue }
    var label: String { self == .jpeg ? "JPG" : rawValue.uppercased() }
    var fileExtension: String { self == .jpeg ? "jpg" : rawValue }
}

/// User preferences, persisted in UserDefaults (on-device only).
final class AppSettings: ObservableObject {
    static let shared = AppSettings()
    private let d = UserDefaults.standard

    @Published var theme: AppTheme { didSet { d.set(theme.rawValue, forKey: "theme"); applyTheme() } }
    @Published var snapping: Bool { didSet { d.set(snapping, forKey: "snapping") } }
    @Published var exportFormat: ExportFormat { didSet { d.set(exportFormat.rawValue, forKey: "exportFormat") } }
    @Published var jpegQuality: Double { didSet { d.set(jpegQuality, forKey: "jpegQuality") } }
    @Published var recentColors: [RGBA] { didSet { d.set(recentColors.map(\.hex), forKey: "recentColors") } }
    @Published var myColors: [RGBA] { didSet { d.set(myColors.map(\.hex), forKey: "myColors") } }
    @Published var recentFonts: [String] { didSet { d.set(recentFonts, forKey: "recentFonts") } }
    @Published var brushSettings: [String: Double] { didSet { d.set(brushSettings, forKey: "brushSettings") } }

    init() {
        theme = AppTheme(rawValue: d.string(forKey: "theme") ?? "") ?? .system
        snapping = d.object(forKey: "snapping") as? Bool ?? true
        exportFormat = ExportFormat(rawValue: d.string(forKey: "exportFormat") ?? "") ?? .png
        jpegQuality = d.object(forKey: "jpegQuality") as? Double ?? 90
        recentColors = (d.stringArray(forKey: "recentColors") ?? []).compactMap(RGBA.init(hex:))
        myColors = (d.stringArray(forKey: "myColors") ?? []).compactMap(RGBA.init(hex:))
        recentFonts = d.stringArray(forKey: "recentFonts") ?? []
        brushSettings = d.dictionary(forKey: "brushSettings") as? [String: Double] ?? [:]
    }

    func noteColorUsed(_ c: RGBA) {
        var r = recentColors.filter { $0 != c }
        r.insert(c, at: 0)
        if r.count > 12 { r = Array(r.prefix(12)) }
        if r != recentColors { recentColors = r }
    }

    func noteFontUsed(_ id: String) {
        var r = recentFonts.filter { $0 != id }
        r.insert(id, at: 0)
        if r.count > 10 { r = Array(r.prefix(10)) }
        if r != recentFonts { recentFonts = r }
    }

    /// Applies the theme to every window instantly (UIKit and SwiftUI, incl. sheets).
    static func isAppWindow(_ w: UIWindow) -> Bool {
        let name = NSStringFromClass(type(of: w))
        return w.windowLevel == .normal && !name.contains("Keyboard") && !name.contains("TextEffects")
    }

    func applyTheme() {
        for scene in UIApplication.shared.connectedScenes {
            guard let ws = scene as? UIWindowScene else { continue }
            // Only our own windows, never the system keyboard windows the scene also lists.
            for w in ws.windows where Self.isAppWindow(w) { w.overrideUserInterfaceStyle = theme.uiStyle }
        }
    }
}
