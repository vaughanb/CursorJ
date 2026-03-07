package com.cursorj.ui.chat

import com.cursorj.CursorJBundle
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
        hideBuildButton()

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

    fun showProgress(text: String = "Agent is working...", color: Color? = null) {
        if (progressIndicator != null) {
            progressIndicator!!.updateText(text)
            if (color != null) progressIndicator!!.updateColor(color)
            return
        }
        clearStatusMessages()
        progressIndicator = ProgressIndicatorPanel(text, color)
        innerPanel.add(progressIndicator!!.component)
        innerPanel.revalidate()
        innerPanel.repaint()
        scrollToBottom()
    }

    fun updateProgressText(text: String) {
        progressIndicator?.updateText(text) ?: showProgress(text)
    }

    fun updateProgressColor(color: Color) {
        progressIndicator?.updateColor(color)
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

    var onToolCallFileClick: ((String) -> Unit)? = null

    private data class ToolCallLine(
        val label: JLabel,
        var path: String?,
    )

    private val toolCallLabels = mutableMapOf<String, ToolCallLine>()

    fun addOrUpdateToolCallLine(id: String, text: String, path: String? = null) {
        val existing = toolCallLabels[id]
        if (existing != null) {
            if (path != null) existing.path = path
            updateToolCallLabel(existing.label, text, existing.path)
        } else {
            val line = ToolCallLine(
                label = object : JLabel() {
                    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
                }.apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    border = JBUI.Borders.empty(1, 12)
                    name = "tool-call-line"
                    font = font.deriveFont(font.size2D - 1)
                },
                path = path,
            )
            line.label.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    line.path?.let { onToolCallFileClick?.invoke(it) }
                }
            })
            updateToolCallLabel(line.label, text, line.path)
            toolCallLabels[id] = line
            innerPanel.add(line.label)
        }
        innerPanel.revalidate()
        innerPanel.repaint()
        scrollToBottom()
    }

    private fun updateToolCallLabel(label: JLabel, text: String, path: String?) {
        val escapedText = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        val color = if (path != null) "#6B9BD2" else "#7A8A99"
        val decoratedText = if (path != null) "<u>▸ $escapedText</u>" else "▸ $escapedText"
        label.text = "<html><span style='color: $color;'>$decoratedText</span></html>"
        label.cursor = if (path != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
        label.toolTipText = path
    }

    private var buildButtonPanel: JComponent? = null

    fun showBuildButton(onBuild: () -> Unit, onViewPlan: () -> Unit) {
        hideBuildButton()
        val viewPlanLabel = JLabel(CursorJBundle.message("chat.plan.viewPlan")).apply {
            foreground = JBColor(Color(0x5890C8), Color(0x6B9BD2))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            font = font.deriveFont(font.size2D - 1)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    onViewPlan()
                }
                override fun mouseEntered(e: java.awt.event.MouseEvent) {
                    text = "<html><u>${CursorJBundle.message("chat.plan.viewPlan")}</u></html>"
                }
                override fun mouseExited(e: java.awt.event.MouseEvent) {
                    text = CursorJBundle.message("chat.plan.viewPlan")
                }
            })
        }
        val buttonBg = JBColor(Color(0xD4A017), Color(0xC99A15))
        val buttonHoverBg = JBColor(Color(0xE0AB1A), Color(0xD5A218))
        val button = object : JLabel(CursorJBundle.message("chat.plan.build"), SwingConstants.CENTER) {
            private var hovering = false
            init {
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) { onBuild() }
                    override fun mouseEntered(e: java.awt.event.MouseEvent) { hovering = true; repaint() }
                    override fun mouseExited(e: java.awt.event.MouseEvent) { hovering = false; repaint() }
                })
            }
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = if (hovering) buttonHoverBg else buttonBg
                g2.fillRoundRect(0, 0, width, height, 6, 6)
                super.paintComponent(g)
            }
        }.apply {
            foreground = JBColor(Color(0x1A1A1A), Color(0x1A1A1A))
            isOpaque = false
            font = font.deriveFont(Font.BOLD)
            border = JBUI.Borders.empty(4, 16)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        val wrapper = object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(8, 8, 4, 8)
            name = "build-button-panel"
            add(viewPlanLabel, BorderLayout.WEST)
            add(button, BorderLayout.EAST)
        }
        buildButtonPanel = wrapper
        innerPanel.add(wrapper)
        innerPanel.revalidate()
        innerPanel.repaint()
        scrollToBottom()
    }

    fun hideBuildButton() {
        buildButtonPanel?.let {
            innerPanel.remove(it)
            buildButtonPanel = null
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

private class ProgressIndicatorPanel(
    initialText: String = "Agent is working...",
    initialColor: Color? = null,
) {
    private val defaultColor = JBColor(Color(0x6B9BD2), Color(0x6B9BD2))
    private val frames = arrayOf(
        "\u280B", "\u2819", "\u2839", "\u2838",
        "\u283C", "\u2834", "\u2826", "\u2827",
        "\u2807", "\u280F",
    )
    private var frameIndex = 0
    @Volatile
    private var statusText = initialText

    private val label = JLabel("${frames[0]}  $initialText").apply {
        foreground = initialColor ?: defaultColor
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
        alignmentX = Component.LEFT_ALIGNMENT
        add(Box.createHorizontalGlue())
        add(label)
        add(Box.createHorizontalGlue())
    }

    private val timer = Timer(100) {
        frameIndex = (frameIndex + 1) % frames.size
        label.text = "${frames[frameIndex]}  $statusText"
    }.apply { start() }

    val component: JComponent get() = wrapper

    fun updateText(text: String) {
        statusText = text
        label.text = "${frames[frameIndex]}  $text"
    }

    fun updateColor(color: Color) {
        label.foreground = color
    }

    fun stop() {
        timer.stop()
    }
}
