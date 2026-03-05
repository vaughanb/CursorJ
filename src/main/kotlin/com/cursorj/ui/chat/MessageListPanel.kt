package com.cursorj.ui.chat

import com.cursorj.acp.ChatMessage
import com.cursorj.ui.components.MessageRenderer
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.*

class MessageListPanel {
    private val panel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(8)
    }

    private val messageComponents = mutableListOf<MessageRenderer>()
    private var streamingRenderer: MessageRenderer? = null

    val component: JComponent get() = panel

    fun updateOrAddMessage(message: ChatMessage) {
        if (message.isStreaming) {
            if (streamingRenderer == null) {
                streamingRenderer = MessageRenderer(message)
                panel.add(streamingRenderer!!.component)
                messageComponents.add(streamingRenderer!!)
            } else {
                streamingRenderer!!.update(message)
            }
        } else {
            if (streamingRenderer != null) {
                streamingRenderer!!.update(message)
                streamingRenderer = null
            } else {
                val renderer = MessageRenderer(message)
                panel.add(renderer.component)
                messageComponents.add(renderer)
            }
        }
        panel.revalidate()
        panel.repaint()
        scrollToBottom()
    }

    fun addErrorMessage(errorText: String) {
        val errorPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            val label = JLabel("<html><font color='#FF6B6B'>Error: $errorText</font></html>")
            add(label, BorderLayout.CENTER)
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        panel.add(errorPanel)
        panel.revalidate()
        panel.repaint()
        scrollToBottom()
    }

    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
            val scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, panel)
            if (scrollPane is JScrollPane) {
                val vsb = scrollPane.verticalScrollBar
                vsb.value = vsb.maximum
            }
        }
    }
}
