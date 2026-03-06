package com.cursorj.ui.chat

import com.cursorj.acp.AgentConnection
import com.cursorj.acp.AcpSession
import com.cursorj.acp.SessionMode
import com.cursorj.acp.messages.ConfigOption
import com.cursorj.acp.messages.ContentBlock
import com.cursorj.acp.messages.ResourceLinkContent
import com.cursorj.acp.messages.TextContent
import com.cursorj.context.DragDropProvider
import com.cursorj.settings.CursorJSettings
import com.cursorj.ui.toolwindow.CursorJService
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

class ChatPanel(private val service: CursorJService) {
    private val log = Logger.getInstance(ChatPanel::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val rootPanel = JPanel(BorderLayout())
    private val messageListPanel = MessageListPanel()
    private val inputPanel = InputPanel()

    private var connection: AgentConnection? = null
    private var session: AcpSession? = null
    private val attachedFiles = mutableListOf<ResourceLinkContent>()

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
    }

    fun updateConfigOptions(options: List<ConfigOption>) {
        SwingUtilities.invokeLater {
            inputPanel.updateConfigOptions(options)
        }
    }

    fun bindConnection(connection: AgentConnection) {
        this.connection = connection
    }

    fun bindSession(session: AcpSession) {
        this.session = session
        session.addMessageListener { message ->
            SwingUtilities.invokeLater {
                messageListPanel.updateOrAddMessage(message)
                if (!message.isStreaming && session.isProcessing) {
                    messageListPanel.showProgress()
                }
            }
        }

        if (session.configOptions.isNotEmpty()) {
            updateConfigOptions(session.configOptions)
        }
        session.addConfigListener { options ->
            updateConfigOptions(options)
        }
    }

    fun sendPrompt(text: String) {
        scope.launch {
            try {
                val contentBlocks = buildContentBlocks(text)
                SwingUtilities.invokeLater {
                    inputPanel.setProcessing(true)
                }
                session?.sendPrompt(contentBlocks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Error sending prompt", e)
                showError(e.message ?: "Failed to send prompt")
            } finally {
                SwingUtilities.invokeLater {
                    messageListPanel.hideProgress()
                    inputPanel.setProcessing(false)
                    attachedFiles.clear()
                    inputPanel.clearFileChips()
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
                inputPanel.setProcessing(false)
            }
        }
    }

    private fun handleModeChange(mode: SessionMode) {
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

    private fun buildContentBlocks(text: String): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        blocks.add(TextContent(text = text))

        if (CursorJSettings.instance.autoAttachActiveFile) {
            blocks.addAll(service.activeFileProvider.buildContextBlocks())
        }
        for (file in attachedFiles) {
            blocks.add(file)
        }

        return blocks
    }
}
