import PhotosUI
import SwiftUI

/// Loads a project off the main thread, then shows the editor.
struct EditorContainer: View {
    let projectID: String
    var onClose: () -> Void
    @State private var model: EditorModel?
    @State private var failed = false

    var body: some View {
        ZStack {
            Color(uiColor: .systemGroupedBackground).ignoresSafeArea()
            if let model {
                EditorView(model: model, onClose: onClose)
            } else if failed {
                VStack(spacing: 12) {
                    Image(systemName: "exclamationmark.triangle.fill").font(.largeTitle).foregroundStyle(.orange)
                    Text("Can't open this project").font(.headline)
                    Button("Back to projects", action: onClose).buttonStyle(.borderedProminent)
                }
            } else {
                ProgressView()
            }
        }
        .task {
            // .task can run again during presentation transitions; load exactly once so the
            // canvas and the panels always share one EditorModel.
            guard model == nil, !failed else { return }
            let store = ProjectStore.shared
            let id = projectID
            let (result, state) = await Task.detached(priority: .userInitiated) { () -> (Result<Document, LoadFailure>, EditorState?) in
                store.cleanupTemporaryFiles(id: id)
                return (store.load(id: id), store.loadEditorState(id: id))
            }.value
            guard model == nil else { return }
            switch result {
            case .success(let doc):
                let m = EditorModel(doc: doc)
                if let state {
                    m.pendingState = state
                    m.restore(state)
                }
                model = m
            case .failure: failed = true
            }
        }
    }
}

/// How far the docked software keyboard covers the window, from UIKit's own keyboard notifications.
///
/// The editor does its own keyboard avoidance instead of using SwiftUI's keyboard safe area:
/// after a text alert (e.g. Rename) on iPad, iOS can keep reporting a stale keyboard-height
/// bottom inset to SwiftUI once the interface style changes (Settings → Theme), squashing the
/// editor into the top half of the screen until relaunch (QA N6). These notifications always
/// end with the real hidden frame, so this value can't go stale.
final class KeyboardInset: ObservableObject {
    static let shared = KeyboardInset()
    @Published private(set) var height: CGFloat = 0

    private init() {
        let nc = NotificationCenter.default
        nc.addObserver(forName: UIResponder.keyboardWillChangeFrameNotification, object: nil, queue: .main) { [weak self] n in
            self?.update(n)
        }
        nc.addObserver(forName: UIResponder.keyboardWillHideNotification, object: nil, queue: .main) { [weak self] _ in
            self?.set(0)
        }
        nc.addObserver(forName: UIResponder.keyboardDidHideNotification, object: nil, queue: .main) { [weak self] _ in
            self?.set(0)
        }
    }

    private func update(_ n: Notification) {
        guard let end = n.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect,
              let window = (UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
                .flatMap(\.windows).first { $0.isKeyWindow }) else { set(0); return }
        let f = window.convert(end, from: window.screen.coordinateSpace)
        let b = window.bounds
        // Only a keyboard docked to the bottom edge takes room; floating/undocked ones don't.
        guard f.height > 0, f.maxY >= b.maxY - 1, f.minY < b.maxY else { set(0); return }
        set(max(0, b.maxY - f.minY))
    }

    private func set(_ h: CGFloat) {
        if abs(h - height) > 0.5 { height = h }
    }
}

struct EditorView: View {
    @ObservedObject var model: EditorModel
    @ObservedObject private var keyboard = KeyboardInset.shared
    var onClose: () -> Void
    @EnvironmentObject var settings: AppSettings
    @Environment(\.horizontalSizeClass) private var hSize
    @Environment(\.verticalSizeClass) private var vSize
    @Environment(\.scenePhase) private var scenePhase
    @State private var showPicker = false
    @State private var showExport = false
    @State private var renamingProject = false
    @State private var projectName = ""
    @State private var renamingLayerID: String?
    @State private var layerName = ""
    @State private var importing = false
    @State private var canvasView: CanvasView?
    @State private var closing = false

