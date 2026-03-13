package com.cursorj.ui.chat

import com.cursorj.CursorJBundle
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class MessageQueuePanel {
    private val entries = mutableListOf<String>()
    private var collapsed = false

    private val headerColor = JBColor(Color(0x6E6E6E), Color(0x9C9C9C))
    private val entryTextColor = JBColor(Color(0x3C3C3C), Color(0xBBBBBB))
    private val entryBorderColor = JBColor(Color(0xE0E0E0), Color(0x3C3C3C))
    private val circleColor = JBColor(Color(0xCCCCCC), Color(0x555555))

    private val rootPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(0, 8, 4, 8)
        isVisible = false
    }

    private val headerLabel = JLabel().apply {
        foreground = headerColor
        font = font.deriveFont(font.size2D - 1)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(4, 4, 4, 4)
    }

    private val entriesPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    var onRemove: ((Int) -> Unit)? = null
    var onEdit: ((Int, String) -> Unit)? = null
    var onSendNow: ((Int) -> Unit)? = null

    val component: JComponent get() = rootPanel

    init {
        headerLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                collapsed = !collapsed
                entriesPanel.isVisible = !collapsed
                updateHeaderText()
                rootPanel.revalidate()
                rootPanel.repaint()
            }
        })

        rootPanel.add(headerLabel, BorderLayout.NORTH)
        rootPanel.add(entriesPanel, BorderLayout.CENTER)
    }

    fun addEntry(text: String) {
        entries.add(text)
        rebuildUI()
    }

    fun removeEntry(index: Int) {
        if (index in entries.indices) {
            entries.removeAt(index)
            rebuildUI()
        }
    }

    fun updateEntry(index: Int, newText: String) {
        if (index in entries.indices) {
            entries[index] = newText
            rebuildUI()
        }
    }

    fun removeAndGet(index: Int): String? {
        if (index !in entries.indices) return null
        val text = entries.removeAt(index)
        rebuildUI()
        return text
    }

    fun clear() {
        entries.clear()
        rebuildUI()
    }

    fun dequeue(): String? {
        if (entries.isEmpty()) return null
        val text = entries.removeAt(0)
        rebuildUI()
        return text
    }

    fun entries(): List<String> = entries.toList()

    fun size(): Int = entries.size

    private fun rebuildUI() {
        entriesPanel.removeAll()
        for (i in entries.indices) {
            entriesPanel.add(buildEntryRow(i, entries[i]))
        }
        rootPanel.isVisible = entries.isNotEmpty()
        entriesPanel.isVisible = !collapsed
        updateHeaderText()
        entriesPanel.revalidate()
        entriesPanel.repaint()
        rootPanel.revalidate()
        rootPanel.repaint()
    }

    private fun updateHeaderText() {
        val arrow = if (collapsed) "\u25B6" else "\u25BC"
        headerLabel.text = "$arrow ${CursorJBundle.message("chat.queue.header", entries.size)}"
    }

    private fun buildEntryRow(index: Int, text: String): JComponent {
        val row = object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension =
                Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(entryBorderColor, 0, 0, 1, 0),
                JBUI.Borders.empty(4, 4, 4, 4),
            )
        }

        val circle = object : JPanel() {
            override fun getPreferredSize(): Dimension = Dimension(14, 14)
            override fun getMinimumSize(): Dimension = preferredSize
            override fun getMaximumSize(): Dimension = preferredSize
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = circleColor
                g2.drawOval(1, 1, width - 3, height - 3)
            }
        }.apply { isOpaque = false }

        val truncated = if (text.length > 60) text.take(57) + "..." else text
        val label = JLabel(truncated).apply {
            foreground = entryTextColor
            font = font.deriveFont(font.size2D - 1)
            border = JBUI.Borders.empty(0, 6, 0, 0)
        }

        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
            isOpaque = false
            add(circle)
            add(label)
        }

        val editBtn = iconButton(AllIcons.Actions.Edit, CursorJBundle.message("chat.queue.edit.tooltip")) {
            val newText = JOptionPane.showInputDialog(rootPanel, null, text)
            if (newText != null && newText.isNotBlank()) {
                onEdit?.invoke(index, newText)
            }
        }
        val sendNowBtn = iconButton(AllIcons.Actions.MoveUp, CursorJBundle.message("chat.queue.sendNow.tooltip")) {
            onSendNow?.invoke(index)
        }

        val deleteBtn = iconButton(AllIcons.Actions.GC, CursorJBundle.message("chat.queue.delete.tooltip")) {
            onRemove?.invoke(index)
        }

        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            add(editBtn)
            add(sendNowBtn)
            add(deleteBtn)
        }

        row.add(leftPanel, BorderLayout.CENTER)
        row.add(actionsPanel, BorderLayout.EAST)
        return row
    }

    private fun iconButton(icon: Icon, tooltip: String, onClick: () -> Unit): JButton {
        return JButton(icon).apply {
            toolTipText = tooltip
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(22, 22)
            addActionListener { onClick() }
        }
    }
}
