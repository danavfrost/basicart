package com.halworks.basicart.ui.editor

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FormatAlignLeft
import androidx.compose.material.icons.automirrored.outlined.FormatAlignRight
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BorderStyle
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FormatAlignCenter
import androidx.compose.material.icons.outlined.FormatAlignJustify
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.halworks.basicart.ui.editor.fadeEdges
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halworks.basicart.data.AppContainer
import com.halworks.basicart.fonts.FontFamily as BaFont
import com.halworks.basicart.model.BackgroundBox
import com.halworks.basicart.model.Colors
import com.halworks.basicart.model.Fill
import com.halworks.basicart.model.FontSelect
import com.halworks.basicart.model.GradientStop
import com.halworks.basicart.model.Join
import com.halworks.basicart.model.LayerBase
import com.halworks.basicart.model.OutlineStyle
import com.halworks.basicart.model.StyleFlag
import com.halworks.basicart.model.TextAlign
import com.halworks.basicart.model.TextCase
import com.halworks.basicart.model.TextLayer
import com.halworks.basicart.model.TextPreset
import com.halworks.basicart.model.TextPresets
import com.halworks.basicart.model.Transform
import com.halworks.basicart.model.withTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private const val BLACK_ = com.halworks.basicart.model.BLACK

private val TABS = listOf(
    "Font" to Icons.Outlined.FontDownload, "Style" to Icons.Outlined.FormatBold, "Color" to Icons.Outlined.Palette,
    "Outline" to Icons.Outlined.BorderStyle, "Shadow" to Icons.Outlined.Gradient, "Effects" to Icons.Outlined.AutoAwesome,
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TextPanel(st: EditorState, requestColor: (ColorRequest) -> Unit, tall: Boolean = false) {
    val t = st.selected as? TextLayer
    if (t == null) {
        Column {
            Hint("Tap the canvas to add text, or drag to set its width. Or start from a style:")
            PresetRow(st) { st.applyPreset(it) }
        }
        return
    }
    // The open tab survives re-selecting layers (kept in the editor state).
    var tab by st::textTab
    val target = st.styleTarget(t)
    Column(if (tall) Modifier.fillMaxHeight() else Modifier) {
        if (target !is EditorState.StyleTarget.Whole) {
            // Makes the selection rule visible: character styling goes to this target.
            Text(
                if (target is EditorState.StyleTarget.Range) "Font, size, color and B·I·U·S apply to the selected text"
                else "Font, size, color and B·I·U·S apply to what you type next",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Six equal tabs that always fit: icon + compact label.
        androidx.compose.material3.PrimaryTabRow(
            selectedTabIndex = tab, containerColor = Color.Transparent,
            divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)) },
        ) {
            TABS.forEachIndexed { i, (name, icon) ->
                Tab(
                    selected = tab == i, onClick = { tab = i },
                    modifier = Modifier.height(56.dp),
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Icon(icon, null, Modifier.size(20.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                }
            }
        }
        Box(Modifier.fillMaxWidth().then(if (tall) Modifier.weight(1f) else Modifier.heightIn(max = if (tab == 0) 252.dp else 212.dp))) {
            when (tab) {
                0 -> FontBrowser(st, t)
                1 -> StyleTab(st, t)
                2 -> ColorTab(st, t, requestColor)
                3 -> OutlineTab(st, t, requestColor)
                4 -> ShadowTab(st, t, requestColor)
                5 -> EffectsTab(st, t, requestColor)
            }
        }
    }
}

/** Bar shown above the keyboard while typing on the canvas. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TextEditBar(st: EditorState, tabsVisible: Boolean = st.textTabsWhileEditing) {
    val t = st.selected as? TextLayer ?: return
    val imeVisible = androidx.compose.foundation.layout.WindowInsets.Companion.isImeVisible
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // B/I/U/S live in the Style tab when the tabs are showing; only one copy at a time.
        if (!tabsVisible) StyleToggles(st, t, compact = true)
        else Text("Text styles", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp))
        Spacer(Modifier.weight(1f))
        // Keyboard ↔ text styles (font, size, color…) without losing the selection.
        val tabs = st.textTabsWhileEditing
        IconButton(onClick = {
            if (tabs) { st.textTabsWhileEditing = false; st.keyboardShowRequest++ }
            else { st.textTabsWhileEditing = true; st.keyboardHideRequest++ }
        }) {
            Icon(if (tabs) Icons.Outlined.Keyboard else Icons.Outlined.Palette, if (tabs) "Show keyboard" else "Show text styles")
        }
        IconButton(onClick = { st.setTextSelection(0, t.text.length) }) { Icon(Icons.Outlined.SelectAll, "Select all text") }
        FilledTonalButton(onClick = { st.finishEditing() }, contentPadding = PaddingValues(horizontal = 16.dp)) {
            Text("Done", maxLines = 1, softWrap = false)
        }
    }
}

/** B · I · U · S, each drawn as a preview of its effect; applies to the highlighted range. */
@Composable
fun StyleToggles(st: EditorState, t: TextLayer, compact: Boolean = false) {
    val side = if (compact) 44.dp else 48.dp
    Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp)) {
        @Composable
        fun btn(flag: StyleFlag, letter: String, style: TextStyle, label: String) {
            val active = st.flagActive(t, flag)
            Box(
                Modifier
                    .size(side)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(role = Role.Checkbox, onClickLabel = label) { st.toggleFlag(flag) }
                    .semantics { contentDescription = label; selected = active },
                contentAlignment = Alignment.Center,
            ) {
                Text(letter, style = style.copy(fontSize = 20.sp, color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface))
            }
        }
        btn(StyleFlag.BOLD, "B", TextStyle(fontWeight = FontWeight.Black), "Bold")
        btn(StyleFlag.ITALIC, "I", TextStyle(fontStyle = FontStyle.Italic, fontFamily = FontFamily.Serif), "Italic")
        btn(StyleFlag.UNDERLINE, "U", TextStyle(textDecoration = TextDecoration.Underline), "Underline")
        btn(StyleFlag.STRIKE, "S", TextStyle(textDecoration = TextDecoration.LineThrough), "Strikethrough")
    }
}

