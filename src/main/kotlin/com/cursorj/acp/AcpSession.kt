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

data class ToolActivity(
    val text: String,
    val path: String? = null,
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

    private val _planEntries = mutableListOf<PlanEntry>()
    val planEntries: List<PlanEntry> get() = _planEntries.toList()

    private val _toolCallContents = mutableMapOf<String, StringBuilder>()
    val toolCallContents: Map<String, String>
        get() = _toolCallContents.mapValues { it.value.toString() }

    private val _planContent = StringBuilder()
    val planContent: String get() = _planContent.toString()

    var planCreated: Boolean = false
        private set

    private val _thoughtContent = StringBuilder()
    val thoughtContent: String get() = _thoughtContent.toString()

    private val updateListeners = mutableListOf<(SessionUpdate) -> Unit>()
    private val messageListeners = mutableListOf<(ChatMessage) -> Unit>()
    private val configListeners = mutableListOf<(List<ConfigOption>) -> Unit>()
    private val activityListeners = mutableListOf<(String) -> Unit>()
    private val toolCallListeners = mutableListOf<(String, ToolActivity) -> Unit>()
    private val planListeners = mutableListOf<(List<PlanEntry>) -> Unit>()

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

    fun addActivityListener(listener: (String) -> Unit) {
        activityListeners.add(listener)
    }

    fun addToolCallListener(listener: (id: String, activity: ToolActivity) -> Unit) {
        toolCallListeners.add(listener)
    }

    fun addPlanListener(listener: (List<PlanEntry>) -> Unit) {
        planListeners.add(listener)
    }

    fun getConfigOption(category: String): ConfigOption? {
        return _configOptions.firstOrNull { it.category == category }
    }

    fun handleSessionUpdate(params: JsonElement) {
        try {
            val updateElement = params.jsonObject["update"] ?: params
            val obj = updateElement.jsonObject
            val updateType = obj["sessionUpdate"]?.jsonPrimitive?.contentOrNull ?: return

            log.info("Session update type: $updateType, keys: ${obj.keys}")

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
                "agent_thought_chunk" -> {
                    val text = extractUpdateText(obj)
                    if (text != null) _thoughtContent.append(text)
                }
                "tool_call" -> {
                    finalizeCurrentText()
                    val toolCallId = obj["toolCallId"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                    val kind = obj["kind"]?.jsonPrimitive?.contentOrNull
                    val status = obj["status"]?.jsonPrimitive?.contentOrNull
                    log.info("tool_call: id=$toolCallId kind=$kind status=$status hasContent=${obj.containsKey("content")} hasRawOutput=${obj.containsKey("rawOutput")}")
                    captureToolCallContent(toolCallId, obj)
                    formatToolActivity(obj)?.let { activity ->
                        notifyActivityListeners(activity.text)
                        notifyToolCallListeners(toolCallId, activity)
                    }
                }
                "tool_call_update" -> {
                    val toolCallId = obj["toolCallId"]?.jsonPrimitive?.contentOrNull ?: return
                    val status = obj["status"]?.jsonPrimitive?.contentOrNull
                    log.info("tool_call_update: id=$toolCallId status=$status hasContent=${obj.containsKey("content")} hasRawOutput=${obj.containsKey("rawOutput")}")
                    captureToolCallContent(toolCallId, obj)
                    formatToolActivity(obj)?.let { activity ->
                        notifyActivityListeners(activity.text)
                        notifyToolCallListeners(toolCallId, activity)
                    }
                }
                "tool_result" -> {
                    notifyActivityListeners("Processing results...")
                }
                "plan" -> {
                    log.info("plan update: entries key exists=${obj.containsKey("entries")}, raw=${obj["entries"]?.toString()?.take(200)}")
                    val entries = obj["entries"]?.let {
                        try { json.decodeFromJsonElement<List<PlanEntry>>(it) } catch (e: Exception) {
                            log.warn("Failed to decode plan entries", e)
                            null
                        }
                    }
                    if (entries != null) {
                        log.info("Decoded ${entries.size} plan entries")
                        _planEntries.clear()
                        _planEntries.addAll(entries)
                        notifyPlanListeners(entries)
                    }
                }
                "current_mode_update" -> {
                    val newMode = obj["modeId"]?.jsonPrimitive?.contentOrNull
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
                else -> {
                    log.info("Unhandled session update type: $updateType, keys: ${obj.keys}")
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

    fun handleCreatePlan(params: JsonElement) {
        planCreated = true
        try {
            val obj = params.jsonObject
            log.info("handleCreatePlan keys: ${obj.keys}, full: ${params.toString().take(1000)}")

            // Try all possible fields where plan content might live
            val content = AcpContentExtractor.extractTextFromContent(obj["content"])
                ?: obj["text"]?.jsonPrimitive?.contentOrNull
                ?: obj["description"]?.jsonPrimitive?.contentOrNull
                ?: obj["plan"]?.let { AcpContentExtractor.extractTextFromContent(it) }
                ?: obj["markdown"]?.jsonPrimitive?.contentOrNull
            if (content != null) {
                log.info("Captured plan content (${content.length} chars)")
                _planContent.append(content)
            }

            // Try to extract plan entries
            val entriesKey = obj["entries"] ?: obj["steps"] ?: obj["items"]
            if (entriesKey != null) {
                try {
                    val entries = json.decodeFromJsonElement<List<PlanEntry>>(entriesKey)
                    log.info("Captured ${entries.size} plan entries from create_plan")
                    _planEntries.clear()
                    _planEntries.addAll(entries)
                    notifyPlanListeners(entries)
                } catch (e: Exception) {
                    log.info("Could not decode plan entries: ${e.message}")
                }
            }
        } catch (e: Exception) {
            log.warn("Error handling create_plan", e)
        }
    }

    private fun captureToolCallContent(toolCallId: String, obj: JsonObject) {
        val text = AcpContentExtractor.extractTextFromContent(obj["content"])
            ?: obj["rawOutput"]?.jsonPrimitive?.contentOrNull
        if (text != null) {
            log.info("Captured tool call content for $toolCallId (${text.length} chars)")
            _toolCallContents.getOrPut(toolCallId) { StringBuilder() }.append(text)
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

    private fun formatToolActivity(obj: JsonObject): ToolActivity? {
        val kind = obj["kind"]?.jsonPrimitive?.contentOrNull
        val title = obj["title"]?.jsonPrimitive?.contentOrNull?.replace("`", "")?.trim()
        val rawInput = try { obj["rawInput"]?.jsonObject } catch (_: Exception) { null }

        val detail: String? = when (kind) {
            "edit" -> {
                rawInput?.get("path")?.jsonPrimitive?.contentOrNull
                    ?.substringAfterLast('/')?.substringAfterLast('\\')
                    ?: title?.removePrefix("Edit")?.trim()?.takeIf { it.isNotBlank() }
            }
            "execute" -> {
                rawInput?.get("command")?.jsonPrimitive?.contentOrNull
                    ?: title?.takeIf { it != "Terminal" }
            }
            "read" -> {
                title?.removePrefix("Read")?.trim()?.takeIf { it.isNotBlank() }
            }
            else -> title
        }

        val truncatedDetail = detail
            ?.let { if (it.length > 50) it.take(47) + "..." else it }

        val verb = when (kind) {
            "edit" -> "Editing"
            "execute" -> "Executing"
            "read" -> "Reading"
            "write" -> "Writing"
            "search" -> "Searching"
            "delete" -> "Deleting"
            else -> null
        }

        val text = when {
            verb != null && truncatedDetail != null -> "$verb $truncatedDetail..."
            verb != null -> "$verb..."
            title != null -> "$title..."
            else -> return null
        }

        val path: String? = when (kind) {
            "edit", "write", "read", "delete" -> {
                rawInput?.get("path")?.jsonPrimitive?.contentOrNull
                    ?: rawInput?.get("filePath")?.jsonPrimitive?.contentOrNull
            }
            "move" -> {
                rawInput?.get("toPath")?.jsonPrimitive?.contentOrNull
                    ?: rawInput?.get("newPath")?.jsonPrimitive?.contentOrNull
                    ?: rawInput?.get("path")?.jsonPrimitive?.contentOrNull
            }
            else -> {
                rawInput?.get("path")?.jsonPrimitive?.contentOrNull
                    ?: rawInput?.get("filePath")?.jsonPrimitive?.contentOrNull
            }
        }

        return ToolActivity(text = text, path = path)
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
        _toolCallContents.clear()
        _planContent.setLength(0)
        _thoughtContent.setLength(0)
        planCreated = false
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

    private fun notifyToolCallListeners(id: String, activity: ToolActivity) {
        for (listener in toolCallListeners) {
            try {
                listener(id, activity)
            } catch (e: Exception) {
                log.warn("Tool call listener error", e)
            }
        }
    }

    private fun notifyPlanListeners(entries: List<PlanEntry>) {
        for (listener in planListeners) {
            try {
                listener(entries)
            } catch (e: Exception) {
                log.warn("Plan listener error", e)
            }
        }
    }

    private fun notifyActivityListeners(activity: String) {
        for (listener in activityListeners) {
            try {
                listener(activity)
            } catch (e: Exception) {
                log.warn("Activity listener error", e)
            }
        }
    }
}
