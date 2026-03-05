package com.cursorj.ui.chat

import com.cursorj.CursorJBundle
import com.cursorj.acp.SessionMode
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*

class InputPanel {
    private val rootPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
            JBUI.Borders.empty(8),
        )
    }

    private val textArea = JBTextArea().apply {
        rows = 3
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(4)
        emptyText.text = CursorJBundle.message("chat.input.placeholder")
    }

    private val sendButton = JButton(AllIcons.Actions.Execute).apply {
        toolTipText = CursorJBundle.message("chat.input.send")
        isFocusable = false
    }

    private val cancelButton = JButton(AllIcons.Actions.Suspend).apply {
        toolTipText = CursorJBundle.message("chat.input.cancel")
        isFocusable = false
        isVisible = false
    }

    private val modeCombo = JComboBox(arrayOf(
        CursorJBundle.message("chat.mode.agent"),
        CursorJBundle.message("chat.mode.plan"),
        CursorJBundle.message("chat.mode.ask"),
    )).apply {
        preferredSize = Dimension(80, preferredSize.height)
    }

    private val fileChipsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
        isOpaque = false
    }

    var onSend: ((String) -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var onModeChanged: ((SessionMode) -> Unit)? = null

    val component: JComponent get() = rootPanel

    init {
        val scrollPane = JScrollPane(textArea).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(sendButton)
            add(Box.createVerticalStrut(4))
            add(cancelButton)
        }

        val bottomBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(modeCombo, BorderLayout.WEST)
            add(fileChipsPanel, BorderLayout.CENTER)
        }

        rootPanel.add(scrollPane, BorderLayout.CENTER)
        rootPanel.add(buttonPanel, BorderLayout.EAST)
        rootPanel.add(bottomBar, BorderLayout.SOUTH)

        textArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    doSend()
                }
            }
        })

        sendButton.addActionListener { doSend() }
        cancelButton.addActionListener { onCancel?.invoke() }

        modeCombo.addActionListener {
            val mode = when (modeCombo.selectedIndex) {
                1 -> SessionMode.PLAN
                2 -> SessionMode.ASK
                else -> SessionMode.AGENT
            }
            onModeChanged?.invoke(mode)
        }
    }

    fun setProcessing(processing: Boolean) {
        sendButton.isVisible = !processing
        cancelButton.isVisible = processing
        textArea.isEditable = !processing
        if (!processing) {
            textArea.text = ""
            textArea.requestFocusInWindow()
        }
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
                    fileChipsPanel.revalidate()
                    fileChipsPanel.repaint()
                }
            })
            add(label)
            add(removeBtn)
        }
        fileChipsPanel.add(chip)
        fileChipsPanel.revalidate()
    }

    fun clearFileChips() {
        fileChipsPanel.removeAll()
        fileChipsPanel.revalidate()
        fileChipsPanel.repaint()
    }

    private fun doSend() {
        val text = textArea.text.trim()
        if (text.isNotEmpty()) {
            onSend?.invoke(text)
        }
    }
}
