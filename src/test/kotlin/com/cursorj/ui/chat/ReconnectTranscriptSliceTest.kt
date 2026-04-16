package com.cursorj.ui.chat

import com.cursorj.acp.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals

class ReconnectTranscriptSliceTest {
    @Test
    fun `slice keeps only last user turn and following assistant messages`() {
        val full = listOf(
            ChatMessage("user", "old question"),
            ChatMessage("assistant", "old answer"),
            ChatMessage("user", "new question"),
            ChatMessage("assistant", "new answer"),
        )
        assertEquals(
            listOf(
                ChatMessage("user", "new question"),
                ChatMessage("assistant", "new answer"),
            ),
            sliceToLastUserTurn(full),
        )
    }

    @Test
    fun `slice keeps multiple assistant messages after last user if present`() {
        val full = listOf(
            ChatMessage("user", "u"),
            ChatMessage("assistant", "a1"),
            ChatMessage("user", "u2"),
            ChatMessage("assistant", "a2a"),
            ChatMessage("assistant", "a2b"),
        )
        assertEquals(
            listOf(
                ChatMessage("user", "u2"),
                ChatMessage("assistant", "a2a"),
                ChatMessage("assistant", "a2b"),
            ),
            sliceToLastUserTurn(full),
        )
    }

    @Test
    fun `slice without user keeps last assistant only`() {
        val full = listOf(
            ChatMessage("assistant", "only"),
        )
        assertEquals(listOf(ChatMessage("assistant", "only")), sliceToLastUserTurn(full))
    }
}
