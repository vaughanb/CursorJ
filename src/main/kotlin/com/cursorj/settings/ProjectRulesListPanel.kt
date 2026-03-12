package com.cursorj.settings

import com.cursorj.CursorJBundle
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JPanel

/**
 * Lists project rules (`.cursor/rules/` files) with add, edit (open in editor), remove.
 * Rules are stored in `.cursor/rules/`; the Cursor agent CLI discovers them automatically.
 */
class ProjectRulesListPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val listModel = DefaultListModel<VirtualFile>()
    private val list = JBList(listModel).apply {
        cellRenderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ) = super.getListCellRendererComponent(
                list,
                (value as? VirtualFile)?.name ?: value?.toString().orEmpty(),
                index,
                isSelected,
                cellHasFocus,
            )
        }
    }

    val component: JPanel = ToolbarDecorator.createDecorator(list)
        .setAddAction { addRule() }
        .setRemoveAction { removeSelectedRule() }
        .setEditAction { editSelectedRule() }
        .disableUpDownActions()
        .createPanel()
        .apply { preferredSize = JBUI.size(400, 160) }

    init {
        add(component, BorderLayout.CENTER)
        refresh()
    }

    fun refresh() {
        listModel.clear()
        for (f in ProjectRulesService.listRuleFiles(project)) {
            listModel.addElement(f)
        }
    }

    private fun addRule() {
        val name = Messages.showInputDialog(
            project,
            CursorJBundle.message("settings.projectRules.add.prompt"),
            CursorJBundle.message("settings.projectRules.add.title"),
            Messages.getQuestionIcon(),
            "",
            null,
        ) ?: return
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val baseName = trimmed.removeSuffix(".mdc").removeSuffix(".md")
        val content = """
            ---
            description: ""
            alwaysApply: false
            ---

            # $baseName

            Your rule content here.
        """.trimIndent()
        val created = ProjectRulesService.createRuleFile(project, trimmed, content)
        if (created != null) {
            listModel.addElement(created)
            FileEditorManager.getInstance(project).openFile(created, true)
        } else {
            Messages.showErrorDialog(
                project,
                CursorJBundle.message("settings.projectRules.add.failed"),
                CursorJBundle.message("settings.projectRules.add.title"),
            )
        }
    }

    private fun removeSelectedRule() {
        val selected = list.selectedValue ?: return
        val ok = Messages.showYesNoDialog(
            project,
            CursorJBundle.message("settings.projectRules.remove.confirm", selected.name),
            CursorJBundle.message("settings.projectRules.remove.title"),
            CursorJBundle.message("settings.projectRules.remove.confirm.yes"),
            CursorJBundle.message("settings.projectRules.remove.confirm.no"),
            Messages.getQuestionIcon(),
        ) == Messages.YES
        if (ok && ProjectRulesService.deleteRuleFile(project, selected)) {
            listModel.removeElement(selected)
        }
    }

    private fun editSelectedRule() {
        val selected = list.selectedValue ?: return
        FileEditorManager.getInstance(project).openFile(selected, true)
    }
}
