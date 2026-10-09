import SwiftUI

struct EditorRoute: Identifiable, Equatable {
    let id: String
}

struct HomeView: View {
    @StateObject private var model = HomeModel()
    @EnvironmentObject var settings: AppSettings
    @State private var showNew = false
    @State private var editing: EditorRoute?
    @State private var renaming: ProjectSummary?
    @State private var renameText = ""
    @State private var deleting: ProjectSummary?
    @State private var showSettings = false
    @State private var showImporter = false
    @State private var exported: URL?
    @State private var exportChoice = false
    @State private var shareExported = false
    @State private var saveExported = false
    @ObservedObject private var router = ImportRouter.shared
    @Environment(\.horizontalSizeClass) private var hSize

    private var columns: [GridItem] {
        [GridItem(.adaptive(minimum: hSize == .regular ? 190 : 150, maximum: 280), spacing: 16)]
    }

    private func importAndOpen(_ url: URL) {
        model.importPackage(url) { id in
            if let id { editing = EditorRoute(id: id) }
        }
    }

    /// The exported zip is only needed until the picker or share sheet is done with it.
    private func discardExported() {
        ProjectPackage.discardExport(exported)
        exported = nil
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVGrid(columns: columns, spacing: 18) {
                    NewTile { showNew = true }
                    ForEach(model.projects) { p in
                        ProjectTile(project: p, generation: model.thumbGeneration,
                                    onOpen: { if p.status == .ok { editing = EditorRoute(id: p.id) } },
                                    onRename: { renameText = p.name; renaming = p },
                                    onDuplicate: { model.duplicate(p) },
                                    onExportProject: {
                                        model.exportPackage(p) { url in
                                            if let url { exported = url; exportChoice = true }
                                        }
                                    },
                                    onDelete: { deleting = p })
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                if model.loaded && model.projects.isEmpty {
                    EmptyHint().padding(.top, 24).padding(.horizontal, 32)
                }
            }
            .ignoresSafeArea(.keyboard) // nothing to type here; never squash the grid (QA N6)
            .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
            .navigationTitle("Basic Art")
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button { showImporter = true } label: {
                        Label("Import", systemImage: "square.and.arrow.down").labelStyle(.titleAndIcon)
                            .font(.subheadline.weight(.semibold))
                            .frame(minHeight: 44)
                    }
                    .accessibilityLabel("Import project file")
                    .accessibilityIdentifier("importButton")
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { showSettings = true } label: {
                        Image(systemName: "gearshape").font(.system(size: 18, weight: .semibold))
                            .frame(width: 44, height: 44)
                    }
                    .accessibilityLabel("Settings")
                    .accessibilityIdentifier("settingsButton")
                }
            }
            .navigationDestination(isPresented: $showSettings) {
                SettingsView(onClearAll: { done in model.deleteAll(completion: done) })
            }
        }
        .sheet(isPresented: $showImporter) {
            ZipPicker { url in
                showImporter = false
                guard let url else { return }
                importAndOpen(url)
            }
            .ignoresSafeArea()
        }
        .onChange(of: router.pending) { url in
            guard let url else { return }
            router.pending = nil
            editing = nil
            importAndOpen(url)
        }
        // An alert, not a confirmation dialog: on iPad a dialog becomes a popover and drops Cancel.
        .alert("Project file ready", isPresented: $exportChoice) {
            Button("Save to Files") { saveExported = true }
            Button("Share…") { shareExported = true }
            Button("Cancel", role: .cancel) { discardExported() }
        } message: {
            Text("An editable copy of this project for another device.")
        }
        .sheet(isPresented: $shareExported, onDismiss: discardExported) {
            if let exported { ShareSheet(items: [exported]) { _ in } }
        }
        .sheet(isPresented: $saveExported, onDismiss: discardExported) {
            if let exported { DocumentExporter(url: exported) { _ in }.ignoresSafeArea() }
        }
        .overlay(alignment: .bottom) {
            if let msg = model.busyMessage {
                HStack(spacing: 10) { ProgressView(); Text(msg).font(.subheadline.weight(.medium)) }
                    .padding(.horizontal, 16).padding(.vertical, 11)
                    .background(.regularMaterial, in: Capsule())
                    .padding(.bottom, 24)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.snappy, value: model.busyMessage)
        .onAppear {
            if let url = router.pending { router.pending = nil; importAndOpen(url) }
            model.reload()
            #if DEBUG
            if let id = UserDefaults.standard.string(forKey: "openProject"), editing == nil {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { editing = EditorRoute(id: id) }
            }
            #endif
        }
        .sheet(isPresented: $showNew) {
            NewImageSheet(existingNames: Set(model.projects.map(\.name))) { doc, photo in
                showNew = false
                model.create(doc, photo: photo) { id in
                    if let id { editing = EditorRoute(id: id) }
                    model.reload()
                }
            }
        }
        .fullScreenCover(item: $editing, onDismiss: {
            ThumbnailCache.shared.removeAll()
            model.reload()
            model.thumbGeneration += 1
        }) { route in
            EditorContainer(projectID: route.id) { editing = nil }
        }
        .alert("Rename project", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("Name", text: $renameText)
                .accessibilityIdentifier("renameField")
            Button("Cancel", role: .cancel) { renaming = nil }
            Button("Rename") {
                if let p = renaming { model.rename(p, to: renameText) }
                renaming = nil
            }
        }
        .alert(deleting.map { "Delete '\($0.name)'?" } ?? "",
               isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
            Button("Cancel", role: .cancel) { deleting = nil }
            Button("Delete", role: .destructive) {
                if let p = deleting { model.delete(p) }
                deleting = nil
            }
        } message: {
            Text("This removes the project from Basic Art. Images you've already exported are not affected.")
        }
        .alert(model.errorTitle, isPresented: Binding(get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(model.errorMessage ?? "") }
    }
}

