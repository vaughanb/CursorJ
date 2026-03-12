package com.cursorj.settings

import com.cursorj.CursorJBundle
import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBTextField
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.Scrollable

/**
 * A list of global user rules, each displayed as a separate row with text field and remove button.
 * Mirrors Cursor IDE's rule management: add/remove individual rules, concatenated when submitted.
 */
class GlobalUserRulesListPanel : JPanel(BorderLayout()) {
    private val rulesPanel = object : JPanel(), Scrollable {
        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
        override fun getScrollableBlockIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int) = 50
        override fun getScrollableUnitIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int) = 10
        override fun getPreferredScrollableViewportSize() = Dimension(0, 130)
    }
    private val scrollPane = JScrollPane(rulesPanel).apply {
        preferredSize = JBUI.size(400, 130)
        border = JBUI.Borders.customLine(JBColor.border(), 1)
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }
    private val addButton = JButton(CursorJBundle.message("settings.globalRules.add")).apply {
        icon = AllIcons.General.Add
        addActionListener { addRuleRow("") }
    }

    private val ruleRows = mutableListOf<RuleRow>()

    init {
        add(scrollPane, BorderLayout.CENTER)
        add(
            JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(addButton) },
            BorderLayout.SOUTH,
        )
    }

    fun setRules(rules: List<String>) {
        clearRows()
        for (rule in rules) {
            addRuleRow(rule)
        }
    }

    fun getRules(): List<String> = ruleRows
        .map { it.textField.text.trim() }
        .filter { it.isNotBlank() }

    private fun addRuleRow(initialText: String) {
        val row = createRuleRow(initialText)
        ruleRows.add(row)
        rulesPanel.add(row.panel)
        rulesPanel.revalidate()
        rulesPanel.repaint()
    }

    private fun removeRuleRow(row: RuleRow) {
        ruleRows.remove(row)
        rulesPanel.remove(row.panel)
        rulesPanel.revalidate()
        rulesPanel.repaint()
    }

    private fun clearRows() {
        for (row in ruleRows) {
            rulesPanel.remove(row.panel)
        }
        ruleRows.clear()
        rulesPanel.revalidate()
        rulesPanel.repaint()
    }

    private class RuleRow(
        val panel: JPanel,
        val textField: JBTextField,
    )

    private fun createRuleRow(initialText: String): RuleRow {
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(4)
        }
        val textField = JBTextField(initialText, 1).apply {
            horizontalAlignment = javax.swing.JTextField.LEADING
            putClientProperty(
                "JTextField.placeholderText",
                CursorJBundle.message("settings.globalRules.row.placeholder"),
            )
        }
        val row = RuleRow(panel, textField)
        val removeButton = JButton(AllIcons.General.Remove).apply {
            toolTipText = CursorJBundle.message("settings.globalRules.remove")
            addActionListener { removeRuleRow(row) }
        }
        panel.add(textField, BorderLayout.CENTER)
        panel.add(
            JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { add(removeButton) },
            BorderLayout.EAST,
        )
        return row
    }
}
