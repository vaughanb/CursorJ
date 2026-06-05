package com.cursorj.ui.chat

import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertTrue

class WrapFlowLayoutTest {
    @Test
    fun `preferred height grows when chips wrap to multiple rows`() {
        val panel = JPanel(WrapFlowLayout(FlowLayout.LEFT, 4, 4))
        repeat(3) { index ->
            panel.add(JLabel("Screenshot-${index}.png").apply {
                preferredSize = Dimension(180, 24)
            })
        }

        panel.setSize(220, Short.MAX_VALUE.toInt())
        val wrappedHeight = panel.preferredSize.height

        panel.setSize(800, Short.MAX_VALUE.toInt())
        val singleRowHeight = panel.preferredSize.height

        assertTrue(
            wrappedHeight > singleRowHeight,
            "expected wrapped layout to be taller than a single row (wrapped=$wrappedHeight, single=$singleRowHeight)",
        )
    }
}
