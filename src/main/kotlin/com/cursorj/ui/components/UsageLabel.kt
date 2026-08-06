package com.cursorj.ui.components

import com.cursorj.acp.messages.TokenUsage
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Compact per-message token line for assistant replies (ACP [TokenUsage]).
 */
class UsageLabel(usage: TokenUsage) {
    private val label = JLabel(buildUsagePlainText(usage)).apply {
        font = font.deriveFont(font.size2D - 2f)
        foreground = JBColor(0x6E6E6E, 0x9C9C9C)
        border = JBUI.Borders.empty(0, 8, 2, 8)
        alignmentX = Component.LEFT_ALIGNMENT
    }

    val component: JPanel = object : JPanel(BorderLayout()) {
        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }.apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        add(label, BorderLayout.WEST)
    }

    fun update(usage: TokenUsage) {
        label.text = buildUsagePlainText(usage)
    }

    companion object {
        /** Formats token counts for display (K / M suffixes). Exposed for unit tests. */
        fun formatTokenCount(n: Long): String {
            if (n < 0) return "0"
            return when {
                n >= 1_000_000 -> trimDecimal(n / 1_000_000.0) + "M"
                n >= 1_000 -> trimDecimal(n / 1_000.0) + "K"
                else -> n.toString()
            }
        }

        private fun trimDecimal(v: Double): String {
            val s = String.format("%.1f", v)
            return s.trimEnd('0').trimEnd('.').ifEmpty { "0" }
        }

        internal fun buildUsagePlainText(usage: TokenUsage): String {
            val parts = mutableListOf<String>()
            val input = usage.inputTokens
            val output = usage.outputTokens
            if (input != null || output != null) {
                val inStr = input?.let { "${formatTokenCount(it)} in" } ?: "— in"
                val outStr = output?.let { "${formatTokenCount(it)} out" } ?: "— out"
                parts.add("$inStr / $outStr")
            } else if (usage.totalTokens != null) {
                parts.add("${formatTokenCount(usage.totalTokens)} total")
            }
            usage.thoughtTokens?.takeIf { it > 0 }?.let {
                parts.add("thought ${formatTokenCount(it)}")
            }
            usage.cachedReadTokens?.takeIf { it > 0 }?.let {
                parts.add("cache read ${formatTokenCount(it)}")
            }
            usage.cachedWriteTokens?.takeIf { it > 0 }?.let {
                parts.add("cache write ${formatTokenCount(it)}")
            }
            return parts.joinToString(" · ")
        }
    }
}
