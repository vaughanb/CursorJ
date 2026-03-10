package com.cursorj.ui.chat

import com.cursorj.CursorJBundle
import com.cursorj.history.ChatHistoryEntry
import com.cursorj.history.ChatHistoryIndexManager
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class ChatHistoryPopup(
    private val indexManager: ChatHistoryIndexManager,
    private val onEntrySelected: (sessionId: String) -> Unit,
) {
    private val listPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    private val scrollPane = JScrollPane(listPanel).apply {
        border = BorderFactory.createEmptyBorder()
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        preferredSize = Dimension(300, 360)
    }

    private val searchField = SearchTextField(false).apply {
        textEditor.emptyText.text = CursorJBundle.message("chat.history.search.placeholder")
    }

    private val rootPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(6)
        add(searchField, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
    }

    private var popup: JBPopup? = null

    init {
        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = rebuildList()
            override fun removeUpdate(e: DocumentEvent) = rebuildList()
            override fun changedUpdate(e: DocumentEvent) = rebuildList()
        })
    }

    fun show(relativeTo: Component) {
        rebuildList()
        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(rootPanel, searchField.textEditor)
            .setTitle(CursorJBundle.message("chat.history.button.tooltip"))
            .setFocusable(true)
            .setRequestFocus(true)
            .setResizable(true)
            .setMovable(false)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setCancelKeyEnabled(true)
            .createPopup()
        popup?.showUnderneathOf(relativeTo)
    }

    private fun rebuildList() {
        listPanel.removeAll()
        val query = searchField.text.trim()
        val entries = if (query.isBlank()) indexManager.listAll() else indexManager.search(query)

        if (entries.isEmpty()) {
            val emptyLabel = JLabel(CursorJBundle.message("chat.history.empty")).apply {
                foreground = JBColor.GRAY
                border = JBUI.Borders.empty(16, 8)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            listPanel.add(emptyLabel)
        } else {
            val grouped = groupByTimePeriod(entries)
            for ((groupLabel, groupEntries) in grouped) {
                addGroupHeader(groupLabel)
                for (entry in groupEntries) {
                    addEntryRow(entry)
                }
            }
        }

        listPanel.revalidate()
        listPanel.repaint()
        SwingUtilities.invokeLater {
            scrollPane.verticalScrollBar.value = 0
        }
    }

    private fun addGroupHeader(label: String) {
        val header = JLabel(label).apply {
            font = font.deriveFont(Font.BOLD, font.size2D - 1)
            foreground = JBColor.GRAY
            border = JBUI.Borders.empty(8, 8, 4, 8)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        header.maximumSize = Dimension(Int.MAX_VALUE, header.preferredSize.height)
        listPanel.add(header)
    }

    private fun addEntryRow(entry: ChatHistoryEntry) {
        val hoverBg = JBColor(Color(0xE8E8E8), Color(0x3C3F41))
        val normalBg = JBColor(Color(0, 0, 0, 0), Color(0, 0, 0, 0))

        val truncatedTitle = if (entry.title.length > 40) {
            entry.title.take(38) + "..."
        } else {
            entry.title
        }

        val row = object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension =
                Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        row.isOpaque = true
        row.background = normalBg
        row.border = JBUI.Borders.empty(4, 8)
        row.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        row.alignmentX = Component.LEFT_ALIGNMENT

        val titleLabel = JLabel(truncatedTitle).apply {
            font = font.deriveFont(font.size2D)
        }
        row.add(titleLabel, BorderLayout.CENTER)

        row.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                popup?.cancel()
                onEntrySelected(entry.sessionId)
            }

            override fun mouseEntered(e: MouseEvent) {
                row.background = hoverBg
            }

            override fun mouseExited(e: MouseEvent) {
                row.background = normalBg
            }
        })

        listPanel.add(row)
    }

    companion object {
        fun groupByTimePeriod(entries: List<ChatHistoryEntry>): List<Pair<String, List<ChatHistoryEntry>>> {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val yesterday = today.minusDays(1)
            val weekAgo = today.minusDays(7)
            val monthAgo = today.minusDays(30)

            val todayList = mutableListOf<ChatHistoryEntry>()
            val yesterdayList = mutableListOf<ChatHistoryEntry>()
            val weekList = mutableListOf<ChatHistoryEntry>()
            val monthList = mutableListOf<ChatHistoryEntry>()
            val olderList = mutableListOf<ChatHistoryEntry>()

            for (entry in entries) {
                val entryDate = Instant.ofEpochMilli(entry.lastActivityAt)
                    .atZone(zone)
                    .toLocalDate()
                when {
                    entryDate == today -> todayList.add(entry)
                    entryDate == yesterday -> yesterdayList.add(entry)
                    entryDate.isAfter(weekAgo) -> weekList.add(entry)
                    entryDate.isAfter(monthAgo) -> monthList.add(entry)
                    else -> olderList.add(entry)
                }
            }

            return buildList {
                if (todayList.isNotEmpty()) add(CursorJBundle.message("chat.history.group.today") to todayList)
                if (yesterdayList.isNotEmpty()) add(CursorJBundle.message("chat.history.group.yesterday") to yesterdayList)
                if (weekList.isNotEmpty()) add(CursorJBundle.message("chat.history.group.previous7days") to weekList)
                if (monthList.isNotEmpty()) add(CursorJBundle.message("chat.history.group.previous30days") to monthList)
                if (olderList.isNotEmpty()) add(CursorJBundle.message("chat.history.group.older") to olderList)
            }
        }
    }
}