    private var sideLayout: Bool { hSize == .regular || vSize == .compact }
    /// The side column is shown (docked) unless the user collapsed it.
    /// Phones in landscape always use the side column (a bottom sheet would bury the canvas).
    private var phoneLandscape: Bool { vSize == .compact }
    private var docked: Bool {
        guard sideLayout else { return false }
        if phoneLandscape {
            // Nothing to show (no options, no layers): give the canvas the whole width.
            return !model.phoneSideCollapsed && (ContextPanelContent.hasContent(model) || model.showLayers)
        }
        return !model.sideCollapsed
    }
    /// Layers list height in the side column: shorter in landscape so options keep room.
    private var layersMaxHeight: CGFloat { vSize == .compact || isWide ? 210 : 300 }
    private var isWide: Bool {
        let b = (UIApplication.shared.connectedScenes.first as? UIWindowScene)?.windows.first?.bounds ?? .zero
        return b.width > b.height
    }

    /// Typing on the canvas: the keyboard bar replaces the tool bar and options.
    private var typing: Bool { model.editingTextID != nil && !model.textStyling }

    private func setCollapsed(_ c: Bool) {
        if phoneLandscape { model.phoneSideCollapsed = c } else { model.sideCollapsed = c }
    }

    private func toggleLayers() {
        withAnimation(.snappy(duration: 0.3)) {
            if sideLayout {
                if !docked { setCollapsed(false); model.showLayers = true }
                else if !model.showLayers { model.showLayers = true }
                else { setCollapsed(true) }
            } else {
                model.showLayers.toggle()
            }
        }
    }

