package com.cursorj.ui.util

import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiThemeBrightnessTest {

    @Test
    fun `white and near-white are not effectively dark`() {
        assertFalse(UiThemeBrightness.isColorEffectivelyDark(Color.WHITE))
        assertFalse(UiThemeBrightness.isColorEffectivelyDark(Color(0xF0, 0xF0, 0xF0)))
    }

    @Test
    fun `black and typical dark editor gray are effectively dark`() {
        assertTrue(UiThemeBrightness.isColorEffectivelyDark(Color.BLACK))
        assertTrue(UiThemeBrightness.isColorEffectivelyDark(Color(0x2B, 0x2B, 0x2B)))
    }

    @Test
    fun `dark user message bubble uses dark html palette`() {
        val userBubbleDark = Color(0x1A, 0x3A, 0x5C)
        assertFalse(UiThemeBrightness.useLightHtmlPaletteForSurface(userBubbleDark))
    }

    @Test
    fun `light user message bubble uses light html palette`() {
        val userBubbleLight = Color(0xE3, 0xF2, 0xFD)
        assertTrue(UiThemeBrightness.useLightHtmlPaletteForSurface(userBubbleLight))
    }
}
