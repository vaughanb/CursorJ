package com.cursorj.actions

import com.cursorj.ui.toolwindow.CursorJService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

/** Opens the model picker of the active CursorJ chat (unbound by default; `CursorJ.OpenModelPicker`). */
class OpenModelPickerAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val chatPanel = CursorJService.getInstance(project)?.activeChatPanel() ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("CursorJ") ?: return
        toolWindow.show {
            toolWindow.activate { chatPanel.openModelPicker() }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.let { CursorJService.getInstance(it)?.activeChatPanel() } != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
