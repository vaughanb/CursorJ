package com.cursorj.acp

import com.cursorj.acp.messages.*
import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

enum class SessionMode(val value: String) {
    AGENT("agent"),
    PLAN("plan"),
    ASK("ask");

    companion object {
        fun fromValue(value: String): SessionMode = entries.firstOrNull { it.value == value } ?: AGENT
    }
}

data class ChatMessage(
    val role: String,
    val content: String,
    val toolCalls: List<ToolCallUpdate> = emptyList(),
    val isStreaming: Boolean = false,
)

class AcpSession(
    val sessionId: String,
    private val client: AcpClient,
) {
    private val log = Logger.getInstance(AcpSession::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    var mode: SessionMode = SessionMode.AGENT
        private set
    var title: String = "New Chat"
        internal set
    var isProcessing: Boolean = false
        private set

    private val _messages = mutableListOf<ChatMessage>()
    val messages: List<ChatMessage> get() = _messages.toList()

    private val updateListeners = mutableListOf<(SessionUpdate) -> Unit>()
    private val messageListeners = mutableListOf<(ChatMessage) -> Unit>()

    private val _currentAgentText = StringBuilder()

    fun addUpdateListener(listener: (SessionUpdate) -> Unit) {
        updateListeners.add(listener)
    }

    fun addMessageListener(listener: (ChatMessage) -> Unit) {
        messageListeners.add(listener)
    }

    fun handleSessionUpdate(params: JsonElement) {
        try {
            val update = json.decodeFromJsonElement<SessionUpdate>(params)
            for (listener in updateListeners) {
                listener(update)
            }

            when (update.sessionUpdate) {
                "agent_message_chunk" -> {
                    val text = update.content?.text ?: return
                    _currentAgentText.append(text)
                    val streamingMessage = ChatMessage(
                        role = "assistant",
                        content = _currentAgentText.toString(),
                        isStreaming = true,
                    )
                    notifyMessageListeners(streamingMessage)
                }
                "agent_message_end" -> {
                    if (_currentAgentText.isNotEmpty()) {
                        val finalMessage = ChatMessage(
                            role = "assistant",
                            content = _currentAgentText.toString(),
                            isStreaming = false,
                        )
                        _messages.add(finalMessage)
                        notifyMessageListeners(finalMessage)
                        _currentAgentText.clear()
                    }
                }
                "tool_call" -> {
                    update.toolCall?.let { tc ->
                        val msg = ChatMessage(
                            role = "tool",
                            content = "Calling ${tc.toolName ?: "tool"}...",
                            toolCalls = listOf(tc),
                        )
                        notifyMessageListeners(msg)
                    }
                }
                "mode_change" -> {
                    update.mode?.let { mode = SessionMode.fromValue(it) }
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to handle session update", e)
        }
    }

    suspend fun sendPrompt(content: List<ContentBlock>): SessionPromptResult {
        val textContent = content.filterIsInstance<TextContent>().joinToString(" ") { it.text }
        if (title == "New Chat" && textContent.isNotBlank()) {
            title = textContent.take(40).let { if (textContent.length > 40) "$it..." else it }
        }

        val userMessage = ChatMessage(role = "user", content = textContent)
        _messages.add(userMessage)
        notifyMessageListeners(userMessage)

        isProcessing = true
        _currentAgentText.clear()
        return try {
            client.sessionPrompt(sessionId, content)
        } finally {
            isProcessing = false
            if (_currentAgentText.isNotEmpty()) {
                val finalMessage = ChatMessage(
                    role = "assistant",
                    content = _currentAgentText.toString(),
                    isStreaming = false,
                )
                _messages.add(finalMessage)
                notifyMessageListeners(finalMessage)
                _currentAgentText.clear()
            }
        }
    }

    suspend fun cancel() {
        if (isProcessing) {
            client.sessionCancel(sessionId)
            isProcessing = false
        }
    }

    suspend fun setMode(newMode: SessionMode) {
        client.sessionSetMode(sessionId, newMode.value)
        mode = newMode
    }

    private fun notifyMessageListeners(message: ChatMessage) {
        for (listener in messageListeners) {
            try {
                listener(message)
            } catch (e: Exception) {
                log.warn("Message listener error", e)
            }
        }
    }
}
