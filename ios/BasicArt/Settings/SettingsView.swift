import SwiftUI

struct SettingsView: View {
    var onClearAll: (@escaping () -> Void) -> Void
    @EnvironmentObject var settings: AppSettings
    @State private var confirmClear = false
    @State private var typeToConfirm = false
    @State private var cleared = false

    var version: String {
        let v = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0"
        let b = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1"
        return "\(v) (\(b))"
    }

    var body: some View {
        Form {
            Section {
                Picker("Theme", selection: $settings.theme) {
                    ForEach(AppTheme.allCases) { Text($0.label).tag($0) }
                }
                .pickerStyle(.segmented)
                .accessibilityIdentifier("themePicker")
                .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16))
            } header: { Text("Appearance") } footer: {
                Text("Only the app around your artwork changes. Your canvas always looks the same.")
                    .padding(.bottom, 8)
            }

            Section("Editor") {
                Toggle(isOn: $settings.snapping) {
                    Label("Snapping", systemImage: "square.dashed.inset.filled")
                }
                .accessibilityHint("Snap layers to the canvas center and edges, and rotation to 45 degree steps")
            }

            Section {
                Picker(selection: $settings.exportFormat) {
                    ForEach(ExportFormat.allCases) { Text($0.label).tag($0) }
                } label: { Label("Default format", systemImage: "square.and.arrow.up") }
                if settings.exportFormat == .jpeg {
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Label("JPG quality", systemImage: "slider.horizontal.3")
                            Spacer()
                            Text("\(Int(settings.jpegQuality))").monospacedDigit().foregroundStyle(.secondary)
                        }
                        Slider(value: $settings.jpegQuality, in: 50...100, step: 1)
                            .accessibilityLabel("JPG quality")
                    }
                }
            } header: { Text("Export") }

            Section {
                Button(role: .destructive) { confirmClear = true } label: {
                    Label("Clear all project history", systemImage: "trash")
                        .foregroundStyle(.red)
                }
                .accessibilityIdentifier("clearAllButton")
            } footer: {
                if cleared { Text("All projects were deleted.") }
            }

            Section("About") {
                NavigationLink {
                    AboutView(version: version)
                } label: { Label("About Basic Art", systemImage: "info.circle") }
                NavigationLink {
                    LicensesView()
                } label: { Label("Licenses & Credits", systemImage: "doc.text") }
                    .accessibilityIdentifier("licensesLink")
            }
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .alert("Delete ALL projects?", isPresented: $confirmClear) {
            Button("Cancel", role: .cancel) {}
            Button("Delete everything", role: .destructive) { typeToConfirm = true }
        } message: {
            Text("This permanently removes every project from Basic Art and cannot be undone. Images you've already exported are not affected.")
        }
        .sheet(isPresented: $typeToConfirm) {
            TypeDeleteSheet {
                onClearAll { cleared = true }
            }
        }
    }
}

private struct TypeDeleteSheet: View {
    var onConfirm: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @FocusState private var focused: Bool

    var enabled: Bool { text == "DELETE" }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 40)).foregroundStyle(.red)
                        .accessibilityHidden(true)
                    Text("Last check").font(.title2.weight(.bold))
                    Text("This permanently removes every project from Basic Art and cannot be undone. Images you've already exported are not affected.")
                        .fixedSize(horizontal: false, vertical: true)
                        .foregroundStyle(.secondary)
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Type DELETE to confirm").font(.subheadline.weight(.semibold))
                        TextField("", text: $text, prompt: Text("Type here").foregroundColor(Color(uiColor: .placeholderText)))
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                            .focused($focused)
                            .font(.title3.monospaced())
                            .padding(12)
                            .background(RoundedRectangle(cornerRadius: 12).fill(Color(uiColor: .tertiarySystemFill)))
                            .accessibilityIdentifier("deleteConfirmField")
                    }
                    Button {
                        onConfirm()
                        dismiss()
                    } label: {
                        Text("Delete everything")
                            .font(.headline)
                            .foregroundStyle(enabled ? Color.white : Color.red.opacity(0.75))
                            .frame(maxWidth: .infinity, minHeight: 50)
                            .background(RoundedRectangle(cornerRadius: 14, style: .continuous)
                                .fill(enabled ? Color.red : Color.red.opacity(0.14)))
                            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous)
                                .strokeBorder(Color.red.opacity(enabled ? 0 : 0.45), lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .disabled(!enabled)
                    .accessibilityIdentifier("deleteEverythingButton")
                }
                .padding(24)
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
        }
        .presentationDetents([.large])
        .onAppear { focused = true }
    }
}

