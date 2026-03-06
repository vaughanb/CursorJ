package com.cursorj.ui.components

import com.cursorj.acp.ChatMessage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import javax.swing.*

class MessageRenderer(private var message: ChatMessage) {
    private val log = Logger.getInstance(MessageRenderer::class.java)

    private val panel = object : JPanel() {
        override fun getMaximumSize(): Dimension {
            val pref = preferredSize
            return Dimension(Int.MAX_VALUE, pref.height)
        }
    }.apply {
        layout = BorderLayout()
        border = JBUI.Borders.empty(6, 0)
        isOpaque = false
    }

    private val contentArea = JTextPane().apply {
        contentType = "text/html"
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty(4, 8)
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

        val html = MarkdownRenderer.renderToHtml(message.content)
        try {
            contentArea.text = html
            contentArea.caretPosition = 0
        } catch (e: Exception) {
            log.debug("HTML rendering failed, falling back to plain text", e)
            contentArea.contentType = "text/plain"
            contentArea.text = message.content
        }

        val bubblePanel = object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension {
                val pref = preferredSize
                return Dimension(Int.MAX_VALUE, pref.height)
            }
        }.apply {
            isOpaque = true
            background = when {
                isUser -> JBColor(Color(0xE3F2FD), Color(0x1A3A5C))
                isTool -> JBColor(Color(0xFFF3E0), Color(0x3C2A10))
                else -> JBColor(Color(0xF5F5F5), Color(0x2D2D2D))
            }
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(
                    JBColor(Color(0xDDDDDD), Color(0x3D3D3D)),
                    1,
                ),
                JBUI.Borders.empty(0),
            )
            add(contentArea, BorderLayout.CENTER)
        }

        panel.add(bubblePanel, BorderLayout.CENTER)
        panel.revalidate()
        panel.repaint()
    }
}
