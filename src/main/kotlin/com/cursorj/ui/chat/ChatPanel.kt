package com.cursorj.ui.chat

import com.cursorj.CursorJBundle
import com.cursorj.acp.AgentConnection
import com.cursorj.acp.AcpSession
import com.cursorj.acp.SessionMode
import com.cursorj.acp.ToolActivity
import com.cursorj.acp.messages.*
import com.cursorj.rollback.RollbackStatus
import com.cursorj.context.DragDropProvider
import com.cursorj.permissions.PermissionMode
import com.cursorj.settings.CursorJSettings
import com.cursorj.ui.toolwindow.CursorJService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
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
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

class ChatPanel(private val service: CursorJService) : Disposable {
    private val log = Logger.getInstance(ChatPanel::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val rootPanel = JPanel(BorderLayout())
    private val messageListPanel = MessageListPanel()
    private val inputPanel = InputPanel()

    private var connection: AgentConnection? = null
    private var session: AcpSession? = null
    private val attachedFiles = mutableListOf<ResourceLinkContent>()
    private val pendingSelectionQueue = PendingSelectionQueue()
    private var pendingToolCall: Pair<String, ToolActivity>? = null
    private val permissionRequestSeq = AtomicInteger(1)
    private val pendingPermissionResponses = ConcurrentHashMap<String, CompletableFuture<String>>()
    private var desiredMode: SessionMode = SessionMode.AGENT
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
    var tabLabel: JLabel? = null

    val component: JComponent get() = rootPanel

    init {
        val scrollPane = JBScrollPane(messageListPanel.component).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        rootPanel.add(scrollPane, BorderLayout.CENTER)
        rootPanel.add(inputPanel.component, BorderLayout.SOUTH)

        inputPanel.onSend = { text -> handleSend(text) }
        inputPanel.onCancel = { handleCancel() }
        inputPanel.onRollback = { handleRollback() }
        inputPanel.onSelectionChipRemoved = { selectionId -> handleSelectionChipRemoved(selectionId) }
        inputPanel.onModeChanged = { mode -> handleModeChange(mode) }
        inputPanel.onModelChanged = { configId, value -> handleConfigOptionChange(configId, value) }

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

        messageListPanel.onToolCallFileClick = { path ->
            openFileInEditor(path)
        }
    }

    fun updateConfigOptions(options: List<ConfigOption>) {
        SwingUtilities.invokeLater {
            inputPanel.updateConfigOptions(options)
        }
    }

    fun bindConnection(connection: AgentConnection) {
        this.connection = connection
        connection.setPermissionPromptResolver { request ->
            queuePermissionRequest(request)
        }
    }

    fun bindSession(session: AcpSession) {
        this.session = session
        session.addMessageListener { message ->
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

        if (session.configOptions.isNotEmpty()) {
            updateConfigOptions(session.configOptions)
        }
        session.addConfigListener { options ->
            updateConfigOptions(options)
        }

        refreshRollbackAvailability()
    }

    fun sendPrompt(text: String) {
        scope.launch {
            try {
                val s = session ?: return@launch
                if (s.mode != desiredMode) {
                    try {
                        s.setMode(desiredMode)
                    } catch (e: Exception) {
                        log.warn("Failed to set mode before prompt", e)
                    }
                }
                val contentBlocks = buildContentBlocks(text)
                SwingUtilities.invokeLater {
                    inputPanel.setProcessing(true)
                    refreshRollbackAvailability()
                }
                s.sendPrompt(contentBlocks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Error sending prompt", e)
                showError(e.message ?: "Failed to send prompt")
            } finally {
                refreshProjectTreeThrottled(force = true)
                SwingUtilities.invokeLater {
                    commitPendingToolCall()
                    messageListPanel.hideProgress()
                    inputPanel.setProcessing(false)
                    refreshRollbackAvailability()
                    attachedFiles.clear()
                    inputPanel.clearFileChips()
                    clearQueuedSelectionContext()
                    if (desiredMode == SessionMode.PLAN && session?.planCreated == true) {
                        messageListPanel.showBuildButton(
                            onBuild = { handleBuild() },
                            onViewPlan = { handleViewPlan() },
                        )
                    }
                }
            }
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
                clearQueuedSelectionContext()
                inputPanel.setProcessing(false)
                refreshRollbackAvailability()
            }
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
                    showStatus(CursorJBundle.message("chat.rollback.success"))
                    refreshProjectTreeThrottled(force = true)
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

    private fun handleViewPlan() {
        val s = session ?: return
        val basePath = service.project.basePath ?: return

        log.info("View Plan: planContent=${s.planContent.length} chars, thought=${s.thoughtContent.length} chars, toolCallContents=${s.toolCallContents.size}, planEntries=${s.planEntries.size}, messages=${s.messages.size}")

        val planDir = java.io.File(basePath, ".cursorj/plans")
        planDir.mkdirs()
        val planFile = java.io.File(planDir, "plan.md")
        planFile.writeText(buildPlanMarkdown(s))

        val normalizedPath = planFile.absolutePath.replace('\\', '/')
        ApplicationManager.getApplication().invokeLater {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(normalizedPath)
            if (vf != null) {
                FileEditorManager.getInstance(service.project).openFile(vf, true)
            }
        }
    }

    private fun buildPlanMarkdown(session: AcpSession): String {
        val sb = StringBuilder()
        sb.appendLine("# Plan\n")

        // Primary: plan content from _cursor/create_plan
        val planContent = session.planContent.trim()
        if (planContent.isNotEmpty()) {
            sb.appendLine(planContent)
            sb.appendLine()
        }

        // Secondary: agent's thinking (detailed reasoning)
        val thought = session.thoughtContent.trim()
        if (thought.isNotEmpty() && planContent.isEmpty()) {
            sb.appendLine(thought)
            sb.appendLine()
        }

        // Tertiary: tool call content
        val toolContent = session.toolCallContents.values.joinToString("\n\n").trim()
        if (toolContent.isNotEmpty() && planContent.isEmpty() && thought.isEmpty()) {
            sb.appendLine(toolContent)
            sb.appendLine()
        }

        // Final fallback: assistant messages
        if (planContent.isEmpty() && thought.isEmpty() && toolContent.isEmpty()) {
            val assistantText = session.messages
                .filter { it.role == "assistant" }
                .joinToString("\n\n") { it.content.trim() }
            if (assistantText.isNotEmpty()) {
                sb.appendLine(assistantText)
                sb.appendLine()
            }
        }

        // Plan entries as tasks
        val entries = session.planEntries
        if (entries.isNotEmpty()) {
            sb.appendLine("## Tasks\n")
            for (entry in entries) {
                val checkbox = if (entry.status == "completed") "[x]" else "[ ]"
                val priority = if (entry.priority != "medium") " _(${entry.priority})_" else ""
                sb.appendLine("- $checkbox ${entry.content}$priority")
            }
            sb.appendLine()
        }

        return sb.toString()
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
        if (configId == "model") {
            val conn = connection ?: return
            conn.changeModel(value)
            session = null
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
        val basePath = service.project.basePath ?: return
        val candidate = java.io.File(path)
        val resolved = if (candidate.isAbsolute) candidate else java.io.File(basePath, path)
        val normalizedPath = resolved.absolutePath.replace('\\', '/')
        ApplicationManager.getApplication().invokeLater {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(normalizedPath)
            if (vf != null) {
                FileEditorManager.getInstance(service.project).openFile(vf, true)
            }
        }
    }

    private fun buildContentBlocks(text: String): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        blocks.add(TextContent(text = text))

        if (CursorJSettings.instance.autoAttachActiveFile) {
            blocks.addAll(service.activeFileProvider.buildContextBlocks())
        }
        for (file in attachedFiles) {
            blocks.add(file)
        }
        blocks.addAll(pendingSelectionQueue.flattenBlocks())

        return blocks
    }

    private fun clearQueuedSelectionContext() {
        pendingSelectionQueue.clear()
        inputPanel.clearSelectionChip()
    }

    override fun dispose() {
        scope.cancel()
        pendingPermissionResponses.values.forEach { future ->
            if (!future.isDone) future.complete("reject-once")
        }
        pendingPermissionResponses.clear()
    }
}
