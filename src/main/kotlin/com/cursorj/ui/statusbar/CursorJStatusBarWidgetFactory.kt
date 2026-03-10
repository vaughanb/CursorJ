package com.cursorj.ui.statusbar

import com.cursorj.CursorJBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Consumer
import java.awt.event.MouseEvent
import java.util.concurrent.CopyOnWriteArrayList

object CursorJConnectionStatus {
    private var connectionDetail: String = normalizeStatusText(CursorJBundle.message("status.disconnected"))
    private var indexingDetail: String? = null
    private var _text = "CursorJ: $connectionDetail"
    val text: String get() = _text

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun update(connected: Boolean, detail: String? = null) {
        connectionDetail = normalizeStatusText(when {
            detail != null -> detail
            connected -> CursorJBundle.message("status.connected")
            else -> CursorJBundle.message("status.disconnected")
        })
        refreshText()
    }

    fun updateIndexing(detail: String?) {
        indexingDetail = detail?.takeIf { it.isNotBlank() }
        refreshText()
    }

    private fun refreshText() {
        _text = when {
            indexingDetail != null -> "CursorJ: $connectionDetail | ${indexingDetail!!}"
            else -> "CursorJ: $connectionDetail"
        }
        for (listener in listeners) {
            listener()
        }
    }

    private fun normalizeStatusText(value: String): String {
        return value.removePrefix("CursorJ: ").trim()
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
}

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
    private val statusListener: () -> Unit = { statusBar?.updateWidget(ID()) }

    override fun ID(): String = "CursorJStatusBar"

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        CursorJConnectionStatus.addListener(statusListener)
    }

    override fun dispose() {
        CursorJConnectionStatus.removeListener(statusListener)
        statusBar = null
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String = CursorJConnectionStatus.text

    override fun getAlignment(): Float = 0f

    override fun getTooltipText(): String = "Click to open CursorJ"

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        ToolWindowManager.getInstance(project).getToolWindow("CursorJ")?.show()
    }
}
