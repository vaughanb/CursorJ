package com.cursorj.actions

import com.cursorj.context.SelectionProvider
import com.cursorj.ui.toolwindow.CursorJService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.wm.ToolWindowManager

class SendToCursorAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selectionProvider = CursorJService.getInstance(project)?.selectionProvider ?: SelectionProvider(project)
        val selection = selectionProvider.getSelectionContext() ?: return

        val toolWindowManager = ToolWindowManager.getInstance(project)
        val toolWindow = toolWindowManager.getToolWindow("CursorJ") ?: return
        toolWindow.show {
            toolWindow.activate {
                val service = CursorJService.getInstance(project) ?: return@activate
                val added = service.tabManager.addSelectionToActiveChat(selection.label, selection.blocks)
                if (!added) {
                    toolWindowManager.notifyByBalloon("CursorJ", MessageType.WARNING, "Unable to add selection to CursorJ chat.")
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val hasSelection = editor?.selectionModel?.selectedText?.isNotBlank() == true
        e.presentation.isEnabledAndVisible = e.project != null && hasSelection
    }
}
