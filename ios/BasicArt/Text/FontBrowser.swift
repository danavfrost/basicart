import SwiftUI

/// Drill-down font browser: Groups → Families → extra weights. Names render in their own fonts.
struct FontBrowser: View {
    @ObservedObject var model: EditorModel
    @EnvironmentObject var settings: AppSettings
    @State private var group: FontGroup?
    @State private var weightsFor: FontFamily?
    @State private var query = ""
    @FocusState private var searching: Bool

    private let catalog = FontCatalog.shared
    /// The selection's family / weight (nil when mixed).
    var currentId: String? { model.uniform(\.fontId) }
    var currentWeight: Int? { model.uniform(\.weight) }

    var body: some View {
        VStack(spacing: 6) {
            HStack(spacing: 8) {
                if group != nil || weightsFor != nil {
                    Button {
                        withAnimation(.snappy(duration: 0.22)) {
                            if weightsFor != nil { weightsFor = nil } else { group = nil }
                        }
                    } label: {
                        Image(systemName: "chevron.backward").font(.body.weight(.semibold)).frame(width: 44, height: 44)
                    }
                    .accessibilityLabel("Back")
                    .accessibilityIdentifier("fontBack")
                }
                HStack(spacing: 6) {
                    Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                    TextField("Search all fonts", text: $query)
                        .focused($searching)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .accessibilityIdentifier("fontSearch")
                    if !query.isEmpty {
                        Button { query = "" } label: {
                            Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                                .frame(width: 44, height: 44).contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Clear search")
                    }
                }
                .padding(.leading, 10)
                .frame(height: 44)
                .background(RoundedRectangle(cornerRadius: 10).fill(Color(uiColor: .tertiarySystemFill)))
            }
            if query.isEmpty && group == nil && weightsFor == nil && !settings.recentFonts.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        Text("Recent").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                        ForEach(settings.recentFonts.filter { catalog.isKnown($0) }, id: \.self) { id in
                            let f = catalog.font(id: id)
                            Button { choose(f) } label: {
                                Text(f.family)
                                    .font(Font(catalog.uiFont(fontId: f.id, weight: f.nearestUprightWeight(to: 400), size: 16)))
                                    .lineLimit(1)
                                    .padding(.horizontal, 12)
                                    .frame(height: 34)
                                    .background(Capsule().fill(f.id == currentId ? Color.accentColor.opacity(0.18) : Color(uiColor: .tertiarySystemFill)))
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Recent font \(f.family)")
                        }
                    }
                }
            }
            list
        }
    }

    @ViewBuilder private var list: some View {
        if let fam = weightsFor {
            List(fam.extraWeights, id: \.self) { file in
                Button {
                    model.setTextFont(fam, weight: file.weight)
                    settings.noteFontUsed(fam.id)
                } label: {
                    HStack {
                        Text(file.styleName)
                            .font(Font(catalog.ctFont(ResolvedFace(file: file, synthBold: false, synthItalic: false), size: 22) as UIFont))
                            .foregroundStyle(.primary)
                        Spacer()
                        Text("\(file.weight)").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                        if fam.id == currentId && file.weight == currentWeight {
                            Image(systemName: "checkmark").foregroundStyle(Color.accentColor).fontWeight(.semibold)
                        }
                    }
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(fam.family) \(file.styleName)")
                .listRowBackground(Color.clear)
            }
            .listStyle(.plain)
                .scrollContentBackground(.hidden)
            .transition(.move(edge: .trailing))
        } else if !query.isEmpty {
            let results = catalog.allFonts.filter { $0.family.localizedCaseInsensitiveContains(query) }
            if results.isEmpty {
                Text("No fonts match “\(query)”").font(.subheadline).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List(results) { f in familyRow(f) }.listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        } else if let g = group {
            List(catalog.fonts(inGroup: g.id)) { f in familyRow(f) }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .transition(.move(edge: .trailing))
        } else {
            List(catalog.allGroups) { g in
                Button {
                    withAnimation(.snappy(duration: 0.22)) { group = g }
                } label: {
                    HStack {
                        Text(g.name)
                            .font(Font(catalog.uiFont(fontId: g.sampleFontId, weight: catalog.font(id: g.sampleFontId).nearestUprightWeight(to: 400), size: 22)))
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                        Spacer()
                        Text("\(catalog.fonts(inGroup: g.id).count)").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                        if let cid = currentId, catalog.font(id: cid).group == g.id && catalog.isKnown(cid) {
                            Circle().fill(Color.accentColor).frame(width: 7, height: 7)
                        }
                        Image(systemName: "chevron.forward").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
                    }
                    .frame(minHeight: 46)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(g.name) fonts")
                .accessibilityIdentifier("fontGroup-\(g.id)")
                .listRowBackground(Color.clear)
            }
            .listStyle(.plain)
                .scrollContentBackground(.hidden)
        }
    }

    private func familyRow(_ f: FontFamily) -> some View {
        familyRowContent(f).listRowBackground(Color.clear)
    }

    private func familyRowContent(_ f: FontFamily) -> some View {
        HStack(spacing: 4) {
            Button { choose(f) } label: {
                HStack {
                    Text(f.family)
                        .font(Font(catalog.uiFont(fontId: f.id, weight: f.nearestUprightWeight(to: 400), size: 22)))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    Spacer()
                    if f.id == currentId {
                        Image(systemName: "checkmark").foregroundStyle(Color.accentColor).fontWeight(.semibold)
                    }
                }
                .frame(minHeight: 46)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(f.family)\(f.id == currentId ? ", selected" : "")")
            .accessibilityIdentifier("font-\(f.id)")
            if !f.extraWeights.isEmpty {
                Button {
                    withAnimation(.snappy(duration: 0.22)) { weightsFor = f }
                } label: {
                    HStack(spacing: 2) {
                        Text("\(f.extraWeights.count)").font(.caption.monospacedDigit())
                        Image(systemName: "chevron.forward").font(.footnote.weight(.semibold))
                    }
                    .foregroundStyle(.secondary)
                    .frame(width: 52, height: 44)
                    .background(RoundedRectangle(cornerRadius: 8).fill(Color(uiColor: .tertiarySystemFill)))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(f.family) weights")
            }
        }
    }

    /// Tapping a family applies its regular weight; other weights come from the weights list, bold from B.
    private func choose(_ f: FontFamily) {
        model.setTextFont(f, weight: f.nearestUprightWeight(to: 400))
        settings.noteFontUsed(f.id)
        UISelectionFeedbackGenerator().selectionChanged()
    }
}
