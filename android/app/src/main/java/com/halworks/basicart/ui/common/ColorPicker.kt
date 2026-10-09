package com.halworks.basicart.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.halworks.basicart.ui.editor.fadeEdges
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.halworks.basicart.data.AppContainer
import com.halworks.basicart.model.Colors

private fun hsvOf(c: Int): FloatArray = FloatArray(3).also { android.graphics.Color.colorToHSV(c, it) }

/** A round color swatch with checkerboard for transparency. ≥ 48dp touch target. */
@Composable
fun Swatch(color: Int, selected: Boolean = false, size: Int = 36, label: String? = null, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label ?: "Color #${Colors.formatRgb(color)}" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size.dp)
                .clip(CircleShape)
                .border(
                    if (selected) 3.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    CircleShape,
                ),
        ) {
            Checkerboard(Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Color(color)))
        }
    }
}


/**
 * The one color picker used everywhere (§9): palettes, recents, My colors, HSV + alpha,
 * hex and RGB fields, and an optional eyedropper.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorPickerSheet(
    app: AppContainer,
    title: String,
    initial: Int,
    allowAlpha: Boolean = true,
    onEyedropper: (() -> Unit)? = null,
    onChange: (Int) -> Unit,
    onDismiss: (Int) -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var color by remember { mutableStateOf(initial) }
    val hsv = remember { hsvOf(initial) }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    var alpha by remember { mutableFloatStateOf(Colors.alpha(initial) / 255f) }
    var paletteIdx by remember { mutableStateOf(0) }
    var customMode by remember { mutableStateOf(false) }
    val recents by app.settings.recentColors.collectAsState()
    val mine by app.settings.myColors.collectAsState()

    fun setColor(c: Int, fromHsv: Boolean = false) {
        val cc = if (allowAlpha) c else Colors.withAlpha(c, 255)
        color = cc
        if (!fromHsv) {
            val h = hsvOf(cc); hue = h[0]; sat = h[1]; value = h[2]; alpha = Colors.alpha(cc) / 255f
        }
        onChange(cc)
    }
    fun fromHsv() = setColor(Colors.withAlpha(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)), (alpha * 255).toInt()), true)

    fun finish() { app.settings.addRecentColor(color); onDismiss(color) }

    ModalBottomSheet(onDismissRequest = { finish() }, sheetState = sheet, scrimColor = Color.Black.copy(alpha = 0.10f)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))) {
                    Checkerboard(Modifier.size(44.dp))
                    Box(Modifier.size(44.dp).background(Color(color)))
                }
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))

                FilledTonalButton(onClick = { finish() }) { Text("Done") }
            }
            Spacer(Modifier.height(8.dp))
            // Compact: either swatches or the custom picker, so the canvas stays visible (live preview).
            androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Swatches", "Custom").forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = customMode == (i == 1), onClick = { customMode = i == 1 },
                        shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, 2), icon = {},
                    ) { Text(label) }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (!customMode) {
            // Palettes
            Row(run { val sc = rememberScrollState(); Modifier.fadeEdges(sc).horizontalScroll(sc) }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                app.palettes.forEachIndexed { i, p ->
                    FilterChip(selected = paletteIdx == i, onClick = { paletteIdx = i }, label = { Text(p.name) })
                }
            }
            Row(run { val sc = rememberScrollState(); Modifier.fadeEdges(sc).horizontalScroll(sc) }) {
                if (onEyedropper != null) EyedropperButton { onEyedropper() }
                app.palettes.getOrNull(paletteIdx)?.colors?.forEach { c ->
                    Swatch(c, selected = c == color) { setColor(c) }
                }
            }
            if (recents.isNotEmpty()) {
                SectionLabel("Recent")
                Row(run { val sc = rememberScrollState(); Modifier.fadeEdges(sc).horizontalScroll(sc) }) {
                    recents.forEach { c -> Swatch(c, selected = c == color) { setColor(c) } }
                }
            }
            SectionLabel("My colors")
            Row(run { val sc = rememberScrollState(); Modifier.fadeEdges(sc).horizontalScroll(sc) }, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { app.settings.addMyColor(color) }) {
                    Icon(Icons.Outlined.Add, contentDescription = "Save current color to My colors")
                }
                mine.forEach { c -> Swatch(c, selected = c == color) { setColor(c) } }
                if (mine.isEmpty()) Text("Tap + to save the current color", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            } else {
            if (onEyedropper != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    EyedropperButton { onEyedropper() }
                    Text("Pick from canvas", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Saturation/value square
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .semantics { contentDescription = "Saturation and brightness" }
                    .pointerInput(Unit) {
                        fun upd(o: Offset) {
                            sat = (o.x / size.width).coerceIn(0f, 1f)
                            value = 1f - (o.y / size.height).coerceIn(0f, 1f)
                            fromHsv()
                        }
                        detectTapGestures { upd(it) }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { ch, _ ->
                            sat = (ch.position.x / size.width).coerceIn(0f, 1f)
                            value = 1f - (ch.position.y / size.height).coerceIn(0f, 1f)
                            fromHsv()
                        }
                    },
            ) {
                drawRect(Brush.horizontalGradient(listOf(Color.White, Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))))))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                // Keep the cursor fully visible at the edges and corners.
                val inset = 10.5f.dp.toPx()
                val p = Offset((sat * size.width).coerceIn(inset, size.width - inset), ((1 - value) * size.height).coerceIn(inset, size.height - inset))
                drawCircle(Color.White, 9.dp.toPx(), p, style = Stroke(3.dp.toPx()))
                drawCircle(Color.Black.copy(alpha = 0.4f), 10.5f.dp.toPx(), p, style = Stroke(1.dp.toPx()))
            }
            Spacer(Modifier.height(10.dp))
            GradientSlider(
                "Hue",
                hue / 360f,
                Brush.horizontalGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }),
            ) { hue = it * 360f; fromHsv() }
            if (allowAlpha) {
                Spacer(Modifier.height(10.dp))
                GradientSlider(
                    "Opacity",
                    alpha,
                    Brush.horizontalGradient(listOf(Color(color).copy(alpha = 0f), Color(color).copy(alpha = 1f))),
                    checker = true,
                ) { alpha = it; fromHsv() }
            }
            Spacer(Modifier.height(10.dp))
            HexRgbFields(color, allowAlpha) { setColor(it) }
            }
        }
    }
}

/** Eyedropper as a swatch-sized button: always the first item of the swatch row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EyedropperButton(onClick: () -> Unit) {
    androidx.compose.material3.TooltipBox(
        positionProvider = androidx.compose.material3.TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text("Pick from canvas") } },
        state = androidx.compose.material3.rememberTooltipState(),
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = "Pick from canvas", onClick = onClick)
                .semantics { contentDescription = "Pick from canvas" },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Colorize, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer) }
        }
    }
}

@Composable
private fun SectionLabel(s: String) {
    Text(
        s, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun GradientSlider(label: String, value: Float, brush: Brush, checker: Boolean = false, onChange: (Float) -> Unit) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .semantics { contentDescription = "$label slider" }
            .pointerInput(Unit) { detectTapGestures { onChange((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) { detectDragGestures { ch, _ -> onChange((ch.position.x / size.width).coerceIn(0f, 1f)) } },
    ) {
        if (checker) checkerboard(Color.White, Color(0xFFD5D5DA), 8.dp.toPx())
        drawRect(brush)
        val x = (value * size.width).coerceIn(12.dp.toPx(), size.width - 12.dp.toPx())
        drawCircle(Color.White, 11.dp.toPx(), Offset(x, size.height / 2), style = Stroke(3.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.35f), 12.5f.dp.toPx(), Offset(x, size.height / 2), style = Stroke(1.dp.toPx()))
    }
}

@Composable
private fun HexRgbFields(color: Int, allowAlpha: Boolean, onColor: (Int) -> Unit) {
    var hex by remember { mutableStateOf(Colors.formatRgb(color)) }
    var r by remember { mutableStateOf("") }
    var g by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    LaunchedEffect(color) {
        if (Colors.parse("#$hex")?.let { it and 0xFFFFFF } != (color and 0xFFFFFF)) hex = Colors.formatRgb(color)
        r = ((color shr 16) and 0xFF).toString(); g = ((color shr 8) and 0xFF).toString(); b = (color and 0xFF).toString()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = hex,
            onValueChange = { s ->
                val clean = s.removePrefix("#").filter { it.isLetterOrDigit() }.take(6).uppercase()
                hex = clean
                if (clean.length == 6) Colors.parse("#$clean")?.let { onColor(Colors.withAlpha(it, Colors.alpha(color))) }
            },
            label = { Text("HEX") },
            prefix = { Text("#") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.weight(1.6f),
        )
        @Composable
        fun channel(label: String, v: String, set: (String) -> Unit, shift: Int) {
            OutlinedTextField(
                value = v,
                onValueChange = { s ->
                    val d = s.filter { it.isDigit() }.take(3)
                    set(d)
                    d.toIntOrNull()?.takeIf { it in 0..255 }?.let { n ->
                        onColor((color and (0xFF shl shift).inv()) or (n shl shift))
                    }
                },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        channel("R", r, { r = it }, 16)
        channel("G", g, { g = it }, 8)
        channel("B", b, { b = it }, 0)
    }
}
