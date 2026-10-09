import SwiftUI

@main
struct BasicArtApp: App {
    @StateObject private var settings = AppSettings.shared

    init() {
        NumericFieldBehavior.install()
        _ = KeyboardInset.shared
        DispatchQueue.global(qos: .utility).async {
            ProjectPackage.cleanupStaging()
            ProjectPackage.sweepExportTemp()
        }
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-uiTestReset") {
            try? ProjectStore.shared.deleteAll()
        }
        #endif
    }

    var body: some Scene {
        WindowGroup {
            HomeView()
                .environmentObject(settings)
                .onOpenURL { url in ImportRouter.shared.pending = url }
                .onAppear { settings.applyTheme() }
        }
    }
}

/// Numeric text fields: select the whole value on focus (typing replaces it) and add a
/// Done bar to number pads, which have no return key.
enum NumericFieldBehavior {
    static func install() {
        NotificationCenter.default.addObserver(forName: UITextField.textDidBeginEditingNotification, object: nil, queue: .main) { n in
            guard let tf = n.object as? UITextField,
                  [.numberPad, .decimalPad, .numbersAndPunctuation].contains(tf.keyboardType) else { return }
            if tf.inputAccessoryView == nil && tf.keyboardType != .numbersAndPunctuation {
                let bar = UIToolbar(frame: CGRect(x: 0, y: 0, width: 320, height: 44))
                bar.items = [UIBarButtonItem(systemItem: .flexibleSpace),
                             UIBarButtonItem(systemItem: .done, primaryAction: UIAction { [weak tf] _ in tf?.resignFirstResponder() })]
                bar.sizeToFit()
                tf.inputAccessoryView = bar
                tf.reloadInputViews()
            }
            DispatchQueue.main.async { tf.selectAll(nil) }
        }
    }
}

/// Hands "Open in Basic Art" files to the home screen.
final class ImportRouter: ObservableObject {
    static let shared = ImportRouter()
    @Published var pending: URL?
}
