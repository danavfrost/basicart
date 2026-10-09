package com.halworks.basicart.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.halworks.basicart.model.Colors
import com.halworks.basicart.ui.common.Checkerboard
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Compact labeled slider: live updates while dragging, one undo step per drag. With [editUnit]
 * the value is tappable for numeric entry (shown value = slider value × editUnit).
 */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String = { it.roundToInt().toString() },
    enabled: Boolean = true,
    steps: Int = 0,
    editUnit: Float? = null,
    editRange: ClosedFloatingPointRange<Float>? = null,
    onDone: () -> Unit,
    onChange: (Float) -> Unit,
) {
    var local by remember { mutableFloatStateOf(value) }
    var dragging by remember { mutableFloatStateOf(0f) }
    var editing by remember { mutableStateOf(false) }
    val shown = if (dragging > 0f) local else value
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(min = 76.dp, max = 116.dp),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else dim, maxLines = 2)
        Slider(
            value = shown.coerceIn(range.start, range.endInclusive),
            onValueChange = { local = it; dragging = 1f; onChange(it) },
            onValueChangeFinished = { dragging = 0f; onDone() },
            valueRange = range,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp).semantics { contentDescription = label },
            colors = SliderDefaults.colors(),
        )
        val valueText = format(shown)
        Text(
            valueText, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium,
            maxLines = 1, softWrap = false,
            color = if (!enabled) dim else if (editUnit != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(min = 52.dp)
                .then(if (editUnit != null && enabled) Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "Type a value for $label") { editing = true } else Modifier)
                .padding(horizontal = 4.dp, vertical = 8.dp),
        )
    }
    if (editing && editUnit != null) {
        NumberDialog(label, (shown * editUnit), onDismiss = { editing = false }) { typed ->
            val r = editRange ?: range
            onChange((typed / editUnit).coerceIn(r.start, r.endInclusive)); onDone()
        }
    }
}

@Composable
private fun NumberDialog(label: String, initial: Float, onDismiss: () -> Unit, onSet: (Float) -> Unit) {
    val start = if (initial == Math.round(initial).toFloat()) Math.round(initial).toString() else fmt1(initial)
    var text by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(start, androidx.compose.ui.text.TextRange(0, start.length))) }
    val fr = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text, onValueChange = { v -> text = v.copy(text = v.text.filter { it.isDigit() || it == '.' || it == '-' }.take(8)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { text.text.toFloatOrNull()?.let(onSet); onDismiss() }),
                modifier = Modifier.focusRequester(fr),
            )
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { text.text.toFloatOrNull()?.let(onSet); onDismiss() }) { Text("Set") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    androidx.compose.runtime.LaunchedEffect(Unit) { fr.requestFocus() }
}

/** Fades the bottom (and top) edge of a vertically scrolled panel so clipped rows read as "scroll for more". */
fun Modifier.fadeEdgesVertical(scroll: androidx.compose.foundation.ScrollState, height: androidx.compose.ui.unit.Dp = 20.dp) = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = height.toPx()
        if (scroll.value > 0) drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black), 0f, h),
            size = androidx.compose.ui.geometry.Size(size.width, h), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
        if (scroll.value < scroll.maxValue) drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black, Color.Transparent), size.height - h, size.height),
            topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h), size = androidx.compose.ui.geometry.Size(size.width, h),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
    }

fun fmt1(v: Float) = String.format(Locale.ROOT, "%.1f", v)
fun fmtPx(v: Float) = "${v.roundToInt()} px"
fun fmtDeg(v: Float) = "${v.roundToInt()}°"
fun fmtPct(v: Float) = "${(v * 100).roundToInt()}%"

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clickable { onChange(!checked) }.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        trailing()
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, enabled: Boolean = true, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        options.forEachIndexed { i, o ->
            SegmentedButton(
                selected = o == selected, onClick = { onSelect(o) }, enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {},
                colors = SegmentedButtonDefaults.colors(
                    disabledActiveContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                    disabledActiveContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    disabledInactiveContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    disabledActiveBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    disabledInactiveBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                ),
            ) {
                // Every option dims together when the control is off.
                Text(label(o), maxLines = 1, style = MaterialTheme.typography.labelLarge,
                    color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            }
        }
    }
}

/** A labeled color well that opens the shared color picker. */
@Composable
fun ColorWell(label: String, color: Int, mixed: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.height(48.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Change $label", onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)) {
            Checkerboard(Modifier.matchParentSize())
            if (mixed) Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Brush.sweepGradient(
                listOf(Color(0xFFE53935), Color(0xFFFDD835), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA), Color(0xFFE53935)))))
            else Box(Modifier.matchParentSize().background(Color(color)))
        }
        Spacer(Modifier.width(8.dp))
        Column2(label, if (mixed) "Mixed" else "#" + Colors.formatRgb(color) + if (Colors.alpha(color) < 255) " · ${(Colors.alpha(color) * 100 / 255)}%" else "")
    }
}

@Composable
private fun Column2(a: String, b: String) {
    androidx.compose.foundation.layout.Column {
        Text(a, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(b, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun PanelRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
}
