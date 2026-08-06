package com.cursorj.ui.chat

import com.cursorj.acp.ChatMode
import com.cursorj.acp.messages.TextContent
import com.cursorj.debug.DebugLogServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModePromptSupportTest {
    @Test
    fun `debug prefix is injected and hidden from display text`() {
        val endpoint = DebugLogServer.DebugEndpoint(
            sessionId = "s1",
            logFilePath = "/tmp/project/.cursorj/debug/session-s1.log",
            url = "http://127.0.0.1:12345/log/s1/token",
            httpAvailable = true,
        )
        val prefixes = ModePromptSupport.modePrefixBlocks(ChatMode.DEBUG, endpoint)
        assertEquals(1, prefixes.size)
        val prefix = prefixes[0].text
        assertTrue(prefix.contains("Debug mode"), "prefix should identify Debug mode")
        assertTrue(prefix.contains(endpoint.logFilePath), "prefix should include log file path")
        assertTrue(prefix.contains(endpoint.url), "prefix should include HTTP URL")

        val userText = "null pointer in AuthService"
        val visible = prefixes + TextContent(userText)
        val display = ModePromptSupport.displayUserText(
            mode = ChatMode.DEBUG,
            userText = userText,
            carryoverContext = null,
            visibleTextBlocks = visible,
        )
        assertEquals(userText, display)
        assertTrue(!display.contains(endpoint.logFilePath), "display must hide debug prefix")
    }

    @Test
    fun `debug prefix falls back when HTTP unavailable`() {
        val endpoint = DebugLogServer.DebugEndpoint(
            sessionId = "s2",
            logFilePath = "C:/proj/.cursorj/debug/session-s2.log",
            url = "",
            httpAvailable = false,
        )
        val prefixes = ModePromptSupport.modePrefixBlocks(ChatMode.DEBUG, endpoint)
        assertEquals(1, prefixes.size)
        assertTrue(prefixes[0].text.contains("HTTP endpoint unavailable"))
        assertTrue(prefixes[0].text.contains(endpoint.logFilePath))
    }

    @Test
    fun `agent mode has no prefix and shows joined visible text`() {
        assertTrue(ModePromptSupport.modePrefixBlocks(ChatMode.AGENT).isEmpty())
        val blocks = listOf(TextContent("hello"), TextContent("world"))
        val display = ModePromptSupport.displayUserText(
            mode = ChatMode.AGENT,
            userText = "ignored",
            carryoverContext = null,
            visibleTextBlocks = blocks,
        )
        assertEquals("hello world", display)
    }

    @Test
    fun `plan mode hides prefix from display`() {
        val prefixes = ModePromptSupport.modePrefixBlocks(ChatMode.PLAN)
        assertEquals(1, prefixes.size)
        assertTrue(prefixes[0].text.contains("Plan mode"))
        val display = ModePromptSupport.displayUserText(
            mode = ChatMode.PLAN,
            userText = "design auth",
            carryoverContext = null,
            visibleTextBlocks = prefixes + TextContent("design auth"),
        )
        assertEquals("design auth", display)
    }
}
