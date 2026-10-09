package com.halworks.basicart.model

/** Layer ordering (layers are bottom → top; index 0 is drawn first). */
object LayerOps {
    fun move(layers: List<Layer>, from: Int, to: Int): List<Layer> {
        if (from !in layers.indices) return layers
        val t = to.coerceIn(0, layers.lastIndex)
        if (from == t) return layers
        val m = layers.toMutableList()
        val l = m.removeAt(from)
        m.add(t, l)
        return m
    }

    fun moveUp(layers: List<Layer>, id: String): List<Layer> {
        val i = layers.indexOfFirst { it.id == id }
        return if (i < 0) layers else move(layers, i, i + 1)
    }

    fun moveDown(layers: List<Layer>, id: String): List<Layer> {
        val i = layers.indexOfFirst { it.id == id }
        return if (i < 0) layers else move(layers, i, i - 1)
    }

    fun toTop(layers: List<Layer>, id: String): List<Layer> {
        val i = layers.indexOfFirst { it.id == id }
        return if (i < 0) layers else move(layers, i, layers.lastIndex)
    }

    fun toBottom(layers: List<Layer>, id: String): List<Layer> {
        val i = layers.indexOfFirst { it.id == id }
        return if (i < 0) layers else move(layers, i, 0)
    }

    fun remove(layers: List<Layer>, id: String) = layers.filterNot { it.id == id }

    /** Inserts [layer] directly above the layer with [aboveId] (or at the top). */
    fun insertAbove(layers: List<Layer>, layer: Layer, aboveId: String?): List<Layer> {
        val i = aboveId?.let { id -> layers.indexOfFirst { it.id == id } } ?: -1
        val m = layers.toMutableList()
        if (i < 0) m.add(layer) else m.add(i + 1, layer)
        return m
    }
}

/**
 * Snapshot undo/redo stack. Holds up to [limit] steps (spec requires at least 50).
 */
class History<T>(private val limit: Int = 100) {
    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()

    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()
    val undoSize get() = past.size

    /** Record [before] as an undoable state; clears redo. */
    fun push(before: T) {
        past.addLast(before)
        while (past.size > limit) past.removeFirst()
        future.clear()
    }

    fun undo(current: T): T? {
        val prev = past.removeLastOrNull() ?: return null
        future.addLast(current)
        return prev
    }

    fun redo(current: T): T? {
        val next = future.removeLastOrNull() ?: return null
        past.addLast(current)
        return next
    }

    fun clear() { past.clear(); future.clear() }

    /** All states held (for asset garbage collection). */
    fun allStates(): List<T> = past.toList() + future.toList()
}
