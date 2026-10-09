import UIKit

/// On-canvas text editing: a UITextView laid over the text layer, styled with the
/// layer's real fonts, size, spacing, case and B/I/U/S spans, positioned and rotated
/// to match. Edits flow into the model as UTF-16 range replacements (§7.9).
final class CanvasTextEditor: NSObject, UITextViewDelegate {
    weak var canvas: CanvasView?
    let textView = EditingTextView()
    private(set) var layerID: String?
    /// Where the user tapped (canvas view coordinates) to start editing: the caret goes there.
    var pendingCaretPoint: CGPoint?
    private var lastDisplay = ""
    private var applying = false
    private let accessory = TextAccessoryBar()

    func attach(to canvas: CanvasView) {
        self.canvas = canvas
        textView.delegate = self
        textView.isHidden = true
        textView.backgroundColor = .clear
        textView.isScrollEnabled = false
        textView.textContainerInset = .zero
        textView.textContainer.lineFragmentPadding = 0
        textView.autocorrectionType = .default
        textView.smartQuotesType = .no
        textView.smartDashesType = .no
        textView.keyboardAppearance = .default
        textView.accessibilityLabel = "Text"
        textView.accessibilityIdentifier = "canvasTextEditor"
        textView.inputAccessoryView = accessory
        accessory.onToggle = { [weak self] f in self?.canvas?.model.toggleStyle(f); self?.refreshAccessory() }
        accessory.onDone = { [weak self] in self?.canvas?.model.endTextEditing() }
        accessory.onStylePanel = { [weak self] in self?.showStylePanel() }
        accessory.onSelectAll = { [weak self] in
            guard let tv = self?.textView else { return }
            tv.selectAll(nil)
        }
        canvas.addSubview(textView)
    }

    var model: EditorModel? { canvas?.model }

    func setEditing(_ id: String?) {
        guard id != layerID else { return }
        layerID = id
        guard let id, model?.doc.layer(id)?.type == .text else {
            textView.isHidden = true
            if textView.isFirstResponder { textView.resignFirstResponder() }
            return
        }
        lastDisplay = ""
        rebuild(force: true)
        textView.isHidden = false
        if model?.textStyling == true, let sel = model?.textSelection {
            // Restored editor state: show the selection without raising the keyboard.
            textView.selectedRange = sel
            refreshAccessory()
            DispatchQueue.main.async { self.updateSelectionHighlight() }
            return
        }
        focusEditor(attempt: 0)
        let len = (textView.text as NSString).length
        textView.selectedRange = NSRange(location: len, length: 0)
        if let pt = pendingCaretPoint, let canvas {
            textView.layoutIfNeeded()
            let local = canvas.convert(pt, to: textView)
            if let pos = textView.closestPosition(to: local) {
                textView.selectedTextRange = textView.textRange(from: pos, to: pos)
            }
        }
        pendingCaretPoint = nil
        model?.textSelection = textView.selectedRange
        refreshAccessory()
        DispatchQueue.main.async { self.canvas?.ensureEditingVisible() }
    }

