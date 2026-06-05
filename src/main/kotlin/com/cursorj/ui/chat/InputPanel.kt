package com.cursorj.ui.chat

import com.cursorj.CursorJBundle
import com.cursorj.acp.ConfigOptionUiSupport
import com.cursorj.acp.SessionMode
import com.cursorj.acp.messages.ConfigOption
import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.*
import javax.swing.DefaultListCellRenderer
import javax.swing.event.CaretEvent
import javax.swing.event.CaretListener
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.AbstractDocument
import javax.swing.text.AttributeSet
import javax.swing.text.Document
import javax.swing.text.DocumentFilter
import javax.swing.text.Highlighter
import javax.swing.text.Position
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

    private val maxModeToggle = JBCheckBox(CursorJBundle.message("chat.maxmode.label")).apply {
        font = font.deriveFont(font.size2D - 1)
        isOpaque = false
        toolTipText = CursorJBundle.message("chat.maxmode.tooltip")
        isVisible = true
    }

    private val configControlsRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
        isOpaque = false
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
    private var lastModelSelectionValue: String? = null
    private var updatingModelCombo = false
    private var rollbackAvailable = false
    private var isProcessing = false
    private var lastKnownRootWidth = -1

    private val fileChipsPanel = JPanel(WrapFlowLayout(FlowLayout.LEFT, 4, 4)).apply {
        isOpaque = false
    }
    private val imageChipsPanel = JPanel(WrapFlowLayout(FlowLayout.LEFT, 4, 4)).apply {
        isOpaque = false
    }
    private val selectionChipPanel = JPanel(WrapFlowLayout(FlowLayout.LEFT, 4, 4)).apply {
        isOpaque = false
    }
    private val chipsStack = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        add(fileChipsPanel)
        add(imageChipsPanel)
        add(selectionChipPanel)
    }
    private val fileChipsWrapper = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(8, 12, 0, 12)
        isVisible = false
        add(chipsStack, BorderLayout.CENTER)
    }

    var onSend: ((String) -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var onQueueMessage: ((String) -> Unit)? = null
    var onRollback: (() -> Unit)? = null
    var onSelectionChipRemoved: ((String) -> Unit)? = null
    var onImageChipRemoved: ((String) -> Unit)? = null
    var onModeChanged: ((SessionMode) -> Unit)? = null
    var onConfigOptionChanged: ((configId: String, value: String) -> Unit)? = null
    /** Invoked when the user toggles MAX mode (after [setMaxMode] sync, not during). */
    var onMaxModeToggled: ((Boolean) -> Unit)? = null
    var onHistoryPrev: ((String) -> String?)? = null
    var onHistoryNext: ((String) -> String?)? = null
    var fileReferenceValidator: ((String) -> Boolean)? = null
        set(value) {
            field = value
            updateHighlights()
        }

    private val chipBgColor = JBColor(Color(0xE0F0FF), Color(0x2A405A))
    private val chipBorderColor = JBColor(Color(0xB0D4FF), Color(0x3A5A7A))
    private val pillPainter = PillHighlightPainter(chipBgColor, chipBorderColor)
    private val activeHighlightTags = mutableListOf<Any>()
    private var adjustingReferenceEdit = false

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

            val rightControls = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                isOpaque = false
                add(sendButton)
                add(cancelButton)
            }

            add(configControlsRow, BorderLayout.WEST)
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

        installReferenceEditGuard()

        textArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_BACK_SPACE) {
                    if (handleBackspaceOrDelete(isDelete = false)) {
                        e.consume()
                        return
                    }
                } else if (e.keyCode == KeyEvent.VK_DELETE) {
                    if (handleBackspaceOrDelete(isDelete = true)) {
                        e.consume()
                        return
                    }
                }

                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    doSend()
                    return
                }

                if (!e.isShiftDown && !e.isControlDown && !e.isAltDown && !e.isMetaDown) {
                    if (e.keyCode == KeyEvent.VK_UP && isCaretOnFirstLine()) {
                        val replacement = onHistoryPrev?.invoke(getInputText())
                        if (replacement != null) {
                            e.consume()
                            setInputText(replacement)
                        }
                        return
                    }

                    if (e.keyCode == KeyEvent.VK_DOWN && isCaretOnLastLine()) {
                        val replacement = onHistoryNext?.invoke(getInputText())
                        if (replacement != null) {
                            e.consume()
                            setInputText(replacement)
                        }
                        return
                    }
                }
            }
        })

        textArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) {
                adjustTextAreaHeight()
                scheduleUpdateHighlights()
            }
            override fun removeUpdate(e: DocumentEvent) {
                adjustTextAreaHeight()
                scheduleUpdateHighlights()
            }
            override fun changedUpdate(e: DocumentEvent) {
                adjustTextAreaHeight()
                scheduleUpdateHighlights()
            }
        })

        rootPanel.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                val width = rootPanel.width
                if (width <= 0) return
                // Re-measure wrapping only when available width changes; this avoids
                // feedback loops where height-only relayouts repeatedly rewrap text.
                if (width != lastKnownRootWidth) {
                    lastKnownRootWidth = width
                    adjustTextAreaHeight()
                    if (fileChipsWrapper.isVisible) {
                        updateChipPanelLayout()
                    }
                }
            }
        })

        fileChipsWrapper.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (fileChipsWrapper.isVisible) {
                    updateChipPanelLayout()
                }
            }
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
            if (updatingModelCombo || updatingConfigWidgets) return@addActionListener
            val idx = modelCombo.selectedIndex
            if (idx in modelValues.indices) {
                val selectedValue = modelValues[idx]
                lastModelSelectionValue = selectedValue
                onConfigOptionChanged?.invoke(modelConfigIdForEvents, selectedValue)
            }
        }

        maxModeToggle.addActionListener {
            if (updatingMaxMode || updatingConfigWidgets) return@addActionListener
            onMaxModeToggled?.invoke(maxModeToggle.isSelected)
        }

    }

    private var updatingMode = false
    private var updatingMaxMode = false

    /**
     * Updates the MAX checkbox without firing [onMaxModeToggled].
     */
    fun setMaxMode(enabled: Boolean) {
        updatingMaxMode = true
        maxModeToggle.isSelected = enabled
        updatingMaxMode = false
    }

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
    private var updatingConfigWidgets = false
    private var modelConfigIdForEvents = "model"

    fun updateConfigOptions(options: List<ConfigOption>) {
        configOptions = options
        updatingConfigWidgets = true
        configControlsRow.removeAll()
        configControlsRow.add(modeCombo)

        val row = ConfigOptionUiSupport.optionsForInputBar(options)
        val hasModelInBar = row.any { ConfigOptionUiSupport.isModelSelector(it) && it.options.isNotEmpty() }
        if (!hasModelInBar) {
            configControlsRow.add(maxModeToggle)
        }
        var modelShown = false
        for (opt in row) {
            when {
                ConfigOptionUiSupport.isModelSelector(opt) && !modelShown && opt.options.isNotEmpty() -> {
                    modelConfigIdForEvents = opt.id
                    updatingModelCombo = true
                    val currentUiSelection = modelValues.getOrNull(modelCombo.selectedIndex)
                    modelCombo.removeAllItems()
                    modelValues = opt.options.map { it.value }
                    opt.options.forEach { o -> modelCombo.addItem(o.name ?: o.value) }
                    val preferredValue = when {
                        !opt.currentValue.isNullOrBlank() && opt.currentValue in modelValues -> opt.currentValue
                        !lastModelSelectionValue.isNullOrBlank() && lastModelSelectionValue in modelValues -> lastModelSelectionValue
                        !currentUiSelection.isNullOrBlank() && currentUiSelection in modelValues -> currentUiSelection
                        else -> modelValues.firstOrNull()
                    }
                    val selectedIdx = preferredValue?.let(modelValues::indexOf)?.coerceAtLeast(0) ?: 0
                    modelCombo.selectedIndex = selectedIdx
                    lastModelSelectionValue = modelValues.getOrNull(selectedIdx)
                    val longestName = opt.options.maxOf { (it.name ?: it.value).length }
                    modelCombo.preferredSize = Dimension((longestName * 8 + 40).coerceIn(100, 220), 26)
                    configControlsRow.add(modelCombo)
                    modelCombo.isVisible = true
                    configControlsRow.add(maxModeToggle)
                    modelShown = true
                    updatingModelCombo = false
                }
                ConfigOptionUiSupport.isBooleanToggle(opt) -> {
                    val configId = opt.id
                    val cb = JBCheckBox(opt.name ?: opt.id, ConfigOptionUiSupport.isToggleChecked(opt)).apply {
                        font = font.deriveFont(font.size2D - 1)
                        isOpaque = false
                    }
                    cb.addActionListener {
                        if (updatingConfigWidgets) return@addActionListener
                        val fresh = configOptions.firstOrNull { it.id == configId } ?: return@addActionListener
                        val (off, on) = ConfigOptionUiSupport.toggleOffOnValues(fresh)
                        val v = if (cb.isSelected) on else off
                        onConfigOptionChanged?.invoke(configId, v)
                    }
                    configControlsRow.add(cb)
                }
                ConfigOptionUiSupport.isGenericSelect(opt) || opt.options.isNotEmpty() -> {
                    val values = opt.options.map { it.value }
                    val combo = JComboBox<String>().apply {
                        minimumSize = Dimension(80, 26)
                        font = font.deriveFont(font.size2D - 1)
                        opt.options.forEach { o -> addItem(o.name ?: o.value) }
                        val idx = values.indexOf(opt.currentValue).coerceAtLeast(0)
                        selectedIndex = idx
                    }
                    val configId = opt.id
                    combo.addActionListener {
                        if (updatingConfigWidgets) return@addActionListener
                        val i = combo.selectedIndex
                        if (i in values.indices) {
                            onConfigOptionChanged?.invoke(configId, values[i])
                        }
                    }
                    configControlsRow.add(combo)
                }
            }
        }
        if (!modelShown) {
            modelCombo.isVisible = false
        }
        updatingConfigWidgets = false
        rootPanel.revalidate()
        rootPanel.repaint()
    }

    fun setProcessing(processing: Boolean) {
        isProcessing = processing
        sendButton.isVisible = !processing
        cancelButton.isVisible = processing
        rollbackButton.isEnabled = rollbackAvailable && !processing
        textArea.emptyText.text = if (processing) {
            CursorJBundle.message("chat.queue.placeholder")
        } else {
            CursorJBundle.message("chat.input.placeholder")
        }
        if (!processing) {
            textArea.requestFocusInWindow()
        }
    }

    fun getInputText(): String = textArea.text

    fun setInputText(text: String) {
        textArea.text = text
        textArea.caretPosition = textArea.document.length
        adjustTextAreaHeight()
        updateHighlights()
    }

    fun insertText(text: String, index: Int = -1) {
        val doc = textArea.document
        val insertPos = if (index in 0..doc.length) index else textArea.caretPosition.coerceIn(0, doc.length)
        textArea.insert(text, insertPos)
        textArea.caretPosition = (insertPos + text.length).coerceAtLeast(0)
        textArea.requestFocusInWindow()
        adjustTextAreaHeight()
        updateHighlights()
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
        val chip = createChip(name, icon = null, removable = true) {
            fileChipsPanel.remove(it)
            updateFileChipsVisibility()
        }
        fileChipsPanel.add(chip)
        updateFileChipsVisibility()
    }

    fun addSelectionChip(id: String, label: String) {
        val chip = createChip(label, icon = null, removable = true) {
            val chipId = it.name ?: return@createChip
            selectionChipPanel.remove(it)
            updateFileChipsVisibility()
            onSelectionChipRemoved?.invoke(chipId)
        }
        chip.name = id
        selectionChipPanel.add(chip)
        updateFileChipsVisibility()
    }

    fun addImageChip(id: String, name: String) {
        val chip = createChip(name, icon = AllIcons.FileTypes.Image, removable = true) {
            val chipId = it.name ?: return@createChip
            imageChipsPanel.remove(it)
            updateFileChipsVisibility()
            onImageChipRemoved?.invoke(chipId)
        }
        chip.name = id
        imageChipsPanel.add(chip)
        updateFileChipsVisibility()
    }

    fun clearFileChips() {
        fileChipsPanel.removeAll()
        updateFileChipsVisibility()
    }

    fun clearSelectionChip() {
        selectionChipPanel.removeAll()
        updateFileChipsVisibility()
    }

    fun clearImageChips() {
        imageChipsPanel.removeAll()
        updateFileChipsVisibility()
    }

    private fun createChip(
        text: String,
        icon: Icon?,
        removable: Boolean,
        onRemove: ((JPanel) -> Unit)?,
    ): JPanel {
        val chip = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(1, 4),
            )
            isOpaque = false
        }
        if (icon != null) {
            val iconLabel = JLabel(icon)
            chip.add(iconLabel)
        }
        val label = JLabel(text).apply {
            font = font.deriveFont(font.size2D - 1)
        }
        chip.add(label)

        if (removable) {
            val removeBtn = JLabel(AllIcons.Actions.Close).apply {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }
            removeBtn.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    onRemove?.invoke(chip)
                }
            })
            chip.add(removeBtn)
        }

        return chip
    }

    private fun updateFileChipsVisibility() {
        val hasFileChips = fileChipsPanel.componentCount > 0
        val hasImageChips = imageChipsPanel.componentCount > 0
        val hasSelectionChips = selectionChipPanel.componentCount > 0
        fileChipsPanel.isVisible = hasFileChips
        imageChipsPanel.isVisible = hasImageChips
        selectionChipPanel.isVisible = hasSelectionChips
        fileChipsWrapper.isVisible = hasFileChips || hasImageChips || hasSelectionChips
        updateChipPanelLayout()
    }

    private fun updateChipPanelLayout() {
        fileChipsPanel.revalidate()
        imageChipsPanel.revalidate()
        selectionChipPanel.revalidate()
        chipsStack.revalidate()
        fileChipsWrapper.revalidate()
        rootPanel.revalidate()
        rootPanel.repaint()
        SwingUtilities.invokeLater {
            if (!fileChipsWrapper.isVisible) return@invokeLater
            fileChipsWrapper.revalidate()
            rootPanel.revalidate()
            rootPanel.repaint()
        }
    }

    private fun doSend() {
        val text = getInputText().trim()
        if (text.isNotEmpty()) {
            setInputText("")
            if (isProcessing) {
                onQueueMessage?.invoke(text)
            } else {
                onSend?.invoke(text)
            }
        }
    }

    private fun isCaretOnFirstLine(): Boolean {
        return isCaretOnVisualBoundary(first = true)
    }

    private fun isCaretOnLastLine(): Boolean {
        return isCaretOnVisualBoundary(first = false)
    }

    private fun isCaretOnVisualBoundary(first: Boolean): Boolean {
        return runCatching {
            val length = textArea.document.length
            val caretPos = textArea.caretPosition.coerceIn(0, length)
            val caretY = modelY(caretPos) ?: return@runCatching true
            val edgeY = if (first) {
                modelY(0)
            } else {
                modelY(length) ?: modelY((length - 1).coerceAtLeast(0))
            } ?: return@runCatching true
            if (first) {
                caretY <= edgeY + visualLineEpsilonPx
            } else {
                caretY >= edgeY - visualLineEpsilonPx
            }
        }.getOrDefault(true)
    }

    private fun modelY(offset: Int): Double? {
        val bounded = offset.coerceIn(0, textArea.document.length)
        return textArea.modelToView2D(bounded)?.y
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

    private fun handleBackspaceOrDelete(isDelete: Boolean): Boolean {
        val validator = fileReferenceValidator ?: return false
        val text = textArea.text
        val caret = textArea.caretPosition.coerceIn(0, text.length)
        if (!isDelete && caret <= 0) return false
        if (isDelete && caret >= text.length) return false

        for (span in FileReferenceSupport.findValidSpans(text, validator)) {
            val inRange = if (isDelete) {
                caret in span.start until span.end
            } else {
                caret in (span.start + 1)..span.end
            }
            if (inRange) {
                textArea.replaceRange("", span.start, span.end)
                textArea.caretPosition = span.start
                adjustTextAreaHeight()
                return true
            }
        }
        return false
    }

    private fun installReferenceEditGuard() {
        val document = textArea.document as? AbstractDocument ?: return

        document.documentFilter = object : DocumentFilter() {
            override fun insertString(fb: FilterBypass, offset: Int, string: String?, attr: AttributeSet?) {
                val insert = string ?: return
                guardedInsert(fb, offset, insert, attr, replaceLength = 0)
            }

            override fun replace(fb: FilterBypass, offset: Int, length: Int, text: String?, attrs: AttributeSet?) {
                val insert = text ?: return
                guardedInsert(fb, offset, insert, attrs, replaceLength = length)
            }

            private fun guardedInsert(
                fb: FilterBypass,
                offset: Int,
                insert: String,
                attr: AttributeSet?,
                replaceLength: Int,
            ) {
                val validator = fileReferenceValidator
                if (validator == null) {
                    if (replaceLength > 0) {
                        super.replace(fb, offset, replaceLength, insert, attr)
                    } else {
                        super.insertString(fb, offset, insert, attr)
                    }
                    return
                }

                val docText = fb.document.getText(0, fb.document.length)
                val spans = FileReferenceSupport.findValidSpans(docText, validator)
                val containing = FileReferenceSupport.spanContaining(spans, offset)
                val (insertOffset, insertText) = when {
                    containing != null -> containing.end to leadingSpaceIfNeeded(fb.document, containing.end, insert)
                    else -> {
                        val refEnding = spans.firstOrNull { it.end == offset }
                        if (refEnding != null) {
                            offset to leadingSpaceIfNeeded(fb.document, offset, insert)
                        } else {
                            offset to insert
                        }
                    }
                }

                if (replaceLength > 0) {
                    super.replace(fb, insertOffset, replaceLength, insertText, attr)
                } else {
                    super.insertString(fb, insertOffset, insertText, attr)
                }
                if (insertOffset != offset) {
                    moveCaretLater(insertOffset + insertText.length)
                }
            }

            override fun remove(fb: FilterBypass, offset: Int, length: Int) {
                if (length <= 0) return
                val validator = fileReferenceValidator
                if (validator == null) {
                    super.remove(fb, offset, length)
                    return
                }

                val text = fb.document.getText(0, fb.document.length)
                val spans = FileReferenceSupport.findValidSpans(text, validator)
                val removeEnd = offset + length
                val affected = spans.filter { span -> span.start < removeEnd && span.end > offset }
                if (affected.isEmpty()) {
                    super.remove(fb, offset, length)
                    return
                }

                val expandedStart = minOf(offset, affected.minOf { it.start })
                val expandedEnd = maxOf(removeEnd, affected.maxOf { it.end })
                super.remove(fb, expandedStart, expandedEnd - expandedStart)
                moveCaretLater(expandedStart)
            }
        }

        textArea.addCaretListener(CaretListener { e ->
            if (adjustingReferenceEdit) return@CaretListener
            if (e.dot != e.mark) return@CaretListener
            val validator = fileReferenceValidator ?: return@CaretListener
            val spans = FileReferenceSupport.findValidSpans(textArea.text, validator)
            val span = FileReferenceSupport.spanContaining(spans, e.dot) ?: return@CaretListener
            moveCaret(span.end)
        })

        textArea.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val validator = fileReferenceValidator ?: return
                @Suppress("DEPRECATION")
                val pos = textArea.viewToModel(e.point).coerceAtLeast(0)
                val spans = FileReferenceSupport.findValidSpans(textArea.text, validator)
                val span = FileReferenceSupport.spanContaining(spans, pos) ?: return
                moveCaret(span.end)
            }
        })
    }

    private fun leadingSpaceIfNeeded(document: Document, offset: Int, insert: String): String {
        if (insert.isEmpty() || insert.first().isWhitespace()) return insert
        if (offset <= 0) return insert
        return if (document.getText(offset - 1, 1) == " ") insert else " $insert"
    }

    private fun moveCaret(position: Int) {
        adjustingReferenceEdit = true
        try {
            textArea.caretPosition = position.coerceIn(0, textArea.document.length)
        } finally {
            adjustingReferenceEdit = false
        }
    }

    private fun moveCaretLater(position: Int) {
        SwingUtilities.invokeLater { moveCaret(position) }
    }

    private fun scheduleUpdateHighlights() {
        SwingUtilities.invokeLater { updateHighlights() }
    }

    private fun updateHighlights() {
        val highlighter = textArea.highlighter ?: return
        val text = textArea.text

        for (tag in activeHighlightTags) {
            highlighter.removeHighlight(tag)
        }
        activeHighlightTags.clear()

        val validator = fileReferenceValidator ?: return

        for (span in FileReferenceSupport.findValidSpans(text, validator)) {
            val tag = highlighter.addHighlight(span.start, span.end, pillPainter)
            if (tag != null) {
                activeHighlightTags.add(tag)
            }
        }
        textArea.repaint()
    }

    class PillHighlightPainter(
        private val bgColor: Color,
        private val borderColor: Color,
    ) : Highlighter.HighlightPainter {
        override fun paint(g: Graphics, p0: Int, p1: Int, bounds: Shape, c: javax.swing.text.JTextComponent) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            try {
                for (rect in highlightRects(c, p0, p1)) {
                    paintPill(g2, rect)
                }
            } finally {
                g2.dispose()
            }
        }

        private fun paintPill(g2: Graphics2D, rect: Rectangle) {
            val padX = 2
            val padY = 1
            val x = rect.x - padX
            val y = rect.y + padY
            val w = rect.width + padX * 2
            val h = rect.height - padY * 2
            if (w <= 0 || h <= 0) return

            g2.color = bgColor
            g2.fillRoundRect(x, y, w, h, 6, 6)

            g2.color = borderColor
            g2.stroke = BasicStroke(1f)
            g2.drawRoundRect(x, y, w, h - 1, 6, 6)
        }

        private fun highlightRects(c: javax.swing.text.JTextComponent, start: Int, end: Int): List<Rectangle> {
            if (start >= end) return emptyList()
            val ui = c.ui ?: return emptyList()

            val rects = mutableListOf<Rectangle>()
            var segmentStart = start
            while (segmentStart < end) {
                val segmentEnd = lineEndOffset(c, segmentStart).coerceAtMost(end)
                val startRect = ui.modelToView2D(c, segmentStart, Position.Bias.Forward)?.bounds ?: break
                val endRect = ui.modelToView2D(c, segmentEnd, Position.Bias.Backward)?.bounds ?: break

                val x = startRect.x.toInt()
                val y = startRect.y.toInt()
                val height = startRect.height.toInt()
                val width = if (Math.abs(startRect.y - endRect.y) < 0.5) {
                    (endRect.x + endRect.width - startRect.x).toInt()
                } else {
                    val lineEndRect = ui.modelToView2D(c, segmentEnd, Position.Bias.Backward)?.bounds ?: startRect
                    (lineEndRect.x + lineEndRect.width - startRect.x).toInt()
                }

                if (width > 0 && height > 0) {
                    rects.add(Rectangle(x, y, width, height))
                }
                segmentStart = segmentEnd
            }
            return rects
        }

        private fun lineEndOffset(c: javax.swing.text.JTextComponent, offset: Int): Int {
            val root = c.document.defaultRootElement
            val line = root.getElementIndex(offset)
            return root.getElement(line).endOffset
        }
    }

    companion object {
        private const val visualLineEpsilonPx = 0.5
    }
}
