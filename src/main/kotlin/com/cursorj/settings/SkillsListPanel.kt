package com.cursorj.settings

import com.cursorj.CursorJBundle
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel

/**
 * Lists discovered agent skills and supports creating skills under `.cursor/skills/`, opening `SKILL.md`, and removing project-local skills.
 */
class SkillsListPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val listModel = DefaultListModel<SkillDefinition>()
    private val list = JBList(listModel).apply {
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ) = super.getListCellRendererComponent(
                list,
                formatSkillLabel(value as? SkillDefinition),
                index,
                isSelected,
                cellHasFocus,
            )
        }
    }

    private val toolbar = ToolbarDecorator.createDecorator(list)
        .setAddAction { addSkill() }
        .setRemoveAction { removeSelectedSkill() }
        .setEditAction { editSelectedSkill() }
        .disableUpDownActions()
        .createPanel()
        .apply { preferredSize = JBUI.size(400, 160) }

    init {
        add(toolbar, BorderLayout.CENTER)
        refresh()
    }

    fun refresh() {
        listModel.clear()
        for (s in SkillsService.discoverSkills(project)) {
            listModel.addElement(s)
        }
    }

    private fun formatSkillLabel(s: SkillDefinition?): String {
        if (s == null) return ""
        val scope = when (s.scope) {
            SkillScope.GLOBAL -> CursorJBundle.message("settings.skills.badge.global")
            SkillScope.PROJECT -> CursorJBundle.message("settings.skills.badge.project")
        }
        val src = when (s.sourceKind) {
            SkillSourceKind.CURSOR -> "cursor"
            SkillSourceKind.AGENTS -> "agents"
            SkillSourceKind.CLAUDE -> "claude"
            SkillSourceKind.CODEX -> "codex"
        }
        val nest = s.nestedScopeDir?.let { " @ $it" }.orEmpty()
        val desc = s.description.trim().take(48)
        val descPart = if (desc.isNotEmpty()) " — $desc" else ""
        return "${s.name} [$scope/$src]$nest$descPart"
    }

    private fun addSkill() {
        val name = Messages.showInputDialog(
            project,
            CursorJBundle.message("settings.skills.add.prompt"),
            CursorJBundle.message("settings.skills.add.title"),
            Messages.getQuestionIcon(),
            "",
            null,
        ) ?: return
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val created = SkillsService.createSkill(project, trimmed)
        if (created != null) {
            refresh()
            FileEditorManager.getInstance(project).openFile(created, true)
        } else {
            Messages.showErrorDialog(
                project,
                CursorJBundle.message("settings.skills.add.failed"),
                CursorJBundle.message("settings.skills.add.title"),
            )
        }
    }

    private fun removeSelectedSkill() {
        val selected = list.selectedValue ?: return
        if (selected.scope == SkillScope.GLOBAL) {
            Messages.showWarningDialog(
                project,
                CursorJBundle.message("settings.skills.remove.globalBlocked"),
                CursorJBundle.message("settings.skills.remove.title"),
            )
            return
        }
        if (!canDeleteInProject(selected)) {
            Messages.showWarningDialog(
                project,
                CursorJBundle.message("settings.skills.remove.outsideProject"),
                CursorJBundle.message("settings.skills.remove.title"),
            )
            return
        }
        val ok = Messages.showYesNoDialog(
            project,
            CursorJBundle.message("settings.skills.remove.confirm", selected.name),
            CursorJBundle.message("settings.skills.remove.title"),
            CursorJBundle.message("settings.skills.remove.confirm.yes"),
            CursorJBundle.message("settings.skills.remove.confirm.no"),
            Messages.getQuestionIcon(),
        ) == Messages.YES
        if (ok && SkillsService.deleteSkill(project, selected)) {
            refresh()
        }
    }

    private fun canDeleteInProject(def: SkillDefinition): Boolean {
        val base = project.basePath?.replace('\\', '/')?.trimEnd('/') ?: return false
        val folder = def.folder.path.replace('\\', '/')
        return folder.startsWith(base)
    }

    private fun editSelectedSkill() {
        val selected = list.selectedValue ?: return
        FileEditorManager.getInstance(project).openFile(selected.skillFile, true)
    }
}