    /// becomeFirstResponder can fail while SwiftUI is mid-transaction (panels animating in);
    /// retry on the next run-loop turns so focus is deterministic.
    private func focusEditor(attempt: Int) {
        guard layerID != nil, !textView.isFirstResponder else { return }
        if !textView.becomeFirstResponder(), attempt < 5 {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self] in self?.focusEditor(attempt: attempt + 1) }
        }
    }

    func documentChanged() {
        guard layerID != nil else { return }
        if let id = layerID, model?.doc.layer(id) == nil { return }
        rebuild(force: false)
        refreshAccessory()
    }

    func layoutEditor() {
        guard let id = layerID, let canvas, let layer = canvas.model.doc.layer(id), let t = layer.text else { return }
        let z = canvas.zoom * CGFloat(layer.transform.scale)
        let layout = TextLayoutEngine.shared.layout(t)
        let fs = CGFloat(t.fontSize) * z
        let slack = t.autoWidth ? max(fs * 2, 40) : 0
        var width = layout.size.width * z + slack
        if t.autoWidth && t.autoWidthLimit.isFinite {
            // Auto-width text wraps at the canvas limit (§7.3 step 5).
            width = min(width, CGFloat(t.autoWidthLimit) * z)
        }
        if t.autoWidth && t.text.isEmpty { width = max(width, fs) }
        let fitting = textView.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude))
        let h = max(fitting.height, layout.size.height * z)
        textView.transform = .identity
        textView.bounds = CGRect(x: 0, y: 0, width: width, height: h)
        // Box centre in view coordinates; the text view's top aligns with the box top.
        let center = canvas.toView(CGPoint(x: layer.transform.x, y: layer.transform.y))
        let th = CGFloat(layer.transform.radians)
        var dx: CGFloat = 0
        if t.autoWidth {
            switch t.align {
            case .left, .justify: dx = slack / 2
            case .right: dx = -slack / 2
            case .center: dx = 0
            }
        }
        let dy = (h - layout.size.height * z) / 2
        let off = CGPoint(x: dx * cos(th) - dy * sin(th), y: dx * sin(th) + dy * cos(th))
        textView.center = CGPoint(x: center.x + off.x, y: center.y + off.y)
        textView.transform = CGAffineTransform(rotationAngle: th)
    }

    // MARK: Attributed text

    private func rebuild(force: Bool) {
        guard let id = layerID, let canvas, let layer = canvas.model.doc.layer(id), let t = layer.text else { return }
        if textView.markedTextRange != nil && !force { layoutEditor(); return }
        applying = true
        defer { applying = false }
        let z = canvas.zoom * CGFloat(layer.transform.scale)
        let attr = attributed(t, z: z)
        let selected = textView.selectedRange
        if force || textView.attributedText.string != attr.string {
            textView.attributedText = attr
            textView.selectedRange = NSRange(location: min(selected.location, attr.length), length: min(selected.length, max(0, attr.length - selected.location)))
        } else {
            textView.textStorage.beginEditing()
            textView.textStorage.setAttributes([:], range: NSRange(location: 0, length: attr.length))
            attr.enumerateAttributes(in: NSRange(location: 0, length: attr.length)) { a, r, _ in
                textView.textStorage.setAttributes(a, range: r)
            }
            textView.textStorage.endEditing()
            textView.selectedRange = selected
        }
        lastDisplay = textView.text
        textView.typingAttributes = typingAttributes(t, z: z)
        DispatchQueue.main.async { self.updateSelectionHighlight() }
        textView.tintColor = canvas.tintColor
        if t.backgroundBox.enabled && t.curve == 0 {
            textView.backgroundColor = t.backgroundBox.color.uiColor
            textView.layer.cornerRadius = CGFloat(t.backgroundBox.cornerRadius * t.fontSize) * z
        } else {
            textView.backgroundColor = .clear
            textView.layer.cornerRadius = 0
        }
        layoutEditor()
    }

    /// Display string: case-transformed per grapheme when the length is unchanged
    /// (so UTF-16 offsets equal the stored text's).
    static func displayText(_ t: TextProps) -> String {
        guard t.textCase != .none else { return t.text }
        var out = ""
        var prevWS = true
        for ch in t.text {
            let s = String(ch)
            var d: String
            switch t.textCase {
            case .none: d = s
            case .upper: d = s.uppercased()
            case .lower: d = s.lowercased()
            case .title: d = prevWS ? s.uppercased() : s.lowercased()
            }
            if (d as NSString).length != (s as NSString).length { d = s }
            out += d
            prevWS = ch.isWhitespace
        }
        return out
    }

    /// Attributes for one run: its own family, weight, size and colour (§7.9).
    /// `lineSize` is the paragraph's largest size (line pitch = lineHeight × that).
    private func attributes(_ t: TextProps, style e: EffectiveStyle, lineSize: Double, z: CGFloat) -> [NSAttributedString.Key: Any] {
        let fam = FontCatalog.shared.font(id: e.fontId)
        let face = FontCatalog.resolve(fam, weight: e.weight, bold: e.flags.bold, italic: e.flags.italic)
        let size = max(1, CGFloat(e.size) * z)
        let font = FontCatalog.shared.ctFont(face, size: size) as UIFont
        let base = FontCatalog.shared.ctFont(FontCatalog.resolve(fam, weight: e.weight, bold: false, italic: false), size: size)
        let A = CTFontGetAscent(base), D = CTFontGetDescent(base)
        let LH = CGFloat(t.lineHeight * lineSize) * z
        let para = NSMutableParagraphStyle()
        para.minimumLineHeight = LH
        para.maximumLineHeight = LH
        para.lineBreakMode = .byWordWrapping
        switch t.align {
        case .left: para.alignment = .left
        case .center: para.alignment = .center
        case .right: para.alignment = .right
        case .justify: para.alignment = .justified
        }
        var kern = CGFloat(t.letterSpacing) * size
        if face.synthBold { kern += 0.04 * size }
        let fill = (e.color ?? t.fill.primaryColor).uiColor
        let layerFS = CGFloat(t.fontSize) * z
        var a: [NSAttributedString.Key: Any] = [
            .font: font, .paragraphStyle: para, .kern: kern, .foregroundColor: fill,
            .baselineOffset: max(0, (LH - A - D) / 2),
        ]
        if t.outline.enabled && t.outline.style != .glow {
            // strokeWidth is a percentage of this run's point size; outlines are em of the layer size.
            a[.strokeColor] = t.outline.color.uiColor
            a[.strokeWidth] = -max(1, t.outline.width * 100 * Double(layerFS / size))
        } else if t.outline.enabled && t.outline.style == .glow {
            let sh = NSShadow()
            sh.shadowColor = t.outline.color.uiColor
            sh.shadowBlurRadius = CGFloat(t.outline.glowRadius) * layerFS / 2
            a[.shadow] = sh
        } else if face.synthBold {
            a[.strokeColor] = fill
            a[.strokeWidth] = -4.0
        }
        if e.flags.underline { a[.underlineStyle] = NSUnderlineStyle.single.rawValue }
        if e.flags.strike { a[.strikethroughStyle] = NSUnderlineStyle.single.rawValue }
        return a
    }

    private func attributed(_ t: TextProps, z: CGFloat) -> NSAttributedString {
        let display = CanvasTextEditor.displayText(t)
        let ns = display as NSString
        let s = NSMutableAttributedString(string: display)
        let eff = Spans.effective(t)
        let n = ns.length
        func style(_ i: Int) -> EffectiveStyle { i < eff.count ? eff[i] : t.layerStyle }
        // Per paragraph: line pitch from its largest size.
        var p = 0
        while p <= n {
            let nl = p < n ? ns.range(of: "\n", options: [], range: NSRange(location: p, length: n - p)).location : NSNotFound
            let end = nl == NSNotFound ? n : nl + 1
            var lineSize = 0.0
            for i in p..<max(p, min(end, n)) { lineSize = max(lineSize, style(i).size) }
            if lineSize == 0 { lineSize = t.fontSize }
            var i = p
            while i < end && i < n {
                let f = style(i)
                var j = i + 1
                while j < end && j < n && style(j) == f { j += 1 }
                s.setAttributes(attributes(t, style: f, lineSize: lineSize, z: z), range: NSRange(location: i, length: j - i))
                i = j
            }
            if end >= n { break }
            p = end
        }
        return s
    }

    private func typingAttributes(_ t: TextProps, z: CGFloat) -> [NSAttributedString.Key: Any] {
        let sel = textView.selectedRange
        let e: EffectiveStyle
        if let ts = model?.typingStyle { e = t.effective(ts) }
        else { e = model?.effectiveStyle(at: sel.location, in: t) ?? t.layerStyle }
        return attributes(t, style: e, lineSize: max(e.size, t.fontSize), z: z)
    }

    // MARK: UITextViewDelegate

    func textViewDidChange(_ tv: UITextView) {
        guard !applying, let id = layerID, let model else { return }
        let new = tv.text ?? ""
        let old = lastDisplay
        guard new != old else { return }
        let (range, replacement) = CanvasTextEditor.diff(old: old, new: new)
        lastDisplay = new
        model.replaceText(id, range: range, with: replacement)
        if tv.markedTextRange == nil { rebuild(force: false) } else { layoutEditor() }
    }

    func textViewDidChangeSelection(_ tv: UITextView) {
        guard !applying, let model else { return }
        if model.textSelection != tv.selectedRange {
            // Moving the cursor clears pending typing styles.
            if let old = model.textSelection, old.length != 0 || tv.selectedRange.length != 0 || abs(old.location - tv.selectedRange.location) > 1 {
                model.typingStyle = nil
            }
            model.textSelection = tv.selectedRange
        }
        refreshAccessory()
    }

    func textViewDidEndEditing(_ tv: UITextView) {
        // Keyboard hidden to use the style panel: stay in editing mode and keep the
        // selection visible. Otherwise (e.g. interactive dismiss) end editing.
        if model?.textStyling == true { updateSelectionHighlight(); return }
        if layerID != nil, !applying { DispatchQueue.main.async { self.model?.endTextEditing() } }
    }

    func textViewDidBeginEditing(_ tv: UITextView) {
        if model?.textStyling == true { model?.textStyling = false }
        updateSelectionHighlight()
    }

    /// Keyboard down, panel up: draw the selection ourselves (UIKit hides it without focus).
    private let highlight = CAShapeLayer()

    func updateSelectionHighlight() {
        if highlight.superlayer == nil {
            highlight.zPosition = -1
            textView.layer.insertSublayer(highlight, at: 0)
        }
        guard model?.textStyling == true, let sel = model?.textSelection, sel.length > 0,
              let start = textView.position(from: textView.beginningOfDocument, offset: sel.location),
              let end = textView.position(from: start, offset: sel.length),
              let range = textView.textRange(from: start, to: end) else {
            highlight.path = nil
            return
        }
        let path = UIBezierPath()
        for r in textView.selectionRects(for: range) where r.rect.width > 0 {
            path.append(UIBezierPath(roundedRect: r.rect, cornerRadius: 2))
        }
        highlight.path = path.cgPath
        highlight.fillColor = (canvas?.tintColor ?? .systemBlue).withAlphaComponent(0.28).cgColor
    }

    /// Hides the keyboard but keeps editing (and the selection) so the panel can style it.
    func showStylePanel() {
        model?.textStyling = true
        textView.resignFirstResponder()
        updateSelectionHighlight()
    }

    /// Minimal single replacement turning `old` into `new` (UTF-16, grapheme-safe).
    static func diff(old: String, new: String) -> (NSRange, String) {
        let o = Array(old.utf16), n = Array(new.utf16)
        var p = 0
        while p < o.count, p < n.count, o[p] == n[p] { p += 1 }
        var so = o.count, sn = n.count
        while so > p, sn > p, o[so - 1] == n[sn - 1] { so -= 1; sn -= 1 }
        // Widen to grapheme boundaries of the old text (the suffix after it is shared).
        let bo = Spans.graphemeBoundaries(old)
        let start = Spans.snapDown(p, bo)
        let endOld = max(start, Spans.snapUp(so, bo))
        let endNew = max(start, endOld + (n.count - o.count))
        let units = Array(n[start..<endNew])
        return (NSRange(location: start, length: endOld - start), String(utf16CodeUnits: units, count: units.count))
    }

    private func refreshAccessory() {
        guard let model else { return }
        accessory.update(active: [.bold: model.isStyleActive(.bold), .italic: model.isStyleActive(.italic),
                                  .underline: model.isStyleActive(.underline), .strike: model.isStyleActive(.strike)])
        if let id = layerID, let canvas, let layer = model.doc.layer(id), let t = layer.text {
            textView.typingAttributes = typingAttributes(t, z: canvas.zoom * CGFloat(layer.transform.scale))
        }
    }
}

