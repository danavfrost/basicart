package com.halworks.basicart.model

import com.halworks.basicart.ui.editor.TapRules
import com.halworks.basicart.ui.editor.TextToolTap
import org.junit.Assert.assertEquals
import org.junit.Test

class TapRulesTest {
    @Test fun textToolNeverCycles() {
        // selected text under the tap → edit it, even with other text layers beneath or on repeat taps
        assertEquals(TextToolTap.Edit("b"), TapRules.textTool("b", listOf("a", "b"), true))
        assertEquals(TextToolTap.Edit("a"), TapRules.textTool("a", listOf("a", "b"), true))
        // unselected text → select the topmost text (second tap then edits)
        assertEquals(TextToolTap.Select("a"), TapRules.textTool(null, listOf("a", "b"), true))
        assertEquals(TextToolTap.Select("a"), TapRules.textTool("photo", listOf("a"), true))
        // no text under the tap → new text box on the canvas, nothing off-canvas
        assertEquals(TextToolTap.Create, TapRules.textTool("a", emptyList(), true))
        assertEquals(TextToolTap.Deselect, TapRules.textTool("a", emptyList(), false))
    }

    @Test fun selectToolCyclesOnSameSpot() {
        assertEquals("a", TapRules.selectTool(null, listOf("a", "b", "c"), false, null))
        assertEquals("b", TapRules.selectTool("b", listOf("a", "b", "c"), false, "b"))
        assertEquals("c", TapRules.selectTool("b", listOf("a", "b", "c"), true, "b"))
        assertEquals("a", TapRules.selectTool("c", listOf("a", "b", "c"), true, "c"))
        assertEquals(null, TapRules.selectTool("a", emptyList(), true, "a"))
    }
}