private struct EmptyHint: View {
    var body: some View {
        VStack(spacing: 8) {
            Text("Make something").font(.title3.weight(.semibold))
            Text("Tap New image to start a blank canvas, or start from a photo to make a meme or a caption in seconds.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: 420)
    }
}

/// Brand gradient from the app icon.
enum Brand {
    static let gradient = LinearGradient(colors: [Color(red: 0x33 / 255.0, green: 0x78 / 255.0, blue: 0xF5 / 255.0),
                                                  Color(red: 0x8C / 255.0, green: 0x40 / 255.0, blue: 0xE6 / 255.0)],
                                         startPoint: .topLeading, endPoint: .bottomTrailing)
    static let wave = Color(red: 1.0, green: 0.82, blue: 0.25)
}

/// Document picker for project files (.zip), copied into our sandbox.
struct ZipPicker: UIViewControllerRepresentable {
    var onPick: (URL?) -> Void
    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let vc = UIDocumentPickerViewController(forOpeningContentTypes: [.zip], asCopy: true)
        vc.delegate = context.coordinator
        return vc
    }
    func updateUIViewController(_ vc: UIDocumentPickerViewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }
    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPick: (URL?) -> Void
        init(onPick: @escaping (URL?) -> Void) { self.onPick = onPick }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) { onPick(urls.first) }
        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { onPick(nil) }
    }
}

struct NewTile: View {
    var action: () -> Void
    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 6) {
                ZStack {
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .fill(Brand.gradient)
                    // The icon's wave, subtle.
                    GeometryReader { g in
                        Path { p in
                            let w = g.size.width, h = g.size.height
                            p.move(to: CGPoint(x: w * 0.18, y: h * 0.78))
                            p.addCurve(to: CGPoint(x: w * 0.84, y: h * 0.73), control1: CGPoint(x: w * 0.40, y: h * 0.90), control2: CGPoint(x: w * 0.62, y: h * 0.60))
                        }
                        .stroke(Brand.wave.opacity(0.9), style: StrokeStyle(lineWidth: g.size.width * 0.05, lineCap: .round))
                    }
                    Image(systemName: "plus")
                        .font(.system(size: 30, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 64, height: 64)
                        .background(Circle().fill(.white.opacity(0.22)))
                        .overlay(Circle().strokeBorder(.white.opacity(0.35), lineWidth: 1))
                        .offset(y: -8)
                }
                .aspectRatio(1, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                .shadow(color: Color(red: 0.4, green: 0.3, blue: 0.9).opacity(0.28), radius: 10, y: 5)
                Text("New image").font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                Text("Blank or from a photo").font(.caption2).foregroundStyle(.secondary)
            }
        }
        .buttonStyle(TileButtonStyle())
        .accessibilityLabel("New image. Blank or from a photo")
        .accessibilityIdentifier("newTile")
    }
}

struct TileButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

struct ProjectTile: View {
    let project: ProjectSummary
    var generation: Int
    var onOpen: () -> Void
    var onRename: () -> Void
    var onDuplicate: () -> Void
    var onExportProject: () -> Void = {}
    var onDelete: () -> Void
    @State private var thumb: UIImage?

