package com.halworks.basicart.ui.editor

/** What a canvas tap does with the Text tool active (pure, unit-tested). */
sealed interface TextToolTap {
    /** Start editing this (already selected) text layer, caret at the tap point. */
    data class Edit(val id: String) : TextToolTap
    /** Select this text layer (a second tap edits it). */
    data class Select(val id: String) : TextToolTap
    /** Create a new text box at the tap point. */
    data object Create : TextToolTap
    /** Tap outside the canvas on nothing: clear the selection. */
    data object Deselect : TextToolTap
}

object TapRules {
    /**
     * Text tool: tapping the selected text layer always edits it; tapping another text layer
     * selects it; tapping where there is no text layer creates text (on the canvas). Never cycles.
     * [hitTextIds] = text layers under the tap, topmost first.
     */
    fun textTool(selectedId: String?, hitTextIds: List<String>, onCanvas: Boolean): TextToolTap = when {
        selectedId != null && selectedId in hitTextIds -> TextToolTap.Edit(selectedId)
        hitTextIds.isNotEmpty() -> TextToolTap.Select(hitTextIds.first())
        onCanvas -> TextToolTap.Create
        else -> TextToolTap.Deselect
    }

    /**
     * Select tool: topmost hit, or — tapping the same spot again — the next layer beneath
     * the current selection (wraps). [hitIds] topmost first.
     */
    fun selectTool(selectedId: String?, hitIds: List<String>, sameSpot: Boolean, lastTapSelected: String?): String? {
        if (hitIds.isEmpty()) return null
        val cur = hitIds.indexOf(selectedId)
        return when {
            sameSpot && cur >= 0 && lastTapSelected == selectedId -> hitIds[(cur + 1) % hitIds.size]
            cur >= 0 -> hitIds[cur]
            else -> hitIds[0]
        }
    }
}
