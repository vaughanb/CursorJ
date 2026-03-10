package com.cursorj.ui.toolwindow

import com.cursorj.CursorJBundle
import com.cursorj.acp.AgentConnection
import com.cursorj.acp.AcpProcessManager
import com.cursorj.context.ActiveFileProvider
import com.cursorj.context.SelectionProvider
import com.cursorj.history.PromptHistoryManager
import com.cursorj.history.PromptHistoryStore
import com.cursorj.history.ChatHistoryIndexManager
import com.cursorj.history.ChatHistoryStore
import com.cursorj.history.ChatTranscriptManager
import com.cursorj.history.ChatTranscriptStore
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
    val promptHistoryManager = PromptHistoryManager(PromptHistoryStore(project.basePath))
    val chatTranscriptManager = ChatTranscriptManager(ChatTranscriptStore(project.basePath))
    val chatHistoryIndexManager = ChatHistoryIndexManager(ChatHistoryStore(project.basePath))

    lateinit var tabManager: SessionTabManager
        private set

    var availableModelInfos: List<AcpProcessManager.ModelInfo> = emptyList()
        private set
    var modelsReady = false
        private set

    private val modelReadyListeners = mutableListOf<() -> Unit>()

    fun initialize() {
        instances[project] = this
        promptHistoryManager.load()
        chatTranscriptManager.load()
        chatHistoryIndexManager.load()
        backfillChatHistoryIfNeeded()
        tabManager = SessionTabManager(this, toolWindow)
        tabManager.addInitialTab()
        val indexingListener: (WorkspaceIndexOrchestrator.IndexLifecycleUpdate) -> Unit = { update ->
            val detail = when (update.state) {
                WorkspaceIndexOrchestrator.IndexLifecycleState.STARTUP_BUILD,
                WorkspaceIndexOrchestrator.IndexLifecycleState.INCREMENTAL_BUILD,
                WorkspaceIndexOrchestrator.IndexLifecycleState.STALE_REBUILDING,
                -> CursorJBundle.message("status.indexing.inProgress")
                WorkspaceIndexOrchestrator.IndexLifecycleState.READY -> CursorJBundle.message("status.indexing.ready")
                WorkspaceIndexOrchestrator.IndexLifecycleState.FAILED ->
                    update.message.ifBlank { CursorJBundle.message("status.indexing.failed") }
            }
            CursorJConnectionStatus.updateIndexing(detail)
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

    private fun backfillChatHistoryIfNeeded() {
        if (chatHistoryIndexManager.storeFileExists() || chatHistoryIndexManager.entryCount() > 0) return

        val savedSessionIds = CursorJSettings.instance.savedSessionIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (savedSessionIds.isEmpty()) return

        for (sessionId in savedSessionIds) {
            val historyKey = "session:$sessionId"
            val title = chatTranscriptManager.transcriptFor(historyKey)
                .asReversed()
                .firstOrNull { it.role == "user" && it.content.isNotBlank() }
                ?.content
                ?.let { firstLine ->
                    firstLine.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(32) ?: "Chat"
                }
                ?: promptHistoryManager.historyFor(historyKey)
                    .lastOrNull()
                    ?.let { prompt ->
                        prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(32) ?: "Chat"
                    }
                ?: "Chat"
            chatHistoryIndexManager.recordSession(sessionId, title)
        }
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
        promptHistoryManager.persist()
        chatTranscriptManager.persist()
        chatHistoryIndexManager.persist()
        instances.remove(project, this)
        scope.cancel()
    }
}
