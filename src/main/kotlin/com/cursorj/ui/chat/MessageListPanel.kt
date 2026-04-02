package com.cursorj.ui.chat

import com.cursorj.CursorJBundle
import com.cursorj.acp.AcpContentExtractor
import com.cursorj.acp.ChatMessage
import com.cursorj.acp.messages.PermissionOption
import com.cursorj.acp.messages.RequestPermissionParams
import com.cursorj.permissions.PermissionPolicy
import com.cursorj.ui.components.CollapsibleDiffPanel
import com.cursorj.ui.components.MessageRenderer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.Locale
import javax.swing.*

class MessageListPanel {
    private val json = Json { prettyPrint = true }

    private val innerPanel = Box.createVerticalBox().apply {
        border = JBUI.Borders.empty(8)
    }

    private val panel = JPanel(BorderLayout()).apply {
        add(innerPanel, BorderLayout.NORTH)
    }

    private val messageComponents = mutableListOf<MessageRenderer>()
    private var streamingRenderer: MessageRenderer? = null
    private var progressIndicator: ProgressIndicatorPanel? = null
    private var scrollToBottomQueued = false

    val component: JComponent get() = panel

    fun updateOrAddMessage(message: ChatMessage) {
        clearStatusMessages()
        hideBuildButton()
        var fullLayoutPassNeeded = false
        var updatedComponent: JComponent? = null

        if (message.isStreaming) {
            hideProgress()
            if (streamingRenderer == null) {
                streamingRenderer = MessageRenderer(message)
                innerPanel.add(streamingRenderer!!.component)
                messageComponents.add(streamingRenderer!!)
                fullLayoutPassNeeded = true
                updatedComponent = streamingRenderer!!.component
            } else {
                streamingRenderer!!.update(message)
                updatedComponent = streamingRenderer!!.component
            }
        } else {
            if (streamingRenderer != null) {
                streamingRenderer!!.update(message)
                updatedComponent = streamingRenderer!!.component
                streamingRenderer = null
            } else {
                val renderer = MessageRenderer(message)
                innerPanel.add(renderer.component)
                messageComponents.add(renderer)
                fullLayoutPassNeeded = true
                updatedComponent = renderer.component
            }
        }
        if (fullLayoutPassNeeded) {
            innerPanel.revalidate()
            innerPanel.repaint()
        } else {
            updatedComponent?.revalidate()
            updatedComponent?.repaint()
        }
        scrollToBottom()
    }

