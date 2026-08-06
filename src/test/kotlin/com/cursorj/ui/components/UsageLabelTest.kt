package com.cursorj.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class UsageLabelTest {
    @Test
    fun `formatTokenCount uses K and M suffixes`() {
        assertEquals("500", UsageLabel.formatTokenCount(500))
        assertEquals("1.2K", UsageLabel.formatTokenCount(1200))
        assertEquals("31.4K", UsageLabel.formatTokenCount(31_400))
        assertEquals("1.5M", UsageLabel.formatTokenCount(1_500_000))
    }

    @Test
    fun `buildUsagePlainText shows in out and optional fields`() {
        val u = com.cursorj.acp.messages.TokenUsage(
            inputTokens = 100L,
            outputTokens = 50L,
            thoughtTokens = 10L,
        )
        val text = UsageLabel.buildUsagePlainText(u)
        assertEquals("100 in / 50 out · thought 10", text)
    }
}
