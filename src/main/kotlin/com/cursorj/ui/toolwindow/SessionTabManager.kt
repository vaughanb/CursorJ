package com.cursorj.ui.toolwindow

import com.cursorj.CursorJBundle
import com.cursorj.acp.AgentConnection
import com.cursorj.acp.AcpSession
import com.cursorj.ui.chat.ChatPanel
import com.cursorj.ui.statusbar.CursorJConnectionStatus
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.ChangeListener

class SessionTabManager(
    private val service: CursorJService,
    private val toolWindow: ToolWindow,
) {
    private val log = Logger.getInstance(SessionTabManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class TabEntry(
        val chatPanel: ChatPanel,
        var connection: AgentConnection? = null,
        var session: AcpSession? = null,
        var title: String = CursorJBundle.message("chat.tab.new"),
    ) : Disposable {
        override fun dispose() {
            connection?.let { Disposer.dispose(it) }
        }
    }

    private val tabs = mutableListOf<TabEntry>()
    private val tabbedPane = JTabbedPane(JTabbedPane.TOP)
    private val rootPanel = JPanel(BorderLayout())
    private val addTabPlaceholder = JPanel()
    private var suppressTabChange = false

    init {
        rootPanel.add(tabbedPane, BorderLayout.CENTER)

        tabbedPane.addChangeListener(ChangeListener {
            if (suppressTabChange) return@ChangeListener
            val idx = tabbedPane.selectedIndex
            val plusIdx = tabbedPane.tabCount - 1
            if (idx >= 0 && idx == plusIdx && tabbedPane.tabCount > 1) {
                SwingUtilities.invokeLater { addNewTab() }
            } else {
                updateStatusBar()
            }
        })
    }

    private fun appendPlusTab() {
        tabbedPane.addTab("", AllIcons.General.Add, addTabPlaceholder, "New chat")
    }

    fun addInitialTab() {
        val content = ContentFactory.getInstance().createContent(rootPanel, "", false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
        appendPlusTab()
        addNewTab()
    }

    fun addNewTab(): ChatPanel {
        val chatPanel = ChatPanel(service)
        val entry = TabEntry(chatPanel = chatPanel)
        Disposer.register(toolWindow.disposable, entry)
        tabs.add(entry)

        suppressTabChange = true
        val insertIndex = tabbedPane.tabCount - 1
        tabbedPane.insertTab(entry.title, null, chatPanel.component, null, insertIndex)
        tabbedPane.setTabComponentAt(insertIndex, createTabComponent(entry))
        tabbedPane.selectedIndex = insertIndex
        suppressTabChange = false

        chatPanel.onFirstPrompt = { prompt ->
            ensureConnectionAndSend(entry, prompt)
        }

        connectEagerly(entry)

        return chatPanel
    }

    private fun createTabComponent(entry: TabEntry): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
        }

        val label = JLabel(entry.title).apply {
            border = JBUI.Borders.emptyRight(6)
        }

        val closeButton = JLabel(AllIcons.Actions.Close).apply {
            toolTipText = "Close tab"
            border = JBUI.Borders.empty(1)
            preferredSize = Dimension(16, 16)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    closeTab(entry)
                }
                override fun mouseEntered(e: MouseEvent) {
                    icon = AllIcons.Actions.CloseHovered
                }
                override fun mouseExited(e: MouseEvent) {
                    icon = AllIcons.Actions.Close
                }
            })
        }

        panel.add(label)
        panel.add(closeButton)

        entry.chatPanel.tabLabel = label

        return panel
    }

    private fun closeTab(entry: TabEntry) {
        val index = tabs.indexOf(entry)
        if (index < 0) return

        suppressTabChange = true
        tabs.removeAt(index)
        tabbedPane.removeTabAt(index)

        if (tabs.isEmpty()) {
            suppressTabChange = false
            addNewTab()
        } else {
            val selectIdx = index.coerceAtMost(tabs.size - 1)
            tabbedPane.selectedIndex = selectIdx
            suppressTabChange = false
        }

        scope.launch(Dispatchers.IO) {
            Disposer.dispose(entry)
        }
        updateStatusBar()
    }

    private fun connectEagerly(entry: TabEntry) {
        entry.chatPanel.showStatus("Connecting to Cursor agent...")

        scope.launch {
            val conn = service.createAgentConnection(entry)
            entry.connection = conn

            conn.onStatusChanged = { msg ->
                entry.chatPanel.showStatus(msg)
            }
            conn.onConnectionChanged = { _ ->
                updateStatusBar()
            }

            entry.chatPanel.bindConnection(conn)

            conn.connect()

            if (conn.isConnected) {
                entry.chatPanel.showStatus("Connected. Type a message to start.")
            } else {
                entry.chatPanel.showError(
                    conn.lastError ?: CursorJBundle.message("error.agent.not.found"),
                )
            }
            updateStatusBar()

            service.onModelsReady {
                conn.updateModelInfos(service.availableModelInfos)
                val modelConfig = conn.buildModelConfigOption()
                if (modelConfig.isNotEmpty()) {
                    entry.chatPanel.updateConfigOptions(modelConfig)
                }
            }
        }
    }

    private fun getActiveTab(): TabEntry? {
        val index = tabbedPane.selectedIndex
        return tabs.getOrNull(index)
    }

    fun getActiveSession(): AcpSession? = getActiveTab()?.session

    fun getActiveConnection(): AgentConnection? = getActiveTab()?.connection

    private fun ensureConnectionAndSend(entry: TabEntry, prompt: String) {
        scope.launch {
            try {
                val conn = entry.connection
                if (conn == null || !conn.isConnected) {
                    entry.chatPanel.showError("Not connected. Please wait for the connection or open a new tab.")
                    return@launch
                }

                if (entry.session == null) {
                    entry.session = conn.createSession()
                    entry.chatPanel.bindSession(entry.session!!)
                }

                val title = prompt.take(30).let { if (prompt.length > 30) "$it..." else it }
                entry.title = title
                SwingUtilities.invokeLater {
                    entry.chatPanel.tabLabel?.text = title
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

    private fun updateStatusBar() {
        val active = getActiveTab()
        val conn = active?.connection
        if (conn == null) {
            CursorJConnectionStatus.update(false, "Connecting...")
        } else if (conn.isConnected) {
            val model = conn.selectedModel
            val detail = if (model != null) "Connected ($model)" else null
            CursorJConnectionStatus.update(true, detail)
        } else {
            CursorJConnectionStatus.update(false)
        }
    }
}
