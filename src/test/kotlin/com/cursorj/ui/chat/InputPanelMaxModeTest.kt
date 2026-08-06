package com.cursorj.ui.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InputPanelMaxModeTest {
    @Test
    fun `setMaxMode does not invoke onMaxModeToggled`() {
        val panel = InputPanel()
        var toggled = false
        panel.onMaxModeToggled = { toggled = true }
        panel.setMaxMode(true)
        assertFalse(toggled, "setMaxMode should sync UI only")
        panel.setMaxMode(false)
        assertFalse(toggled)
    }
}
