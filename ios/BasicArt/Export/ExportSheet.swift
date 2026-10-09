import Photos
import SwiftUI
import UniformTypeIdentifiers

enum ExportSize: Hashable {
    case full, half, quarter, custom
}

enum Exporter {
    struct Options {
        var format: ExportFormat
        var jpegQuality: Double // 50–100
        var pixelWidth: Int
        var pixelHeight: Int
    }

    static func pixelSize(for doc: Document, size: ExportSize, customLongEdge: Int) -> (Int, Int) {
        let W = Double(doc.canvas.width), H = Double(doc.canvas.height)
        let s: Double
        switch size {
        case .full: s = 1
        case .half: s = 0.5
        case .quarter: s = 0.25
        case .custom: s = Double(max(1, min(16384, customLongEdge))) / max(W, H)
        }
        return (max(1, Int((W * s).rounded())), max(1, Int((H * s).rounded())))
    }

    /// Renders from the model at full resolution and encodes. Call off the main thread.
    static func export(_ doc: Document, assets: AssetProvider, options: Options) -> Data? {
        let flatten: RGBA? = options.format == .jpeg ? .white : nil
        guard let img = Renderer.renderImage(doc, pixelWidth: options.pixelWidth, pixelHeight: options.pixelHeight,
                                             assets: assets, options: RenderOptions(), opaqueBackground: flatten) else { return nil }
        switch options.format {
        case .png: return ImageEncoder.png(img)
        case .jpeg: return ImageEncoder.jpeg(img, quality: options.jpegQuality / 100)
        case .gif: return GIFEncoder.encode(img)
        }
    }

    static func sanitizedFileName(_ s: String) -> String {
        let bad = CharacterSet(charactersIn: "/\\?%*|\"<>:").union(.newlines).union(.controlCharacters)
        let cleaned = s.components(separatedBy: bad).joined(separator: "-").trimmingCharacters(in: .whitespaces)
        return cleaned.isEmpty ? "Basic Art" : String(cleaned.prefix(100))
    }
}

struct ExportSheet: View {
    let doc: Document
    let assets: AssetProvider
    /// Flushes autosave (and the editor state) before packing the project file.
    var prepareProject: (@escaping () -> Void) -> Void = { $0() }
    @State private var projectBusy = false
    @EnvironmentObject var settings: AppSettings
    @Environment(\.dismiss) private var dismiss
    @State private var format: ExportFormat = .png
    @State private var quality: Double = 90
    @State private var size: ExportSize = .full
    @State private var customEdge = 2048
    @State private var fileName = ""
    @State private var working = false
    @State private var message: (String, Bool)?
    @State private var shareURL: URL?
    @State private var showShare = false
    @State private var showFiles = false
    @State private var savedToPhotos = false
    @State private var banner: (String, Bool)?

