package com.cursorj.ui.chat

import com.cursorj.CursorJBundle
import com.cursorj.acp.ChatMode
import com.cursorj.acp.messages.TextContent
import com.cursorj.debug.DebugLogServer

/**
 * Builds Plan/Debug mode prompt prefixes and decides what the user bubble should show.
 * Extracted from [ChatPanel] so the Debug/Plan injection rules are unit-testable.
 */
object ModePromptSupport {
    /**
     * Returns content blocks to prepend for the current [mode].
     * For [ChatMode.DEBUG], [debugEndpoint] must be non-null (from [DebugLogServer.beginTurn]).
     */
    fun modePrefixBlocks(
        mode: ChatMode,
        debugEndpoint: DebugLogServer.DebugEndpoint? = null,
    ): List<TextContent> {
        return when (mode) {
            ChatMode.PLAN -> listOf(
                TextContent(text = CursorJBundle.message("chat.plan.promptPrefix")),
            )
            ChatMode.DEBUG -> {
                val endpoint = debugEndpoint ?: return emptyList()
                val httpHint = if (endpoint.httpAvailable && endpoint.url.isNotBlank()) {
                    endpoint.url
                } else {
                    "(HTTP endpoint unavailable — use the log file only)"
                }
                listOf(
                    TextContent(
                        text = CursorJBundle.message(
                            "chat.debug.promptPrefix",
                            endpoint.logFilePath,
                            httpHint,
                        ),
                    ),
                )
            }
            else -> emptyList()
        }
    }

    /**
     * User-visible text for the chat bubble. Plan/Debug prefixes and carryover context are omitted
     * so the bubble shows only the user's typed prompt.
     */
    fun displayUserText(
        mode: ChatMode,
        userText: String,
        carryoverContext: String?,
        visibleTextBlocks: List<TextContent>,
    ): String {
        return when {
            !carryoverContext.isNullOrBlank() -> userText
            mode == ChatMode.PLAN || mode == ChatMode.DEBUG -> userText
            else -> visibleTextBlocks.joinToString(" ") { it.text }
        }
    }
}
