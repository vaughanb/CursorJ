package com.cursorj.actions

import com.cursorj.ui.toolwindow.CursorJService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Steps the active CursorJ chat to the next effort / reasoning level, wrapping around (unbound by default;
 * `CursorJ.CycleEffort`). The new value is confirmed in the chat status line.
 */
class CycleEffortAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        CursorJService.getInstance(project)?.activeChatPanel()?.cycleEffort()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.let { CursorJService.getInstance(it)?.activeChatPanel() } != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
