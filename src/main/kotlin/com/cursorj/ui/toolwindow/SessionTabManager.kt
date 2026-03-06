package com.cursorj.ui.toolwindow

import com.cursorj.CursorJBundle
import com.cursorj.acp.AcpSession
import com.cursorj.acp.messages.ConfigOption
import com.cursorj.settings.CursorJSettings
import com.cursorj.ui.chat.ChatPanel
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import kotlinx.coroutines.*
import javax.swing.SwingUtilities

class SessionTabManager(
    private val service: CursorJService,
    private val toolWindow: ToolWindow,
) {
    private val log = Logger.getInstance(SessionTabManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private data class TabEntry(
        val content: Content,
        val chatPanel: ChatPanel,
        var session: AcpSession? = null,
    )

    private val tabs = mutableListOf<TabEntry>()

    fun addInitialTab() {
        val chatPanel = addNewTab()
        chatPanel.showStatus("Connecting to Cursor agent...")

        service.addConnectionListener { success ->
            if (success) {
                chatPanel.showStatus("Connected. Type a message to start.")
            } else {
                chatPanel.showError(
                    service.lastError ?: CursorJBundle.message("error.agent.not.found"),
                )
            }
        }
    }

    fun addNewTab(): ChatPanel {
        val chatPanel = ChatPanel(service)

        val content = ContentFactory.getInstance().createContent(
            chatPanel.component,
            CursorJBundle.message("chat.tab.new"),
            false,
        )
        content.isCloseable = tabs.isNotEmpty()

        val entry = TabEntry(content = content, chatPanel = chatPanel)
        tabs.add(entry)
        toolWindow.contentManager.addContent(content)
        toolWindow.contentManager.setSelectedContent(content)

        chatPanel.onFirstPrompt = { prompt ->
            ensureSessionAndSend(entry, prompt)
        }

        val modelConfig = service.buildModelConfigOption()
        if (modelConfig.isNotEmpty()) {
            chatPanel.updateConfigOptions(modelConfig)
        }

        return chatPanel
    }

    fun closeTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        if (tabs.size <= 1) return

        val entry = tabs.removeAt(index)
        toolWindow.contentManager.removeContent(entry.content, true)
    }

    fun getActiveSession(): AcpSession? {
        val selectedContent = toolWindow.contentManager.selectedContent ?: return null
        return tabs.find { it.content == selectedContent }?.session
    }

    fun saveSessionIds() {
        val sessionIds = tabs.mapNotNull { it.session?.sessionId }
        CursorJSettings.instance.savedSessionIds = sessionIds.toMutableList()
    }

    fun broadcastStatus(message: String) {
        for (tab in tabs) {
            tab.chatPanel.showStatus(message)
        }
    }

    fun broadcastConfigOptions(options: List<ConfigOption>) {
        for (tab in tabs) {
            tab.chatPanel.updateConfigOptions(options)
        }
    }

    private fun ensureSessionAndSend(entry: TabEntry, prompt: String) {
        scope.launch {
            try {
                if (!service.isInitialized) {
                    val ready = CompletableDeferred<Boolean>()
                    service.addConnectionListener { ready.complete(it) }
                    if (!ready.await()) {
                        entry.chatPanel.showError(
                            service.lastError ?: CursorJBundle.message("error.agent.not.found"),
                        )
                        return@launch
                    }
                }

                if (entry.session == null) {
                    entry.session = service.createSession()
                    entry.chatPanel.bindSession(entry.session!!)
                }

                val title = prompt.take(30).let { if (prompt.length > 30) "$it..." else it }
                SwingUtilities.invokeLater {
                    entry.content.displayName = title
                }

                entry.chatPanel.sendPrompt(prompt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to create session or send prompt", e)
                entry.chatPanel.showError(e.message ?: "Unknown error")
            }
        }
    }
}
