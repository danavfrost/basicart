package com.halworks.basicart.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.halworks.basicart.ui.LocalWorkspace

/** Draws a transparency checkerboard over the whole draw scope. */
fun DrawScope.checkerboard(a: Color, b: Color, cell: Float = 8.dp.toPx()) {
    drawRect(a)
    val cols = (size.width / cell).toInt() + 1
    val rows = (size.height / cell).toInt() + 1
    for (r in 0 until rows) for (c in 0 until cols) {
        if ((r + c) % 2 == 1) drawRect(b, Offset(c * cell, r * cell), Size(cell, cell))
    }
}

@Composable
fun Checkerboard(modifier: Modifier) {
    val ws = LocalWorkspace.current
    Canvas(modifier) { checkerboard(ws.checkerA, ws.checkerB) }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(); onDismiss() },
                colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                else ButtonDefaults.textButtonColors(),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun RenameDialog(title: String, initial: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val fr = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { if (it.text.length <= 100) value = it },
                singleLine = true,
                label = { Text("Name") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.focusRequester(fr),
            )
        },
        confirmButton = {
            TextButton(enabled = value.text.isNotBlank(), onClick = { onRename(value.text.trim()); onDismiss() }) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    LaunchedEffect(Unit) { fr.requestFocus() }
}

/** Small wrapper so content stays centered within a max width on tablets. */
@Composable
fun Centered(content: @Composable () -> Unit) = Box { content() }
