package com.cursorj.ui.chat

import com.cursorj.acp.ChatMessage

/**
 * After an agent reconnect (e.g. max mode toggle), the persisted transcript may contain the full
 * conversation, while the new session only continues from the latest turn. The UI should match that
 * by showing the last user message and the assistant reply (or replies) that follow it, not older turns.
 */
internal fun sliceToLastUserTurn(messages: List<ChatMessage>): List<ChatMessage> {
    val filtered = messages.filter { !it.isStreaming && it.content.isNotBlank() }
    if (filtered.isEmpty()) return emptyList()
    val lastUserIdx = filtered.indexOfLast { it.role.equals("user", ignoreCase = true) }
    return if (lastUserIdx >= 0) {
        filtered.subList(lastUserIdx, filtered.size)
    } else {
        filtered.takeLast(1)
    }
}
