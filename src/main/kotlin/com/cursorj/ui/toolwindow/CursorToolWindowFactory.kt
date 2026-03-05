package com.cursorj.ui.toolwindow

import com.cursorj.CursorJBundle
import com.cursorj.acp.AcpClient
import com.cursorj.acp.AcpProcessManager
import com.cursorj.acp.AcpSession
import com.cursorj.context.ActiveFileProvider
import com.cursorj.context.SelectionProvider
import com.cursorj.handlers.FileSystemHandler
import com.cursorj.handlers.PermissionHandler
import com.cursorj.handlers.TerminalHandler
import com.cursorj.settings.CursorJSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import kotlinx.coroutines.*

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
    private val log = Logger.getInstance(CursorJService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val processManager = AcpProcessManager(this)
    val client = AcpClient(this)
    val activeFileProvider = ActiveFileProvider(project)
    val selectionProvider = SelectionProvider(project)
    val fileSystemHandler = FileSystemHandler(project)
    val terminalHandler = TerminalHandler(project)
    val permissionHandler = PermissionHandler()

    lateinit var tabManager: SessionTabManager
        private set

    var isInitialized = false
        private set

    fun initialize() {
        tabManager = SessionTabManager(this, toolWindow)
        tabManager.addInitialTab()
    }

    fun connectAndInit(onReady: (Boolean) -> Unit) {
        scope.launch {
            try {
                if (!processManager.start()) {
                    withContext(Dispatchers.Main) { onReady(false) }
                    return@launch
                }

                val reader = processManager.reader!!
                val writer = processManager.writer!!
                client.connect(reader, writer)

                fileSystemHandler.register(client)
                terminalHandler.register(client)
                permissionHandler.register(client)

                client.initialize()
                client.authenticate()

                isInitialized = true

                val savedSessionIds = CursorJSettings.instance.savedSessionIds
                if (savedSessionIds.isNotEmpty()) {
                    for (sessionId in savedSessionIds) {
                        try {
                            client.sessionLoad(sessionId)
                        } catch (e: Exception) {
                            log.info("Could not resume session $sessionId: ${e.message}")
                        }
                    }
                    CursorJSettings.instance.savedSessionIds.clear()
                }

                withContext(Dispatchers.Main) { onReady(true) }
            } catch (e: Exception) {
                log.error("Failed to initialize ACP connection", e)
                withContext(Dispatchers.Main) { onReady(false) }
            }
        }
    }

    suspend fun createSession(): AcpSession {
        val cwd = project.basePath ?: System.getProperty("user.home")
        val result = client.sessionNew(cwd)
        val session = AcpSession(result.sessionId, client)

        client.addNotificationHandler { method, params ->
            if (method == "session/update") {
                session.handleSessionUpdate(params)
            }
        }

        return session
    }

    override fun dispose() {
        scope.cancel()
        terminalHandler.disposeAll()
    }
}