    var body: some View {
        GeometryReader { geo in
            let _ = { model.fitReserve = (!sideLayout) ? max(180, geo.size.height * 0.42) * 0.85 : 0 }()
            VStack(spacing: 0) {
                EditorTopBar(model: model, layersActive: sideLayout ? docked && model.showLayers : model.showLayers,
                             onLayers: toggleLayers, onBack: close, onRename: {
                    projectName = model.doc.name
                    renamingProject = true
                }, onExport: { model.endTextEditing(); showExport = true })
                HStack(spacing: 0) {
                    ZStack(alignment: .top) {
                        CanvasRepresentable(model: model) { v in DispatchQueue.main.async { canvasView = v } }
                            .id(ObjectIdentifier(model))
                            .accessibilityIdentifier("canvas")
                        overlays
                    }
                    .overlay(alignment: .bottomTrailing) {
                        if model.viewportZoomed && model.editingTextID == nil {
                            Button { model.fitRequest += 1 } label: {
                                Image(systemName: "arrow.down.right.and.arrow.up.left")
                                    .font(.system(size: 15, weight: .semibold))
                                    .frame(width: 44, height: 44)
                                    .background(.regularMaterial, in: Circle())
                                    .shadow(color: .black.opacity(0.15), radius: 6, y: 2)
                            }
                            .buttonStyle(.plain)
                            .padding(12)
                            .accessibilityLabel("Fit canvas to screen")
                            .transition(.scale.combined(with: .opacity))
                        }
                    }
                    .overlay(alignment: .trailing) {
                        if sideLayout && !docked {
                            SidePanelHandle(expanded: false) {
                                withAnimation(.snappy(duration: 0.3)) {
                                    setCollapsed(false)
                                    if !ContextPanelContent.hasContent(model) { model.showLayers = true }
                                }
                            }
                            .transition(.move(edge: .trailing).combined(with: .opacity))
                        }
                    }
                    .animation(.snappy, value: model.viewportZoomed)
                    .clipped()
                    // While typing, the canvas runs under the keyboard (it keeps the text visible itself);
                    // side panels stop above it.
                    if docked {
                        sideColumn
                            .padding(.bottom, typing ? kbPad(geo) : 0)
                            .environment(\.fontBrowserHeight, max(300, geo.size.height - (model.showLayers ? layersMaxHeight : 0) - 170))
                            .frame(width: min(380, max(hSize == .regular ? 350 : 300, geo.size.width * 0.32)))
                            .overlay(alignment: .leading) {
                                SidePanelHandle(expanded: true) {
                                    withAnimation(.snappy(duration: 0.3)) { setCollapsed(true) }
                                }
                                .offset(x: -30)
                            }
                            .transition(.move(edge: .trailing))
                    }
                }
                if !docked && !phoneLandscape && !typing {
                    ContextPanelHost(model: model, onEyedropper: startEyedropper, onAddImage: { showPicker = true },
                                     maxHeight: max(180, geo.size.height * (geo.size.width > geo.size.height ? 0.32 : 0.42)))
                }
                if !typing {
                    EditorToolBar(model: model, onImage: { showPicker = true })
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            // Other fields (numbers in the panels) push the whole editor up.
            .padding(.bottom, typing ? 0 : kbPad(geo))
            .animation(.snappy(duration: 0.25), value: keyboard.height)
            .overlay(alignment: .trailing) {
                if !docked && model.showLayers {
                    LayersPanel(model: model, onRename: { id in
                        layerName = model.doc.layer(id)?.name ?? ""
                        renamingLayerID = id
                    }, onClose: { withAnimation(.snappy) { model.showLayers = false } })
                    .frame(width: min(300, geo.size.width * 0.78))
                    .frame(height: min(geo.size.height * 0.62, 52 + CGFloat(max(1, model.doc.layers.count)) * 56 + (model.doc.layers.isEmpty ? 120 : 12)))
                    .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Color(uiColor: .secondarySystemGroupedBackground)))
                    .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(Color.primary.opacity(0.08)))
                    .shadow(color: .black.opacity(0.22), radius: 18, y: 6)
                    .padding(.trailing, 10)
                    .padding(.top, 54)
                    .frame(maxHeight: .infinity, alignment: .top)
                    .transition(.move(edge: .trailing).combined(with: .opacity))
                }
            }
        }
        .ignoresSafeArea(.keyboard)
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
        .animation(.snappy(duration: 0.25), value: typing)
        .animation(.snappy(duration: 0.28), value: model.showLayers)
        .animation(.snappy(duration: 0.3), value: model.sideCollapsed)
        .animation(.snappy(duration: 0.3), value: model.phoneSideCollapsed)
        .animation(.snappy(duration: 0.28), value: model.tool)
        .animation(.snappy(duration: 0.25), value: model.panelCollapsed)
        .sheet(isPresented: $showPicker) {
            PhotoPicker(selectionLimit: 0) { results in
                showPicker = false
                if model.tool == .image { model.tool = .select }
                guard !results.isEmpty else { return }
                importing = true
                Task {
                    let imgs = await ImageImporter.load(results)
                    importing = false
                    if imgs.isEmpty { model.showToast("Couldn't open those photos") } else { model.addImages(imgs) }
                }
            }
            .ignoresSafeArea()
        }
        .sheet(isPresented: $showExport) {
            ExportSheet(doc: model.doc, assets: model.assets, prepareProject: { done in model.flush(completion: done) })
        }
        .confirmationDialog(contextTitle, isPresented: Binding(get: { model.contextMenuLayerID != nil },
                                                              set: { if !$0 { model.contextMenuLayerID = nil } }),
                            titleVisibility: .visible) {
            if let id = model.contextMenuLayerID {
                LayerMenuItems(model: model, id: id, includeEditText: true, onRename: nil)
            }
        }
        .alert("Rename project", isPresented: $renamingProject) {
            TextField("Name", text: $projectName)
            Button("Cancel", role: .cancel) {}
            Button("Rename") { model.rename(projectName) }
        }
        .alert("Rename layer", isPresented: Binding(get: { renamingLayerID != nil }, set: { if !$0 { renamingLayerID = nil } })) {
            TextField("Name", text: $layerName)
            Button("Cancel", role: .cancel) { renamingLayerID = nil }
            Button("Rename") { if let id = renamingLayerID { model.renameLayer(id, layerName) }; renamingLayerID = nil }
        }
        .onChange(of: scenePhase) { phase in
            if phase != .active { model.endTextEditing(); model.flush() }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification)) { _ in
            let task = UIApplication.shared.beginBackgroundTask(withName: "save")
            model.flush { UIApplication.shared.endBackgroundTask(task) }
        }
        .onAppear {
            // Default: expanded on wide windows (≥ 900 pt), collapsed otherwise, until the user chooses.
            model.applySideDefault(width: (UIApplication.shared.connectedScenes.first as? UIWindowScene)?.windows.first?.bounds.width ?? 0)
            if docked { model.showLayers = true }
            #if DEBUG
            let d = UserDefaults.standard
            if let t = d.string(forKey: "tool").flatMap(EditorTool.init(rawValue:)) { model.tool = t }
            if let s = d.string(forKey: "selectLayer") { model.select(s) }
            if d.bool(forKey: "showLayers") { model.showLayers = true }
            if let e = d.string(forKey: "editText") { DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { model.beginTextEditing(e) } }
            if d.bool(forKey: "showExport") { DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { showExport = true } }
            #endif
        }
        .onChange(of: model.tool) { t in if t == .image { showPicker = true } }
        .onReceive(NotificationCenter.default.publisher(for: UIDevice.orientationDidChangeNotification)) { _ in
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                model.applySideDefault(width: (UIApplication.shared.connectedScenes.first as? UIWindowScene)?.windows.first?.bounds.width ?? 0)
            }
        }
        .onChange(of: vSize) { _ in
            model.applySideDefault(width: (UIApplication.shared.connectedScenes.first as? UIWindowScene)?.windows.first?.bounds.width ?? 0)
        }
    }

    private var contextTitle: String {
        model.contextMenuLayerID.flatMap { model.doc.layer($0)?.name } ?? "Layer"
    }

    @ViewBuilder private var overlays: some View {
        VStack(spacing: 8) {
            if let toast = model.toast {
                Text(toast)
                    .font(.subheadline.weight(.medium))
                    .padding(.horizontal, 14).padding(.vertical, 9)
                    .background(.regularMaterial, in: Capsule())
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .accessibilityAddTraits(.updatesFrequently)
            }
            if model.eyedropper != nil {
                HStack(spacing: 8) {
                    Image(systemName: "eyedropper")
                    Text("Touch the canvas, lift to pick")
                    Button("Cancel") { model.eyedropper = nil }.fontWeight(.semibold)
                }
                .font(.subheadline)
                .padding(.horizontal, 14).padding(.vertical, 9)
                .background(.regularMaterial, in: Capsule())
            }
            if importing {
                HStack(spacing: 8) { ProgressView(); Text("Adding photos…") }
                    .font(.subheadline)
                    .padding(.horizontal, 14).padding(.vertical, 9)
                    .background(.regularMaterial, in: Capsule())
            }
        }
        .padding(.top, 10)
        .animation(.snappy, value: model.toast)
    }

    @ViewBuilder private var sideColumn: some View {
        VStack(spacing: 0) {
            if model.showLayers {
                LayersPanel(model: model, onRename: { id in
                    layerName = model.doc.layer(id)?.name ?? ""
                    renamingLayerID = id
                }, onClose: { withAnimation(.snappy) { model.showLayers = false } })
                .frame(height: hasPanelContent ? min(layersMaxHeight, 56 + CGFloat(max(1, model.doc.layers.count)) * 50 + (model.doc.layers.isEmpty ? 110 : 8)) : nil)
                Divider()
            }
            if hasPanelContent {
                ScrollView {
                    ContextPanelContent(model: model, onEyedropper: startEyedropper, onAddImage: { showPicker = true })
                        .padding(.vertical, 8)
                }
            } else if !model.showLayers {
                Spacer()
            }
            Spacer(minLength: 0)
        }
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .overlay(alignment: .leading) { Divider() }
    }

    private var hasPanelContent: Bool { ContextPanelContent.hasContent(model) }

    /// Room the keyboard takes beyond the bottom safe area (home indicator).
    private func kbPad(_ geo: GeometryProxy) -> CGFloat {
        max(0, keyboard.height - geo.safeAreaInsets.bottom)
    }

    private func startEyedropper() {}

    private func close() {
        guard !closing else { return }
        closing = true
        model.close { onClose() }
    }
}

