package com.cursorj.settings

import com.cursorj.CursorJBundle
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.TitledSeparator
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Project-level configurable for Cursor project rules.
 * Rules are stored in `.cursor/rules/`; changes apply immediately to the project.
 */
class CursorJProjectConfigurable(private val project: Project) : Configurable {
    private var rulesPanel: ProjectRulesListPanel? = null
    private var mainPanel: JPanel? = null

    override fun getDisplayName(): String = CursorJBundle.message("settings.projectRules.displayName")

    override fun createComponent(): JComponent {
        rulesPanel = ProjectRulesListPanel(project)
        mainPanel = FormBuilder.createFormBuilder()
            .addComponent(TitledSeparator(CursorJBundle.message("settings.projectRules.displayName")), 1)
            .addComponent(
                com.intellij.ui.components.JBLabel(
                    CursorJBundle.message("settings.projectRules.description"),
                ),
                1,
            )
            .addComponent(rulesPanel!!, 1)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        return mainPanel!!
    }

    override fun isModified(): Boolean = false

    override fun apply() {}

    override fun reset() {
        rulesPanel?.refresh()
    }

    override fun disposeUIResources() {
        rulesPanel = null
        mainPanel = null
    }
}
