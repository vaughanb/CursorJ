package com.cursorj.ui.toolwindow

import com.cursorj.acp.AgentConnection
import com.cursorj.acp.AcpProcessManager
import com.cursorj.context.ActiveFileProvider
import com.cursorj.context.SelectionProvider
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

class CursorToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = CursorJService(project, toolWindow)
        Disposer.register(toolWindow.disposable, service)
        service.initialize()
    }
}

class CursorJService(
    val project: Project,
    private val toolWindow: ToolWindow,
) : Disposable {
    companion object {
        private val instances = ConcurrentHashMap<Project, CursorJService>()

        fun getInstance(project: Project): CursorJService? = instances[project]
    }

    private val log = Logger.getInstance(CursorJService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val activeFileProvider = ActiveFileProvider(project)
    val selectionProvider = SelectionProvider(project)

    lateinit var tabManager: SessionTabManager
        private set

    var availableModelInfos: List<AcpProcessManager.ModelInfo> = emptyList()
        private set
    var modelsReady = false
        private set

    private val modelReadyListeners = mutableListOf<() -> Unit>()

    fun initialize() {
        instances[project] = this
        tabManager = SessionTabManager(this, toolWindow)
        tabManager.addInitialTab()
        fetchModelsAsync()
    }

    private fun fetchModelsAsync() {
        scope.launch {
            withContext(Dispatchers.IO) {
                val tempPm = AcpProcessManager(this@CursorJService)
                try {
                    availableModelInfos = tempPm.fetchAvailableModelsWithInfo()
                    log.info("Available models: ${availableModelInfos.map { it.id }}")
                } finally {
                    Disposer.dispose(tempPm)
                }
            }
            modelsReady = true
            for (listener in modelReadyListeners) {
                listener()
            }
            modelReadyListeners.clear()
        }
    }

    fun onModelsReady(listener: () -> Unit) {
        if (modelsReady) {
            listener()
        } else {
            modelReadyListeners.add(listener)
        }
    }

    fun createAgentConnection(parentDisposable: Disposable, model: String? = null): AgentConnection {
        return AgentConnection(project, parentDisposable, availableModelInfos, model)
    }

    override fun dispose() {
        instances.remove(project, this)
        scope.cancel()
    }
}
