package com.halworks.basicart.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class BgKind { WHITE, TRANSPARENT, COLOR }

/** White / Transparent / Color choice with a swatch per chip and a check on the selected one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackgroundChips(selected: BgKind, color: Int, modifier: Modifier = Modifier, onSelect: (BgKind) -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        @Composable
        fun chip(kind: BgKind, label: String, swatch: @Composable () -> Unit) {
            val on = selected == kind
            FilterChip(
                selected = on, onClick = { onSelect(kind) }, label = { Text(label) },
                // Check first (like every other chip in the app), then the swatch.
                leadingIcon = {
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        if (on) { Icon(Icons.Outlined.Check, "Selected", Modifier.size(18.dp)); androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp)) }
                        swatch()
                    }
                },
            )
        }
        chip(BgKind.WHITE, "White") { Box(Modifier.size(18.dp).clip(CircleShape).background(Color.White).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)) }
        chip(BgKind.TRANSPARENT, "Transparent") { Box(Modifier.size(18.dp).clip(CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)) { Checkerboard(Modifier.matchParentSize()) } }
        chip(BgKind.COLOR, "Color") { Box(Modifier.size(18.dp).clip(CircleShape).background(Color(color)).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)) }
    }
}