// MARK: - Top bar

struct SidePanelHandle: View {
    var expanded: Bool
    var action: () -> Void
    var body: some View {
        Button(action: action) {
            Image(systemName: expanded ? "chevron.compact.right" : "chevron.compact.left")
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(.secondary)
                .frame(width: 28, height: 64)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(Color.primary.opacity(0.08)))
                .shadow(color: .black.opacity(0.12), radius: 4, x: expanded ? -1 : -2)
                .frame(width: 44, height: 72)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(expanded ? "Collapse side panel" : "Show side panel")
        .accessibilityIdentifier("sidePanelHandle")
    }
}

struct EditorTopBar: View {
    @ObservedObject var model: EditorModel
    var layersActive: Bool
    var onLayers: () -> Void
    var onBack: () -> Void
    var onRename: () -> Void
    var onExport: () -> Void

    var body: some View {
        HStack(spacing: 2) {
            Button(action: onBack) {
                Image(systemName: "chevron.backward").font(.system(size: 18, weight: .semibold))
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel("Back to projects")
            .accessibilityIdentifier("editorBack")
            Button(action: onRename) {
                Text(model.doc.name)
                    .font(.headline)
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .foregroundStyle(.primary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Project name: \(model.doc.name)")
            .accessibilityHint("Double-tap to rename")
            Group {
                barButton("arrow.uturn.backward", "Undo", enabled: model.canUndo) { model.undo() }
                    .accessibilityIdentifier("undoButton")
                barButton("arrow.uturn.forward", "Redo", enabled: model.canRedo) { model.redo() }
                    .accessibilityIdentifier("redoButton")
                barButton(layersActive ? "square.3.layers.3d.top.filled" : "square.3.layers.3d", "Layers", enabled: true, action: onLayers)
                .accessibilityIdentifier("layersButton")
                .accessibilityAddTraits(layersActive ? .isSelected : [])
            }
            Button(action: onExport) {
                Text("Export")
                    .font(.subheadline.weight(.semibold))
                    .padding(.horizontal, 14)
                    .frame(height: 34)
                    .background(Capsule().fill(Brand.gradient))
                    .foregroundStyle(.white)
                    .frame(minHeight: 44)
            }
            .padding(.leading, 6)
            .accessibilityIdentifier("exportButton")
        }
        .padding(.horizontal, 8)
        .frame(height: 52)
        .background(.bar)
        .overlay(alignment: .bottom) { Divider() }
    }

    private func barButton(_ symbol: String, _ label: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 17, weight: .medium))
                .frame(width: 44, height: 44)
        }
        .disabled(!enabled)
        .accessibilityLabel(label)
    }
}