    fun replaceConversation(messages: List<ChatMessage>) {
        progressIndicator?.stop()
        progressIndicator = null
        buildButtonPanel = null
        permissionCards.clear()
        toolCallLabels.clear()
        streamingRenderer = null
        innerPanel.removeAll()
        messageComponents.clear()

        for (message in messages) {
            if (message.content.isBlank()) continue
            val renderer = MessageRenderer(message.copy(isStreaming = false))
            innerPanel.add(renderer.component)
            messageComponents.add(renderer)
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
        val panel: JPanel,
        var path: String?,
    )

    private val toolCallLabels = mutableMapOf<String, ToolCallLine>()
    private val permissionCards = mutableMapOf<String, JComponent>()

    fun addOrUpdateToolCallLine(id: String, text: String, path: String? = null) {
        val existing = toolCallLabels[id]
        if (existing != null) {
            if (path != null) existing.path = path
            updateToolCallLine(existing, text)
        } else {
            val line = ToolCallLine(
                panel = object : JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)) {
                    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
                }.apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    border = JBUI.Borders.empty(1, 12)
                    name = "tool-call-line"
                    isOpaque = false
                },
                path = path,
            )
            updateToolCallLine(line, text)
            toolCallLabels[id] = line
            innerPanel.add(line.panel)
        }
        innerPanel.revalidate()
        innerPanel.repaint()
        scrollToBottom()
    }

    var onDiffFileClick: ((path: String, line: Int, addedLines: List<Int>) -> Unit)? = null

    fun addDiffPanel(filePath: String, oldText: String, newText: String) {
        val diffPanel = CollapsibleDiffPanel(filePath, oldText, newText, onFileClick = onDiffFileClick)
        innerPanel.add(diffPanel.component)
        innerPanel.revalidate()
        innerPanel.repaint()
        scrollToBottom()
    }

    fun addPermissionRequestCard(
        requestId: String,
        request: RequestPermissionParams,
        onDecision: (String) -> Unit,
        onRunEverything: (() -> Boolean)? = null,
    ) {
        if (PermissionPolicy.isInteractivePermissionRequest(request)) {
            addAgentQuestionCard(requestId, request, onDecision)
        } else {
            addToolPermissionRequestCard(requestId, request, onDecision, onRunEverything)
        }
    }

    /** Interactive plan/design questions: distinct styling, full-text clickable rows (not a permission dropdown). */
    private fun addAgentQuestionCard(
        requestId: String,
        request: RequestPermissionParams,
        onDecision: (String) -> Unit,
    ) {
        permissionCards[requestId]?.let {
            innerPanel.remove(it)
            permissionCards.remove(requestId)
        }

        // Match Plan mode / tool-permission card (amber) — not user-message blue
        val borderColor = JBColor(Color(0xCC9F52), Color(0x8D6A33))
        val bgColor = JBColor(Color(0xFFF9EE), Color(0x3A3222))
        val titleColor = JBColor(Color(0x7C530E), Color(0xD0A86A))
        val secondaryColor = JBColor(Color(0x6E6E6E), Color(0x9C9C9C))
        val rowBaseBg = JBColor(Color(0xFFFFFF), Color(0x2F2C26))
        val rowHoverBg = JBColor(Color(0xFFF3E0), Color(0x4A4030))
        val rowDisabledBg = JBColor(Color(0xF0EBE3), Color(0x3D3A34))
        val rowBorderColor = JBColor(Color(0xE0C995), Color(0x6B5E48))
        val rowTextColor = JBColor(Color(0x3D3518), Color(0xDDD5C8))

        val card = object : JPanel() {
            init {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
            }
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(borderColor, 1),
                JBUI.Borders.empty(8, 10),
            )
            isOpaque = true
            background = bgColor
            name = "agent-question-card"
        }

        val topic = PermissionPolicy.displayToolName(request)
        card.add(
            JLabel(CursorJBundle.message("chat.question.cardTitle")).apply {
                foreground = titleColor
                font = font.deriveFont(Font.BOLD)
                alignmentX = Component.LEFT_ALIGNMENT
            },
        )
        card.add(Box.createVerticalStrut(4))
        card.add(
            JLabel(topic).apply {
                foreground = titleColor
                font = font.deriveFont(Font.BOLD)
                alignmentX = Component.LEFT_ALIGNMENT
            },
        )

        val bodyText = request.description?.takeIf { it.isNotBlank() }
            ?: AcpContentExtractor.extractTextFromContent(request.toolCall?.content)
        bodyText?.takeIf { it.isNotBlank() }?.let { desc ->
            card.add(Box.createVerticalStrut(6))
            card.add(
                JTextArea(desc).apply {
                    isEditable = false
                    lineWrap = true
                    wrapStyleWord = true
                    isOpaque = false
                    foreground = secondaryColor
                    border = JBUI.Borders.empty(0)
                    columns = 56
                    alignmentX = Component.LEFT_ALIGNMENT
                    font = font.deriveFont(Font.PLAIN, (font.size2D - 1f).coerceAtLeast(11f))
                },
            )
        }

        val options = request.options
        if (options.isEmpty()) {
            card.add(Box.createVerticalStrut(8))
            card.add(
                JLabel(CursorJBundle.message("chat.question.noOptions")).apply {
                    foreground = secondaryColor
                    alignmentX = Component.LEFT_ALIGNMENT
                },
            )
        } else {
            card.add(Box.createVerticalStrut(8))
            card.add(
                JLabel(CursorJBundle.message("chat.question.hint")).apply {
                    foreground = secondaryColor
                    font = font.deriveFont(Font.PLAIN, (font.size2D - 1f).coerceAtLeast(10f))
                    alignmentX = Component.LEFT_ALIGNMENT
                },
            )
            card.add(Box.createVerticalStrut(4))

            val listPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                alignmentX = Component.LEFT_ALIGNMENT
                isOpaque = false
            }

            val statusLabel = JLabel().apply {
                isVisible = false
                foreground = secondaryColor
                alignmentX = Component.LEFT_ALIGNMENT
                font = font.deriveFont(Font.PLAIN, (font.size2D - 1f).coerceAtLeast(10f))
            }

            val rows = mutableListOf<JPanel>()

            fun applyRowVisual(row: JPanel, textArea: JTextArea, enabled: Boolean) {
                row.isEnabled = enabled
                textArea.isEnabled = enabled
                row.background = if (enabled) rowBaseBg else rowDisabledBg
                row.cursor = if (enabled) {
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                } else {
                    Cursor.getDefaultCursor()
                }
                textArea.cursor = row.cursor
            }

            fun disableAllRows() {
                for (i in 0 until listPanel.componentCount) {
                    val c = listPanel.getComponent(i)
                    if (c is JPanel && rows.contains(c)) {
                        val ta = c.getComponent(0) as? JTextArea
                        if (ta != null) applyRowVisual(c, ta, false)
                    }
                }
            }

            val optionFontSize = (card.font.size2D - 2f).coerceAtLeast(11f)
            for (option in options) {
                val label = normalizePermissionLabel(option)
                val row = JPanel(BorderLayout()).apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    border = JBUI.Borders.compound(
                        JBUI.Borders.customLine(rowBorderColor, 1),
                        JBUI.Borders.empty(4, 8),
                    )
                    background = rowBaseBg
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                }
                val textArea = JTextArea(label).apply {
                    isEditable = false
                    lineWrap = true
                    wrapStyleWord = true
                    isOpaque = false
                    foreground = rowTextColor
                    border = JBUI.Borders.empty(0)
                    columns = 64
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    font = font.deriveFont(Font.PLAIN, optionFontSize)
                }
                row.add(textArea, BorderLayout.CENTER)
                rows.add(row)

                val hover = object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) {
                        if (row.isEnabled) row.background = rowHoverBg
                    }

                    override fun mouseExited(e: MouseEvent) {
                        if (row.isEnabled) row.background = rowBaseBg
                    }

                    override fun mouseClicked(e: MouseEvent) {
                        if (!row.isEnabled) return
                        statusLabel.text = CursorJBundle.message("chat.question.selected", label)
                        statusLabel.isVisible = true
                        disableAllRows()
                        onDecision(option.optionId)
                    }
                }
                row.addMouseListener(hover)
                textArea.addMouseListener(hover)
                listPanel.add(row)
                listPanel.add(Box.createVerticalStrut(4))
            }

            card.add(listPanel)
            card.add(Box.createVerticalStrut(4))
            card.add(statusLabel)
        }

        permissionCards[requestId] = card
        innerPanel.add(card)
        innerPanel.revalidate()
        innerPanel.repaint()
        scrollToBottom()
    }

    private fun addToolPermissionRequestCard(
        requestId: String,
        request: RequestPermissionParams,
        onDecision: (String) -> Unit,
        onRunEverything: (() -> Boolean)?,
    ) {
        permissionCards[requestId]?.let {
            innerPanel.remove(it)
            permissionCards.remove(requestId)
        }

        val borderColor = JBColor(Color(0xCC9F52), Color(0x8D6A33))
        val bgColor = JBColor(Color(0xFFF9EE), Color(0x3A3222))
        val titleColor = JBColor(Color(0x7C530E), Color(0xD0A86A))
        val secondaryColor = JBColor(Color(0x6E6E6E), Color(0x9C9C9C))

        val card = object : JPanel() {
            init {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
            }
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(borderColor, 1),
                JBUI.Borders.empty(8, 10),
            )
            isOpaque = true
            background = bgColor
            name = "permission-card"
        }

        val toolName = PermissionPolicy.displayToolName(request)
        val title = JLabel("Permission required: $toolName").apply {
            foreground = titleColor
            font = font.deriveFont(Font.BOLD)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        card.add(title)

        val bodyText = request.description?.takeIf { it.isNotBlank() }
            ?: AcpContentExtractor.extractTextFromContent(request.toolCall?.content)
        bodyText?.takeIf { it.isNotBlank() }?.let { desc ->
            card.add(Box.createVerticalStrut(4))
            card.add(
                JTextArea(desc).apply {
                    isEditable = false
                    lineWrap = true
                    wrapStyleWord = true
                    isOpaque = false
                    foreground = secondaryColor
                    border = JBUI.Borders.empty(0)
                    columns = 48
                    alignmentX = Component.LEFT_ALIGNMENT
                },
            )
        }

        val argsText = formatArguments(request.arguments)
        val argsScroll = JScrollPane(
            JTextArea(argsText).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                border = JBUI.Borders.empty(6)
            },
        ).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            preferredSize = Dimension(380, 120)
            maximumSize = Dimension(Int.MAX_VALUE, 180)
            alignmentX = Component.LEFT_ALIGNMENT
            isVisible = false
        }

        if (argsText.isNotBlank()) {
            card.add(Box.createVerticalStrut(6))
            val toggleArgs = JButton(CursorJBundle.message("permission.args.show")).apply {
                isContentAreaFilled = false
                isBorderPainted = false
                foreground = JBColor(Color(0x5890C8), Color(0x6B9BD2))
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                alignmentX = Component.LEFT_ALIGNMENT
                horizontalAlignment = SwingConstants.LEFT
            }
            toggleArgs.addActionListener {
                argsScroll.isVisible = !argsScroll.isVisible
                toggleArgs.text = if (argsScroll.isVisible) {
                    CursorJBundle.message("permission.args.hide")
                } else {
                    CursorJBundle.message("permission.args.show")
                }
                innerPanel.revalidate()
                innerPanel.repaint()
                scrollToBottom()
            }
            card.add(toggleArgs)
            card.add(Box.createVerticalStrut(4))
            card.add(argsScroll)
        }

        val options = if (request.options.isEmpty()) {
            listOf(
                PermissionOption("allow-once", CursorJBundle.message("permission.allow.once")),
                PermissionOption("allow-always", CursorJBundle.message("permission.allow.always")),
                PermissionOption("reject-once", CursorJBundle.message("permission.reject")),
            )
        } else {
            request.options
        }

        card.add(Box.createVerticalStrut(8))
        val statusLabel = JLabel().apply {
            isVisible = false
            foreground = secondaryColor
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val actionsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val allowOptionId = PermissionPolicy.chooseAllowOption(options)

        data class PermissionChoice(
            val optionId: String,
            val label: String,
            val isRunEverything: Boolean = false,
        )

        val choices = mutableListOf<PermissionChoice>()
        for (option in options) {
            choices.add(
                PermissionChoice(
                    optionId = option.optionId,
                    label = normalizePermissionLabel(option),
                ),
            )
        }
        if (onRunEverything != null) {
            choices.add(
                PermissionChoice(
                    optionId = "__run_everything__",
                    label = CursorJBundle.message("permission.mode.runEverything"),
                    isRunEverything = true,
                ),
            )
        }

        val selector = JComboBox(choices.map { it.label }.toTypedArray()).apply {
            minimumSize = Dimension(170, 26)
            preferredSize = Dimension(210, 26)
            toolTipText = CursorJBundle.message("permission.card.selectOption")
        }
        val applyButton = JButton(CursorJBundle.message("permission.card.apply")).apply {
            addActionListener {
                val idx = selector.selectedIndex.coerceIn(0, choices.lastIndex)
                val selected = choices[idx]
                if (selected.isRunEverything) {
                    val enabled = onRunEverything?.invoke() == true
                    if (!enabled) return@addActionListener
                    actionsPanel.isVisible = false
                    statusLabel.text = CursorJBundle.message("permission.card.selected.runEverything")
                    statusLabel.isVisible = true
                    onDecision(allowOptionId)
                } else {
                    actionsPanel.isVisible = false
                    statusLabel.text = CursorJBundle.message("permission.card.selected", selected.label)
                    statusLabel.isVisible = true
                    onDecision(selected.optionId)
                }
            }
        }
        actionsPanel.add(selector)
        actionsPanel.add(applyButton)
        card.add(actionsPanel)
        card.add(Box.createVerticalStrut(4))
        card.add(statusLabel)

        permissionCards[requestId] = card
        innerPanel.add(card)
        innerPanel.revalidate()
        innerPanel.repaint()
        scrollToBottom()
    }

    private fun updateToolCallLine(line: ToolCallLine, text: String) {
        val path = line.path
        val panel = line.panel
        panel.removeAll()

        val textColor = JBColor(Color(0x8E9BAA), Color(0x6B7785))
        val linkColor = JBColor(Color(0x7FAED4), Color(0x5A88B0))
        val baseFont = panel.font.deriveFont(panel.font.size2D - 2)

        fun textLabel(value: String): JLabel = JLabel(value).apply {
            foreground = textColor
            font = baseFont
        }

        val filename = path
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.takeIf { it.isNotBlank() }

        if (path != null && filename != null) {
            val idx = text.indexOf(filename)
            val prefix = if (idx >= 0) "\u25B8 ${text.substring(0, idx)}" else "\u25B8 $text "
            val suffix = if (idx >= 0) text.substring(idx + filename.length) else ""

            if (prefix.isNotEmpty()) panel.add(textLabel(prefix))

            val fileLabel = JLabel(filename).apply {
                foreground = linkColor
                font = baseFont
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                this.text = "<html><u>${filename.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")}</u></html>"
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) {
                        onToolCallFileClick?.invoke(path)
                    }
                })
            }
            panel.add(fileLabel)

            if (suffix.isNotEmpty()) panel.add(textLabel(suffix))
        } else {
            panel.add(textLabel("\u25B8 $text"))
        }

        panel.toolTipText = path
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
        if (toRemove.isEmpty()) return
        toRemove.forEach { innerPanel.remove(it) }
        innerPanel.revalidate()
        innerPanel.repaint()
    }

    private fun scrollToBottom() {
        if (scrollToBottomQueued) return
        scrollToBottomQueued = true
        SwingUtilities.invokeLater {
            scrollToBottomQueued = false
            val scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, panel)
            if (scrollPane is JScrollPane) {
                val vsb = scrollPane.verticalScrollBar
                vsb.value = vsb.maximum
            }
        }
    }

    private fun formatArguments(arguments: JsonElement?): String {
        if (arguments == null) return ""
        return try {
            json.encodeToString(JsonElement.serializer(), arguments)
        } catch (_: Exception) {
            arguments.toString()
        }
    }

    private fun normalizePermissionLabel(option: PermissionOption): String {
        val raw = option.label?.trim().orEmpty().ifEmpty { option.name?.trim().orEmpty() }
        if (raw.isNotEmpty() && !raw.contains("-")) {
            return raw
        }
        return option.optionId
            .trim()
            .replace('_', '-')
            .split('-')
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                token.lowercase(Locale.getDefault()).replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase(Locale.getDefault()) else ch.toString()
                }
            }
            .ifBlank { option.optionId }
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