    var pixels: (Int, Int) { Exporter.pixelSize(for: doc, size: size, customLongEdge: customEdge) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack {
                        Spacer()
                        ExportPreview(doc: doc, assets: assets)
                            .frame(height: 150)
                        Spacer()
                    }
                    .listRowBackground(Color.clear)
                }
                Section {
                    Button {
                        projectBusy = true
                        prepareProject {
                            DispatchQueue.global(qos: .userInitiated).async {
                                let url = try? ProjectPackage.export(id: doc.id)
                                DispatchQueue.main.async {
                                    projectBusy = false
                                    if let url { shareURL = url; showShare = true }
                                    else { message = ("Couldn't export the project file", false) }
                                }
                            }
                        }
                    } label: {
                        HStack {
                            Label("Export project file", systemImage: "archivebox")
                            Spacer()
                            if projectBusy { ProgressView() } else { Text(".zip").foregroundStyle(.secondary) }
                        }
                        .frame(minHeight: 36)
                    }
                    .disabled(projectBusy)
                    .accessibilityIdentifier("exportProjectFile")
                } header: { Text("Editable project file") } footer: {
                    Text("An editable copy of the whole project — layers, text styles, photos and drawings — to open in Basic Art on another device. Save it to Files or share it.")
                }
                Section("Format") {
                    Picker("Format", selection: $format) {
                        ForEach(ExportFormat.allCases) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("formatPicker")
                    Text(formatNote).font(.footnote).foregroundStyle(.secondary)
                    if format == .jpeg {
                        LabeledSlider(label: "Quality", value: $quality, range: 50...100, step: 1)
                    }
                }
                Section("Size") {
                    Picker("Size", selection: $size) {
                        Text("100%").tag(ExportSize.full)
                        Text("50%").tag(ExportSize.half)
                        Text("25%").tag(ExportSize.quarter)
                        Text("Custom").tag(ExportSize.custom)
                    }
                    .pickerStyle(.segmented)
                    if size == .custom {
                        HStack {
                            Text("Long edge").foregroundStyle(.secondary)
                            Spacer()
                            TextField("px", value: $customEdge, format: .number)
                                .keyboardType(.numberPad)
                                .multilineTextAlignment(.trailing)
                                .frame(width: 90)
                            Text("px").foregroundStyle(.secondary)
                        }
                    }
                    HStack {
                        Text("Output")
                        Spacer()
                        Text("\(pixels.0) × \(pixels.1) px").monospacedDigit().foregroundStyle(.secondary)
                    }
                }
                Section("File name") {
                    HStack {
                        TextField("Name", text: $fileName).accessibilityIdentifier("exportFileName")
                        Text(".\(format.fileExtension)").foregroundStyle(.secondary)
                    }
                }
                if let (m, ok) = message {
                    Section {
                        Label(m, systemImage: ok ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                            .foregroundStyle(ok ? .green : .orange)
                            .font(.subheadline.weight(.medium))
                            .accessibilityIdentifier("exportMessage")
                    }
                }
            }
            .safeAreaInset(edge: .bottom) {
                HStack(spacing: 10) {
                    Button { saveToPhotos() } label: {
                        Label(savedToPhotos ? "Saved to Photos" : "Save to Photos",
                              systemImage: savedToPhotos ? "checkmark.circle.fill" : "photo.on.rectangle")
                            .font(.headline)
                            .frame(maxWidth: .infinity, minHeight: 50)
                    }
                    .buttonStyle(.borderedProminent)
                    .buttonBorderShape(.roundedRectangle(radius: 14))
                    .accessibilityIdentifier("saveToPhotos")
                    Button { prepare { url in shareURL = url; showFiles = true } } label: {
                        Image(systemName: "folder").font(.title3.weight(.medium)).frame(width: 30, height: 50)
                    }
                    .buttonStyle(.bordered)
                    .buttonBorderShape(.roundedRectangle(radius: 14))
                    .accessibilityLabel("Save to Files")
                    .accessibilityIdentifier("saveToFiles")
                    Button { prepare { url in shareURL = url; showShare = true } } label: {
                        Image(systemName: "square.and.arrow.up").font(.title3.weight(.medium)).frame(width: 30, height: 50)
                    }
                    .buttonStyle(.bordered)
                    .buttonBorderShape(.roundedRectangle(radius: 14))
                    .accessibilityLabel("Share")
                    .accessibilityIdentifier("shareButton")
                }
                .padding(.horizontal, 16)
                .padding(.top, 10)
                .padding(.bottom, 6)
                .background(.bar)
            }
            .disabled(working)
            .overlay(alignment: .top) {
                if let (m, ok) = banner {
                    Label(m, systemImage: ok ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(ok ? Color.green : Color.orange)
                        .padding(.horizontal, 16).padding(.vertical, 10)
                        .background(.regularMaterial, in: Capsule())
                        .shadow(color: .black.opacity(0.15), radius: 8, y: 3)
                        .padding(.top, 8)
                        .transition(.move(edge: .top).combined(with: .opacity))
                        .accessibilityIdentifier("exportBanner")
                }
            }
            .animation(.snappy, value: banner?.0)
            .onChange(of: message?.0) { m in
                guard let m, let ok = message?.1 else { return }
                banner = (m, ok)
                UIAccessibility.post(notification: .announcement, argument: m)
                DispatchQueue.main.asyncAfter(deadline: .now() + 2.6) { if banner?.0 == m { banner = nil } }
            }
            .overlay {
                if working {
                    VStack(spacing: 12) {
                        ProgressView().controlSize(.large)
                        Text("Exporting \(pixels.0) × \(pixels.1)…").font(.subheadline)
                    }
                    .padding(24)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                }
            }
            .navigationTitle("Export")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() }.fontWeight(.semibold) }
            }
            .sheet(isPresented: $showShare, onDismiss: discardShared) {
                if let shareURL { ShareSheet(items: [shareURL]) { completed in if completed { message = ("Shared", true) } } }
            }
            .sheet(isPresented: $showFiles, onDismiss: discardShared) {
                if let shareURL {
                    DocumentExporter(url: shareURL) { ok in if ok { message = ("Saved to Files", true) } }
                        .ignoresSafeArea()
                }
            }
        }
        .onAppear {
            format = settings.exportFormat
            quality = settings.jpegQuality
            fileName = Exporter.sanitizedFileName(doc.name)
            customEdge = max(doc.canvas.width, doc.canvas.height)
        }
    }

    private var formatNote: String {
        switch format {
        case .png: return "Lossless. Keeps transparency."
        case .jpeg: return "JPG: smaller files. Transparent areas become white."
        case .gif: return "256 colors with dithering. Keeps transparency."
        }
    }

    private func render(_ completion: @escaping (Data?) -> Void) {
        working = true
        message = nil
        savedToPhotos = false
        let opts = Exporter.Options(format: format, jpegQuality: quality, pixelWidth: pixels.0, pixelHeight: pixels.1)
        let doc = self.doc, assets = self.assets
        DispatchQueue.global(qos: .userInitiated).async {
            let data = autoreleasepool { Exporter.export(doc, assets: assets, options: opts) }
            DispatchQueue.main.async {
                working = false
                if data == nil { message = ("Export failed. Try a smaller size.", false) }
                completion(data)
            }
        }
    }

    /// Exported files live in tmp only until the share sheet / Files picker is done with them.
    private func discardShared() {
        ProjectPackage.discardExport(shareURL)
        shareURL = nil
    }

    private func prepare(_ then: @escaping (URL) -> Void) {
        render { data in
            guard let data else { return }
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent("Export", isDirectory: true)
            try? FileManager.default.removeItem(at: dir)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let url = dir.appendingPathComponent("\(Exporter.sanitizedFileName(fileName)).\(format.fileExtension)")
            do {
                try data.write(to: url, options: .atomic)
                then(url)
            } catch {
                message = ("Couldn't write the file.", false)
            }
        }
    }

    private func saveToPhotos() {
        render { data in
            guard let data else { return }
            PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
                guard status == .authorized || status == .limited else {
                    DispatchQueue.main.async { message = ("Basic Art isn't allowed to add photos. You can change this in Settings.", false) }
                    return
                }
                PHPhotoLibrary.shared().performChanges({
                    let opts = PHAssetResourceCreationOptions()
                    opts.originalFilename = "\(Exporter.sanitizedFileName(fileName)).\(format.fileExtension)"
                    PHAssetCreationRequest.forAsset().addResource(with: .photo, data: data, options: opts)
                }) { ok, _ in
                    DispatchQueue.main.async {
                        message = ok ? ("Saved to Photos", true) : ("Couldn't save to Photos", false)
                        savedToPhotos = ok
                        if ok { UINotificationFeedbackGenerator().notificationOccurred(.success) }
                    }
                }
            }
        }
    }
}

