package com.cursorj.ui.chat

import com.cursorj.CursorJBundle
import com.cursorj.acp.SessionMode
import com.cursorj.acp.messages.ConfigOption
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.*
import javax.swing.DefaultListCellRenderer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.View

class InputPanel {
    private val borderColor = JBColor(Color(0xCCCCCC), Color(0x4A4A4A))
    private val containerBg = JBColor(Color(0xFFFFFF), Color(0x2B2B2B))
    private val arcSize = 12

    private val rootPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(6, 8, 8, 8)
        isOpaque = false
    }

    private val topActionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
        isOpaque = false
        border = JBUI.Borders.empty(0, 0, 6, 0)
        isVisible = false
    }

    private val container = object : JPanel(BorderLayout()) {
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = containerBg
            g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arcSize.toFloat(), arcSize.toFloat()))
            g2.dispose()
        }

        override fun paintBorder(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = borderColor
            g2.stroke = BasicStroke(1f)
            g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arcSize.toFloat(), arcSize.toFloat()))
            g2.dispose()
        }
    }.apply {
        isOpaque = false
        border = JBUI.Borders.empty(0)
    }

    private val minRows = 2
    private val maxRows = 12

    private val textArea = JBTextArea().apply {
        rows = minRows
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(10, 12, 4, 12)
        isOpaque = false
        emptyText.text = CursorJBundle.message("chat.input.placeholder")
    }

    private val sendButton = JButton(AllIcons.Actions.Execute).apply {
        toolTipText = CursorJBundle.message("chat.input.send")
        isFocusable = false
        isContentAreaFilled = false
        isBorderPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        preferredSize = Dimension(28, 28)
    }

    private val cancelButton = JButton(AllIcons.Actions.Suspend).apply {
        toolTipText = CursorJBundle.message("chat.input.cancel")
        isFocusable = false
        isContentAreaFilled = false
        isBorderPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        preferredSize = Dimension(28, 28)
        isVisible = false
    }

    private val planLabel = CursorJBundle.message("chat.mode.plan")
    private val askLabel = CursorJBundle.message("chat.mode.ask")
    private val defaultFg = UIManager.getColor("ComboBox.foreground") ?: JBColor.foreground()

    private val planColor = JBColor(Color(0xD4A017), Color(0xD4A017))
    private val askColor = JBColor(Color(0x4CAF50), Color(0x6BC46D))

    private val modeCombo = JComboBox(arrayOf(
        CursorJBundle.message("chat.mode.agent"),
        planLabel,
        askLabel,
    )).apply {
        minimumSize = Dimension(90, 26)
        preferredSize = Dimension(90, 26)
        prototypeDisplayValue = CursorJBundle.message("chat.mode.agent") + "  "
        font = font.deriveFont(font.size2D - 1)
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int,
                isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (!isSelected || index == -1) {
                    foreground = modeColorFor(value)
                }
                return this
            }
        }
    }

    private val modelCombo = JComboBox<String>().apply {
        minimumSize = Dimension(100, 26)
        preferredSize = Dimension(130, 26)
        font = font.deriveFont(font.size2D - 1)
        isVisible = false
    }

    private val rollbackButton = JButton(CursorJBundle.message("chat.rollback.action")).apply {
        minimumSize = Dimension(100, 26)
        preferredSize = Dimension(100, 26)
        isFocusable = false
        isEnabled = false
        isVisible = false
        toolTipText = CursorJBundle.message("chat.rollback.action")
    }

    private var modelValues: List<String> = emptyList()
    private var updatingModelCombo = false
    private var rollbackAvailable = false
    private var isProcessing = false

    private val fileChipsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
        isOpaque = false
    }
    private val fileChipsWrapper = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(8, 12, 0, 12)
        isVisible = false
        add(fileChipsPanel, BorderLayout.CENTER)
    }

    var onSend: ((String) -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var onRollback: (() -> Unit)? = null
    var onModeChanged: ((SessionMode) -> Unit)? = null
    var onModelChanged: ((configId: String, value: String) -> Unit)? = null

    val component: JComponent get() = rootPanel
    val dropTargetComponent: JComponent get() = textArea

    init {
        val scrollPane = JScrollPane(textArea).apply {
            border = BorderFactory.createEmptyBorder()
            isOpaque = false
            viewport.isOpaque = false
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        val bottomBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 8, 6, 8)

            val leftControls = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                add(modeCombo)
                add(modelCombo)
            }

            val rightControls = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                isOpaque = false
                add(sendButton)
                add(cancelButton)
            }

            add(leftControls, BorderLayout.WEST)
            add(rightControls, BorderLayout.EAST)
        }

        val editorArea = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(fileChipsWrapper, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }

        container.add(editorArea, BorderLayout.CENTER)
        container.add(bottomBar, BorderLayout.SOUTH)

        topActionsPanel.add(rollbackButton)
        rootPanel.add(topActionsPanel, BorderLayout.NORTH)
        rootPanel.add(container, BorderLayout.CENTER)

        textArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    doSend()
                }
            }
        })

        textArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = adjustTextAreaHeight()
            override fun removeUpdate(e: DocumentEvent) = adjustTextAreaHeight()
            override fun changedUpdate(e: DocumentEvent) = adjustTextAreaHeight()
        })

        rootPanel.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = adjustTextAreaHeight()
        })

        sendButton.addActionListener { doSend() }
        cancelButton.addActionListener { onCancel?.invoke() }
        rollbackButton.addActionListener { onRollback?.invoke() }

        modeCombo.addActionListener {
            updateModeColor()
            if (updatingMode) return@addActionListener
            val mode = when (modeCombo.selectedIndex) {
                1 -> SessionMode.PLAN
                2 -> SessionMode.ASK
                else -> SessionMode.AGENT
            }
            onModeChanged?.invoke(mode)
        }

        modelCombo.addActionListener {
            if (updatingModelCombo) return@addActionListener
            val idx = modelCombo.selectedIndex
            if (idx in modelValues.indices) {
                val modelConfigId = configOptions.firstOrNull { it.category == "model" }?.id ?: "model"
                onModelChanged?.invoke(modelConfigId, modelValues[idx])
            }
        }
    }

    private var updatingMode = false

    fun setMode(mode: SessionMode) {
        updatingMode = true
        modeCombo.selectedIndex = when (mode) {
            SessionMode.PLAN -> 1
            SessionMode.ASK -> 2
            SessionMode.AGENT -> 0
        }
        updateModeColor()
        updatingMode = false
    }

    private fun modeColorFor(value: Any?): Color = when (value) {
        planLabel -> planColor
        askLabel -> askColor
        else -> defaultFg
    }

    private fun updateModeColor() {
        modeCombo.foreground = modeColorFor(modeCombo.selectedItem)
        modeCombo.repaint()
    }

    private var configOptions: List<ConfigOption> = emptyList()

    fun updateConfigOptions(options: List<ConfigOption>) {
        configOptions = options
        val modelOption = options.firstOrNull { it.category == "model" }
        if (modelOption != null && modelOption.options.isNotEmpty()) {
            updatingModelCombo = true
            modelCombo.removeAllItems()
            modelValues = modelOption.options.map { it.value }
            modelOption.options.forEach { opt ->
                modelCombo.addItem(opt.name ?: opt.value)
            }
            val selectedIdx = modelValues.indexOf(modelOption.currentValue).coerceAtLeast(0)
            modelCombo.selectedIndex = selectedIdx
            val longestName = modelOption.options.maxOf { (it.name ?: it.value).length }
            modelCombo.preferredSize = Dimension((longestName * 8 + 40).coerceIn(100, 200), 26)
            modelCombo.isVisible = true
            updatingModelCombo = false
        } else {
            modelCombo.isVisible = false
        }
        rootPanel.revalidate()
        rootPanel.repaint()
    }

    fun setProcessing(processing: Boolean) {
        isProcessing = processing
        sendButton.isVisible = !processing
        cancelButton.isVisible = processing
        rollbackButton.isEnabled = rollbackAvailable && !processing
        textArea.isEditable = !processing
        if (!processing) {
            textArea.text = ""
            textArea.requestFocusInWindow()
        }
    }

    fun setRollbackEnabled(enabled: Boolean) {
        rollbackAvailable = enabled
        rollbackButton.isVisible = enabled
        topActionsPanel.isVisible = enabled
        rollbackButton.isEnabled = enabled && !isProcessing
        rootPanel.revalidate()
        rootPanel.repaint()
    }

    fun addFileChip(name: String) {
        val chip = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(1, 4),
            )
            val label = JLabel(name).apply {
                font = font.deriveFont(font.size2D - 1)
            }
            val removeBtn = JLabel(AllIcons.Actions.Close).apply {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }
            removeBtn.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    fileChipsPanel.remove(this@apply)
                    updateFileChipsVisibility()
                }
            })
            add(label)
            add(removeBtn)
        }
        fileChipsPanel.add(chip)
        updateFileChipsVisibility()
    }

    fun clearFileChips() {
        fileChipsPanel.removeAll()
        updateFileChipsVisibility()
    }

    private fun updateFileChipsVisibility() {
        fileChipsWrapper.isVisible = fileChipsPanel.componentCount > 0
        fileChipsPanel.revalidate()
        fileChipsPanel.repaint()
        fileChipsWrapper.revalidate()
        fileChipsWrapper.repaint()
        rootPanel.revalidate()
        rootPanel.repaint()
    }

    private fun doSend() {
        val text = textArea.text.trim()
        if (text.isNotEmpty()) {
            textArea.text = ""
            adjustTextAreaHeight()
            onSend?.invoke(text)
        }
    }

    private fun adjustTextAreaHeight() {
        val scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, textArea) as? JScrollPane
            ?: return
        val fm = textArea.getFontMetrics(textArea.font)
        val lineHeight = fm.height
        val insets = textArea.insets
        val minHeight = minRows * lineHeight + insets.top + insets.bottom
        val maxHeight = maxRows * lineHeight + insets.top + insets.bottom

        val viewportWidth = scrollPane.viewport.width.coerceAtLeast(100)
        val root = textArea.ui?.getRootView(textArea) ?: return
        root.setSize(viewportWidth.toFloat(), Float.MAX_VALUE)
        val contentHeight = root.getPreferredSpan(View.Y_AXIS).toInt() + insets.top + insets.bottom
        val targetHeight = contentHeight.coerceIn(minHeight, maxHeight)

        val currentHeight = scrollPane.preferredSize.height
        if (targetHeight != currentHeight) {
            scrollPane.preferredSize = Dimension(scrollPane.preferredSize.width, targetHeight)
            rootPanel.revalidate()
            rootPanel.repaint()
        }
    }
}
