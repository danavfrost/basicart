import Foundation

/// Layer ordering operations. Array order is bottom → top (index 0 drawn first).
extension Document {
    mutating func moveLayerUp(_ id: String) {
        guard let i = index(of: id), i < layers.count - 1 else { return }
        layers.swapAt(i, i + 1)
    }

    mutating func moveLayerDown(_ id: String) {
        guard let i = index(of: id), i > 0 else { return }
        layers.swapAt(i, i - 1)
    }

    mutating func moveLayerToTop(_ id: String) {
        guard let i = index(of: id), i < layers.count - 1 else { return }
        let l = layers.remove(at: i)
        layers.append(l)
    }

    mutating func moveLayerToBottom(_ id: String) {
        guard let i = index(of: id), i > 0 else { return }
        let l = layers.remove(at: i)
        layers.insert(l, at: 0)
    }

    /// Drag reorder: move the layer to final array index `to` (bottom → top indexing).
    mutating func moveLayer(_ id: String, toIndex to: Int) {
        guard let i = index(of: id) else { return }
        let target = max(0, min(layers.count - 1, to))
        guard target != i else { return }
        let l = layers.remove(at: i)
        layers.insert(l, at: target)
    }

    @discardableResult
    mutating func removeLayer(_ id: String) -> Layer? {
        guard let i = index(of: id) else { return nil }
        return layers.remove(at: i)
    }

    /// Inserts a copy directly above the original. Returns the new id.
    @discardableResult
    mutating func duplicateLayer(_ id: String, offset: Double = 24) -> String? {
        guard let i = index(of: id) else { return nil }
        var copy = layers[i]
        copy.id = Document.newID()
        copy.name = Document.copyName(copy.name)
        copy.transform.x += offset
        copy.transform.y += offset
        copy.locked = false
        layers.insert(copy, at: i + 1)
        return copy.id
    }

    mutating func updateLayer(_ id: String, _ body: (inout Layer) -> Void) {
        guard let i = index(of: id) else { return }
        body(&layers[i])
    }

    static func copyName(_ name: String) -> String {
        let n = name.hasSuffix(" copy") ? name : name + " copy"
        return String(n.prefix(100))
    }

    /// A fresh layer name like "Text 3".
    func nextLayerName(_ base: String) -> String {
        var n = 1
        let names = Set(layers.map(\.name))
        while names.contains("\(base) \(n)") { n += 1 }
        return "\(base) \(n)"
    }
}

/// Snapshot undo/redo stack (documents are value types, so snapshots share storage).
struct UndoHistory<T> {
    private(set) var past: [T] = []
    private(set) var future: [T] = []
    let limit: Int

    init(limit: Int = 100) { self.limit = limit }

    var canUndo: Bool { !past.isEmpty }
    var canRedo: Bool { !future.isEmpty }

    /// Record the state *before* a change.
    mutating func record(_ before: T) {
        past.append(before)
        if past.count > limit { past.removeFirst(past.count - limit) }
        future.removeAll()
    }

    mutating func undo(current: T) -> T? {
        guard let prev = past.popLast() else { return nil }
        future.append(current)
        return prev
    }

    mutating func redo(current: T) -> T? {
        guard let next = future.popLast() else { return nil }
        past.append(current)
        return next
    }

    mutating func clear() { past.removeAll(); future.removeAll() }

    /// Every state still reachable (for asset/stroke garbage collection).
    var allStates: [T] { past + future }
}