    private static let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .medium
        f.timeStyle = .none
        f.doesRelativeDateFormatting = true
        return f
    }()
    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .none
        f.timeStyle = .short
        return f
    }()
    static func relativeDate(_ d: Date) -> String {
        "\(dayFormatter.string(from: d)), \(timeFormatter.string(from: d))"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button(action: onOpen) {
                ZStack {
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .fill(Color(uiColor: .secondarySystemGroupedBackground))
                    content
                        .padding(10)
                }
                .aspectRatio(1, contentMode: .fit)
                .overlay(
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .strokeBorder(Color.primary.opacity(0.06))
                )
            }
            .buttonStyle(TileButtonStyle())
            .disabled(project.status != .ok)
            .accessibilityLabel(accessibilityText)
            .accessibilityIdentifier("projectTile")
            .overlay(alignment: .topTrailing) { menu }
            Text(project.status == .corrupt ? "Damaged project" : project.name)
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
                .foregroundStyle(.primary)
            Text(Self.relativeDate(project.modified))
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
        .task(id: "\(project.id)-\(generation)-\(project.hasThumb)") {
            guard project.hasThumb else { thumb = nil; return }
            thumb = await ThumbnailCache.shared.load(project.thumbURL)
        }
    }

    private var accessibilityText: String {
        switch project.status {
        case .ok: return "Open \(project.name)"
        case .corrupt: return "Can't open this project"
        case .newerVersion: return "\(project.name). Made with a newer version of Basic Art."
        }
    }

    @ViewBuilder private var content: some View {
        switch project.status {
        case .corrupt:
            VStack(spacing: 8) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 30)).foregroundStyle(.orange)
                Text("Can't open this project")
                    .font(.footnote.weight(.medium)).multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
            }
        case .newerVersion:
            ZStack {
                if let thumb { thumbView(thumb).opacity(0.35) }
                VStack(spacing: 6) {
                    Image(systemName: "arrow.up.circle.fill").font(.system(size: 28)).foregroundStyle(Color.accentColor)
                    Text("This project was made with a newer version of Basic Art. Update the app to open it.")
                        .font(.caption).multilineTextAlignment(.center).foregroundStyle(.secondary)
                }
            }
        case .ok:
            if let thumb { thumbView(thumb) } else {
                Image(systemName: "photo").font(.system(size: 28)).foregroundStyle(.tertiary)
            }
        }
    }

    private func thumbView(_ img: UIImage) -> some View {
        Image(uiImage: img)
            .resizable()
            .aspectRatio(contentMode: .fit)
            .background(Checkerboard(cell: 6))
            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
            .shadow(color: .black.opacity(0.12), radius: 3, y: 1)
    }

    private var menu: some View {
        Menu {
            if project.status == .ok {
                Button { onRename() } label: { Label("Rename", systemImage: "pencil") }
                Button { onDuplicate() } label: { Label("Duplicate", systemImage: "plus.square.on.square") }
                Button { onExportProject() } label: { Label("Export project file", systemImage: "archivebox") }
            }
            Button(role: .destructive) { onDelete() } label: { Label("Delete project", systemImage: "trash") }
        } label: {
            Image(systemName: "ellipsis")
                .rotationEffect(.degrees(90))
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(.primary)
                .frame(width: 30, height: 30)
                .background(.regularMaterial, in: Circle())
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .tint(.primary)
        .accessibilityLabel("More options for \(project.name)")
        .accessibilityIdentifier("tileMenu")
        .padding(2)
    }
}

/// Transparency checkerboard (workspace UI only).
struct Checkerboard: View {
    var cell: CGFloat = 8
    var body: some View {
        Image(uiImage: CheckerTile.image(cell: cell))
            .resizable(resizingMode: .tile)
    }
}

enum CheckerTile {
    static let light = UIColor(white: 1.0, alpha: 1)
    static let dark = UIColor(white: 0.84, alpha: 1)
    private static var cache: [CGFloat: UIImage] = [:]
    static func image(cell: CGFloat) -> UIImage {
        if let c = cache[cell] { return c }
        let size = CGSize(width: cell * 2, height: cell * 2)
        let img = UIGraphicsImageRenderer(size: size).image { ctx in
            light.setFill(); ctx.fill(CGRect(origin: .zero, size: size))
            dark.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: cell, height: cell))
            ctx.fill(CGRect(x: cell, y: cell, width: cell, height: cell))
        }
        cache[cell] = img
        return img
    }
}
