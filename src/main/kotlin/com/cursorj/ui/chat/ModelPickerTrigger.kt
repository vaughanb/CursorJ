package com.cursorj.ui.chat

import com.cursorj.CursorJBundle
import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Compact prompt-bar trigger for the model picker, like Cursor's own: `Claude Opus 4.7  300K Extra High ▾`
 * with the parameter suffix muted. A click, Space, Enter or Down opens the picker through [onActivate].
 */
internal class ModelPickerTrigger : JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)) {
    var modelName: String = ""
        private set
    var suffix: String = ""
        private set
    var onActivate: (() -> Unit)? = null

    private val nameLabel = JLabel().apply {
        font = font.deriveFont(font.size2D - 1)
    }
    private val suffixLabel = JLabel().apply {
        font = font.deriveFont(font.size2D - 1)
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        border = JBUI.Borders.emptyLeft(6)
    }
    private val arrowLabel = JLabel(AllIcons.General.ArrowDown).apply {
        border = JBUI.Borders.emptyLeft(2)
    }
    private var hovered = false

    init {
        name = NAME
        isOpaque = false
        isFocusable = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(3, 6)
        toolTipText = CursorJBundle.message("chat.modelPicker.trigger.tooltip")
        add(nameLabel)
        add(suffixLabel)
        add(arrowLabel)

        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) activate()
            }

            override fun mouseEntered(e: MouseEvent) {
                hovered = true
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = false
                repaint()
            }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_SPACE || e.keyCode == KeyEvent.VK_ENTER || e.keyCode == KeyEvent.VK_DOWN) {
                    activate()
                    e.consume()
                }
            }
        })
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = repaint()
            override fun focusLost(e: FocusEvent) = repaint()
        })
    }

    /** Shows [modelName] with the muted parameter [suffix] (hidden when blank). */
    fun setModel(modelName: String, suffix: String) {
        this.modelName = modelName
        this.suffix = suffix
        nameLabel.text = modelName
        suffixLabel.text = suffix
        suffixLabel.isVisible = suffix.isNotBlank()
        // Explicit getter: the bare name resolves to the protected Swing field, which is null until first use.
        getAccessibleContext().accessibleName = listOf(modelName, suffix).filter { it.isNotBlank() }.joinToString(" ")
        revalidate()
        repaint()
    }

    private fun activate() {
        requestFocusInWindow()
        onActivate?.invoke()
    }

    override fun paintComponent(g: Graphics) {
        if (hovered || isFocusOwner) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = JBUI.CurrentTheme.ActionButton.hoverBackground()
            g2.fillRoundRect(0, 0, width, height, 8, 8)
            g2.dispose()
        }
        super.paintComponent(g)
    }

    companion object {
        /** Swing component name (for tests and UI automation). */
        const val NAME = "modelPickerTrigger"
    }
}