struct ExportPreview: View {
    let doc: Document
    let assets: AssetProvider
    @State private var image: UIImage?
    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().aspectRatio(contentMode: .fit)
                    .background(Checkerboard(cell: 6))
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                    .shadow(color: .black.opacity(0.18), radius: 8, y: 3)
            } else {
                ProgressView()
            }
        }
        .accessibilityLabel("Preview of \(doc.name)")
        .task {
            let doc = doc, assets = assets
            let img = await Task.detached(priority: .userInitiated) { () -> UIImage? in
                let s = min(1, 480 / CGFloat(max(doc.canvas.width, doc.canvas.height)))
                var o = RenderOptions(); o.imageMaxPixel = 1024
                return Renderer.renderImage(doc, scale: s, assets: assets, options: o).map { UIImage(cgImage: $0) }
            }.value
            image = img
        }
    }
}

struct ShareSheet: UIViewControllerRepresentable {
    var items: [Any]
    var onComplete: (Bool) -> Void
    func makeUIViewController(context: Context) -> UIActivityViewController {
        let vc = UIActivityViewController(activityItems: items, applicationActivities: nil)
        vc.completionWithItemsHandler = { _, completed, _, _ in onComplete(completed) }
        return vc
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}

struct DocumentExporter: UIViewControllerRepresentable {
    var url: URL
    var onComplete: (Bool) -> Void
    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let vc = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
        vc.delegate = context.coordinator
        return vc
    }
    func updateUIViewController(_ vc: UIDocumentPickerViewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(onComplete: onComplete) }
    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onComplete: (Bool) -> Void
        init(onComplete: @escaping (Bool) -> Void) { self.onComplete = onComplete }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) { onComplete(true) }
        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { onComplete(false) }
    }
}