final class EditingTextView: UITextView {
    override func caretRect(for position: UITextPosition) -> CGRect {
        var r = super.caretRect(for: position)
        r.size.width = max(2, r.size.width)
        return r
    }
}

/// Keyboard accessory: B / I / U / S drawn as previews of their effect, and Done.
final class TextAccessoryBar: UIInputView {
    var onToggle: (StyleFlags.Flag) -> Void = { _ in }
    var onDone: () -> Void = {}
    var onSelectAll: () -> Void = {}
    var onStylePanel: () -> Void = {}
    private var buttons: [StyleFlags.Flag: UIButton] = [:]

    init() {
        super.init(frame: CGRect(x: 0, y: 0, width: 320, height: 56), inputViewStyle: .keyboard)
        allowsSelfSizing = true
        let stack = UIStackView()
        stack.axis = .horizontal
        stack.spacing = 5
        stack.alignment = .center
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        let specs: [(StyleFlags.Flag, String, String)] = [(.bold, "B", "Bold"), (.italic, "I", "Italic"),
                                                          (.underline, "U", "Underline"), (.strike, "S", "Strikethrough")]
        for (flag, letter, name) in specs {
            let b = UIButton(type: .system)
            var config = UIButton.Configuration.gray()
            config.cornerStyle = .medium
            config.attributedTitle = AttributedString(StyleButtonTitle.make(letter: letter, flag: flag, size: 18))
            b.configuration = config
            b.accessibilityLabel = name
            b.addAction(UIAction { [weak self] _ in self?.onToggle(flag) }, for: .touchUpInside)
            b.widthAnchor.constraint(equalToConstant: 40).isActive = true
            b.heightAnchor.constraint(equalToConstant: 44).isActive = true
            buttons[flag] = b
            stack.addArrangedSubview(b)
        }
        var selConfig = UIButton.Configuration.gray()
        selConfig.cornerStyle = .medium
        selConfig.title = "All"
        selConfig.titleLineBreakMode = .byClipping
        let selectAll = UIButton(configuration: selConfig, primaryAction: UIAction { [weak self] _ in self?.onSelectAll() })
        selectAll.accessibilityLabel = "Select all"
        selectAll.accessibilityIdentifier = "selectAllButton"
        selectAll.heightAnchor.constraint(equalToConstant: 44).isActive = true
        stack.addArrangedSubview(selectAll)
        var styleConfig = UIButton.Configuration.gray()
        styleConfig.cornerStyle = .medium
        styleConfig.image = UIImage(systemName: "paintpalette")
        let styleButton = UIButton(configuration: styleConfig, primaryAction: UIAction { [weak self] _ in self?.onStylePanel() })
        styleButton.accessibilityLabel = "Font, size and color for the selection"
        styleButton.accessibilityIdentifier = "stylePanelButton"
        styleButton.widthAnchor.constraint(equalToConstant: 44).isActive = true
        styleButton.heightAnchor.constraint(equalToConstant: 44).isActive = true
        stack.addArrangedSubview(styleButton)
        let spacer = UIView()
        stack.addArrangedSubview(spacer)
        var doneConfig = UIButton.Configuration.filled()
        doneConfig.title = "Done"
        doneConfig.titleLineBreakMode = .byClipping
        doneConfig.cornerStyle = .capsule
        let done = UIButton(configuration: doneConfig, primaryAction: UIAction { [weak self] _ in self?.onDone() })
        done.accessibilityIdentifier = "textDoneButton"
        done.heightAnchor.constraint(equalToConstant: 44).isActive = true
        done.setContentCompressionResistancePriority(.required, for: .horizontal)
        done.setContentHuggingPriority(.required, for: .horizontal)
        selectAll.setContentCompressionResistancePriority(.defaultHigh, for: .horizontal)
        stack.addArrangedSubview(done)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: layoutMarginsGuide.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: layoutMarginsGuide.trailingAnchor),
            stack.topAnchor.constraint(equalTo: topAnchor, constant: 6),
            stack.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -6),
        ])
    }

    required init?(coder: NSCoder) { fatalError() }

    override var intrinsicContentSize: CGSize { CGSize(width: UIView.noIntrinsicMetric, height: 56) }

    func update(active: [StyleFlags.Flag: Bool]) {
        for (flag, b) in buttons {
            let on = active[flag] ?? false
            var c = b.configuration
            c?.baseBackgroundColor = on ? b.tintColor : .tertiarySystemFill
            c?.baseForegroundColor = on ? .white : .label
            b.configuration = c
            b.accessibilityTraits = on ? [.button, .selected] : .button
        }
    }
}

enum StyleButtonTitle {
    static func make(letter: String, flag: StyleFlags.Flag, size: CGFloat) -> NSAttributedString {
        var attrs: [NSAttributedString.Key: Any] = [:]
        switch flag {
        case .bold: attrs[.font] = UIFont.systemFont(ofSize: size, weight: .heavy)
        case .italic: attrs[.font] = UIFont.italicSystemFont(ofSize: size)
        case .underline:
            attrs[.font] = UIFont.systemFont(ofSize: size, weight: .medium)
            attrs[.underlineStyle] = NSUnderlineStyle.single.rawValue
        case .strike:
            attrs[.font] = UIFont.systemFont(ofSize: size, weight: .medium)
            attrs[.strikethroughStyle] = NSUnderlineStyle.single.rawValue
        }
        return NSAttributedString(string: letter, attributes: attrs)
    }
}
