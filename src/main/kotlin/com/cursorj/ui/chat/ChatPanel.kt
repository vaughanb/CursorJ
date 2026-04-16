package com.cursorj.ui.chat

import com.cursorj.CursorJBundle
import com.cursorj.acp.AcpException
import com.cursorj.acp.AgentConnection
import com.cursorj.acp.AcpSession
import com.cursorj.acp.ConfigOptionUiSupport
import com.cursorj.acp.MaxModeManager
import com.cursorj.acp.ChatMessage
import com.cursorj.acp.SessionMode
import com.cursorj.acp.ToolActivity
import com.cursorj.acp.messages.*
import com.cursorj.rollback.RollbackStatus
import com.cursorj.context.DragDropProvider
import com.cursorj.permissions.PermissionMode
import com.cursorj.settings.CursorJSettings
import com.cursorj.ui.statusbar.CursorJConnectionStatus
import com.cursorj.ui.toolwindow.CursorJService
import com.cursorj.ui.util.EditorInsertedDiffHighlight
import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.Color
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

class ChatPanel(
    private val service: CursorJService,
    initialHistorySessionKey: String,
) : Disposable {
    private data class PromptPayload(
        val contentBlocks: List<ContentBlock>,
        val displayUserText: String,
    )

    private val log = Logger.getInstance(ChatPanel::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val rootPanel = JPanel(BorderLayout())
    private val messageListPanel = MessageListPanel()
    private val inputPanel = InputPanel()
    private val messageQueuePanel = MessageQueuePanel()
    private val statusLabel = JLabel(CursorJConnectionStatus.text).apply {
        font = font.deriveFont(font.size2D - 2)
        foreground = JBColor(Color(0x999999), Color(0x707070))
        border = JBUI.Borders.empty(2, 12, 4, 12)
    }
    private val statusListener: () -> Unit = {
        SwingUtilities.invokeLater { statusLabel.text = CursorJConnectionStatus.text }
    }
    private var historySessionKey: String = initialHistorySessionKey

    private var connection: AgentConnection? = null
    private var session: AcpSession? = null
    private val attachedFiles = mutableListOf<ResourceLinkContent>()
    private val pendingSelectionQueue = PendingSelectionQueue()
    private var pendingToolCall: Pair<String, ToolActivity>? = null
    private val permissionRequestSeq = AtomicInteger(1)
    private val chatThemeRefreshScheduled = AtomicBoolean(false)
    private val pendingPermissionResponses = ConcurrentHashMap<String, CompletableFuture<String>>()
    private var desiredMode: SessionMode = SessionMode.AGENT
    private var activeHighlighters = mutableListOf<Pair<Editor, RangeHighlighter>>()
    private var firstPromptCarryoverContext: String? = null
    private var pendingModelSwitchContext: String? = null
    private var lastProjectTreeRefreshAt = 0L
    private val minRefreshIntervalMs = 750L

    private val agentProgressColor = JBColor(Color(0x6B9BD2), Color(0x6B9BD2))
    private val planProgressColor = JBColor(Color(0xD4A017), Color(0xD4A017))
    private val askProgressColor = JBColor(Color(0x4CAF50), Color(0x6BC46D))

    private fun modeProgressColor(): Color = when (desiredMode) {
        SessionMode.PLAN -> planProgressColor
        SessionMode.ASK -> askProgressColor
        SessionMode.AGENT -> agentProgressColor
    }

    var onFirstPrompt: ((String) -> Unit)? = null
    var onPromptSubmitted: ((String) -> Unit)? = null
    var onSessionReplaced: ((AcpSession) -> Unit)? = null
    /** Called after MAX mode is written to cli-config.json so the tab can reconnect the agent. */
    var onReconnectRequested: (() -> Unit)? = null
    val component: JComponent get() = rootPanel

    init {
        val scrollPane = JBScrollPane(messageListPanel.component).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        val southPanel = JPanel(BorderLayout()).apply {
            add(messageQueuePanel.component, BorderLayout.NORTH)
            add(inputPanel.component, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        }
        rootPanel.add(scrollPane, BorderLayout.CENTER)
        rootPanel.add(southPanel, BorderLayout.SOUTH)

        CursorJConnectionStatus.addListener(statusListener)

        inputPanel.onSend = { text -> handleSend(text) }
        inputPanel.onCancel = { handleCancel() }
        inputPanel.onQueueMessage = { text -> handleQueueMessage(text) }
        inputPanel.onRollback = { handleRollback() }
        inputPanel.onSelectionChipRemoved = { selectionId -> handleSelectionChipRemoved(selectionId) }
        inputPanel.onModeChanged = { mode -> handleModeChange(mode) }
        inputPanel.onConfigOptionChanged = { configId, value -> handleConfigOptionChange(configId, value) }
        inputPanel.onMaxModeToggled = { enabled -> handleMaxModeToggle(enabled) }
        inputPanel.onHistoryPrev = { currentInput ->
            service.promptHistoryManager.previous(historySessionKey, currentInput)
        }
        inputPanel.onHistoryNext = { currentInput ->
            service.promptHistoryManager.next(historySessionKey, currentInput)
        }

        messageQueuePanel.onRemove = { index -> handleQueueRemove(index) }
        messageQueuePanel.onEdit = { index, newText -> handleQueueEdit(index, newText) }
        messageQueuePanel.onSendNow = { index -> handleQueueSendNow(index) }

        val dragDrop = DragDropProvider { blocks ->
            for (block in blocks) {
                if (block is ResourceLinkContent) {
                    attachedFiles.add(block)
                    inputPanel.addFileChip(block.name ?: block.uri)
                }
            }
        }
        dragDrop.install(rootPanel)
        dragDrop.install(inputPanel.component)
        dragDrop.install(inputPanel.dropTargetComponent)

        installThemeChangeListeners()

        messageListPanel.onToolCallFileClick = { path ->
            openFileInEditor(path)
        }
        messageListPanel.onDiffFileClick = { path, line, addedLines ->
            openFileInEditorWithHighlights(path, line, addedLines)
        }

        SwingUtilities.invokeLater {
            inputPanel.setMaxMode(MaxModeManager.isMaxModeEnabled())
        }
    }

    fun updateConfigOptions(options: List<ConfigOption>) {
        SwingUtilities.invokeLater {
            inputPanel.updateConfigOptions(options)
        }
    }

    /**
     * Updates the MAX checkbox to match a value read from disk without firing the user toggle callback
     * (used when `cli-config.json` changes outside CursorJ).
     */
    fun applyMaxModeFromDisk(enabled: Boolean) {
        SwingUtilities.invokeLater {
            inputPanel.setMaxMode(enabled)
        }
    }

    fun bindConnection(connection: AgentConnection) {
        this.connection = connection
        inputPanel.setMaxMode(MaxModeManager.isMaxModeEnabled())
        connection.setPermissionPromptResolver { request ->
            queuePermissionRequest(request)
        }
    }

    /**
     * Clears connection/session refs before disposing [AgentConnection] for an agent process restart.
     */
    fun prepareForAgentReconnect() {
        connection = null
        session = null
    }

    /**
     * Reloads the message list from the local transcript store (used after agent reconnect).
     * [afterReload] runs on the EDT immediately after the list is rebuilt (e.g. to show a status line
     * that would otherwise be cleared when the transcript UI is replaced).
     */
    fun reloadConversationFromLocalTranscript(afterReload: (() -> Unit)? = null) {
        val messages = sliceToLastUserTurn(
            service.chatTranscriptManager.transcriptFor(historySessionKey),
        )
        SwingUtilities.invokeLater {
            messageListPanel.replaceConversation(messages)
            refreshRollbackAvailability()
            afterReload?.invoke()
        }
    }

    private fun handleMaxModeToggle(enabled: Boolean) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                MaxModeManager.setMaxMode(enabled)
            }
            SwingUtilities.invokeLater {
                if (!ok) {
                    inputPanel.setMaxMode(!enabled)
                    showError(CursorJBundle.message("chat.maxmode.writeFailed"))
                    return@invokeLater
                }
                showStatus(CursorJBundle.message("chat.maxmode.reconnecting"))
                onReconnectRequested?.invoke()
            }
        }
    }

    fun bindSession(session: AcpSession) {
        this.session = session
        val existingMessages = session.messages.filter { !it.isStreaming && it.content.isNotBlank() }
        if (existingMessages.isNotEmpty()) {
            service.chatTranscriptManager.replaceSession(historySessionKey, existingMessages)
            firstPromptCarryoverContext = null
        }
        SwingUtilities.invokeLater {
            if (existingMessages.isNotEmpty()) {
                messageListPanel.replaceConversation(existingMessages)
            }
            refreshRollbackAvailability()
        }
        session.addMessageListener { message ->
            if (!message.isStreaming) {
                service.chatTranscriptManager.addMessage(historySessionKey, message)
            }
            if (message.role == "user") return@addMessageListener
            SwingUtilities.invokeLater {
                if (message.isStreaming) {
                    commitPendingToolCall()
                }
                messageListPanel.updateOrAddMessage(message)
                if (!message.isStreaming && session.isProcessing) {
                    messageListPanel.showProgress(color = modeProgressColor())
                }
                refreshRollbackAvailability()
            }
        }

        session.addActivityListener { activity ->
            SwingUtilities.invokeLater {
                messageListPanel.updateProgressText(activity)
                messageListPanel.updateProgressColor(modeProgressColor())
            }
        }

        session.addToolCallListener { id, activity ->
            SwingUtilities.invokeLater {
                if (activity.kind == "edit") {
                    if (activity.path != null) refreshProjectTreeThrottled()
                    refreshRollbackAvailability()
                    return@invokeLater
                }
                val pending = pendingToolCall
                if (pending != null && pending.first != id) {
                    messageListPanel.addOrUpdateToolCallLine(
                        pending.first,
                        pending.second.text,
                        pending.second.path,
                    )
                }
                pendingToolCall = id to activity
                if (activity.path != null) {
                    refreshProjectTreeThrottled()
                }
                refreshRollbackAvailability()
            }
        }

        session.addEditDiffListener { diff ->
            SwingUtilities.invokeLater {
                messageListPanel.addDiffPanel(diff.path, diff.oldText, diff.newText)
            }
        }

        updateConfigOptions(session.configOptions)
        session.addConfigListener { options ->
            updateConfigOptions(options)
        }

        refreshRollbackAvailability()
    }

    fun sendPrompt(text: String) {
        onPromptSubmitted?.invoke(text)
        SwingUtilities.invokeLater {
            val userMessage = ChatMessage(role = "user", content = text)
            messageListPanel.updateOrAddMessage(userMessage)
            messageListPanel.showProgress(color = modeProgressColor())
            inputPanel.setProcessing(true)
            refreshRollbackAvailability()
        }
        scope.launch {
            try {
                var s = session
                if (s == null) {
                    val conn = connection
                    if (conn == null || !conn.isConnected) {
                        showError("Not connected. Please wait for the connection to complete.")
                        return@launch
                    }
                    s = conn.createSession()
                    session = s
                    bindSession(s)
                    onSessionReplaced?.invoke(s)
                }
                if (s.mode != desiredMode) {
                    try {
                        s.setMode(desiredMode)
                    } catch (e: Exception) {
                        log.warn("Failed to set mode before prompt", e)
                    }
                }
                val carryoverContext = firstPromptCarryoverContext
                val promptPayload = buildPromptPayload(text, carryoverContext)
                s.sendPrompt(promptPayload.contentBlocks, promptPayload.displayUserText)
                if (!carryoverContext.isNullOrBlank()) {
                    firstPromptCarryoverContext = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isContextExhausted(e)) {
                    recoverFromContextExhaustion(text)
                } else {
                    log.warn("Error sending prompt", e)
                    showError(e.message ?: "Failed to send prompt")
                }
            } finally {
                refreshProjectTreeThrottled(force = true)
                SwingUtilities.invokeLater {
                    commitPendingToolCall()
                    messageListPanel.hideProgress()
                    refreshRollbackAvailability()
                    attachedFiles.clear()
                    inputPanel.clearFileChips()
                    clearQueuedSelectionContext()
                    if (desiredMode == SessionMode.PLAN &&
                        (session?.planCreated == true || !session?.agentWrittenPlanPath.isNullOrBlank())
                    ) {
                        messageListPanel.showBuildButton(
                            onBuild = { handleBuild() },
                            onViewPlan = { handleViewPlan() },
                        )
                    }
                    drainNextQueuedMessage()
                }
            }
        }
    }

    private fun isContextExhausted(e: Exception): Boolean {
        val message = e.message?.lowercase() ?: return false
        return message.contains("resource_exhausted") ||
            message.contains("context_length_exceeded") ||
            message.contains("maximum context length")
    }

    private suspend fun recoverFromContextExhaustion(originalPromptText: String) {
        val conn = connection
        if (conn == null || !conn.isConnected) {
            showError("Context limit reached but no active connection for recovery.")
            return
        }

        log.info("Context exhausted — recovering with new session and conversation summary")
        showStatus("Context limit reached. Continuing in a new session\u2026")

        try {
            val carryover = service.chatTranscriptManager.buildCarryoverContext(historySessionKey)
            val newSession = conn.createSession()

            session = newSession
            bindSession(newSession)

            if (newSession.mode != desiredMode) {
                try { newSession.setMode(desiredMode) } catch (_: Exception) {}
            }

            onSessionReplaced?.invoke(newSession)

            val retryPromptPayload = buildPromptPayload(originalPromptText, carryover)
            SwingUtilities.invokeLater {
                inputPanel.setProcessing(true)
            }
            newSession.sendPrompt(retryPromptPayload.contentBlocks, retryPromptPayload.displayUserText)
        } catch (retryEx: CancellationException) {
            throw retryEx
        } catch (retryEx: Exception) {
            log.warn("Recovery after context exhaustion failed", retryEx)
            showError("Context recovery failed: ${retryEx.message}")
        }
    }

    fun showError(message: String) {
        SwingUtilities.invokeLater {
            messageListPanel.addErrorMessage(message)
        }
    }

    fun showStatus(message: String) {
        SwingUtilities.invokeLater {
            messageListPanel.addStatusMessage(message)
        }
    }

    private fun handleSend(text: String) {
        if (text.isBlank()) return
        service.promptHistoryManager.addPrompt(historySessionKey, text)
        service.promptHistoryManager.clearNavigation(historySessionKey)
        if (session == null) {
            onFirstPrompt?.invoke(text)
        } else {
            sendPrompt(text)
        }
    }

    private fun handleCancel() {
        scope.launch {
            session?.cancel()
            SwingUtilities.invokeLater {
                commitPendingToolCall()
                messageListPanel.hideProgress()
                clearQueuedSelectionContext()
                messageQueuePanel.clear()
                attachedFiles.clear()
                inputPanel.clearFileChips()
                inputPanel.setProcessing(false)
                service.promptHistoryManager.clearNavigation(historySessionKey)
                refreshRollbackAvailability()
            }
        }
    }

    private fun handleQueueMessage(text: String) {
        if (text.isBlank()) return
        service.promptHistoryManager.addPrompt(historySessionKey, text)
        messageQueuePanel.addEntry(text)
    }

    private fun handleQueueRemove(index: Int) {
        messageQueuePanel.removeEntry(index)
    }

    private fun handleQueueEdit(index: Int, newText: String) {
        messageQueuePanel.updateEntry(index, newText)
    }

    private fun handleQueueSendNow(index: Int) {
        val text = messageQueuePanel.removeAndGet(index) ?: return
        scope.launch {
            session?.cancel()
            SwingUtilities.invokeLater {
                commitPendingToolCall()
                messageListPanel.hideProgress()
                refreshRollbackAvailability()
                attachedFiles.clear()
                inputPanel.clearFileChips()
                clearQueuedSelectionContext()
                service.promptHistoryManager.clearNavigation(historySessionKey)
                sendPrompt(text)
            }
        }
    }

    private fun drainNextQueuedMessage() {
        val nextText = messageQueuePanel.dequeue()
        if (nextText != null) {
            service.promptHistoryManager.clearNavigation(historySessionKey)
            sendPrompt(nextText)
        } else {
            inputPanel.setProcessing(false)
        }
    }

    fun updateHistorySessionKey(sessionKey: String) {
        historySessionKey = sessionKey
        service.promptHistoryManager.clearNavigation(historySessionKey)
    }

    fun applyLocalTranscriptFallback(messages: List<ChatMessage>, carryoverContext: String?) {
        if (messages.isEmpty()) return
        firstPromptCarryoverContext = carryoverContext
        SwingUtilities.invokeLater {
            messageListPanel.replaceConversation(messages)
            refreshRollbackAvailability()
        }
    }

    fun queueSelectionContext(label: String, blocks: List<ContentBlock>) {
        val selection = pendingSelectionQueue.add(label, blocks) ?: return
        inputPanel.addSelectionChip(selection.id, selection.label)
        showStatus("${selection.label} added to CursorJ chat context.")
    }

    private fun handleSelectionChipRemoved(selectionId: String) {
        val removed = pendingSelectionQueue.remove(selectionId) ?: return
        showStatus("${removed.label} removed from CursorJ chat context.")
    }

    private fun handleRollback() {
        val s = session ?: return
        val confirmed = Messages.showYesNoDialog(
            service.project,
            CursorJBundle.message("chat.rollback.confirm.message"),
            CursorJBundle.message("chat.rollback.confirm.title"),
            Messages.getWarningIcon(),
        ) == Messages.YES
        if (!confirmed) return

        scope.launch {
            val result = s.rollbackLastTurn()
            when (result.status) {
                RollbackStatus.SUCCESS -> {
                    clearDiffHighlights()
                    showStatus(CursorJBundle.message("chat.rollback.success"))
                    refreshProjectTreeThrottled(force = true)
                    service.workspaceIndexOrchestrator.notifyRollback()
                }
                RollbackStatus.NO_CHECKPOINT -> {
                    showStatus(CursorJBundle.message("chat.rollback.unavailable"))
                }
                RollbackStatus.PROCESSING, RollbackStatus.IN_PROGRESS -> {
                    showStatus(CursorJBundle.message("chat.rollback.inProgress"))
                }
                RollbackStatus.INTERRUPTED -> {
                    showError(CursorJBundle.message("chat.rollback.interrupted"))
                }
                RollbackStatus.FAILED -> {
                    showError(
                        CursorJBundle.message(
                            "chat.rollback.failed",
                            result.errorMessage ?: "Unknown error",
                        ),
                    )
                }
            }
            SwingUtilities.invokeLater { refreshRollbackAvailability() }
        }
    }

    private fun commitPendingToolCall() {
        pendingToolCall?.let { (id, activity) ->
            messageListPanel.addOrUpdateToolCallLine(id, activity.text, activity.path)
        }
        pendingToolCall = null
    }

    private fun handleBuild() {
        messageListPanel.hideBuildButton()
        desiredMode = SessionMode.AGENT
        inputPanel.setMode(SessionMode.AGENT)
        sendPrompt("Implement the plan above.")
    }

    /**
     * Opens the same plan file the agent uses (often under user home `~/.cursor/plans/` or workspace
     * `.cursor/plans/`), not a duplicate under `.cursorj/plans/`.
     */
    private fun handleViewPlan() {
        val s = session ?: return
        val basePath = service.project.basePath ?: return

        log.info("View Plan: recorded=${s.agentWrittenPlanPath}, hint=${s.agentPlanPathHint}")

        val path = resolvePlanFileToOpen(basePath, s)
        if (path == null) {
            SwingUtilities.invokeLater {
                showStatus(CursorJBundle.message("chat.plan.view.missing"))
            }
            return
        }

        val normalizedPath = path.replace('\\', '/')
        ApplicationManager.getApplication().invokeLater {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(normalizedPath)
            if (vf != null) {
                FileEditorManager.getInstance(service.project).openFile(vf, true)
            } else {
                showStatus(CursorJBundle.message("chat.plan.view.missing"))
            }
        }
    }

    private fun resolvePlanFileToOpen(basePath: String, session: AcpSession): String? {
        session.agentWrittenPlanPath?.let { stored ->
            val file = resolveUserPath(basePath, stored)
            if (file.isFile) return file.absolutePath
        }
        session.agentPlanPathHint?.let { hint ->
            val file = resolveUserPath(basePath, hint)
            if (file.isFile) return file.absolutePath
        }
        return findNewestWorkspaceCursorPlan(basePath)
    }

    private fun resolveUserPath(basePath: String, path: String): java.io.File {
        val f = java.io.File(path)
        return if (f.isAbsolute) f else java.io.File(basePath, path)
    }

    private fun findNewestWorkspaceCursorPlan(basePath: String): String? {
        val dir = java.io.File(basePath, ".cursor/plans")
        if (!dir.isDirectory) return null
        return dir.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".md", ignoreCase = true) || it.name.endsWith(".plan.md", ignoreCase = true)) }
            ?.maxByOrNull { it.lastModified() }
            ?.absolutePath
    }

    private fun handleModeChange(mode: SessionMode) {
        desiredMode = mode
        scope.launch {
            try {
                session?.setMode(mode)
            } catch (e: Exception) {
                log.warn("Failed to change mode", e)
            }
        }
    }

    private fun handleConfigOptionChange(configId: String, value: String) {
        scope.launch {
            try {
                val currentSession = session ?: return@launch
                if (ConfigOptionUiSupport.isModelConfigId(configId)) {
                    val conn = connection ?: return@launch
                    val targetModel = value.trim()
                    if (targetModel.isBlank()) return@launch

                    log.info(
                        "model-switch: phase=request sessionId=${currentSession.sessionId} " +
                            "requested=$targetModel",
                    )
                    currentSession.setConfigOption(configId, targetModel)
                    val confirmedModel = currentSession.configOptions
                        .firstOrNull { ConfigOptionUiSupport.isModelSelector(it) }
                        ?.currentValue
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                    if (confirmedModel != null) {
                        conn.setSelectedModel(confirmedModel)
                    }
                    log.info(
                        "model-switch: phase=confirmed sessionId=${currentSession.sessionId} " +
                            "requested=$targetModel confirmed=${confirmedModel ?: "<missing>"}",
                    )
                    val displayName = conn.selectedModelDisplayName() ?: confirmedModel ?: targetModel
                    pendingModelSwitchContext = "The active model has been switched to: $displayName"
                    val connectedDetail = conn.connectedStatusDetail() ?: "Connected"
                    showStatus("$connectedDetail. Model switched.")
                    return@launch
                }
                currentSession.setConfigOption(configId, value)
            } catch (e: Exception) {
                log.warn("setConfigOption failed", e)
                val msg = e.message ?: e.javaClass.simpleName
                SwingUtilities.invokeLater {
                    showStatus(CursorJBundle.message("chat.config.option.failed", msg))
                }
            }
        }
    }

    private fun enableRunEverything(): Boolean {
        val settings = CursorJSettings.instance
        val currentMode = PermissionMode.fromId(settings.permissionMode)
        if (currentMode == PermissionMode.RUN_EVERYTHING) return true

        if (!settings.runEverythingConfirmationAcknowledged) {
            val confirmed = Messages.showYesNoDialog(
                service.project,
                CursorJBundle.message("permission.mode.confirm.enable.message"),
                CursorJBundle.message("permission.mode.confirm.enable.title"),
                Messages.getWarningIcon(),
            ) == Messages.YES
            if (!confirmed) {
                return false
            }
            settings.runEverythingConfirmationAcknowledged = true
        }

        settings.permissionMode = PermissionMode.RUN_EVERYTHING.id
        showStatus(CursorJBundle.message("permission.mode.status.runEverything"))
        return true
    }

    private fun queuePermissionRequest(request: RequestPermissionParams): CompletableFuture<String> {
        val requestId = "permission-${permissionRequestSeq.getAndIncrement()}"
        val response = CompletableFuture<String>()
        pendingPermissionResponses[requestId] = response
        response.whenComplete { _, _ -> pendingPermissionResponses.remove(requestId) }
        SwingUtilities.invokeLater {
            messageListPanel.addPermissionRequestCard(
                requestId = requestId,
                request = request,
                onDecision = { optionId ->
                    if (!response.isDone) {
                        response.complete(optionId)
                    }
                },
                onRunEverything = { enableRunEverything() },
            )
        }
        return response
    }

    private fun refreshProjectTree() {
        ApplicationManager.getApplication().invokeLater {
            service.project.basePath?.let { basePath ->
                LocalFileSystem.getInstance().findFileByPath(basePath.replace('\\', '/'))
                    ?.refresh(false, true)
            }
        }
    }

    private fun refreshProjectTreeThrottled(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastProjectTreeRefreshAt) < minRefreshIntervalMs) return
        lastProjectTreeRefreshAt = now
        refreshProjectTree()
    }

    private fun refreshRollbackAvailability() {
        val canRollback = session?.canRollbackLastTurn() == true
        inputPanel.setRollbackEnabled(canRollback)
    }

    private fun openFileInEditor(path: String) {
        openFileInEditorAtLine(path, -1)
    }

    private fun openFileInEditorAtLine(path: String, line: Int) {
        openFileInEditorWithHighlights(path, line, emptyList())
    }

    private fun openFileInEditorWithHighlights(path: String, line: Int, addedLines: List<Int>) {
        val basePath = service.project.basePath ?: return
        val candidate = java.io.File(path)
        val resolved = if (candidate.isAbsolute) candidate else java.io.File(basePath, path)
        val normalizedPath = resolved.absolutePath.replace('\\', '/')
        ApplicationManager.getApplication().invokeLater {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(normalizedPath) ?: return@invokeLater
            val editors = FileEditorManager.getInstance(service.project).openFile(vf, true)
            val textEditor = editors.filterIsInstance<TextEditor>().firstOrNull()
            val editor = textEditor?.editor ?: return@invokeLater

            if (line > 0) {
                val offset = editor.document.getLineStartOffset((line - 1).coerceIn(0, editor.document.lineCount - 1))
                editor.caretModel.moveToOffset(offset)
                editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            }

            clearDiffHighlights()
            if (addedLines.isNotEmpty()) {
                applyDiffHighlights(editor, addedLines)
            }
        }
    }

    private fun applyDiffHighlights(editor: Editor, addedLines: List<Int>) {
        val attrs = EditorInsertedDiffHighlight.attributesForScheme(editor.colorsScheme)
        val lineCount = editor.document.lineCount
        val markup = editor.markupModel

        for (lineIdx in addedLines) {
            if (lineIdx < 0 || lineIdx >= lineCount) continue
            val highlighter = markup.addLineHighlighter(
                lineIdx,
                HighlighterLayer.SELECTION - 1,
                attrs,
            )
            highlighter.isGreedyToRight = true
            activeHighlighters.add(editor to highlighter)
        }
    }

    private fun clearDiffHighlights() {
        for ((editor, highlighter) in activeHighlighters) {
            try {
                editor.markupModel.removeHighlighter(highlighter)
            } catch (_: Exception) {
                // editor may have been disposed
            }
        }
        activeHighlighters.clear()
    }

    private suspend fun buildPromptPayload(text: String, carryoverContext: String?): PromptPayload {
        val visibleBlocks = mutableListOf<ContentBlock>()
        if (!carryoverContext.isNullOrBlank()) {
            visibleBlocks.add(TextContent("Context from a previous chat:\n\n$carryoverContext\n\nCurrent prompt:"))
        }
        if (desiredMode == SessionMode.PLAN) {
            visibleBlocks.add(TextContent(text = CursorJBundle.message("chat.plan.promptPrefix")))
        }
        visibleBlocks.add(TextContent(text = text))

        if (CursorJSettings.instance.autoAttachActiveFile) {
            visibleBlocks.addAll(service.activeFileProvider.buildContextBlocks())
        }
        for (file in attachedFiles) {
            visibleBlocks.add(file)
        }
        visibleBlocks.addAll(pendingSelectionQueue.flattenBlocks())
        visibleBlocks.addAll(buildRetrievedContext(text))

        val hiddenRuleBlocks = mutableListOf<ContentBlock>()
        val modelContext = pendingModelSwitchContext
        if (!modelContext.isNullOrBlank()) {
            hiddenRuleBlocks.add(TextContent(text = modelContext))
            pendingModelSwitchContext = null
        }
        val settings = CursorJSettings.instance
        val rulesText = settings.getGlobalUserRules().joinToString("\n\n")
        if (rulesText.isNotBlank()) {
            hiddenRuleBlocks.add(
                TextContent(
                    text = "Global user rules (hidden from chat display; always apply):\n\n$rulesText",
                ),
            )
        }

        val displayUserText = when {
            !carryoverContext.isNullOrBlank() -> text
            desiredMode == SessionMode.PLAN -> text
            else -> visibleBlocks
                .filterIsInstance<TextContent>()
                .joinToString(" ") { it.text }
        }

        return PromptPayload(
            contentBlocks = hiddenRuleBlocks + visibleBlocks,
            displayUserText = displayUserText,
        )
    }

    private suspend fun buildRetrievedContext(text: String): List<ContentBlock> {
        val settings = CursorJSettings.instance
        if (!settings.enableProjectIndexing) return emptyList()
        if (service.project.isDisposed) return emptyList()

        val queryText = text.take(RETRIEVAL_QUERY_MAX_CHARS)
        val openFiles = FileEditorManager.getInstance(service.project).openFiles
            .map { it.path.replace('\\', '/') }
        val pathHint = service.activeFileProvider.activeFile?.path
        val retrieval = runCatching {
            service.workspaceIndexOrchestrator.retrieveForPrompt(
                text = queryText,
                pathHint = pathHint,
                openFiles = openFiles,
            )
        }.getOrElse { e ->
            log.debug("Failed to retrieve indexed context for prompt", e)
            return emptyList()
        }
        if (retrieval.hits.isEmpty()) return emptyList()

        var remainingBudget = settings.retrievalSnippetCharBudget
        val selectedHits = mutableListOf<com.cursorj.indexing.model.RetrievalHit>()
        for (hit in retrieval.hits) {
            if (remainingBudget <= 0) break
            val cost = hit.snippet.length + 120
            if (cost > remainingBudget && selectedHits.isNotEmpty()) break
            selectedHits.add(hit)
            remainingBudget -= cost
        }
        if (selectedHits.isEmpty()) return emptyList()

        val mergedText = buildString {
            appendLine("Indexed project context:")
            for ((index, hit) in selectedHits.withIndex()) {
                appendLine()
                appendLine("[$index] ${hit.path}:${hit.startLine}-${hit.endLine} (${hit.source}, score=${"%.2f".format(hit.score)})")
                appendLine("```")
                appendLine(hit.snippet)
                appendLine("```")
            }
        }
        return listOf(TextContent(mergedText))
    }

    private fun clearQueuedSelectionContext() {
        pendingSelectionQueue.clear()
        inputPanel.clearSelectionChip()
    }

    private fun installThemeChangeListeners() {
        val conn = service.project.messageBus.connect(this)
        conn.subscribe(
            LafManagerListener.TOPIC,
            object : LafManagerListener {
                override fun lookAndFeelChanged(source: LafManager) {
                    scheduleChatThemeRefresh()
                }
            },
        )
        conn.subscribe(
            EditorColorsManager.TOPIC,
            object : EditorColorsListener {
                override fun globalSchemeChange(scheme: EditorColorsScheme?) {
                    scheduleChatThemeRefresh()
                }
            },
        )
    }

    private fun scheduleChatThemeRefresh() {
        if (!chatThemeRefreshScheduled.compareAndSet(false, true)) {
            return
        }
        SwingUtilities.invokeLater {
            try {
                messageListPanel.refreshEmbeddedHtmlForTheme()
            } finally {
                chatThemeRefreshScheduled.set(false)
            }
        }
    }

    override fun dispose() {
        CursorJConnectionStatus.removeListener(statusListener)
        clearDiffHighlights()
        scope.cancel()
        pendingPermissionResponses.values.forEach { future ->
            if (!future.isDone) future.complete("reject-once")
        }
        pendingPermissionResponses.clear()
    }

    companion object {
        private const val RETRIEVAL_QUERY_MAX_CHARS = 500
    }
}
