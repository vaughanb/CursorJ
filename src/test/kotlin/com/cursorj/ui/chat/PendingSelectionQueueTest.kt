package com.cursorj.ui.chat

import com.cursorj.acp.messages.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PendingSelectionQueueTest {
    @Test
    fun `flattenBlocks preserves insertion order across selections`() {
        val queue = PendingSelectionQueue()
        queue.add("TodoApp.kt (L3-L5)", listOf(TextContent("first")))
        queue.add("TodoApp.kt (L10)", listOf(TextContent("second"), TextContent("third")))

        val texts = queue.flattenBlocks().filterIsInstance<TextContent>().map { it.text }
        assertEquals(listOf("first", "second", "third"), texts)
    }

    @Test
    fun `remove only deletes matching queued selection`() {
        val queue = PendingSelectionQueue()
        val first = assertNotNull(queue.add("TodoApp.kt (L3-L5)", listOf(TextContent("first"))))
        queue.add("TodoApp.kt (L10)", listOf(TextContent("second")))

        queue.remove(first.id)

        val texts = queue.flattenBlocks().filterIsInstance<TextContent>().map { it.text }
        assertEquals(listOf("second"), texts)
    }

    @Test
    fun `clear removes all queued selections`() {
        val queue = PendingSelectionQueue()
        queue.add("TodoApp.kt (L3-L5)", listOf(TextContent("first")))
        queue.add("TodoApp.kt (L10)", listOf(TextContent("second")))

        queue.clear()

        assertEquals(emptyList(), queue.flattenBlocks())
    }
}
