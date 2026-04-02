package com.cursorj.ui.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.Color

class EditorInsertedDiffHighlightTest {

    @Test
    fun `fallback for light paper uses green-tinted background and darker stripe`() {
        val (bg, stripe) = EditorInsertedDiffHighlight.fallbackBackgroundAndStripeForPaper(Color.WHITE)
        assertEquals(Color(0xEAF8EE), bg)
        assertEquals(Color(0x28A745), stripe)
    }

    @Test
    fun `fallback for dark paper uses dark green background and lighter stripe`() {
        val (bg, stripe) = EditorInsertedDiffHighlight.fallbackBackgroundAndStripeForPaper(Color(0x2B2B2B))
        assertEquals(Color(0x2D4A32), bg)
        assertEquals(Color(0x56D364), stripe)
    }
}
