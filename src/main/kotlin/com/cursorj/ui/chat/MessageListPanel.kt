package com.cursorj.ui.chat

import com.cursorj.acp.ChatMessage
import com.cursorj.ui.components.MessageRenderer
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

class MessageListPanel {
    private val innerPanel = Box.createVerticalBox().apply {
        border = JBUI.Borders.empty(8)
    }

    private val panel = JPanel(BorderLayout()).apply {
        add(innerPanel, BorderLayout.NORTH)
    }

    private val messageComponents = mutableListOf<MessageRenderer>()
    private var streamingRenderer: MessageRenderer? = null
    private var progressIndicator: ProgressIndicatorPanel? = null

    val component: JComponent get() = panel

    fun updateOrAddMessage(message: ChatMessage) {
        clearStatusMessages()

        if (message.isStreaming) {
            hideProgress()
            if (streamingRenderer == null) {
                streamingRenderer = MessageRenderer(message)
                innerPanel.add(streamingRenderer!!.component)
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
                innerPanel.add(renderer.component)
                messageComponents.add(renderer)
            }
        }
        innerPanel.revalidate()
        innerPanel.repaint()
        scrollToBottom()
    }

    fun showProgress() {
        if (progressIndicator != null) return
        clearStatusMessages()
        progressIndicator = ProgressIndicatorPanel()
        innerPanel.add(progressIndicator!!.component)
        innerPanel.revalidate()
        innerPanel.repaint()
        scrollToBottom()
    }

    fun hideProgress() {
        progressIndicator?.let {
            it.stop()
            innerPanel.remove(it.component)
            progressIndicator = null
            innerPanel.revalidate()
            innerPanel.repaint()
        }
    }

    fun addStatusMessage(statusText: String) {
        clearStatusMessages()
        hideProgress()
        val statusLabel = object : JLabel("<html><font color='gray'>$statusText</font></html>") {
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            name = "status-message"
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(4, 8)
        }
        innerPanel.add(statusLabel)
        innerPanel.revalidate()
        innerPanel.repaint()
    }

    fun addErrorMessage(errorText: String) {
        clearStatusMessages()
        hideProgress()
        val errorLabel = object : JLabel("<html><font color='#FF6B6B'>Error: $errorText</font></html>") {
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(4, 8)
        }
        innerPanel.add(errorLabel)
        innerPanel.revalidate()
        innerPanel.repaint()
        scrollToBottom()
    }

    private fun clearStatusMessages() {
        val toRemove = innerPanel.components.filter { it.name == "status-message" }
        toRemove.forEach { innerPanel.remove(it) }
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

private class ProgressIndicatorPanel {
    private val frames = arrayOf(
        "\u280B", "\u2819", "\u2839", "\u2838",
        "\u283C", "\u2834", "\u2826", "\u2827",
        "\u2807", "\u280F",
    )
    private var frameIndex = 0

    private val label = JLabel("${frames[0]}  Agent is working...").apply {
        foreground = JBColor(Color(0x6B9BD2), Color(0x6B9BD2))
        font = font.deriveFont(font.size2D + 1)
        border = JBUI.Borders.empty(6, 8)
        name = "progress-indicator"
    }

    private val wrapper = object : JPanel() {
        init {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
        }
        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }.apply {
        isOpaque = false
        alignmentX = Component.CENTER_ALIGNMENT
        add(Box.createHorizontalGlue())
        add(label)
        add(Box.createHorizontalGlue())
    }

    private val timer = Timer(100) {
        frameIndex = (frameIndex + 1) % frames.size
        label.text = "${frames[frameIndex]}  Agent is working..."
    }.apply { start() }

    val component: JComponent get() = wrapper

    fun stop() {
        timer.stop()
    }
}