// MARK: - Tool bar

struct EditorToolBar: View {
    @ObservedObject var model: EditorModel
    var onImage: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            ForEach(EditorTool.allCases) { t in
                let selected = model.tool == t
                Button {
                    if t == .image { onImage(); return }
                    if model.tool == t && t != .select { model.tool = .select } else { model.tool = t }
                    UISelectionFeedbackGenerator().selectionChanged()
                } label: {
                    VStack(spacing: 3) {
                        Image(systemName: t.symbol)
                            .font(.system(size: 19, weight: selected ? .semibold : .regular))
                            .frame(width: 44, height: 30)
                            .background(Capsule().fill(selected ? Color.accentColor.opacity(0.16) : .clear))
                        Text(t.label)
                            .font(.caption2.weight(selected ? .semibold : .medium))
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    }
                    .foregroundStyle(selected ? Color.accentColor : Color.primary.opacity(0.78))
                    .frame(maxWidth: .infinity, minHeight: 50)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(t.label)
                .accessibilityIdentifier("tool-\(t.rawValue)")
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .padding(.horizontal, 4)
        .padding(.top, 4)
        .background(.bar)
        .overlay(alignment: .top) { Divider() }
    }
}

// MARK: - Layer menu (shared by long-press and the layers panel)

struct LayerMenuItems: View {
    @ObservedObject var model: EditorModel
    let id: String
    var includeEditText: Bool
    var onRename: ((String) -> Void)?

    var body: some View {
        if includeEditText, model.doc.layer(id)?.type == .text {
            Button("Edit text") { model.beginTextEditing(id) }
        }
        if let onRename { Button("Rename") { onRename(id) } }
        let i = model.doc.index(of: id) ?? 0
        let isTop = i == model.doc.layers.count - 1, isBottom = i == 0
        Button("Move to top") { model.moveToTop(id) }.disabled(isTop)
        Button("Move up") { model.moveUp(id) }.disabled(isTop)
        Button("Move down") { model.moveDown(id) }.disabled(isBottom)
        Button("Move to bottom") { model.moveToBottom(id) }.disabled(isBottom)
        Button("Duplicate") { model.duplicateLayer(id) }
        if model.doc.layer(id)?.type == .drawing {
            Button("Merge down") { model.mergeDown(id) }.disabled(!model.canMergeDown(id))
        }
        Button("Delete", role: .destructive) { model.deleteLayer(id) }
    }
}
