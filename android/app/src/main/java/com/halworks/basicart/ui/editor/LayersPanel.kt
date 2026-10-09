package com.halworks.basicart.ui.editor

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.halworks.basicart.model.Layer
import com.halworks.basicart.model.TextLayer
import com.halworks.basicart.ui.common.Checkerboard
import com.halworks.basicart.ui.common.RenameDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun LayersPanel(st: EditorState, cache: LayerCache, onClose: (() -> Unit)?, docked: Boolean = false) {
    val doc = st.doc ?: return
    val display = doc.layers.asReversed() // top layer first
    val rowH = 60.dp
    val rowPx = with(LocalDensity.current) { rowH.toPx() }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    var renaming by remember { mutableStateOf<Layer?>(null) }

    Column {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Layers", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("${doc.layers.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onClose != null) IconButton(onClick = onClose) {
                Icon(if (docked) Icons.AutoMirrored.Outlined.KeyboardArrowRight else Icons.Outlined.Close, if (docked) "Hide layers" else "Close layers")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        if (display.isEmpty()) {
            Text(
                "No layers yet. Add text, a photo, a drawing or a shape from the tool bar.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }
        LazyColumn {
            itemsIndexed(display, key = { _, l -> l.base.id }) { i, l ->
                val dragging = dragId == l.base.id
                // Rows the dragged row passes over shift to make room.
                val fromI = dragId?.let { id -> display.indexOfFirst { it.base.id == id } } ?: -1
                val target = if (fromI >= 0) (fromI + (dragDy / rowPx).roundToInt()).coerceIn(0, display.lastIndex) else -1
                val shift = when {
                    dragging || fromI < 0 -> 0f
                    i in (fromI + 1)..target -> -rowPx
                    i in target until fromI -> rowPx
                    else -> 0f
                }
                val animShift by animateFloatAsState(shift, label = "shift")
                LayerRow(
                    st, cache, l, selected = st.selectedId == l.base.id, dragging = dragging,
                    modifier = Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragDy else animShift }
                        .pointerInput(l.base.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragId = l.base.id; dragDy = 0f; st.selectedId = l.base.id },
                                onDrag = { ch, d -> ch.consume(); dragDy += d.y },
                                onDragEnd = {
                                    val from = display.indexOfFirst { it.base.id == dragId }
                                    val to = (from + (dragDy / rowPx).roundToInt()).coerceIn(0, display.lastIndex)
                                    val n = display.size
                                    if (from >= 0 && to != from) st.moveLayer(n - 1 - from, n - 1 - to)
                                    dragId = null; dragDy = 0f
                                },
                                onDragCancel = { dragId = null; dragDy = 0f },
                            )
                        },
                    height = rowH,
                    onRename = { renaming = l },
                )
            }
        }
    }
    renaming?.let { l ->
        RenameDialog("Rename layer", l.base.name, onRename = { st.rename(l.base.id, it) }, onDismiss = { renaming = null })
    }
}

@Composable
private fun LayerRow(
    st: EditorState, cache: LayerCache, l: Layer, selected: Boolean, dragging: Boolean,
    modifier: Modifier, height: androidx.compose.ui.unit.Dp, onRename: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val thumbPx = with(LocalDensity.current) { 40.dp.toPx().roundToInt() }
    val thumb by produceState<Bitmap?>(null, l.withBase(l.base.copy(name = "", visible = true, locked = false))) {
        value = withContext(Dispatchers.Default) { try { cache.thumbnail(l, thumbPx) } catch (t: Throwable) { null } }
    }
    val bg = when {
        dragging -> MaterialTheme.colorScheme.surfaceContainerHighest
        selected -> MaterialTheme.colorScheme.secondaryContainer
        else -> Color.Transparent
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .then(if (dragging) Modifier.shadow(8.dp, RoundedCornerShape(12.dp)) else Modifier)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClickLabel = "Select ${l.base.name}") { st.selectedId = l.base.id }
            .padding(start = 4.dp)
            .semantics { contentDescription = "Layer ${l.base.name}" + if (selected) ", selected" else "" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.DragIndicator, "Long-press and drag to reorder", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        Spacer(Modifier.width(4.dp))
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)).alpha(if (l.base.visible) 1f else 0.4f)) {
            Checkerboard(Modifier.matchParentSize())
            thumb?.let { Image(it.asImageBitmap(), null, Modifier.matchParentSize()) }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            l.base.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).alpha(if (l.base.visible) 1f else 0.5f),
        )
        IconButton(onClick = { st.setVisible(l.base.id, !l.base.visible) }, Modifier.size(40.dp)) {
            Icon(if (l.base.visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, if (l.base.visible) "Hide layer" else "Show layer",
                Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { st.setLocked(l.base.id, !l.base.locked) }, Modifier.size(40.dp)) {
            Icon(if (l.base.locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen, if (l.base.locked) "Unlock layer" else "Lock layer",
                Modifier.size(20.dp), tint = if (l.base.locked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
        Box {
            IconButton(onClick = { menu = true }, Modifier.size(40.dp)) { Icon(Icons.Outlined.MoreVert, "Layer options", Modifier.size(20.dp)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (l is TextLayer) DropdownMenuItem(text = { Text("Edit text") }, onClick = { menu = false; st.startEditing(l.base.id) })
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; st.duplicateLayer(l.base.id) })
                DropdownMenuItem(text = { Text("Move to top") }, onClick = { menu = false; st.reorder("top", l.base.id) })
                DropdownMenuItem(text = { Text("Move to bottom") }, onClick = { menu = false; st.reorder("bottom", l.base.id) })
                if (st.canMergeDown(l.base.id)) DropdownMenuItem(text = { Text("Merge down") }, onClick = { menu = false; st.mergeDown(l.base.id) })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; st.deleteLayer(l.base.id) })
            }
        }
    }
}