private fun upd(st: EditorState, live: Boolean = true, f: (TextLayer) -> TextLayer) = st.updateSelected<TextLayer>(live) { f(it) }

// ------------------------------------------------------------------ Font

@Composable
private fun rememberTypefaceFamily(app: AppContainer, fontId: String, weight: Int? = null): FontFamily? {
    val ff by produceState<FontFamily?>(null, fontId, weight) {
        value = withContext(Dispatchers.IO) { FontFamily(app.fonts.previewTypeface(fontId, weight)) }
    }
    return ff
}

private sealed interface FontLevel {
    data object Groups : FontLevel
    data class Families(val groupId: String) : FontLevel
    data class Weights(val fontId: String) : FontLevel
}

@Composable
private fun FontBrowser(st: EditorState, t: TextLayer) {
    val app = st.app
    // Current font/weight of whatever the styling applies to (null = mixed).
    val styles = st.targetStyles(t)
    val curFont = styles.map { it.fontId }.distinct().singleOrNull()
    val curWeight = styles.map { it.weight }.distinct().singleOrNull()
    var level by remember { mutableStateOf<FontLevel>(FontLevel.Families(app.fonts.family(curFont ?: t.fontId).group)) }
    var query by remember { mutableStateOf("") }
    val recents by app.settings.recentFonts.collectAsState()

    fun choose(f: BaFont, weight: Int? = null) {
        app.settings.addRecentFont(f.id)
        // Tapping the current family returns to its regular weight (extra weights live in the list).
        val w = weight ?: if (f.id == curFont) f.defaultWeight else null
        st.setTextFont(f.id, w)
    }

    Column {
        // Search
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(44.dp)
                .clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text(
                    if (curFont == null) "Mixed fonts · search ${app.fonts.fonts.size}" else "Search ${app.fonts.fonts.size} fonts",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                )
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search fonts" },
                )
            }
            if (query.isNotEmpty()) IconButton(onClick = { query = "" }, Modifier.size(36.dp)) { Icon(Icons.Outlined.Close, "Clear search", Modifier.size(18.dp)) }
        }
        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            val results = app.fonts.fonts.filter { it.family.lowercase().contains(q) || app.fonts.groups.firstOrNull { g -> g.id == it.group }?.name?.lowercase()?.contains(q) == true }
            LazyColumn(Modifier.fillMaxWidth()) {
                if (results.isEmpty()) item { Hint("No fonts match “$query”.") }
                items(results, key = { it.id }) { f -> FamilyRow(app, f, f.id == curFont, onChoose = { choose(f) }, onWeights = { query = ""; level = FontLevel.Weights(f.id) }) }
            }
            return@Column
        }
        if (recents.isNotEmpty() && level !is FontLevel.Weights) {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(recents.filter { app.fonts.has(it) }, key = { it }) { id ->
                    val f = app.fonts.family(id)
                    val ff = rememberTypefaceFamily(app, id)
                    FilterChip(
                        selected = id == curFont, onClick = { choose(f) },
                        label = { Text(f.family, fontFamily = ff, maxLines = 1, fontSize = 15.sp) },
                    )
                }
            }
        }
        AnimatedContent(
            targetState = level,
            transitionSpec = {
                val forward = when {
                    initialState is FontLevel.Groups -> true
                    initialState is FontLevel.Families && targetState is FontLevel.Weights -> true
                    else -> false
                }
                (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
            },
            label = "fontLevel",
        ) { lv ->
            when (lv) {
                FontLevel.Groups -> LazyColumn(Modifier.fillMaxWidth()) {
                    items(app.fonts.groups, key = { it.id }) { g ->
                        val ff = rememberTypefaceFamily(app, g.sampleFontId)
                        val current = curFont != null && app.fonts.family(curFont).group == g.id
                        Row(
                            Modifier.fillMaxWidth().height(52.dp).clickable(onClickLabel = "Open ${g.name}") { level = FontLevel.Families(g.id) }.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(g.name, fontFamily = ff, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Text("${app.fonts.inGroup(g.id).size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                is FontLevel.Families -> Column {
                    val g = app.fonts.groups.firstOrNull { it.id == lv.groupId }
                    BackRow(g?.name ?: "Fonts", "All font groups") { level = FontLevel.Groups }
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(app.fonts.inGroup(lv.groupId), key = { it.id }) { f ->
                            FamilyRow(app, f, f.id == curFont, onChoose = { choose(f) }, onWeights = { level = FontLevel.Weights(f.id) })
                        }
                    }
                }
                is FontLevel.Weights -> Column {
                    val f = app.fonts.family(lv.fontId)
                    BackRow(f.family, "Back to ${app.fonts.groups.firstOrNull { it.id == f.group }?.name ?: "fonts"}") { level = FontLevel.Families(f.group) }
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(f.extraStyles, key = { it.weight }) { file ->
                            val ff = rememberTypefaceFamily(app, f.id, file.weight)
                            val sel = curFont == f.id && curWeight == file.weight
                            Row(
                                Modifier.fillMaxWidth().height(52.dp).clickable { choose(f, file.weight) }.padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(file.styleName, fontFamily = ff, fontSize = 21.sp, modifier = Modifier.weight(1f), maxLines = 1)
                                Text("${file.weight}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (sel) Icon(Icons.Outlined.Check, "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackRow(title: String, backLabel: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp).clickable(onClickLabel = backLabel, onClick = onBack).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, backLabel, Modifier.padding(8.dp).size(20.dp))
        Text(title, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun FamilyRow(app: AppContainer, f: BaFont, selected: Boolean, onChoose: () -> Unit, onWeights: () -> Unit) {
    val ff = rememberTypefaceFamily(app, f.id)
    Row(
        Modifier.fillMaxWidth().height(52.dp)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f) else Color.Transparent)
            .clickable(onClickLabel = "Use ${f.family}", onClick = onChoose).padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            f.family, fontFamily = ff, fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { contentDescription = f.family },
            color = if (ff == null) Color.Transparent else MaterialTheme.colorScheme.onSurface,
        )
        if (selected) Icon(Icons.Outlined.Check, "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 4.dp))
        if (f.hasExtraWeights) {
            IconButton(onClick = onWeights) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "${f.family} weights (${f.extraStyles.size})", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else Spacer(Modifier.width(48.dp))
    }
}

// ------------------------------------------------------------------ Style

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun StyleTab(st: EditorState, t: TextLayer) {
    Column(run { val vs = rememberScrollState(); Modifier.fadeEdgesVertical(vs).verticalScroll(vs) }.padding(vertical = 8.dp)) {
        // Wraps onto two rows when the panel is narrow (docked side panel), never clipping.
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StyleToggles(st, t)
            val aligns = listOf(
                TextAlign.LEFT to Icons.AutoMirrored.Outlined.FormatAlignLeft, TextAlign.CENTER to Icons.Outlined.FormatAlignCenter,
                TextAlign.RIGHT to Icons.AutoMirrored.Outlined.FormatAlignRight, TextAlign.JUSTIFY to Icons.Outlined.FormatAlignJustify,
            )
            Row(Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                aligns.forEach { (a, icon) ->
                    val on = t.align == a
                    Box(
                        Modifier.size(40.dp, 48.dp).background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable(onClickLabel = "Align ${a.json}") { upd(st, false) { it.copy(align = a) } },
                        contentAlignment = Alignment.Center,
                    ) { Icon(icon, "Align ${a.json}", Modifier.size(20.dp), tint = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        // Size follows the selection rule; mixed sizes show "Mixed" until changed.
        val sizes = st.targetStyles(t).map { it.size }.distinct()
        val shown = sizes.first().toFloat()
        LabeledSlider("Size", shown, 8f..maxOf(400f, shown), { if (sizes.size > 1) "Mixed" else "${it.roundToInt()} px" },
            editUnit = 1f, editRange = 4f..2000f, onDone = st::endLive) { v ->
            st.setTextSize(v.roundToInt().toDouble(), liveEdit = true)
        }
        LabeledSlider("Spacing", t.letterSpacing.toFloat(), -0.2f..1f, { fmtPx(it * t.fontSize.toFloat()) }, onDone = st::endLive) { v ->
            upd(st) { it.copy(letterSpacing = (Math.round(v * 100) / 100.0)) }
        }
        LabeledSlider("Line height", t.lineHeight.toFloat(), 0.6f..3f, { String.format(java.util.Locale.ROOT, "%.2f×", it) }, onDone = st::endLive) { v ->
            upd(st) { it.copy(lineHeight = (Math.round(v * 20) / 20.0)) }
        }
        // Wraps instead of scrolling, so no chip is ever cut off at the panel edge.
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(TextCase.NONE to "Normal", TextCase.UPPER to "ALL CAPS", TextCase.LOWER to "lowercase", TextCase.TITLE to "Title Case").forEach { (c, label) ->
                FilterChip(selected = t.textCase == c, onClick = { upd(st, false) { it.copy(textCase = c) } }, label = { Text(label) })
            }
        }
    }
}

// ------------------------------------------------------------------ Color

@Composable
private fun ColorTab(st: EditorState, t: TextLayer, requestColor: (ColorRequest) -> Unit) {
    val fill = t.fill
    val target = st.styleTarget(t)
    // Effective solid colors under the target (null = layer gradient); several = mixed.
    val colors = st.targetStyles(t).map { it.color ?: (fill as? Fill.Solid)?.color }.distinct()
    val single = colors.singleOrNull()
    Column(run { val vs = rememberScrollState(); Modifier.fadeEdgesVertical(vs).verticalScroll(vs) }.padding(vertical = 4.dp)) {
        if (target !is EditorState.StyleTarget.Whole) {
            Text(if (target is EditorState.StyleTarget.Range) "Selected text" else "Next text you type",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            PanelRow {
                ColorWell("Color", single ?: Colors.withAlpha(0, 0), mixed = single == null) {
                    requestColor(ColorRequest("Selected text color", single ?: BLACK_, onChange = { c -> st.setTextColor(c, liveEdit = true) }, onDone = { st.endLive() }))
                }
            }
            QuickColors(st) { c -> st.setTextColor(c) }
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Text("Whole text box", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        Segmented(listOf("solid", "linear", "radial"), when (fill) { is Fill.Solid -> "solid"; is Fill.Linear -> "linear"; is Fill.Radial -> "radial" },
            { when (it) { "solid" -> "Solid"; "linear" -> "Linear"; else -> "Radial" } }) { kind ->
            // Choosing a fill type is layer-wide and clears span colors (FORMAT.md §7.9).
            upd(st, false) { l ->
                val stops = when (val f = l.fill) {
                    is Fill.Solid -> listOf(GradientStop(0.0, f.color), GradientStop(1.0, 0xFF4A55E0.toInt()))
                    is Fill.Linear -> f.stops
                    is Fill.Radial -> f.stops
                }
                com.halworks.basicart.model.CharStyling.setLayerFill(l, when (kind) {
                    "solid" -> Fill.Solid(stops.first().color)
                    "linear" -> Fill.Linear((l.fill as? Fill.Linear)?.angle ?: 90.0, stops)
                    else -> Fill.Radial(stops)
                })
            }
        }
        when (fill) {
            is Fill.Solid -> {
                val wholeMixed = target is EditorState.StyleTarget.Whole && single == null
                PanelRow {
                    ColorWell("Fill", fill.color, mixed = wholeMixed) {
                        requestColor(ColorRequest("Text color", fill.color, onChange = { c ->
                            upd(st) { com.halworks.basicart.model.CharStyling.setLayerFill(it, Fill.Solid(c)) }
                        }, onDone = { st.endLive() }))
                    }
                }
                QuickColors(st) { c -> upd(st, false) { com.halworks.basicart.model.CharStyling.setLayerFill(it, Fill.Solid(c)) } }
            }
            else -> {
                val stops = if (fill is Fill.Linear) fill.stops else (fill as Fill.Radial).stops
                fun setStops(s: List<GradientStop>, live: Boolean) = upd(st, live) { l ->
                    l.copy(fill = when (val f = l.fill) { is Fill.Linear -> f.copy(stops = s); is Fill.Radial -> f.copy(stops = s); else -> f })
                }
                Row(run { val sc = rememberScrollState(); Modifier.fadeEdges(sc).horizontalScroll(sc) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    stops.forEachIndexed { i, s ->
                        ColorWell(if (i == 0) "Start" else if (i == stops.lastIndex) "End" else "Middle", s.color) {
                            requestColor(ColorRequest("Gradient color", s.color, onChange = { c ->
                                setStops(stops.toMutableList().also { it[i] = s.copy(color = c) }, true)
                            }, onDone = { st.endLive() }))
                        }
                    }
                    if (stops.size < 3) {
                        IconButton(onClick = { setStops(listOf(stops[0], GradientStop(0.5, stops[0].color), stops.last()), false) }) { Icon(Icons.Outlined.Add, "Add middle color") }
                    } else {
                        IconButton(onClick = { setStops(listOf(stops[0], stops.last()), false) }) { Icon(Icons.Outlined.Close, "Remove middle color") }
                    }
                }
                if (fill is Fill.Linear) {
                    LabeledSlider("Angle", fill.angle.toFloat(), 0f..359f, ::fmtDeg, onDone = st::endLive) { v ->
                        upd(st) { l -> (l.fill as? Fill.Linear)?.let { l.copy(fill = it.copy(angle = v.roundToInt().toDouble())) } ?: l }
                    }
                }
            }
        }
        LabeledSlider("Opacity", t.base.opacity.toFloat(), 0f..1f, ::fmtPct, onDone = st::endLive) { v ->
            upd(st) { it.copy(base = it.base.copy(opacity = (Math.round(v * 100) / 100.0))) }
        }
    }
}

@Composable
fun QuickColors(st: EditorState, onPick: (Int) -> Unit) {
    val recents by st.app.settings.recentColors.collectAsState()
    val colors = (recents + st.app.palettes.firstOrNull()?.colors.orEmpty()).distinct().take(18)
    LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
        items(colors) { c -> com.halworks.basicart.ui.common.Swatch(c, size = 30) { st.app.settings.addRecentColor(c); onPick(c) } }
    }
}

// ------------------------------------------------------------------ Outline

@Composable
private fun OutlineTab(st: EditorState, t: TextLayer, requestColor: (ColorRequest) -> Unit) {
    val o = t.outline
    val fs = t.fontSize.toFloat()
    Column(run { val vs = rememberScrollState(); Modifier.fadeEdgesVertical(vs).verticalScroll(vs) }.padding(vertical = 4.dp)) {
        SwitchRow("Outline", o.enabled, { v -> upd(st, false) { it.copy(outline = it.outline.copy(enabled = v)) } })
        val en = o.enabled
        Segmented(OutlineStyle.entries, o.style, { when (it) { OutlineStyle.SOLID -> "Solid"; OutlineStyle.DOUBLE -> "Double"; OutlineStyle.GLOW -> "Glow" } }, enabled = en) { s ->
            upd(st, false) { it.copy(outline = it.outline.copy(style = s, enabled = true)) }
        }
        PanelRow {
            ColorWell(if (o.style == OutlineStyle.GLOW) "Glow" else if (o.style == OutlineStyle.DOUBLE) "Inner" else "Color", o.color) {
                requestColor(ColorRequest("Outline color", o.color, onChange = { c -> upd(st) { it.copy(outline = it.outline.copy(color = c, enabled = true)) } }, onDone = { st.endLive() }))
            }
            if (o.style == OutlineStyle.DOUBLE) {
                ColorWell("Outer", o.color2) {
                    requestColor(ColorRequest("Outer outline color", o.color2, onChange = { c -> upd(st) { it.copy(outline = it.outline.copy(color2 = c)) } }, onDone = { st.endLive() }))
                }
            }
        }
        LabeledSlider(if (o.style == OutlineStyle.DOUBLE) "Inner" else "Thickness", o.width.toFloat(), 0f..0.5f, { fmtPx(it * fs) }, enabled = en, onDone = st::endLive) { v ->
            upd(st) { it.copy(outline = it.outline.copy(width = Math.round(v * 1000) / 1000.0)) }
        }
        if (o.style == OutlineStyle.DOUBLE) {
            LabeledSlider("Outer", o.width2.toFloat(), 0f..0.5f, { fmtPx(it * fs) }, enabled = en, onDone = st::endLive) { v ->
                upd(st) { it.copy(outline = it.outline.copy(width2 = Math.round(v * 1000) / 1000.0)) }
            }
        }
        if (o.style == OutlineStyle.GLOW) {
            LabeledSlider("Radius", o.glowRadius.toFloat(), 0f..2f, { fmtPx(it * fs) }, enabled = en, onDone = st::endLive) { v ->
                upd(st) { it.copy(outline = it.outline.copy(glowRadius = Math.round(v * 100) / 100.0)) }
            }
        }
        Segmented(Join.entries, o.join, { if (it == Join.ROUND) "Round" else "Sharp" }, enabled = en) { j ->
            upd(st, false) { it.copy(outline = it.outline.copy(join = j)) }
        }
    }
}

// ------------------------------------------------------------------ Shadow

@Composable
private fun ShadowTab(st: EditorState, t: TextLayer, requestColor: (ColorRequest) -> Unit) {
    val s = t.shadow
    val fs = t.fontSize.toFloat()
    val dist = hypot(s.offsetX, s.offsetY).toFloat()
    val ang = Math.toDegrees(atan2(s.offsetY, s.offsetX)).toFloat().let { if (it < 0) it + 360f else it }
    fun setPolar(d: Float, a: Float) = upd(st) {
        val r = Math.toRadians(a.toDouble())
        it.copy(shadow = it.shadow.copy(offsetX = Math.round(d * cos(r) * 1000) / 1000.0, offsetY = Math.round(d * sin(r) * 1000) / 1000.0))
    }
    Column(run { val vs = rememberScrollState(); Modifier.fadeEdgesVertical(vs).verticalScroll(vs) }.padding(vertical = 4.dp)) {
        // Same layout as Outline: toggle row, then the color on its own row.
        SwitchRow("Shadow", s.enabled, { v -> upd(st, false) { it.copy(shadow = it.shadow.copy(enabled = v)) } })
        PanelRow {
            ColorWell("Color", Colors.withAlpha(s.color, 255)) {
                requestColor(ColorRequest("Shadow color", Colors.withAlpha(s.color, 255), allowAlpha = false,
                    onChange = { c -> upd(st) { it.copy(shadow = it.shadow.copy(color = Colors.withAlpha(c, Colors.alpha(it.shadow.color)), enabled = true)) } },
                    onDone = { st.endLive() }))
            }
        }
        val en = s.enabled
        LabeledSlider("Opacity", Colors.alpha(s.color) / 255f, 0f..1f, ::fmtPct, enabled = en, onDone = st::endLive) { v ->
            upd(st) { it.copy(shadow = it.shadow.copy(color = Colors.withAlpha(it.shadow.color, (v * 255).roundToInt()))) }
        }
        LabeledSlider("Blur", s.blur.toFloat(), 0f..1f, { fmtPx(it * fs) }, enabled = en, onDone = st::endLive) { v ->
            upd(st) { it.copy(shadow = it.shadow.copy(blur = Math.round(v * 1000) / 1000.0)) }
        }
        LabeledSlider("Distance", dist, 0f..1f, { fmtPx(it * fs) }, enabled = en, onDone = st::endLive) { v -> setPolar(v, ang) }
        LabeledSlider("Angle", ang, 0f..359f, ::fmtDeg, enabled = en, onDone = st::endLive) { v -> setPolar(dist.coerceAtLeast(0.001f), v) }
    }
}

// ------------------------------------------------------------------ Effects

@Composable
private fun EffectsTab(st: EditorState, t: TextLayer, requestColor: (ColorRequest) -> Unit) {
    val b = t.backgroundBox
    val fs = t.fontSize.toFloat()
    val curved = t.curve != 0.0
    Column(run { val vs = rememberScrollState(); Modifier.fadeEdgesVertical(vs).verticalScroll(vs) }.padding(vertical = 4.dp)) {
        Text("Presets", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        PresetRow(st) { st.applyPreset(it) }
        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        SwitchRow(if (curved) "Background box (not with curve)" else "Background box", b.enabled && !curved, { v ->
            if (!curved) upd(st, false) { it.copy(backgroundBox = it.backgroundBox.copy(enabled = v)) }
        })
        PanelRow {
            ColorWell("Color", b.color) {
                requestColor(ColorRequest("Background box color", b.color, onChange = { c -> upd(st) { it.copy(backgroundBox = it.backgroundBox.copy(color = c, enabled = true)) } }, onDone = { st.endLive() }))
            }
        }
        val en = b.enabled && !curved
        LabeledSlider("Padding", b.padding.toFloat(), 0f..1f, { fmtPx(it * fs) }, enabled = en, onDone = st::endLive) { v ->
            upd(st) { it.copy(backgroundBox = it.backgroundBox.copy(padding = Math.round(v * 1000) / 1000.0)) }
        }
        LabeledSlider("Corners", b.cornerRadius.toFloat(), 0f..1f, { fmtPx(it * fs) }, enabled = en, onDone = st::endLive) { v ->
            upd(st) { it.copy(backgroundBox = it.backgroundBox.copy(cornerRadius = Math.round(v * 1000) / 1000.0)) }
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        LabeledSlider("Curve", t.curve.toFloat(), -100f..100f, { v -> if (v.roundToInt() == 0) "Off" else "${v.roundToInt()}%" }, editUnit = 1f, onDone = st::endLive) { v ->
            val r = v.roundToInt().let { if (kotlin.math.abs(it) < 3) 0 else it }
            upd(st) { it.copy(curve = r.toDouble()) }
        }
        LabeledSlider("Rotation", t.base.transform.rotation.let { if (it > 180) it - 360 else it }.toFloat(), -180f..180f, ::fmtDeg, editUnit = 1f, onDone = st::endLive) { v ->
            upd(st) { it.withTransform(it.base.transform.copy(rotation = ((v.roundToInt() % 360) + 360) % 360.0)) as TextLayer }
        }
        LabeledSlider("Slant", t.skew.toFloat(), -45f..45f, ::fmtDeg, editUnit = 1f, onDone = st::endLive) { v ->
            upd(st) { it.copy(skew = v.roundToInt().toDouble()) }
        }
    }
}

/** Preset chips, each previewed in its own look (rendered by the real renderer). */
@Composable
fun PresetRow(st: EditorState, onApply: (TextPreset) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        items(st.app.presets, key = { it.id }) { p ->
            val preview by produceState<Bitmap?>(null, p.id) {
                value = withContext(Dispatchers.Default) { presetPreview(st, p) }
            }
            Surface(
                onClick = { onApply(p) },
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF6E6A7C),
                modifier = Modifier.size(width = 124.dp, height = 64.dp).semantics { contentDescription = "${p.name} text style" },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    preview?.let { Image(it.asImageBitmap(), null, Modifier.padding(6.dp)) }
                }
            }
        }
    }
}

private fun presetPreview(st: EditorState, p: TextPreset): Bitmap {
    val base = TextLayer(LayerBase("p", "p", transform = Transform(0.0, 0.0)), text = p.name, fontSize = 40.0)
    val t = TextPresets.apply(base, p)
    val r = st.renderer
    val b = r.contentBounds(t)
    val w = 300; val h = 140
    val s = minOf(w / b.width(), h / b.height(), 1.6f)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    c.translate(w / 2f, h / 2f)
    c.scale(s, s)
    c.translate(-b.centerX(), -b.centerY())
    r.drawContent(c, t, s)
    return bmp
}

