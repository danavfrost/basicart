package com.halworks.basicart.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.halworks.basicart.ui.editor.fadeEdges
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.halworks.basicart.data.AppContainer
import com.halworks.basicart.data.Projects
import com.halworks.basicart.model.TRANSPARENT
import com.halworks.basicart.model.WHITE
import com.halworks.basicart.ui.common.Checkerboard
import com.halworks.basicart.ui.common.ColorPickerSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class SizePreset(val name: String, val w: Int, val h: Int)

private val PRESETS = listOf(
    SizePreset("Square", 1080, 1080),
    SizePreset("Portrait", 1080, 1350),
    SizePreset("Story/Phone", 1080, 1920),
    SizePreset("Landscape", 1920, 1080),
    SizePreset("Banner", 1500, 500),
    SizePreset("Meme", 1200, 1200),
    SizePreset("A4 @150dpi", 1240, 1754),
)

private enum class Bg { WHITE, TRANSPARENT, COLOR }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewImageSheet(app: AppContainer, onDismiss: () -> Unit, onCreated: (String) -> Unit, onError: (String) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var wText by remember { mutableStateOf("1080") }
    var hText by remember { mutableStateOf("1080") }
    var lock by remember { mutableStateOf(false) }
    var preset by remember { mutableStateOf<String?>("Square") }
    var bg by remember { mutableStateOf(Bg.WHITE) }
    var bgColor by remember { mutableStateOf(0xFFFFD54F.toInt()) }
    var pickColor by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val w = wText.toIntOrNull()
    val h = hText.toIntOrNull()
    val valid = w != null && h != null && w in 16..8192 && h in 16..8192
    val bytes = if (valid) w!!.toLong() * h!! * 4 else 0L
    val heavy = bytes > Runtime.getRuntime().maxMemory() / 6

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                val id = withContext(Dispatchers.IO) { Projects.createFromPhoto(app, context, uri, name) }
                busy = false
                if (id != null) onCreated(id) else onError("Couldn't open that image")
            }
        }
    }

    fun setW(s: String) {
        val clean = s.filter { it.isDigit() }.take(4)
        val oldW = wText.toIntOrNull(); val oldH = hText.toIntOrNull()
        wText = clean; preset = null
        val nw = clean.toIntOrNull()
        if (lock && nw != null && oldW != null && oldH != null && oldW > 0) hText = (nw.toDouble() * oldH / oldW).roundToInt().coerceAtLeast(1).toString()
    }
    fun setH(s: String) {
        val clean = s.filter { it.isDigit() }.take(4)
        val oldW = wText.toIntOrNull(); val oldH = hText.toIntOrNull()
        hText = clean; preset = null
        val nh = clean.toIntOrNull()
        if (lock && nh != null && oldW != null && oldH != null && oldH > 0) wText = (nh.toDouble() * oldW / oldH).roundToInt().coerceAtLeast(1).toString()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, modifier = Modifier.statusBarsPadding()) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 16.dp),
        ) {
            Text("New image", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Outlined.Image, null)
                Spacer(Modifier.width(8.dp))
                Text("Start from photo")
            }
            Spacer(Modifier.height(20.dp))
            Text("Size", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PRESETS.forEach { p ->
                    FilterChip(
                        selected = preset == p.name,
                        onClick = { preset = p.name; wText = p.w.toString(); hText = p.h.toString() },
                        label = { Text("${p.name}  ${p.w}×${p.h}") },
                        leadingIcon = if (preset == p.name) ({ Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) }) else null,
                    )
                }
                FilterChip(selected = preset == null, onClick = { preset = null }, label = { Text("Custom") },
                    leadingIcon = if (preset == null) ({ Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) }) else null)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = wText, onValueChange = ::setW, label = { Text("Width (px)") }, singleLine = true,
                    isError = w == null || w !in 16..8192,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f),
                )
                IconToggleButton(checked = lock, onCheckedChange = { lock = it }) {
                    Icon(if (lock) Icons.Outlined.Link else Icons.Outlined.LinkOff, contentDescription = if (lock) "Aspect ratio locked" else "Aspect ratio unlocked",
                        tint = if (lock) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = hText, onValueChange = ::setH, label = { Text("Height (px)") }, singleLine = true,
                    isError = h == null || h !in 16..8192,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    modifier = Modifier.weight(1f),
                )
            }
            if (!valid) {
                Text("Each side must be 16–8192 px.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            } else if (heavy) {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("That's a very large canvas for this device. It may be slow.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("Background", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            com.halworks.basicart.ui.common.BackgroundChips(
                when (bg) { Bg.WHITE -> com.halworks.basicart.ui.common.BgKind.WHITE; Bg.TRANSPARENT -> com.halworks.basicart.ui.common.BgKind.TRANSPARENT; Bg.COLOR -> com.halworks.basicart.ui.common.BgKind.COLOR },
                bgColor,
            ) { k -> bg = when (k) { com.halworks.basicart.ui.common.BgKind.WHITE -> Bg.WHITE; com.halworks.basicart.ui.common.BgKind.TRANSPARENT -> Bg.TRANSPARENT; else -> Bg.COLOR } }
            // Inline color choice (no second sheet on top of this one).
            androidx.compose.animation.AnimatedVisibility(bg == Bg.COLOR) {
                val colors = remember { app.palettes.flatMap { it.colors }.distinct() }
                val sc = rememberScrollState()
                Row(Modifier.padding(top = 8.dp).fadeEdges(sc).horizontalScroll(sc)) {
                    colors.forEach { c -> com.halworks.basicart.ui.common.Swatch(c, selected = c == bgColor, size = 32) { bgColor = c } }
                }
            }
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(100) }, singleLine = true,
                label = { Text("Name (optional)") }, placeholder = { Text("Untitled") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        val color = when (bg) { Bg.WHITE -> WHITE; Bg.TRANSPARENT -> TRANSPARENT; Bg.COLOR -> bgColor }
                        val id = withContext(Dispatchers.IO) { Projects.create(app, w!!, h!!, color, name) }
                        busy = false
                        onCreated(id)
                    }
                },
                enabled = valid && !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Create")
            }
        }
    }
}
