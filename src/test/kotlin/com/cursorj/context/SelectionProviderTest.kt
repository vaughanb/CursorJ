package com.cursorj.context

import com.cursorj.acp.messages.ResourceLinkContent
import com.cursorj.acp.messages.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SelectionProviderTest {
    @Test
    fun `buildSelectionContext includes file reference and single line label`() {
        val context = SelectionProvider.buildSelectionContext(
            selectedText = "println(\"hello\")",
            filePath = "C:/work/src/TodoApp.kt",
            fileName = "TodoApp.kt",
            startLine = 12,
            endLine = 12,
        )

        assertEquals("TodoApp.kt (L12)", context.label)
        val resource = context.blocks.filterIsInstance<ResourceLinkContent>().single()
        assertEquals("C:/work/src/TodoApp.kt", resource.uri)
        assertEquals("TodoApp.kt", resource.name)

        val textBlock = context.blocks.filterIsInstance<TextContent>().single()
        assertTrue(textBlock.text.contains("C:/work/src/TodoApp.kt:L12"))
        assertTrue(textBlock.text.contains("println(\"hello\")"))
    }

    @Test
    fun `buildSelectionContext uses line range and no resource when file is unavailable`() {
        val context = SelectionProvider.buildSelectionContext(
            selectedText = "task.done = true",
            filePath = null,
            fileName = null,
            startLine = 20,
            endLine = 27,
        )

        assertEquals("Selection (L20-L27)", context.label)
        assertTrue(context.blocks.none { it is ResourceLinkContent })

        val textBlock = context.blocks.filterIsInstance<TextContent>().single()
        assertTrue(textBlock.text.contains("L20-L27"))
        assertTrue(textBlock.text.contains("task.done = true"))
    }
}
