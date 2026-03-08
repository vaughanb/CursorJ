package com.cursorj.ui.chat

import com.cursorj.acp.messages.ContentBlock
import java.util.UUID

class PendingSelectionQueue {
    data class Entry(
        val id: String,
        val label: String,
        val blocks: List<ContentBlock>,
    )

    private val entries = mutableListOf<Entry>()

    fun add(label: String, blocks: List<ContentBlock>): Entry? {
        if (blocks.isEmpty()) return null
        val entry = Entry(
            id = UUID.randomUUID().toString(),
            label = label,
            blocks = blocks,
        )
        entries.add(entry)
        return entry
    }

    fun remove(id: String): Entry? {
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return null
        return entries.removeAt(index)
    }

    fun flattenBlocks(): List<ContentBlock> {
        return entries.flatMap { it.blocks }
    }

    fun clear() {
        entries.clear()
    }
}
