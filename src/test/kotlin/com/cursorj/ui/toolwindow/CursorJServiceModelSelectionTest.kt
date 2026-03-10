package com.cursorj.ui.toolwindow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CursorJServiceModelSelectionTest {
    @Test
    fun `prefers explicit model when valid`() {
        val selected = CursorJService.selectInitialModel(
            explicitModel = "gpt-5",
            configuredDefaultModel = "sonnet",
            availableModelIds = setOf("gpt-5", "sonnet"),
        )
        assertEquals("gpt-5", selected)
    }

    @Test
    fun `falls back to configured model when explicit empty`() {
        val selected = CursorJService.selectInitialModel(
            explicitModel = "   ",
            configuredDefaultModel = "sonnet",
            availableModelIds = setOf("gpt-5", "sonnet"),
        )
        assertEquals("sonnet", selected)
    }

    @Test
    fun `returns null when candidate not in discovered list`() {
        val selected = CursorJService.selectInitialModel(
            explicitModel = null,
            configuredDefaultModel = "unknown-model",
            availableModelIds = setOf("gpt-5", "sonnet"),
        )
        assertNull(selected)
    }

    @Test
    fun `accepts configured model when discovery list unavailable`() {
        val selected = CursorJService.selectInitialModel(
            explicitModel = null,
            configuredDefaultModel = "custom-model",
            availableModelIds = emptySet(),
        )
        assertEquals("custom-model", selected)
    }
}
