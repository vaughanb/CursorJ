package com.cursorj.acp

import com.cursorj.acp.messages.*
import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.*

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
    initialConfigOptions: List<ConfigOption> = emptyList(),
) {
    private val log = Logger.getInstance(AcpSession::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    var mode: SessionMode = SessionMode.AGENT
        private set
    var title: String = "New Chat"
        internal set
    var isProcessing: Boolean = false
        private set

    private val _configOptions = mutableListOf<ConfigOption>().apply { addAll(initialConfigOptions) }
    val configOptions: List<ConfigOption> get() = _configOptions.toList()

    private val _messages = mutableListOf<ChatMessage>()
    val messages: List<ChatMessage> get() = _messages.toList()

    private val updateListeners = mutableListOf<(SessionUpdate) -> Unit>()
    private val messageListeners = mutableListOf<(ChatMessage) -> Unit>()
    private val configListeners = mutableListOf<(List<ConfigOption>) -> Unit>()

    private val _currentAgentText = StringBuilder()

    fun addUpdateListener(listener: (SessionUpdate) -> Unit) {
        updateListeners.add(listener)
    }

    fun addMessageListener(listener: (ChatMessage) -> Unit) {
        messageListeners.add(listener)
    }

    fun addConfigListener(listener: (List<ConfigOption>) -> Unit) {
        configListeners.add(listener)
    }

    fun getConfigOption(category: String): ConfigOption? {
        return _configOptions.firstOrNull { it.category == category }
    }

    fun handleSessionUpdate(params: JsonElement) {
        try {
            val updateElement = params.jsonObject["update"] ?: params
            val obj = updateElement.jsonObject
            val updateType = obj["sessionUpdate"]?.jsonPrimitive?.contentOrNull ?: return

            log.debug("Session update: $updateType")

            when (updateType) {
                "agent_message_chunk" -> {
                    val text = extractUpdateText(obj) ?: return
                    _currentAgentText.append(text)
                    val accumulated = _currentAgentText.toString()
                    if (accumulated.isNotBlank()) {
                        val streamingMessage = ChatMessage(
                            role = "assistant",
                            content = accumulated,
                            isStreaming = true,
                        )
                        notifyMessageListeners(streamingMessage)
                    }
                }
                "agent_message_end" -> {
                    extractUpdateText(obj)?.let { _currentAgentText.append(it) }
                    finalizeCurrentText()
                }
                "tool_call" -> {
                    finalizeCurrentText()

                    val toolCall = obj["toolCall"]?.let {
                        try { json.decodeFromJsonElement<ToolCallUpdate>(it) } catch (_: Exception) { null }
                    }
                    toolCall?.let { tc ->
                        val msg = ChatMessage(
                            role = "tool",
                            content = "Calling ${tc.toolName ?: "tool"}...",
                            toolCalls = listOf(tc),
                        )
                        notifyMessageListeners(msg)
                    }
                }
                "mode_change" -> {
                    val newMode = obj["mode"]?.jsonPrimitive?.contentOrNull
                    newMode?.let { mode = SessionMode.fromValue(it) }
                }
                "config_options_update" -> {
                    val options = obj["configOptions"]?.let {
                        try { json.decodeFromJsonElement<List<ConfigOption>>(it) } catch (_: Exception) { null }
                    }
                    if (options != null) {
                        updateConfigOptions(options)
                    }
                }
            }

            try {
                val update = json.decodeFromJsonElement<SessionUpdate>(updateElement)
                for (listener in updateListeners) {
                    listener(update)
                }
            } catch (_: Exception) { }
        } catch (e: Exception) {
            log.warn("Failed to handle session update", e)
        }
    }

    private fun finalizeCurrentText() {
        val finalText = _currentAgentText.toString()
        if (finalText.isNotBlank()) {
            val finalMessage = ChatMessage(
                role = "assistant",
                content = finalText,
                isStreaming = false,
            )
            _messages.add(finalMessage)
            notifyMessageListeners(finalMessage)
        }
        _currentAgentText.clear()
    }

    private fun extractUpdateText(updateObj: JsonObject): String? {
        return AcpContentExtractor.extractTextFromContent(updateObj["content"])
            ?: updateObj["text"]?.jsonPrimitive?.contentOrNull
            ?: AcpContentExtractor.extractTextFromContent(updateObj["message"])
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
            finalizeCurrentText()
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

    suspend fun setConfigOption(configId: String, value: String) {
        try {
            val result = client.sessionSetConfigOption(sessionId, configId, value)
            if (result.configOptions.isNotEmpty()) {
                updateConfigOptions(result.configOptions)
                return
            }
        } catch (e: Exception) {
            log.info("session/set_config_option not supported by agent, updating locally: ${e.message}")
        }
        val updated = _configOptions.map { option ->
            if (option.id == configId) option.copy(currentValue = value) else option
        }
        updateConfigOptions(updated)
    }

    private fun updateConfigOptions(options: List<ConfigOption>) {
        _configOptions.clear()
        _configOptions.addAll(options)
        for (listener in configListeners) {
            try {
                listener(options)
            } catch (e: Exception) {
                log.warn("Config listener error", e)
            }
        }
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
