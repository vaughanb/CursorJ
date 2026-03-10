package com.cursorj.ui.toolwindow

import com.cursorj.acp.AgentConnection
import com.cursorj.acp.AcpProcessManager
import com.cursorj.context.ActiveFileProvider
import com.cursorj.context.SelectionProvider
import com.cursorj.indexing.WorkspaceIndexOrchestrator
import com.cursorj.settings.CursorJSettings
import com.cursorj.ui.statusbar.CursorJConnectionStatus
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

        internal fun selectInitialModel(
            explicitModel: String?,
            configuredDefaultModel: String?,
            availableModelIds: Set<String>,
        ): String? {
            val normalizedExplicit = explicitModel?.trim()?.takeIf { it.isNotBlank() }
            val normalizedDefault = configuredDefaultModel?.trim()?.takeIf { it.isNotBlank() }
            val candidate = normalizedExplicit ?: normalizedDefault ?: return null
            if (availableModelIds.isEmpty()) return candidate
            return candidate.takeIf { it in availableModelIds }
        }
    }

    private val log = Logger.getInstance(CursorJService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val activeFileProvider = ActiveFileProvider(project)
    val selectionProvider = SelectionProvider(project)
    val workspaceIndexOrchestrator = WorkspaceIndexOrchestrator(project)

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
        val indexingListener: (WorkspaceIndexOrchestrator.IndexLifecycleUpdate) -> Unit = { update ->
            CursorJConnectionStatus.updateIndexing(update.message)
            if (CursorJSettings.instance.showIndexingStatusInChat) {
                tabManager.showIndexLifecycle(update)
            }
        }
        workspaceIndexOrchestrator.addLifecycleListener(indexingListener)
        Disposer.register(this) {
            workspaceIndexOrchestrator.removeLifecycleListener(indexingListener)
            CursorJConnectionStatus.updateIndexing(null)
        }
        Disposer.register(this, workspaceIndexOrchestrator)
        workspaceIndexOrchestrator.start()
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
        val configuredDefaultModel = CursorJSettings.instance.defaultModel
        val availableIds = availableModelInfos.map { it.id }.toSet()
        val initialModel = selectInitialModel(
            explicitModel = model,
            configuredDefaultModel = configuredDefaultModel,
            availableModelIds = availableIds,
        )
        if (model.isNullOrBlank() && !configuredDefaultModel.isBlank() && initialModel == null && availableIds.isNotEmpty()) {
            log.warn("Ignoring invalid default model '$configuredDefaultModel'; falling back to agent default")
        }
        return AgentConnection(
            project = project,
            parentDisposable = parentDisposable,
            modelInfos = availableModelInfos,
            workspaceIndexOrchestrator = workspaceIndexOrchestrator,
            initialModel = initialModel,
        )
    }

    override fun dispose() {
        instances.remove(project, this)
        scope.cancel()
    }
}
