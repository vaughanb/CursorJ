package com.cursorj.ui.toolwindow

import com.cursorj.CursorJBundle
import com.cursorj.acp.AgentConnection
import com.cursorj.acp.AcpSession
import com.cursorj.acp.messages.ContentBlock
import com.cursorj.indexing.WorkspaceIndexOrchestrator
import com.cursorj.settings.CursorJConfigurable
import com.cursorj.settings.CursorJSettings
import com.cursorj.ui.chat.ChatHistoryPopup
import com.cursorj.ui.chat.ChatPanel
import com.cursorj.ui.statusbar.CursorJConnectionStatus
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.JBColor
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.UUID
import javax.swing.*

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
        var historyKey: String,
        var title: String = CursorJBundle.message("chat.tab.new"),
        var isDisposed: Boolean = false,
        var tabButton: JPanel? = null,
        var tabTitleLabel: JLabel? = null,
        var cardId: String = "",
    ) : Disposable {
        override fun dispose() {
            isDisposed = true
            connection?.let { Disposer.dispose(it) }
        }
    }

    private val tabs = mutableListOf<TabEntry>()
    private var selectedEntry: TabEntry? = null
    private var nextCardId = 0
    private var scrollOffset = 0
    private var startupRestoreTotal = 0
    private var startupRestoreFinished = 0
    private var startupRestoreSucceeded = 0

    private val accentColor = JBColor(Color(0x4083C9), Color(0x4A88C7))
    private val arrowFg = JBColor(Color(0x999999), Color(0x888888))

    private val tabStrip = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
    }

    private val tabClip = object : JPanel(null) {
        init { isOpaque = false }
        override fun doLayout() {
            val stripPref = tabStrip.preferredSize
            val maxOff = maxOf(0, stripPref.width - width)
            scrollOffset = scrollOffset.coerceIn(0, maxOff)
            tabStrip.setBounds(-scrollOffset, 0, maxOf(stripPref.width, width), height)
            tabStrip.doLayout()
        }
        override fun getPreferredSize(): Dimension {
            val h = tabStrip.preferredSize.height
            return Dimension(0, h)
        }
    }

    private val scrollLeftBtn = JLabel("\u25C2").apply {
        foreground = arrowFg
        border = JBUI.Borders.empty(4, 4)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isVisible = false
        font = font.deriveFont(font.size2D + 2)
        toolTipText = "Scroll tabs left"
    }

    private val scrollRightBtn = JLabel("\u25B8").apply {
        foreground = arrowFg
        border = JBUI.Borders.empty(4, 4)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isVisible = false
        font = font.deriveFont(font.size2D + 2)
        toolTipText = "Scroll tabs right"
    }

    private val addButton = JLabel(AllIcons.General.Add).apply {
        toolTipText = "New chat"
        border = JBUI.Borders.empty(6, 4, 6, 6)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isOpaque = false
    }

    private val historyButton = JLabel(AllIcons.Vcs.History).apply {
        toolTipText = CursorJBundle.message("chat.history.button.tooltip")
        border = JBUI.Borders.empty(6, 4, 6, 4)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isOpaque = false
    }

    private val settingsButton = JLabel(AllIcons.General.GearPlain).apply {
        toolTipText = CursorJBundle.message("settings.title")
        border = JBUI.Borders.empty(6, 4, 6, 6)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isOpaque = false
    }

    private val rightControls = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        add(scrollRightBtn)
        add(addButton)
        add(historyButton)
        add(settingsButton)
    }

    private val headerPanel = JPanel(BorderLayout()).apply {
        add(scrollLeftBtn, BorderLayout.WEST)
        add(tabClip, BorderLayout.CENTER)
        add(rightControls, BorderLayout.EAST)
        border = JBUI.Borders.customLineBottom(JBColor.border())
    }

    private val contentPanel = JPanel(CardLayout())
    private val rootPanel = JPanel(BorderLayout()).apply {
        add(headerPanel, BorderLayout.NORTH)
        add(contentPanel, BorderLayout.CENTER)
    }

    init {
        tabClip.add(tabStrip)

        scrollLeftBtn.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                scrollOffset = maxOf(0, scrollOffset - SCROLL_STEP)
                tabClip.doLayout()
                tabClip.repaint()
                updateScrollButtons()
            }
        })

        scrollRightBtn.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val max = maxOf(0, tabStrip.preferredSize.width - tabClip.width)
                scrollOffset = minOf(max, scrollOffset + SCROLL_STEP)
                tabClip.doLayout()
                tabClip.repaint()
                updateScrollButtons()
            }
        })

        addButton.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                addNewTab()
            }
        })

        historyButton.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                showHistoryPopup()
            }
        })

        settingsButton.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                ShowSettingsUtil.getInstance().showSettingsDialog(service.project, CursorJConfigurable::class.java)
            }
        })

        val wheelListener = java.awt.event.MouseWheelListener { e ->
            val max = maxOf(0, tabStrip.preferredSize.width - tabClip.width)
            if (max <= 0) return@MouseWheelListener
            scrollOffset = (scrollOffset + e.wheelRotation * 40).coerceIn(0, max)
            tabClip.doLayout()
            tabClip.repaint()
            updateScrollButtons()
            e.consume()
        }
        tabClip.addMouseWheelListener(wheelListener)
        tabStrip.addMouseWheelListener(wheelListener)

        tabClip.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                tabClip.doLayout()
                tabClip.repaint()
                updateScrollButtons()
            }
        })
    }

    private fun updateScrollButtons() {
        val stripWidth = tabStrip.preferredSize.width
        val clipWidth = tabClip.width
        val overflow = stripWidth > clipWidth
        scrollLeftBtn.isVisible = overflow && scrollOffset > 0
        scrollRightBtn.isVisible = overflow && scrollOffset < stripWidth - clipWidth
    }

    fun addInitialTab() {
        val content = ContentFactory.getInstance().createContent(rootPanel, "", false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)

        val savedSessionIds = CursorJSettings.instance.savedSessionIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (savedSessionIds.isEmpty()) {
            addNewTab()
        } else {
            startupRestoreTotal = savedSessionIds.size
            startupRestoreFinished = 0
            startupRestoreSucceeded = 0
            for (sessionId in savedSessionIds) {
                addRestoredTab(sessionId)
            }
            broadcastStatusToTabs("Restoring $startupRestoreTotal previous chats...")
        }
    }

    fun addNewTab(): ChatPanel {
        return addTab(
            historyKey = "tab-${UUID.randomUUID()}",
            initialTitle = CursorJBundle.message("chat.tab.new"),
            restoreSessionId = null,
        )
    }

    private fun addRestoredTab(sessionId: String): ChatPanel {
        val historyKey = "session:$sessionId"
        val indexTitle = service.chatHistoryIndexManager.listAll()
            .firstOrNull { it.sessionId == sessionId }?.title
        val restoredTitle = indexTitle
            ?: service.chatTranscriptManager.transcriptFor(historyKey)
                .asReversed()
                .firstOrNull { it.role == "user" && it.content.isNotBlank() }
                ?.content
                ?.let(::buildHeuristicTitle)
            ?: service.promptHistoryManager.historyFor(historyKey)
                .lastOrNull()
                ?.let(::buildHeuristicTitle)
            ?: CursorJBundle.message("chat.tab.new")
        return addTab(
            historyKey = historyKey,
            initialTitle = restoredTitle,
            restoreSessionId = sessionId,
        )
    }

    private fun addTab(historyKey: String, initialTitle: String, restoreSessionId: String?): ChatPanel {
        val chatPanel = ChatPanel(service, historyKey)
        val cardId = "card-${nextCardId++}"
        val entry = TabEntry(
            chatPanel = chatPanel,
            historyKey = historyKey,
            title = initialTitle,
            cardId = cardId,
        )
        Disposer.register(toolWindow.disposable, entry)
        Disposer.register(entry, chatPanel)
        tabs.add(entry)

        val tabButton = createTabButton(entry)
        entry.tabButton = tabButton
        tabStrip.add(tabButton)
        tabStrip.revalidate()

        contentPanel.add(chatPanel.component, cardId)
        selectTab(entry)
        updateScrollButtons()

        chatPanel.onFirstPrompt = { prompt ->
            ensureConnectionAndSend(entry, prompt)
        }
        chatPanel.onSessionReplaced = { newSession ->
            entry.session = newSession
            val newHistoryKey = "session:${newSession.sessionId}"
            service.chatTranscriptManager.migrateSessionKey(entry.historyKey, newHistoryKey)
            service.promptHistoryManager.migrateSessionKey(entry.historyKey, newHistoryKey)
            entry.historyKey = newHistoryKey
            chatPanel.updateHistorySessionKey(newHistoryKey)
            service.chatHistoryIndexManager.recordSession(newSession.sessionId, entry.title)
            persistSavedSessions()
        }

        connectEagerly(entry, restoreSessionId)

        return chatPanel
    }

    private fun createTabButton(entry: TabEntry): JPanel {
        val panel = object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension {
                val pref = preferredSize
                return Dimension(pref.width, pref.height)
            }
        }
        panel.isOpaque = false
        panel.border = JBUI.Borders.empty(4, 8, 4, 4)

        val label = JLabel(entry.title)
        label.border = JBUI.Borders.emptyRight(4)
        entry.tabTitleLabel = label

        val closeButton = JLabel(AllIcons.Actions.Close).apply {
            toolTipText = "Close tab"
            border = JBUI.Borders.empty(1)
            preferredSize = Dimension(16, 16)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
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

        panel.add(label, BorderLayout.CENTER)
        panel.add(closeButton, BorderLayout.EAST)

        panel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                when {
                    SwingUtilities.isMiddleMouseButton(e) -> closeTab(entry)
                    SwingUtilities.isRightMouseButton(e) -> {
                        val popup = JPopupMenu()
                        popup.add(JMenuItem("Close Tab").apply {
                            addActionListener { closeTab(entry) }
                        })
                        popup.show(panel, e.x, e.y)
                    }
                    else -> selectTab(entry)
                }
            }
        })

        return panel
    }

    private fun selectTab(entry: TabEntry) {
        if (entry.isDisposed) return
        selectedEntry = entry
        (contentPanel.layout as CardLayout).show(contentPanel, entry.cardId)
        updateTabVisuals()
        scrollTabIntoView(entry)
        updateStatusBar()
    }

    private fun updateTabVisuals() {
        for (tab in tabs) {
            val button = tab.tabButton ?: continue
            val isSelected = tab == selectedEntry
            button.border = if (isSelected) {
                BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 2, 0, accentColor),
                    JBUI.Borders.empty(4, 8, 2, 4),
                )
            } else {
                JBUI.Borders.empty(4, 8, 4, 4)
            }
        }
        tabStrip.repaint()
    }

    private fun scrollTabIntoView(entry: TabEntry) {
        SwingUtilities.invokeLater {
            entry.tabButton?.let { button ->
                val btnLeft = button.x
                val btnRight = btnLeft + button.width
                val visLeft = scrollOffset
                val visRight = scrollOffset + tabClip.width

                if (btnLeft < visLeft) {
                    scrollOffset = btnLeft
                } else if (btnRight > visRight) {
                    scrollOffset = btnRight - tabClip.width
                }
                scrollOffset = scrollOffset.coerceIn(0, maxOf(0, tabStrip.preferredSize.width - tabClip.width))
                tabClip.doLayout()
                tabClip.repaint()
                updateScrollButtons()
            }
        }
    }

    private fun closeTab(entry: TabEntry) {
        val index = tabs.indexOf(entry)
        if (index < 0) return

        tabs.removeAt(index)
        entry.tabButton?.let { tabStrip.remove(it) }
        contentPanel.remove(entry.chatPanel.component)
        tabStrip.revalidate()
        tabStrip.repaint()
        contentPanel.revalidate()

        if (tabs.isEmpty()) {
            selectedEntry = null
            addNewTab()
        } else {
            val selectIdx = index.coerceAtMost(tabs.size - 1)
            selectTab(tabs[selectIdx])
        }
        updateScrollButtons()

        scope.launch(Dispatchers.IO) {
            Disposer.dispose(entry)
        }
        persistSavedSessions()
        updateStatusBar()
    }

    private fun connectEagerly(entry: TabEntry, restoreSessionId: String? = null) {
        entry.chatPanel.showStatus("Connecting to Cursor agent...")

        scope.launch {
            var restoreSucceeded = false
            try {
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
                    if (!restoreSessionId.isNullOrBlank()) {
                        val requestedHistoryKey = "session:$restoreSessionId"
                        try {
                            val restoredSession = conn.loadSession(restoreSessionId)
                            entry.session = restoredSession
                            val restoredHistoryKey = "session:${restoredSession.sessionId}"
                            if (requestedHistoryKey != restoredHistoryKey) {
                                service.chatTranscriptManager.migrateSessionKey(requestedHistoryKey, restoredHistoryKey)
                                service.chatHistoryIndexManager.removeSession(restoreSessionId)
                            }
                            entry.historyKey = restoredHistoryKey
                            entry.chatPanel.updateHistorySessionKey(restoredHistoryKey)
                            entry.chatPanel.bindSession(restoredSession)

                            val defaultTitle = CursorJBundle.message("chat.tab.new")
                            if (entry.title == defaultTitle) {
                                val restoredTitle = restoredSession.messages
                                    .asReversed()
                                    .firstOrNull { it.role == "user" && it.content.isNotBlank() }
                                    ?.content
                                    ?.let(::buildHeuristicTitle)
                                    ?: service.promptHistoryManager.historyFor(restoredHistoryKey)
                                        .lastOrNull()
                                        ?.let(::buildHeuristicTitle)
                                if (!restoredTitle.isNullOrBlank()) {
                                    applyTabTitle(entry, restoredTitle)
                                }
                            }
                            service.chatHistoryIndexManager.recordSession(
                                restoredSession.sessionId,
                                entry.title,
                            )

                            if (restoredSession.messages.isNotEmpty()) {
                                restoreSucceeded = true
                                entry.chatPanel.showStatus("Restored previous chat.")
                            } else {
                                val localTranscript = service.chatTranscriptManager.transcriptFor(restoredHistoryKey)
                                if (localTranscript.isNotEmpty()) {
                                    val carryover = service.chatTranscriptManager.buildCarryoverContext(restoredHistoryKey)
                                    entry.chatPanel.applyLocalTranscriptFallback(localTranscript, carryover)
                                    restoreSucceeded = true
                                    entry.chatPanel.showStatus("Restored local transcript. First new prompt will include carryover context.")
                                } else {
                                    entry.chatPanel.showStatus("Connected. Previous chat could not be restored.")
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log.warn("Failed to restore session $restoreSessionId; tab will start fresh", e)
                            val localTranscript = service.chatTranscriptManager.transcriptFor(requestedHistoryKey)
                            if (localTranscript.isNotEmpty()) {
                                val carryover = service.chatTranscriptManager.buildCarryoverContext(requestedHistoryKey)
                                entry.chatPanel.applyLocalTranscriptFallback(localTranscript, carryover)
                                restoreSucceeded = true
                                entry.chatPanel.showStatus("Connected. Restored local transcript (server session unavailable).")
                            } else {
                                entry.chatPanel.showStatus("Connected. Previous chat could not be restored.")
                            }
                        }
                    } else {
                        entry.chatPanel.showStatus("Connected. Type a message to start.")
                    }
                } else {
                    entry.chatPanel.showError(
                        conn.lastError ?: CursorJBundle.message("error.agent.not.found"),
                    )
                }
                persistSavedSessions()
                updateStatusBar()

                service.onModelsReady {
                    conn.updateModelInfos(service.availableModelInfos)
                    val modelConfig = conn.buildModelConfigOption()
                    if (modelConfig.isNotEmpty()) {
                        entry.chatPanel.updateConfigOptions(modelConfig)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Failed while connecting tab", e)
                entry.chatPanel.showError(e.message ?: "Failed to connect")
            } finally {
                if (!restoreSessionId.isNullOrBlank()) {
                    markStartupRestoreAttempt(restoreSucceeded)
                }
            }
        }
    }

    private fun getActiveTab(): TabEntry? = selectedEntry

    fun getActiveSession(): AcpSession? = getActiveTab()?.session

    fun getActiveConnection(): AgentConnection? = getActiveTab()?.connection

    fun showIndexLifecycle(update: WorkspaceIndexOrchestrator.IndexLifecycleUpdate) {
        val message = when (update.state) {
            WorkspaceIndexOrchestrator.IndexLifecycleState.STARTUP_BUILD -> update.message
            WorkspaceIndexOrchestrator.IndexLifecycleState.INCREMENTAL_BUILD -> update.message
            WorkspaceIndexOrchestrator.IndexLifecycleState.STALE_REBUILDING -> null
            WorkspaceIndexOrchestrator.IndexLifecycleState.FAILED -> update.message
            WorkspaceIndexOrchestrator.IndexLifecycleState.READY -> null
        }
        if (message.isNullOrBlank()) return
        for (tab in tabs) {
            tab.chatPanel.showStatus(message)
        }
    }

    fun addSelectionToActiveChat(label: String, blocks: List<ContentBlock>): Boolean {
        if (blocks.isEmpty()) return false
        var entry = getActiveTab()
        if (entry == null) {
            addNewTab()
            entry = getActiveTab()
        }
        entry?.chatPanel?.queueSelectionContext(label, blocks) ?: return false
        return true
    }

    private fun showHistoryPopup() {
        val popup = ChatHistoryPopup(
            indexManager = service.chatHistoryIndexManager,
            onEntrySelected = { sessionId -> openHistoryEntry(sessionId) },
            onClearHistory = { clearAllHistory() },
        )
        popup.show(historyButton)
    }

    private fun clearAllHistory() {
        service.chatHistoryIndexManager.clearAll()
        CursorJSettings.instance.savedSessionIds = mutableListOf()
    }

    private fun openHistoryEntry(sessionId: String) {
        val existing = tabs.find { entry ->
            entry.session?.sessionId == sessionId ||
                entry.historyKey == "session:$sessionId"
        }
        if (existing != null) {
            selectTab(existing)
            return
        }
        addRestoredTab(sessionId)
    }

    private fun ensureConnectionAndSend(entry: TabEntry, prompt: String) {
        scope.launch {
            try {
                val conn = entry.connection
                if (conn == null || !conn.isConnected) {
                    entry.chatPanel.showError("Not connected. Please wait for the connection or open a new tab.")
                    return@launch
                }

                if (entry.session == null) {
                    val defaultTitle = CursorJBundle.message("chat.tab.new")
                    val titleToUse = if (entry.title != defaultTitle) entry.title else buildHeuristicTitle(prompt)
                    val oldSessionId = entry.historyKey.removePrefix("session:")
                        .takeIf { entry.historyKey.startsWith("session:") && it.isNotBlank() }

                    entry.session = conn.createSession()
                    val sessionId = entry.session!!.sessionId
                    val sessionHistoryKey = "session:$sessionId"
                    service.promptHistoryManager.migrateSessionKey(entry.historyKey, sessionHistoryKey)
                    service.chatTranscriptManager.migrateSessionKey(entry.historyKey, sessionHistoryKey)
                    if (oldSessionId != null && oldSessionId != sessionId) {
                        service.chatHistoryIndexManager.removeSession(oldSessionId)
                    }
                    entry.historyKey = sessionHistoryKey
                    entry.chatPanel.updateHistorySessionKey(sessionHistoryKey)
                    entry.chatPanel.bindSession(entry.session!!)
                    service.chatHistoryIndexManager.recordSession(sessionId, titleToUse)
                    applyTabTitle(entry, titleToUse)
                    persistSavedSessions()
                }

                entry.session?.sessionId?.let { service.chatHistoryIndexManager.touchActivity(it) }
                entry.chatPanel.sendPrompt(prompt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to create session or send prompt", e)
                entry.chatPanel.showError(e.message ?: "Unknown error")
            }
        }
    }

    private fun persistSavedSessions() {
        val sessionIds = tabs
            .mapNotNull { entry ->
                entry.session?.sessionId?.trim()?.takeIf { it.isNotBlank() }
                    ?: entry.historyKey
                        .removePrefix("session:")
                        .takeIf { entry.historyKey.startsWith("session:") && it.isNotBlank() }
            }
            .filter { it.isNotBlank() }
            .distinct()
            .toMutableList()
        CursorJSettings.instance.savedSessionIds = sessionIds
    }

    private fun markStartupRestoreAttempt(succeeded: Boolean) {
        val summary = synchronized(this) {
            if (startupRestoreTotal <= 0) return
            startupRestoreFinished += 1
            if (succeeded) startupRestoreSucceeded += 1
            if (startupRestoreFinished >= startupRestoreTotal) {
                val message = "Startup restore: restored $startupRestoreSucceeded of $startupRestoreTotal chats."
                startupRestoreTotal = 0
                startupRestoreFinished = 0
                startupRestoreSucceeded = 0
                message
            } else {
                null
            }
        }
        if (summary != null) {
            broadcastStatusToTabs(summary)
        }
    }

    private fun broadcastStatusToTabs(message: String) {
        for (tab in tabs) {
            tab.chatPanel.showStatus(message)
        }
    }

    private fun buildHeuristicTitle(prompt: String): String {
        val cleaned = prompt
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?: CursorJBundle.message("chat.tab.new")

        val normalized = cleaned
            .replace("`", "")
            .replace("\"", "")
            .replace("'", "")
            .replace(Regex("""\s+"""), " ")
            .trim()

        val withoutPrefix = normalized.replace(
            Regex("""^(please|can you|could you|would you|i need|i want|help me|let's|lets)\s+""", RegexOption.IGNORE_CASE),
            "",
        )

        val stopWords = setOf(
            "a", "an", "the", "to", "for", "of", "in", "on", "with", "and", "or",
            "by", "from", "this", "that", "these", "those", "please", "can", "could",
            "would", "should", "you", "me", "my", "our", "we", "i", "need", "want",
            "like", "help",
        )

        val tokens = Regex("""[A-Za-z0-9+#./_-]+""")
            .findAll(withoutPrefix)
            .map { it.value }
            .filter { it.length > 1 && it.lowercase() !in stopWords }
            .toList()

        val core = if (tokens.isNotEmpty()) {
            tokens.take(4).joinToString(" ") { token ->
                if (token.all { it.isUpperCase() } || token.any { it.isDigit() }) token
                else token.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
        } else {
            withoutPrefix.take(30).trim()
        }

        return core.take(32).let { if (core.length > 32) "$it..." else it }
    }

    private fun applyTabTitle(entry: TabEntry, title: String) {
        entry.title = title
        val sessionId = entry.session?.sessionId
            ?: entry.historyKey.removePrefix("session:").takeIf { entry.historyKey.startsWith("session:") }
        if (sessionId != null) {
            service.chatHistoryIndexManager.updateTitle(sessionId, title)
        }
        SwingUtilities.invokeLater {
            if (!entry.isDisposed) {
                entry.tabTitleLabel?.text = title
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

    companion object {
        private const val SCROLL_STEP = 120
    }
}
