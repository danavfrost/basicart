import PhotosUI
import SwiftUI

struct SizePreset: Identifiable, Hashable {
    var id: String { name }
    var name: String
    var width: Int
    var height: Int

    static let all: [SizePreset] = [
        SizePreset(name: "Square", width: 1080, height: 1080),
        SizePreset(name: "Portrait", width: 1080, height: 1350),
        SizePreset(name: "Story/Phone", width: 1080, height: 1920),
        SizePreset(name: "Landscape", width: 1920, height: 1080),
        SizePreset(name: "Banner", width: 1500, height: 500),
        SizePreset(name: "Meme", width: 1200, height: 1200),
        SizePreset(name: "A4 @150dpi", width: 1240, height: 1754),
    ]
}

enum BackgroundChoice: String, CaseIterable, Identifiable {
    case white, transparent, color
    var id: String { rawValue }
    var label: String { rawValue.capitalized }
}

struct NewImageSheet: View {
    var existingNames: Set<String> = []
    var onCreate: (Document, ImportedImage?) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var width = 1080
    @State private var height = 1080
    @State private var lockAspect = false
    @State private var preset: String? = "Square"
    @State private var background: BackgroundChoice = .white
    @State private var bgColor = RGBA(hex: "#FFD23FFF")!
    @State private var name = ""
    @State private var showPicker = false
    @State private var showColor = false
    @State private var loadingPhoto = false
    @State private var photoError = false

    /// "Untitled", then "Untitled 2", "Untitled 3"… (§4).
    var defaultName: String {
        if !existingNames.contains("Untitled") { return "Untitled" }
        var n = 2
        while existingNames.contains("Untitled \(n)") { n += 1 }
        return "Untitled \(n)"
    }

    var isLarge: Bool { width * height > 6000 * 6000 || Double(width * height * 4) > Double(ProcessInfo.processInfo.physicalMemory) / 16 }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Button {
                        showPicker = true
                    } label: {
                        HStack(spacing: 14) {
                            Image(systemName: "photo.on.rectangle.angled")
                                .font(.system(size: 22, weight: .semibold))
                                .foregroundStyle(.white)
                                .frame(width: 44, height: 44)
                                .background(RoundedRectangle(cornerRadius: 11, style: .continuous).fill(Color.accentColor))
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Start from photo").font(.body.weight(.semibold)).foregroundStyle(.primary)
                                Text("Canvas matches the photo — great for memes").font(.footnote).foregroundStyle(.secondary)
                            }
                            Spacer()
                            if loadingPhoto { ProgressView() } else {
                                Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("startFromPhoto")
                    .disabled(loadingPhoto)
                }

                Section("Size") {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(SizePreset.all) { p in
                                Chip(title: p.name, subtitle: "\(p.width)×\(p.height)", selected: preset == p.name) {
                                    preset = p.name
                                    width = p.width; height = p.height
                                }
                                .accessibilityIdentifier("size-\(p.name)")
                            }
                            Chip(title: "Custom", subtitle: "Any size", selected: preset == nil) { preset = nil }
                        }
                        .padding(.vertical, 4)
                    }
                    .listRowInsets(EdgeInsets(top: 6, leading: 12, bottom: 6, trailing: 12))
                    HStack(spacing: 10) {
                        PixelField(label: "Width", value: $width, onChange: { new, old in
                            preset = nil
                            if lockAspect, old > 0 { height = clampSide(Int((Double(new) * Double(height) / Double(old)).rounded())) }
                        }, onEdit: { preset = nil })
                        Button {
                            lockAspect.toggle()
                        } label: {
                            Image(systemName: lockAspect ? "lock.fill" : "lock.open")
                                .frame(width: 44, height: 44)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel(lockAspect ? "Aspect ratio locked" : "Aspect ratio unlocked")
                        PixelField(label: "Height", value: $height, onChange: { new, old in
                            preset = nil
                            if lockAspect, old > 0 { width = clampSide(Int((Double(new) * Double(width) / Double(old)).rounded())) }
                        }, onEdit: { preset = nil })
                    }
                    if isLarge {
                        Label("This is a very large canvas. It may be slow or use a lot of memory on this device.", systemImage: "exclamationmark.triangle")
                            .font(.footnote).foregroundStyle(.orange)
                    }
                }

