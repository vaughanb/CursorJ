package com.cursorj.acp

import com.cursorj.acp.messages.ConfigOption
import com.cursorj.acp.messages.ConfigOptionValue
import com.cursorj.handlers.FileSystemHandler
import com.cursorj.handlers.PermissionHandler
import com.cursorj.handlers.TerminalHandler
import com.cursorj.rollback.TurnRollbackManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.swing.SwingUtilities

class AgentConnection(
    private val project: Project,
    private val parentDisposable: Disposable,
    modelInfos: List<AcpProcessManager.ModelInfo>,
    initialModel: String? = null,
) : Disposable {
    private val log = Logger.getInstance(AgentConnection::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var _modelInfos: List<AcpProcessManager.ModelInfo> = modelInfos

    fun updateModelInfos(infos: List<AcpProcessManager.ModelInfo>) {
        _modelInfos = infos
    }

    val processManager = AcpProcessManager(this)
    val client = AcpClient(this)
    private val rollbackManager = TurnRollbackManager.forProject(project)
    private val fileSystemHandler = FileSystemHandler(project)
    private val terminalHandler = TerminalHandler(project)
    private val permissionHandler = PermissionHandler()

    var session: AcpSession? = null
        private set
    var isConnected = false
        private set
    var selectedModel: String? = initialModel
        private set
    var lastError: String? = null
        private set

    var onStatusChanged: ((String) -> Unit)? = null
    var onConnectionChanged: ((Boolean) -> Unit)? = null

    private val visibleSessionUpdateHandler: NotificationHandler = { method, params ->
        if (method == "session/update") {
            val visible = session
            if (visible != null) {
                val updateSessionId = extractSessionId(params)
                if (updateSessionId == null || updateSessionId == visible.sessionId) {
                    visible.handleSessionUpdate(params)
                }
            }
        }
    }

    init {
        Disposer.register(parentDisposable, this)
        processManager.workingDirectory = project.basePath
        if (initialModel != null) {
            processManager.modelOverride = initialModel
        }
    }

    fun connectAsync() {
        scope.launch {
            connect()
        }
    }

    suspend fun connect() {
        try {
            broadcastStatus("Connecting...")

            if (!processManager.start()) {
                lastError = "Cursor agent not found"
                log.warn(lastError!!)
                isConnected = false
                notifyConnectionChanged(false)
                return
            }

            client.clearHandlers()
            client.connect(processManager.reader!!, processManager.writer!!)

            registerHandlers()
            client.addDisconnectListener { handleDisconnect() }
            client.addNotificationHandler(visibleSessionUpdateHandler)

            log.info("Sending ACP initialize...")
            client.initialize()
            client.authenticate()
            log.info("ACP authenticated successfully")

            isConnected = true
            lastError = null
            notifyConnectionChanged(true)

            monitorProcess()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is AcpException && e.message == "Disconnected") {
                log.info("Connection disposed during setup")
            } else {
                lastError = "Connection failed: ${e.message}"
                log.warn("Failed to initialize ACP connection", e)
            }
            isConnected = false
            notifyConnectionChanged(false)
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

        val newSession = AcpSession(result.sessionId, client, rollbackManager, configOptions)
        session = newSession

        return newSession
    }

    fun changeModel(modelId: String) {
        log.info("Changing model to: $modelId")
        selectedModel = modelId
        processManager.modelOverride = modelId
        session = null

        scope.launch {
            isConnected = false
            broadcastStatus("Switching model...")

            processManager.stop()
            client.disconnect()

            connect()
            if (isConnected) {
                broadcastStatus("Switched to $modelId. Type a message to start.")
            }
        }
    }

    fun buildModelConfigOption(): List<ConfigOption> {
        if (_modelInfos.isEmpty()) return emptyList()
        val currentModel = selectedModel
            ?: _modelInfos.firstOrNull { it.isCurrent }?.id
            ?: _modelInfos.firstOrNull()?.id
            ?: return emptyList()
        return listOf(
            ConfigOption(
                id = "model",
                name = "Model",
                category = "model",
                type = "select",
                currentValue = currentModel,
                options = _modelInfos.map { ConfigOptionValue(value = it.id, name = it.displayName) },
            ),
        )
    }

    private fun registerHandlers() {
        fileSystemHandler.register(client)
        terminalHandler.register(client)
        permissionHandler.register(client)
        client.addServerRequestHandler { method, params ->
            when (method) {
                "_cursor/create_plan" -> {
                    log.info("_cursor/create_plan received, params keys: ${(params as? JsonObject)?.keys}")
                    session?.handleCreatePlan(params)
                    JsonObject(emptyMap())
                }
                "fs/find_text_in_files",
                "editor/apply_edit",
                "editor/get_open_files" -> {
                    log.info("Stub response for unimplemented method: $method")
                    JsonObject(emptyMap())
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
        if (!isConnected) return
        session?.markActiveTurnInterrupted()
        isConnected = false
        lastError = "Agent disconnected"
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

                client.clearHandlers()
                client.connect(processManager.reader!!, processManager.writer!!)

                registerHandlers()
                client.addDisconnectListener { handleDisconnect() }
                client.addNotificationHandler(visibleSessionUpdateHandler)

                client.initialize()
                client.authenticate()

                isConnected = true
                lastError = null
                notifyConnectionChanged(true)
                log.info("Reconnected successfully")
                broadcastStatus("Reconnected. You may need to resend your last message.")

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
        isConnected = false
        notifyConnectionChanged(false)
        broadcastStatus("Disconnected. Please restart the tab.")
    }

    private fun broadcastStatus(message: String) {
        SwingUtilities.invokeLater {
            onStatusChanged?.invoke(message)
        }
    }

    private fun notifyConnectionChanged(connected: Boolean) {
        SwingUtilities.invokeLater {
            onConnectionChanged?.invoke(connected)
        }
    }

    private fun extractSessionId(params: kotlinx.serialization.json.JsonElement): String? {
        val obj = params as? JsonObject ?: return null
        obj["sessionId"]?.jsonPrimitive?.contentOrNull?.let { return it }
        obj["session_id"]?.jsonPrimitive?.contentOrNull?.let { return it }
        val updateObj = obj["update"]?.jsonObject
        updateObj?.get("sessionId")?.jsonPrimitive?.contentOrNull?.let { return it }
        updateObj?.get("session_id")?.jsonPrimitive?.contentOrNull?.let { return it }
        return null
    }

    override fun dispose() {
        scope.cancel()
        terminalHandler.disposeAll()
    }
}
