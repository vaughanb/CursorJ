package com.cursorj.ui.statusbar

import com.cursorj.CursorJBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Consumer
import java.awt.event.MouseEvent

class CursorJStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = "CursorJStatusBar"
    override fun getDisplayName(): String = "CursorJ"
    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget {
        return CursorJStatusBarWidget(project)
    }

    override fun disposeWidget(widget: StatusBarWidget) {}
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

private class CursorJStatusBarWidget(
    private val project: Project,
) : StatusBarWidget, StatusBarWidget.TextPresentation {
    private var statusBar: StatusBar? = null
    private var currentText = CursorJBundle.message("status.disconnected")

    override fun ID(): String = "CursorJStatusBar"

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun dispose() {
        statusBar = null
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String = currentText

    override fun getAlignment(): Float = 0f

    override fun getTooltipText(): String = "Click to open CursorJ"

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        ToolWindowManager.getInstance(project).getToolWindow("CursorJ")?.show()
    }

    fun updateStatus(text: String) {
        currentText = text
        statusBar?.updateWidget(ID())
    }
}
