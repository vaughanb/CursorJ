package com.cursorj.ui.components

import com.cursorj.acp.ChatMessage
import com.cursorj.ui.util.UiThemeBrightness
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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
    }

    private var collapsed = true
    private var togglePlainText = ""

    private val toggleLabel = JLabel().apply {
        foreground = JBColor(Color(0x5890C8), Color(0x6B9BD2))
        font = font.deriveFont(font.size2D - 1)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(2, 8, 4, 8)
        isVisible = false
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                collapsed = !collapsed
                updateContent()
                panel.revalidate()
                panel.repaint()
                SwingUtilities.invokeLater {
                    val scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, panel)
                    (scrollPane as? JScrollPane)?.revalidate()
                }
            }
            override fun mouseEntered(e: MouseEvent) {
                text = "<html><u>$togglePlainText</u></html>"
            }
            override fun mouseExited(e: MouseEvent) {
                text = togglePlainText
            }
        })
    }

    val component: JComponent get() = panel

    init {
        bubblePanel.add(contentArea, BorderLayout.CENTER)
        bubblePanel.add(toggleLabel, BorderLayout.SOUTH)
        panel.add(bubblePanel, BorderLayout.CENTER)
        applyBubbleColor()
        updateContent()
    }

    fun update(newMessage: ChatMessage) {
        val roleChanged = message.role != newMessage.role
        message = newMessage
        if (roleChanged) {
            collapsed = true
            applyBubbleColor()
        }
        updateContent()
    }

    /** Re-apply bubble colors and re-render markdown HTML (e.g. after IDE theme or editor scheme change). */
    fun refreshTheme() {
        applyBubbleColor()
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

    private fun isCollapsible(): Boolean {
        return message.role == "user" && message.content.lines().size > COLLAPSE_LINE_THRESHOLD
    }

    private fun updateContent() {
        val collapsible = isCollapsible()
        val displayContent = if (collapsible && collapsed) {
            message.content.lines().take(COLLAPSE_LINE_THRESHOLD).joinToString("\n")
        } else {
            message.content
        }

        val baseFontSize = contentArea.font?.size ?: 13
        val lightHtml = UiThemeBrightness.useLightHtmlPaletteForSurface(bubblePanel.background)
        val html = MarkdownRenderer.renderToHtml(displayContent, baseFontSize, lightHtml)
        try {
            contentArea.contentType = "text/html"
            contentArea.text = html
            contentArea.caretPosition = 0
        } catch (e: Exception) {
            log.debug("HTML rendering failed, falling back to plain text", e)
            contentArea.contentType = "text/plain"
            contentArea.text = displayContent
        }

        if (collapsible) {
            val totalLines = message.content.lines().size
            val hiddenLines = totalLines - COLLAPSE_LINE_THRESHOLD
            togglePlainText = if (collapsed) "Show $hiddenLines more lines\u2026" else "Show less"
            toggleLabel.text = togglePlainText
            toggleLabel.isVisible = true
        } else {
            toggleLabel.isVisible = false
        }
    }

    companion object {
        private const val COLLAPSE_LINE_THRESHOLD = 6
    }
}
