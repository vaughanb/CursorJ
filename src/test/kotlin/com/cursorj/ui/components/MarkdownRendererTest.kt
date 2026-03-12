package com.cursorj.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MarkdownRendererTest {

    @Test
    fun `bold with double asterisks is rendered`() {
        val html = MarkdownRenderer.renderToHtml("**KDoc in 'TodoApp.kt'**")
        assertEquals(
            true,
            html.contains("<span style=\"font-weight: bold;\">") && html.contains("KDoc in &#39;TodoApp.kt&#39;"),
            "Expected bold span around escaped content, got: $html",
        )
    }

    @Test
    fun `bold with double underscores is rendered`() {
        val html = MarkdownRenderer.renderToHtml("__bold text__")
        assertEquals(
            true,
            html.contains("<span style=\"font-weight: bold;\">") && html.contains("bold text"),
            "Expected bold span for __bold__, got: $html",
        )
    }

    @Test
    fun `asterisks are not visible in output`() {
        val html = MarkdownRenderer.renderToHtml("1. **KDoc in 'TodoApp.kt'**")
        assertEquals(
            false,
            html.contains("**"),
            "Raw ** should not appear in HTML output, got: $html",
        )
    }
}