struct AboutView: View {
    var version: String
    static let sourceURL = "github.com/danavfrost/basicart"

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                Image(uiImage: UIImage(named: "AboutIcon") ?? UIImage())
                    .resizable()
                    .frame(width: 96, height: 96)
                    .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                    .shadow(color: .black.opacity(0.15), radius: 10, y: 4)
                    .accessibilityHidden(true)
                VStack(spacing: 4) {
                    Text("Basic Art").font(.title.weight(.bold))
                    Text("Version \(version)").font(.subheadline).foregroundStyle(.secondary)
                }
                Text("No ads. No accounts. No internet. No in-app purchases. A simple, basic art editing tool.")
                    .font(.body)
                    .multilineTextAlignment(.center)
                VStack(alignment: .leading, spacing: 12) {
                    Label {
                        Text("Basic Art collects no data and makes no network connections. Your projects are kept on this device (and in your device backups). Uninstalling the app deletes them; images you've exported stay where you saved them.")
                    } icon: { Image(systemName: "lock.shield") }
                    Label {
                        Text("Basic Art is open source. Source code: \(Self.sourceURL)")
                            .textSelection(.enabled)
                    } icon: { Image(systemName: "chevron.left.forwardslash.chevron.right") }
                }
                .font(.footnote)
                .foregroundStyle(.secondary)
                .padding(16)
                .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color(uiColor: .secondarySystemGroupedBackground)))
            }
            .padding(24)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("About")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct LicensesView: View {
    @State private var query = ""
    let fonts = FontCatalog.shared.allFonts.sorted { $0.family.localizedCaseInsensitiveCompare($1.family) == .orderedAscending }

    var filtered: [FontFamily] {
        query.isEmpty ? fonts : fonts.filter { $0.family.localizedCaseInsensitiveContains(query) || $0.author.localizedCaseInsensitiveContains(query) }
    }

    var body: some View {
        List {
            Section {
                NavigationLink {
                    LicenseTextView(title: "Basic Art", text: AppLicense.mit)
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Basic Art").font(.body.weight(.semibold))
                        Text("MIT License").font(.footnote).foregroundStyle(.secondary)
                    }
                }
            } header: { Text("App") } footer: {
                Text("Basic Art uses no third-party code libraries.")
            }
            Section("Fonts (\(fonts.count))") {
                ForEach(filtered) { f in
                    NavigationLink {
                        LicenseTextView(title: f.family, text: FontCatalog.shared.licenseText(f),
                                        header: "\(f.copyright)\nDesigned by \(f.author)\n\(f.licenseName)")
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(f.family).font(.body.weight(.semibold))
                            Text("\(f.author) · \(f.licenseName)").font(.footnote).foregroundStyle(.secondary).lineLimit(2)
                        }
                    }
                }
            }
        }
        .searchable(text: $query, prompt: "Search fonts")
        .navigationTitle("Licenses & Credits")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct LicenseTextView: View {
    var title: String
    var text: String
    var header: String? = nil
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if let header {
                    Text(header).font(.subheadline.weight(.medium))
                }
                Text(text).font(.footnote.monospaced()).textSelection(.enabled)
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }
}

enum AppLicense {
    static let mit = """
    MIT License

    Copyright (c) 2026 Dana Frost

    Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
    """
}
