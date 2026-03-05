package com.cursorj.ui.components

import com.cursorj.acp.ChatMessage
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

class MessageRenderer(private var message: ChatMessage) {
    private val panel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(4, 8)
        maximumSize = Dimension(Int.MAX_VALUE, Short.MAX_VALUE.toInt())
    }

    private val contentArea = JEditorPane().apply {
        contentType = "text/html"
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty(6, 10)
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
    }

    val component: JComponent get() = panel

    init {
        render()
    }

    fun update(newMessage: ChatMessage) {
        message = newMessage
        render()
    }

    private fun render() {
        panel.removeAll()

        val isUser = message.role == "user"
        val isTool = message.role == "tool"

        val bubblePanel = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = when {
                isUser -> JBColor(Color(0xE3F2FD), Color(0x1A3A5C))
                isTool -> JBColor(Color(0xFFF3E0), Color(0x3C2A10))
                else -> JBColor(Color(0xF5F5F5), Color(0x2D2D2D))
            }
            border = JBUI.Borders.empty(2)
        }

        val roleLabel = JLabel(
            when (message.role) {
                "user" -> "You"
                "assistant" -> "CursorJ"
                "tool" -> "Tool"
                else -> message.role
            },
        ).apply {
            font = font.deriveFont(Font.BOLD, font.size2D - 1)
            foreground = JBColor.GRAY
            border = JBUI.Borders.empty(0, 10, 2, 0)
        }

        val html = MarkdownRenderer.renderToHtml(message.content)
        contentArea.text = html

        bubblePanel.add(contentArea, BorderLayout.CENTER)

        val wrapperPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
        }
        wrapperPanel.add(roleLabel, BorderLayout.NORTH)
        wrapperPanel.add(bubblePanel, BorderLayout.CENTER)

        if (message.isStreaming) {
            val streamingIndicator = JLabel("...").apply {
                foreground = JBColor.GRAY
                border = JBUI.Borders.empty(0, 10, 0, 0)
            }
            wrapperPanel.add(streamingIndicator, BorderLayout.SOUTH)
        }

        panel.add(wrapperPanel, BorderLayout.CENTER)
        panel.revalidate()
        panel.repaint()
    }
}
