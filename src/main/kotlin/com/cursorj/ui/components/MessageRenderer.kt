package com.cursorj.ui.components

import com.cursorj.acp.ChatMessage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import javax.swing.*

class MessageRenderer(private var message: ChatMessage) {
    private val log = Logger.getInstance(MessageRenderer::class.java)

    private val panel = object : JPanel() {
        override fun getMinimumSize(): Dimension {
            val pref = preferredSize
            return Dimension(0, pref.height)
        }

        override fun getMaximumSize(): Dimension {
            val pref = preferredSize
            return Dimension(Int.MAX_VALUE, pref.height)
        }
    }.apply {
        layout = BorderLayout()
        border = JBUI.Borders.empty(6, 0)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private val contentArea = JTextPane().apply {
        contentType = "text/html"
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty(4, 8)
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        minimumSize = Dimension(0, 0)
    }

    private val bubblePanel = object : JPanel(BorderLayout()) {
        override fun getMinimumSize(): Dimension {
            val pref = preferredSize
            return Dimension(0, pref.height)
        }

        override fun getMaximumSize(): Dimension {
            val pref = preferredSize
            return Dimension(Int.MAX_VALUE, pref.height)
        }
    }.apply {
        isOpaque = true
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(
                JBColor(Color(0xDDDDDD), Color(0x3D3D3D)),
                1,
            ),
            JBUI.Borders.empty(0),
        )
        add(contentArea, BorderLayout.CENTER)
    }

    val component: JComponent get() = panel

    init {
        panel.add(bubblePanel, BorderLayout.CENTER)
        applyBubbleColor()
        updateContent()
    }

    fun update(newMessage: ChatMessage) {
        val roleChanged = message.role != newMessage.role
        message = newMessage
        if (roleChanged) applyBubbleColor()
        updateContent()
    }

    private fun applyBubbleColor() {
        val isUser = message.role == "user"
        val isTool = message.role == "tool"
        bubblePanel.background = when {
            isUser -> JBColor(Color(0xE3F2FD), Color(0x1A3A5C))
            isTool -> JBColor(Color(0xFFF3E0), Color(0x3C2A10))
            else -> JBColor(Color(0xF5F5F5), Color(0x2D2D2D))
        }
    }

    private fun updateContent() {
        val baseFontSize = contentArea.font?.size ?: 13
        val html = MarkdownRenderer.renderToHtml(message.content, baseFontSize)
        try {
            contentArea.text = html
            contentArea.caretPosition = 0
        } catch (e: Exception) {
            log.debug("HTML rendering failed, falling back to plain text", e)
            contentArea.contentType = "text/plain"
            contentArea.text = message.content
        }
    }
}
