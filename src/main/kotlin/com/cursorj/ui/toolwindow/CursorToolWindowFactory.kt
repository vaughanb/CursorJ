package com.cursorj.ui.toolwindow

import com.cursorj.CursorJBundle
import com.cursorj.acp.AcpClient
import com.cursorj.acp.AcpProcessManager
import com.cursorj.acp.AcpSession
import com.cursorj.acp.messages.ConfigOption
import com.cursorj.acp.messages.ConfigOptionValue
import com.cursorj.context.ActiveFileProvider
import com.cursorj.context.SelectionProvider
import com.cursorj.handlers.FileSystemHandler
import com.cursorj.handlers.PermissionHandler
import com.cursorj.handlers.TerminalHandler
import com.cursorj.settings.CursorJSettings
import com.cursorj.ui.statusbar.CursorJConnectionStatus
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import kotlinx.coroutines.*
import javax.swing.SwingUtilities

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

    var lastError: String? = null
        private set

    private val connectionListeners = mutableListOf<(Boolean) -> Unit>()
    private val sessions = mutableListOf<AcpSession>()

    var availableModelInfos: List<AcpProcessManager.ModelInfo> = emptyList()
        private set
    var selectedModel: String? = null

    fun addConnectionListener(listener: (Boolean) -> Unit) {
        if (isInitialized) {
            listener(true)
        } else {
            connectionListeners.add(listener)
        }
    }

    fun initialize() {
        tabManager = SessionTabManager(this, toolWindow)
        tabManager.addInitialTab()
        connectEagerly()
    }

    private fun connectEagerly() {
        scope.launch {
            try {
                log.info("Starting Cursor agent ACP connection...")

                CursorJConnectionStatus.update(false, "Connecting...")

                withContext(Dispatchers.IO) {
                    availableModelInfos = processManager.fetchAvailableModelsWithInfo()
                    log.info("Available models: ${availableModelInfos.map { it.id }}")
                    val current = availableModelInfos.firstOrNull { it.isCurrent }
                    if (current != null) {
                        selectedModel = current.id
                    }
                }

                processManager.workingDirectory = project.basePath
                if (!processManager.start()) {
                    lastError = CursorJBundle.message("error.agent.not.found")
                    log.warn(lastError!!)
                    CursorJConnectionStatus.update(false)
                    notifyConnectionListeners(false)
                    return@launch
                }

                val reader = processManager.reader!!
                val writer = processManager.writer!!
                client.connect(reader, writer)

                fileSystemHandler.register(client)
                terminalHandler.register(client)
                permissionHandler.register(client)
                registerFallbackHandler()

                client.addDisconnectListener { handleDisconnect() }

                log.info("Sending ACP initialize...")
                client.initialize()
                log.info("ACP initialized, authenticating...")
                client.authenticate()
                log.info("ACP authenticated successfully")

                isInitialized = true
                lastError = null
                CursorJConnectionStatus.update(true)
                notifyConnectionListeners(true)

                val modelConfig = buildModelConfigOption()
                if (modelConfig.isNotEmpty()) {
                    SwingUtilities.invokeLater {
                        tabManager.broadcastConfigOptions(modelConfig)
                    }
                }

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

                monitorProcess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = "ACP connection failed: ${e.message}"
                log.error("Failed to initialize ACP connection", e)
                CursorJConnectionStatus.update(false)
                notifyConnectionListeners(false)
            }
        }
    }

    private fun registerFallbackHandler() {
        client.addServerRequestHandler { method, _ ->
            when (method) {
                "fs/find_text_in_files",
                "editor/apply_edit",
                "editor/get_open_files" -> {
                    log.info("Stub response for unimplemented method: $method")
                    kotlinx.serialization.json.JsonObject(emptyMap())
                }
                else -> null
            }
        }
    }

    private fun monitorProcess() {
        scope.launch(Dispatchers.IO) {
            try {
                val exitCode = processManager.waitForExit()
                if (exitCode != null) {
                    log.warn("Agent process exited with code $exitCode")
                    handleDisconnect()
                }
            } catch (_: CancellationException) { }
        }
    }

    private fun handleDisconnect() {
        if (!isInitialized) return
        isInitialized = false
        lastError = "Agent disconnected"
        CursorJConnectionStatus.update(false, "Reconnecting...")
        log.warn("Agent connection lost, attempting reconnect...")

        scope.launch {
            delay(2000)
            attemptReconnect()
        }
    }

    private suspend fun attemptReconnect() {
        for (attempt in 1..3) {
            log.info("Reconnect attempt $attempt/3...")
            broadcastStatus("Reconnecting (attempt $attempt/3)...")

            processManager.stop()
            client.disconnect()

            try {
                if (!processManager.start()) {
                    log.warn("Reconnect: agent binary not found")
                    continue
                }

                val reader = processManager.reader!!
                val writer = processManager.writer!!
                client.connect(reader, writer)

                fileSystemHandler.register(client)
                terminalHandler.register(client)
                permissionHandler.register(client)
                registerFallbackHandler()
                client.addDisconnectListener { handleDisconnect() }

                client.initialize()
                client.authenticate()

                isInitialized = true
                lastError = null
                CursorJConnectionStatus.update(true)
                log.info("Reconnected successfully")
                broadcastStatus("Reconnected. You may need to resend your last message.")

                for (session in sessions) {
                    client.addNotificationHandler { method, params ->
                        if (method == "session/update") {
                            session.handleSessionUpdate(params)
                        }
                    }
                }

                monitorProcess()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Reconnect attempt $attempt failed: ${e.message}")
                if (attempt < 3) delay(3000L * attempt)
            }
        }

        lastError = "Failed to reconnect after 3 attempts"
        CursorJConnectionStatus.update(false)
        broadcastStatus("Disconnected. Please restart the CursorJ tool window.")
    }

    fun changeModel(modelId: String) {
        log.info("Changing model to: $modelId")
        selectedModel = modelId
        processManager.modelOverride = modelId
        sessions.clear()

        scope.launch {
            isInitialized = false
            CursorJConnectionStatus.update(false, "Switching model...")
            broadcastStatus("Switching to $modelId...")

            processManager.stop()
            client.disconnect()

            try {
                if (!processManager.start()) {
                    log.warn("Failed to restart agent with new model")
                    CursorJConnectionStatus.update(false)
                    broadcastStatus("Failed to switch model. Agent not found.")
                    return@launch
                }

                val reader = processManager.reader!!
                val writer = processManager.writer!!
                client.connect(reader, writer)

                fileSystemHandler.register(client)
                terminalHandler.register(client)
                permissionHandler.register(client)
                registerFallbackHandler()
                client.addDisconnectListener { handleDisconnect() }

                client.initialize()
                client.authenticate()

                isInitialized = true
                lastError = null
                CursorJConnectionStatus.update(true)
                log.info("Model switched to $modelId successfully")
                broadcastStatus("Switched to $modelId. Type a message to start.")

                monitorProcess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to switch model", e)
                CursorJConnectionStatus.update(false)
                broadcastStatus("Failed to switch model: ${e.message}")
            }
        }
    }

    private fun broadcastStatus(message: String) {
        SwingUtilities.invokeLater {
            tabManager.broadcastStatus(message)
        }
    }

    private fun notifyConnectionListeners(success: Boolean) {
        SwingUtilities.invokeLater {
            for (listener in connectionListeners) {
                listener(success)
            }
            connectionListeners.clear()
        }
    }

    suspend fun createSession(): AcpSession {
        val cwd = project.basePath ?: System.getProperty("user.home")
        log.info("Creating new ACP session with cwd: $cwd")
        val result = client.sessionNew(cwd)
        log.info("Session created with ${result.configOptions.size} config options")

        val configOptions = if (result.configOptions.any { it.category == "model" }) {
            result.configOptions
        } else {
            result.configOptions + buildModelConfigOption()
        }

        val session = AcpSession(result.sessionId, client, configOptions)
        sessions.add(session)

        client.addNotificationHandler { method, params ->
            if (method == "session/update") {
                session.handleSessionUpdate(params)
            }
        }

        return session
    }

    fun buildModelConfigOption(): List<ConfigOption> {
        if (availableModelInfos.isEmpty()) return emptyList()
        val currentModel = selectedModel
            ?: availableModelInfos.firstOrNull { it.isCurrent }?.id
            ?: availableModelInfos.firstOrNull()?.id
            ?: return emptyList()
        return listOf(
            ConfigOption(
                id = "model",
                name = "Model",
                category = "model",
                type = "select",
                currentValue = currentModel,
                options = availableModelInfos.map { ConfigOptionValue(value = it.id, name = it.displayName) },
            ),
        )
    }

    override fun dispose() {
        scope.cancel()
        terminalHandler.disposeAll()
    }
}