                Section("Background") {
                    Picker("Background", selection: $background) {
                        ForEach(BackgroundChoice.allCases) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("backgroundPicker")
                    if background == .color {
                        Button { showColor = true } label: {
                            HStack {
                                Text("Color").foregroundStyle(.primary)
                                Spacer()
                                ColorSwatch(color: bgColor, size: 28)
                            }
                        }
                    }
                }

                Section("Name") {
                    TextField(defaultName, text: $name)
                        .accessibilityIdentifier("nameField")
                }
            }
            .navigationTitle("New Image")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") { create(photo: nil) }
                        .fontWeight(.semibold)
                        .accessibilityIdentifier("createButton")
                }
            }
            .sheet(isPresented: $showPicker) {
                PhotoPicker(selectionLimit: 1) { results in
                    showPicker = false
                    guard !results.isEmpty else { return }
                    loadingPhoto = true
                    Task {
                        let imgs = await ImageImporter.load(results)
                        loadingPhoto = false
                        if let img = imgs.first { create(photo: img) } else { photoError = true }
                    }
                }
                .ignoresSafeArea()
            }
            .sheet(isPresented: $showColor) {
                ColorPickerSheet(title: "Background", color: bgColor, allowsAlpha: true) { bgColor = $0 }
            }
            .alert("Couldn't open that photo", isPresented: $photoError) { Button("OK", role: .cancel) {} }
        }
    }

    private func clampSide(_ v: Int) -> Int { min(Canvas.maxSide, max(Canvas.minSide, v)) }

    private func create(photo: ImportedImage?) {
        var w = clampSide(width), h = clampSide(height)
        if let photo {
            let longest = Double(max(photo.naturalWidth, photo.naturalHeight))
            let s = min(1, Double(Canvas.maxSide) / longest)
            w = clampSide(Int((Double(photo.naturalWidth) * s).rounded()))
            h = clampSide(Int((Double(photo.naturalHeight) * s).rounded()))
        }
        let bg: RGBA
        switch background {
        case .white: bg = .white
        case .transparent: bg = .clear
        case .color: bg = bgColor
        }
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        var doc = Document(name: String((trimmed.isEmpty ? defaultName : trimmed).prefix(100)),
                           canvas: Canvas(width: w, height: h, background: bg))
        doc.created = Date(); doc.modified = Date()
        onCreate(doc, photo)
    }
}

struct Chip: View {
    var title: String
    var subtitle: String?
    var selected: Bool
    var action: () -> Void
    var body: some View {
        Button(action: action) {
            VStack(spacing: 1) {
                Text(title).font(.subheadline.weight(.semibold))
                if let subtitle { Text(subtitle).font(.caption2).opacity(0.75) }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .frame(minHeight: 46)
            .foregroundStyle(selected ? Color.white : Color.primary)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(selected ? Color.accentColor : Color(uiColor: .tertiarySystemFill))
            )
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// Integer pixel entry with min/max clamp on commit.
struct PixelField: View {
    var label: String
    @Binding var value: Int
    var onChange: (_ new: Int, _ old: Int) -> Void
    var onEdit: () -> Void = {}
    @State private var text = ""
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            HStack(spacing: 4) {
                TextField(label, text: $text)
                    .keyboardType(.numberPad)
                    .focused($focused)
                    .font(.body.monospacedDigit())
                    .onSubmit(commit)
                Text("px").font(.caption).foregroundStyle(.secondary)
            }
            .padding(.horizontal, 10)
            .frame(minHeight: 40)
            .background(RoundedRectangle(cornerRadius: 9).fill(Color(uiColor: .tertiarySystemFill)))
        }
        .onAppear { text = "\(value)" }
        .onChange(of: value) { v in if !focused { text = "\(v)" } }
        .onChange(of: focused) { f in if !f { commit() } }
        .onChange(of: text) { t in
            if focused && t != "\(value)" { onEdit() }
            if focused, let v = Int(t), v >= Canvas.minSide, v <= Canvas.maxSide, v != value {
                let old = value
                value = v
                onChange(v, old)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(label)
    }

    private func commit() {
        let v = min(Canvas.maxSide, max(Canvas.minSide, Int(text) ?? value))
        if v != value { let old = value; value = v; onChange(v, old) }
        text = "\(v)"
    }
}
