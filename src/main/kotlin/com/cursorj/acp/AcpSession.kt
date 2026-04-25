package com.cursorj.acp

import com.cursorj.acp.messages.*
import com.cursorj.rollback.RollbackResult
import com.cursorj.rollback.TurnRollbackManager
import com.cursorj.settings.CursorJSettings
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
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
    /** Per-turn token usage from [SessionPromptResult.usage] when the agent reports it. */
    val turnUsage: TokenUsage? = null,
)

data class ToolActivity(
    val text: String,
    val path: String? = null,
    val kind: String? = null,
)

data class EditDiffContent(
    val toolCallId: String,
    val path: String,
    val oldText: String,
    val newText: String,
)

class AcpSession(
    val sessionId: String,
    private val client: AcpClient,
    private val rollbackManager: TurnRollbackManager,
    initialConfigOptions: List<ConfigOption> = emptyList(),
    initialModels: AcpModelsInfo? = null,
) {
    /**
     * Invoked when a path under `.cursor/plans/` (markdown plan files) is recorded: initial save, tool status, or edit diff.
     * Used to refresh open editor buffers from disk.
     */
    var onPlanFileTouch: ((String) -> Unit)? = null

    private val log = Logger.getInstance(AcpSession::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val prettyJson = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var mode: SessionMode = SessionMode.AGENT
        private set
    var title: String = "New Chat"
        internal set
    var isProcessing: Boolean = false
        private set

    var acpModels: AcpModelsInfo? = initialModels
        private set

    private val _configOptions = mutableListOf<ConfigOption>().apply { addAll(initialConfigOptions) }
    val configOptions: List<ConfigOption> get() = _configOptions.toList()

    private val _messages = mutableListOf<ChatMessage>()
    val messages: List<ChatMessage> get() = _messages.toList()

    private val _planEntries = mutableListOf<PlanEntry>()
    val planEntries: List<PlanEntry> get() = _planEntries.toList()

    private val _todos = mutableListOf<TodoItem>()
    val todos: List<TodoItem> get() = _todos.toList()

    private val _availableCommands = mutableListOf<String>()
    val availableCommands: List<String> get() = _availableCommands.toList()

    private val _toolCallContents = mutableMapOf<String, StringBuilder>()
    val toolCallContents: Map<String, String>
        get() = _toolCallContents.mapValues { it.value.toString() }

    private val _planContent = StringBuilder()
    val planContent: String get() = _planContent.toString()

    var planCreated: Boolean = false
        private set

    /** Workspace path to the plan file written by the agent (e.g. under `.cursor/plans/`), normalized `/`. */
    var agentWrittenPlanPath: String? = null
        private set

    /** Optional path hint from `cursor/create_plan` if the agent sends one (relative or absolute). */
    var agentPlanPathHint: String? = null
        private set

    private val _thoughtContent = StringBuilder()
    val thoughtContent: String get() = _thoughtContent.toString()

    private data class TrackedToolCall(
        val toolCallId: String,
        var kind: String? = null,
        var commandPreview: String? = null,
        var inProgressSinceMs: Long? = null,
        var lastWarnedAtMs: Long = 0L,
        var failFastTriggered: Boolean = false,
    )

    private data class ToolCallWatchdogSignal(
        val toolCallId: String,
        val commandPreview: String?,
        val elapsedMs: Long,
    )

    private val toolCallStateLock = Any()
    private val trackedToolCalls = linkedMapOf<String, TrackedToolCall>()

    private val updateListeners = mutableListOf<(SessionUpdate) -> Unit>()
    private val messageListeners = mutableListOf<(ChatMessage) -> Unit>()
    private val configListeners = mutableListOf<(List<ConfigOption>) -> Unit>()
    private val activityListeners = mutableListOf<(String) -> Unit>()
    private val toolCallListeners = mutableListOf<(String, ToolActivity) -> Unit>()
    private val planListeners = mutableListOf<(List<PlanEntry>) -> Unit>()
    private val editDiffListeners = mutableListOf<(EditDiffContent) -> Unit>()
    private val availableCommandsListeners = mutableListOf<(List<String>) -> Unit>()
    private val usageListeners = mutableListOf<(SessionUsageInfo) -> Unit>()
    private val subagentTaskListeners = mutableListOf<(SubagentTaskEvent) -> Unit>()

    private val _subagentTaskHistory = mutableListOf<SubagentTaskEvent>()
    private val subagentTaskHistoryLock = Any()

    @Volatile
    private var pendingFirstUpdate: CompletableDeferred<Unit>? = null

    private val _currentAgentText = StringBuilder()

    private var _sessionUsage: SessionUsageInfo? = null
    val sessionUsage: SessionUsageInfo? get() = _sessionUsage

    private var _lastTurnUsage: TokenUsage? = null
    val lastTurnUsage: TokenUsage? get() = _lastTurnUsage

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

    fun addEditDiffListener(listener: (EditDiffContent) -> Unit) {
        editDiffListeners.add(listener)
    }

    fun addPlanListener(listener: (List<PlanEntry>) -> Unit) {
        planListeners.add(listener)
    }

    fun addAvailableCommandsListener(listener: (List<String>) -> Unit) {
        availableCommandsListeners.add(listener)
    }

    fun addUsageListener(listener: (SessionUsageInfo) -> Unit) {
        usageListeners.add(listener)
    }

    fun addSubagentTaskListener(listener: (SubagentTaskEvent) -> Unit) {
        subagentTaskListeners.add(listener)
    }

    val subagentTaskHistory: List<SubagentTaskEvent>
        get() = synchronized(subagentTaskHistoryLock) { _subagentTaskHistory.toList() }

    fun getConfigOption(category: String): ConfigOption? {
        return _configOptions.firstOrNull { it.category == category }
    }

    fun handleSessionUpdate(params: JsonElement) {
        pendingFirstUpdate?.complete(Unit)
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
                    debugDumpToolCall("tool_call", toolCallId, kind, obj)
                    if (kind == "execute") {
                        extractExecuteCommandPreview(obj)?.let { cmd ->
                            log.info("tool_call execute command: $cmd")
                        }
                    }
                    updateTrackedToolCall(
                        toolCallId = toolCallId,
                        kind = kind,
                        status = status,
                        commandPreview = extractExecuteCommandPreview(obj),
                    )
                    captureToolCallContent(toolCallId, obj)
                    tryRecordPlanFilePathFromToolCallPayload(obj)
                    formatToolActivity(obj)?.let { activity ->
                        notifyActivityListeners(activity.text)
                        notifyToolCallListeners(toolCallId, activity)
                    }
                }
                "tool_call_update" -> {
                    val toolCallId = obj["toolCallId"]?.jsonPrimitive?.contentOrNull ?: return
                    val kind = obj["kind"]?.jsonPrimitive?.contentOrNull
                    val status = obj["status"]?.jsonPrimitive?.contentOrNull
                    log.info("tool_call_update: id=$toolCallId status=$status hasContent=${obj.containsKey("content")} hasRawOutput=${obj.containsKey("rawOutput")}")
                    debugDumpToolCall("tool_call_update", toolCallId, kind, obj)
                    val commandPreview = extractExecuteCommandPreview(obj)
                    if (status == "in_progress") {
                        commandPreview?.let { cmd ->
                            log.info("tool_call_update execute command: $cmd")
                        }
                    }
                    updateTrackedToolCall(
                        toolCallId = toolCallId,
                        kind = kind,
                        status = status,
                        commandPreview = commandPreview,
                    )
                    captureToolCallContent(toolCallId, obj)
                    tryRecordPlanFilePathFromToolCallPayload(obj)
                    if (status == "completed") {
                        extractEditDiffs(toolCallId, obj)
                    }
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
                        if (entries.isNotEmpty()) {
                            planCreated = true
                        }
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
                "available_commands_update" -> {
                    val commands = parseAvailableCommands(obj["availableCommands"])
                    if (commands.isNotEmpty()) {
                        updateAvailableCommands(commands)
                    }
                }
                "usage_update" -> {
                    val used = obj["used"]?.jsonPrimitive?.longOrNull ?: 0L
                    val size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L
                    val cost = obj["cost"]?.takeUnless { it is JsonNull }?.let {
                        runCatching { json.decodeFromJsonElement<UsageCost>(it) }.getOrNull()
                    }
                    val usageInfo = SessionUsageInfo(used = used, size = size, cost = cost)
                    _sessionUsage = usageInfo
                    notifyUsageListeners(usageInfo)
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

    fun recordAgentPlanFileWritten(absolutePath: String) {
        val normalized = absolutePath.replace('\\', '/')
        agentWrittenPlanPath = normalized
        if (AgentPlanFileSupport.isCursorAgentPlanMarkdownPath(normalized)) {
            planCreated = true
            onPlanFileTouch?.invoke(normalized)
        }
        log.info("Recorded agent plan file: $normalized")
    }

    /**
     * Picks up paths like `Plan saved to file:///...` (often under user home `.cursor/plans/`, not the workspace).
     */
    private fun tryRecordPlanFilePathFromToolCallPayload(obj: JsonObject) {
        val text = AcpContentExtractor.extractTextFromContent(obj["content"]) ?: return
        val path = AgentPlanFileSupport.parseLocalPathFromToolCallStatusText(text) ?: return
        recordAgentPlanFileWritten(path)
    }

    fun handleCreatePlan(params: JsonElement) {
        planCreated = true
        try {
            val obj = params.jsonObject
            log.info("handleCreatePlan keys: ${obj.keys}, full: ${params.toString().take(1000)}")

            agentPlanPathHint = listOf("path", "planPath", "file", "planFile", "outputPath")
                .firstNotNullOfOrNull { key -> obj[key]?.jsonPrimitive?.contentOrNull }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (agentPlanPathHint != null) {
                log.info("Plan path hint from create_plan: $agentPlanPathHint")
            }

            // Try all possible fields where plan content might live (see `cursor/create_plan` payloads)
            val content = AcpContentExtractor.extractTextFromContent(obj["content"])
                ?: obj["text"]?.jsonPrimitive?.contentOrNull
                ?: obj["description"]?.jsonPrimitive?.contentOrNull
                ?: obj["plan"]?.let { AcpContentExtractor.extractTextFromContent(it) }
                ?: obj["markdown"]?.jsonPrimitive?.contentOrNull
                ?: obj["overview"]?.jsonPrimitive?.contentOrNull
            if (content != null) {
                log.info("Captured plan content (${content.length} chars)")
                _planContent.append(content)
            }

            // Try to extract plan entries (`todos` is used by cursor/create_plan alongside `plan` markdown)
            val entriesKey = obj["entries"] ?: obj["steps"] ?: obj["items"] ?: obj["todos"]
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

    fun handleUpdateTodos(params: JsonElement) {
        try {
            val obj = params.jsonObject
            val todosElement = obj["todos"] ?: return
            val parsedTodos = json.decodeFromJsonElement<List<TodoItem>>(todosElement)
            _todos.clear()
            _todos.addAll(parsedTodos)
            log.info("Updated todos from _cursor/update_todos: ${parsedTodos.size} item(s)")
        } catch (e: Exception) {
            log.warn("Error handling _cursor/update_todos", e)
        }
    }

    /**
     * Handles Cursor ACP `cursor/task` (subagent) payloads from notifications or server requests.
     * @return parsed event for JSON-RPC result fields, or null if the payload could not be parsed.
     */
    fun handleCursorTask(params: JsonElement): SubagentTaskEvent? {
        val obj = params as? JsonObject
        val event = CursorTaskPayload.parse(params) ?: run {
            log.warn("cursor/task: parse failed, param keys=${obj?.keys}")
            return null
        }
        val promptRaw = obj?.get("prompt")?.jsonPrimitive?.contentOrNull
        if (!promptRaw.isNullOrBlank()) {
            log.info(
                "cursor/task id=${event.toolCallId} type=${event.subagentTypeLabel} " +
                    "desc=${event.description.take(120)} prompt=${CursorTaskPayload.promptForLog(promptRaw)}",
            )
        } else {
            log.info(
                "cursor/task id=${event.toolCallId} type=${event.subagentTypeLabel} " +
                    "desc=${event.description.take(120)}",
            )
        }
        synchronized(subagentTaskHistoryLock) {
            val idx = _subagentTaskHistory.indexOfLast { it.toolCallId == event.toolCallId }
            if (idx >= 0) {
                _subagentTaskHistory[idx] = event
            } else {
                _subagentTaskHistory.add(event)
            }
            while (_subagentTaskHistory.size > MAX_SUBAGENT_TASK_HISTORY) {
                _subagentTaskHistory.removeAt(0)
            }
        }
        notifySubagentTaskListeners(event)
        return event
    }

    private fun extractEditDiffs(toolCallId: String, obj: JsonObject) {
        val contentArray = obj["content"]?.let {
            if (it is JsonArray) it else null
        } ?: return
        for (element in contentArray) {
            val entry = (element as? JsonObject) ?: continue
            val type = entry["type"]?.jsonPrimitive?.contentOrNull ?: continue
            if (type != "diff") continue
            val path = entry["path"]?.jsonPrimitive?.contentOrNull ?: continue
            val oldTextElement = entry["oldText"]
            val oldText = if (oldTextElement == null || oldTextElement is JsonNull) ""
                else oldTextElement.jsonPrimitive.contentOrNull ?: ""
            val newTextElement = entry["newText"]
            val newText = if (newTextElement == null || newTextElement is JsonNull) continue
                else newTextElement.jsonPrimitive.contentOrNull ?: continue
            if (oldText == newText) continue
            val normalizedPlanPath = runCatching {
                java.io.File(path).canonicalFile.absolutePath.replace('\\', '/')
            }.getOrElse { path.replace('\\', '/') }
            if (AgentPlanFileSupport.isCursorAgentPlanMarkdownPath(normalizedPlanPath)) {
                recordAgentPlanFileWritten(normalizedPlanPath)
                log.info("Plan file touched via edit diff: $normalizedPlanPath")
            }
            val diff = EditDiffContent(toolCallId, path, oldText, newText)
            for (listener in editDiffListeners) {
                try {
                    listener(diff)
                } catch (e: Exception) {
                    log.warn("Error in edit diff listener", e)
                }
            }
        }
    }

    private fun debugDumpToolCall(event: String, toolCallId: String, kind: String?, obj: JsonObject) {
        if (!runCatching { CursorJSettings.instance.enableAcpRawLogging }.getOrDefault(false)) return
        try {
            val formatted = prettyJson.encodeToString(JsonObject.serializer(), obj)
            val entry = "=== $event id=$toolCallId kind=$kind ===\n$formatted\n\n"
            log.info("DEBUG_TOOL_CALL [$event] id=$toolCallId kind=$kind keys=${obj.keys}")
            debugLogFile?.let { file ->
                file.parentFile?.mkdirs()
                file.appendText(entry)
            }
        } catch (e: Exception) {
            log.info("DEBUG_TOOL_CALL [$event] id=$toolCallId kind=$kind (dump failed: ${e.message})")
        }
    }


    private fun captureToolCallContent(toolCallId: String, obj: JsonObject) {
        val text = AcpContentExtractor.extractTextFromContent(obj["content"])
            ?: extractRawOutputText(obj["rawOutput"])
        if (!text.isNullOrBlank()) {
            log.info("Captured tool call content for $toolCallId (${text.length} chars)")
            _toolCallContents.getOrPut(toolCallId) { StringBuilder() }.append(text)
        }
    }

    private fun extractRawOutputText(rawOutput: JsonElement?): String? {
        return when (rawOutput) {
            null, JsonNull -> null
            is JsonPrimitive -> rawOutput.contentOrNull
            is JsonObject, is JsonArray -> {
                AcpContentExtractor.extractTextFromContent(rawOutput) ?: rawOutput.toString()
            }
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

    /**
     * Attaches [usage] to the most recent finalized assistant message (same turn as [SessionPromptResult]).
     */
    internal fun applyTurnUsageToLastAssistant(usage: TokenUsage) {
        _lastTurnUsage = usage
        for (i in _messages.indices.reversed()) {
            val msg = _messages[i]
            if (msg.role == "assistant" && !msg.isStreaming) {
                val updated = msg.copy(turnUsage = usage)
                _messages[i] = updated
                notifyMessageListeners(updated)
                return
            }
        }
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

        return ToolActivity(text = text, path = path, kind = kind)
    }

    private fun extractUpdateText(updateObj: JsonObject): String? {
        return AcpContentExtractor.extractTextFromContent(updateObj["content"])
            ?: updateObj["text"]?.jsonPrimitive?.contentOrNull
            ?: AcpContentExtractor.extractTextFromContent(updateObj["message"])
    }

    private fun extractExecuteCommandPreview(obj: JsonObject): String? {
        val kind = obj["kind"]?.jsonPrimitive?.contentOrNull
        if (kind != "execute") return null
        val rawInput = obj["rawInput"]?.jsonObject ?: return null
        val command = rawInput["command"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val args = rawInput["args"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (command.isBlank()) return null
        val full = if (args.isEmpty()) command else (sequenceOf(command) + args.asSequence()).joinToString(" ")
        return if (full.length > 300) full.take(297) + "..." else full
    }

    suspend fun sendPrompt(content: List<ContentBlock>, displayUserText: String? = null): SessionPromptResult {
        val promptText = content.filterIsInstance<TextContent>().joinToString(" ") { it.text }
        val displayText = displayUserText?.takeIf { it.isNotBlank() } ?: promptText
        if (title == "New Chat" && displayText.isNotBlank()) {
            title = displayText.take(40).let { if (displayText.length > 40) "$it..." else it }
        }

        val userMessage = ChatMessage(role = "user", content = displayText)
        _messages.add(userMessage)
        notifyMessageListeners(userMessage)

        val turnId = rollbackManager.beginTurn(sessionId, displayText)
        isProcessing = true
        _currentAgentText.clear()
        _toolCallContents.clear()
        _planContent.setLength(0)
        _thoughtContent.setLength(0)
        planCreated = false
        agentWrittenPlanPath = null
        agentPlanPathHint = null
        clearTrackedToolCalls()

        val firstUpdateSignal = CompletableDeferred<Unit>()
        pendingFirstUpdate = firstUpdateSignal

        var interrupted = false
        var lastPromptResult: SessionPromptResult? = null
        val stuckToolSignal = CompletableDeferred<ToolCallWatchdogSignal>()
        val promptDeferred = sessionScope.async(start = CoroutineStart.LAZY) {
            client.sessionPrompt(sessionId, content)
        }
        val watchdogJob = sessionScope.launch {
            monitorToolCalls(stuckToolSignal)
        }
        val firstUpdateTimeoutJob = sessionScope.launch {
            val received = withTimeoutOrNull(FIRST_UPDATE_TIMEOUT_MS) {
                firstUpdateSignal.await()
            }
            if (received == null && !stuckToolSignal.isCompleted) {
                stuckToolSignal.complete(
                    ToolCallWatchdogSignal(
                        toolCallId = "no-response",
                        commandPreview = null,
                        elapsedMs = FIRST_UPDATE_TIMEOUT_MS,
                    ),
                )
            }
        }
        val livenessJob = sessionScope.launch {
            while (currentCoroutineContext().isActive && isProcessing) {
                delay(LIVENESS_CHECK_INTERVAL_MS)
                if (!client.isConnected && !stuckToolSignal.isCompleted) {
                    stuckToolSignal.complete(
                        ToolCallWatchdogSignal(
                            toolCallId = "connection-lost",
                            commandPreview = null,
                            elapsedMs = 0,
                        ),
                    )
                    break
                }
            }
        }
        return try {
            withTimeout(MAX_TURN_DURATION_MS) {
                promptDeferred.start()
                select {
                    promptDeferred.onAwait { result ->
                        lastPromptResult = result
                        result
                    }
                    stuckToolSignal.onAwait { signal ->
                        interrupted = true
                        val message = buildFailFastMessage(signal)
                        log.warn(
                            "Watchdog fail-fast for session=$sessionId " +
                                "signal=${signal.toolCallId} elapsedMs=${signal.elapsedMs} " +
                                "command=${signal.commandPreview ?: "<unknown>"}",
                        )
                        notifyActivityListeners(message)
                        notifyToolCallListeners(
                            signal.toolCallId,
                            ToolActivity(text = message),
                        )
                        sessionScope.launch {
                            runCatching { client.sessionCancel(sessionId) }
                                .onFailure { e ->
                                    log.info("session/cancel after watchdog trigger failed: ${e.message}")
                                }
                        }
                        promptDeferred.cancel(CancellationException(message))
                        throw AcpException(TOOL_WATCHDOG_ERROR_CODE, message)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            interrupted = true
            val message = "Turn timed out after ${MAX_TURN_DURATION_MS / 1000}s"
            log.warn(message)
            sessionScope.launch {
                runCatching { client.sessionCancel(sessionId) }
            }
            promptDeferred.cancel(CancellationException(message))
            throw AcpException(TOOL_WATCHDOG_ERROR_CODE, message)
        } catch (e: AcpException) {
            if (e.code == -1 || e.code == TOOL_WATCHDOG_ERROR_CODE) {
                interrupted = true
            }
            throw e
        } finally {
            pendingFirstUpdate = null
            watchdogJob.cancel()
            firstUpdateTimeoutJob.cancel()
            livenessJob.cancel()
            promptDeferred.cancel()
            isProcessing = false
            finalizeCurrentText()
            lastPromptResult?.usage?.let { applyTurnUsageToLastAssistant(it) }
            clearTrackedToolCalls()
            rollbackManager.completeTurn(sessionId, turnId, interrupted = interrupted)
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

    suspend fun setModel(modelId: String) {
        client.sessionSetModel(sessionId, modelId)
    }

    suspend fun setConfigOption(configId: String, value: String) {
        val isModelSwitch = ConfigOptionUiSupport.isModelConfigId(configId)
        val requestedValue = value.trim()
        if (isModelSwitch) {
            log.info(
                "model-switch: phase=request sessionId=$sessionId configId=$configId requested=$requestedValue",
            )
        }
        try {
            val result = client.sessionSetConfigOption(sessionId, configId, value)
            if (result.configOptions.isNotEmpty()) {
                updateConfigOptions(result.configOptions)
                if (isModelSwitch) {
                    val confirmed = result.configOptions
                        .firstOrNull { ConfigOptionUiSupport.isModelSelector(it) }
                        ?.currentValue
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                    log.info(
                        "model-switch: phase=response sessionId=$sessionId configId=$configId " +
                            "requested=$requestedValue confirmed=${confirmed ?: "<missing>"} " +
                            "optionsCount=${result.configOptions.size}",
                    )
                }
            } else {
                if (!isModelSwitch) {
                    applyLocalConfigOption(configId, value)
                } else {
                    log.info(
                        "model-switch: phase=response sessionId=$sessionId configId=$configId " +
                            "requested=$requestedValue confirmed=<missing> optionsCount=0",
                    )
                }
            }
        } catch (e: AcpException) {
            if (e.code == -32601) {
                if (isModelSwitch) {
                    log.info(
                        "model-switch: phase=fallback sessionId=$sessionId configId=$configId " +
                            "requested=$requestedValue fallback=session/set_model reason=${e.code}:${e.message}",
                    )
                    client.sessionSetModel(sessionId, value)
                } else {
                    log.info("session/set_config_option not supported, updating locally: ${e.message}")
                    applyLocalConfigOption(configId, value)
                }
            } else {
                throw e
            }
        }
    }

    private fun applyLocalConfigOption(configId: String, value: String) {
        val updated = _configOptions.map { option ->
            if (option.id == configId) option.copy(currentValue = value) else option
        }
        updateConfigOptions(updated)
    }

    private fun mergeConfigOption(existing: ConfigOption, incoming: ConfigOption): ConfigOption {
        if (!ConfigOptionUiSupport.isModelSelector(existing)) {
            return incoming
        }
        if (incoming.options.isEmpty() || existing.options.isEmpty()) {
            return incoming
        }
        if (incoming.options.size >= existing.options.size) {
            return incoming
        }

        // Some agents send incremental model option updates with a reduced option set.
        // Keep prior values appended so the dropdown does not unexpectedly shrink.
        val mergedOptions = mutableListOf<ConfigOptionValue>()
        val seen = mutableSetOf<String>()
        fun addUnique(value: ConfigOptionValue) {
            val key = value.value.trim().lowercase()
            if (seen.add(key)) {
                mergedOptions.add(value)
            }
        }

        incoming.options.forEach(::addUnique)
        existing.options.forEach(::addUnique)
        return incoming.copy(options = mergedOptions)
    }

    private fun mergeConfigOptions(incoming: List<ConfigOption>): List<ConfigOption> {
        if (_configOptions.isEmpty()) return incoming
        if (incoming.isEmpty()) return _configOptions.toList()

        val incomingById = incoming.associateBy { it.id }
        val merged = mutableListOf<ConfigOption>()
        val seenIds = mutableSetOf<String>()

        for (existing in _configOptions) {
            val replacement = incomingById[existing.id]
            if (replacement != null) {
                merged += mergeConfigOption(existing, replacement)
                seenIds += existing.id
            } else {
                merged += existing
                seenIds += existing.id
            }
        }

        for (option in incoming) {
            if (seenIds.add(option.id)) {
                merged += option
            }
        }
        return merged
    }

    fun canRollbackLastTurn(): Boolean {
        return rollbackManager.canRollback(sessionId, isProcessing)
    }

    fun rollbackLastTurn(): RollbackResult {
        return rollbackManager.rollbackLastTurn(sessionId, isProcessing)
    }

    fun markActiveTurnInterrupted() {
        rollbackManager.markActiveTurnInterrupted(sessionId)
    }

    private fun updateConfigOptions(options: List<ConfigOption>) {
        val merged = mergeConfigOptions(options)
        _configOptions.clear()
        _configOptions.addAll(merged)
        for (listener in configListeners) {
            try {
                listener(merged)
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

    private fun notifyAvailableCommandsListeners(commands: List<String>) {
        for (listener in availableCommandsListeners) {
            try {
                listener(commands)
            } catch (e: Exception) {
                log.warn("Available commands listener error", e)
            }
        }
    }

    private fun notifyUsageListeners(info: SessionUsageInfo) {
        for (listener in usageListeners) {
            try {
                listener(info)
            } catch (e: Exception) {
                log.warn("Usage listener error", e)
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

    private fun notifySubagentTaskListeners(event: SubagentTaskEvent) {
        for (listener in subagentTaskListeners) {
            try {
                listener(event)
            } catch (e: Exception) {
                log.warn("Subagent task listener error", e)
            }
        }
    }

    private fun updateTrackedToolCall(
        toolCallId: String,
        kind: String?,
        status: String?,
        commandPreview: String?,
    ) {
        if (toolCallId.isBlank() || toolCallId == "unknown") return
        synchronized(toolCallStateLock) {
            val normalizedStatus = status?.trim()?.lowercase()
            if (isTerminalToolCallStatus(normalizedStatus)) {
                trackedToolCalls.remove(toolCallId)
                return
            }
            val tracked = trackedToolCalls.getOrPut(toolCallId) {
                TrackedToolCall(toolCallId = toolCallId)
            }
            if (!kind.isNullOrBlank()) tracked.kind = kind
            if (!commandPreview.isNullOrBlank()) tracked.commandPreview = commandPreview
            if (normalizedStatus == "in_progress" && tracked.inProgressSinceMs == null) {
                tracked.inProgressSinceMs = System.currentTimeMillis()
            }
        }
    }

    private fun isTerminalToolCallStatus(status: String?): Boolean {
        return when (status) {
            "completed", "failed", "error", "cancelled", "canceled" -> true
            else -> false
        }
    }

    private suspend fun monitorToolCalls(stuckSignal: CompletableDeferred<ToolCallWatchdogSignal>) {
        while (currentCoroutineContext().isActive && isProcessing && !stuckSignal.isCompleted) {
            delay(TOOL_WATCHDOG_POLL_INTERVAL_MS)
            val now = System.currentTimeMillis()
            val warningMessages = mutableListOf<Pair<String, String>>()
            var failFastSignal: ToolCallWatchdogSignal? = null

            synchronized(toolCallStateLock) {
                for (tracked in trackedToolCalls.values) {
                    val startedAt = tracked.inProgressSinceMs ?: continue
                    val elapsed = now - startedAt
                    if (elapsed >= TOOL_WATCHDOG_WARN_AFTER_MS &&
                        (tracked.lastWarnedAtMs == 0L || now - tracked.lastWarnedAtMs >= TOOL_WATCHDOG_WARN_REPEAT_MS)
                    ) {
                        tracked.lastWarnedAtMs = now
                        val message = buildWatchdogStatusLine(tracked.toolCallId, tracked.commandPreview, elapsed)
                        warningMessages += tracked.toolCallId to message
                    }
                    val failFastThresholdMs = failFastThresholdFor(tracked)
                    if (!tracked.failFastTriggered && elapsed >= failFastThresholdMs) {
                        tracked.failFastTriggered = true
                        failFastSignal = ToolCallWatchdogSignal(
                            toolCallId = tracked.toolCallId,
                            commandPreview = tracked.commandPreview,
                            elapsedMs = elapsed,
                        )
                        break
                    }
                }
            }

            for ((toolCallId, message) in warningMessages) {
                log.warn("Tool call still in progress: $message")
                notifyActivityListeners(message)
                notifyToolCallListeners(toolCallId, ToolActivity(text = message))
            }

            val signal = failFastSignal ?: continue
            if (!stuckSignal.isCompleted) {
                stuckSignal.complete(signal)
            }
            return
        }
    }

    private fun buildWatchdogStatusLine(toolCallId: String, commandPreview: String?, elapsedMs: Long): String {
        val elapsedSeconds = (elapsedMs / 1000L).coerceAtLeast(1L)
        val commandText = commandPreview?.takeIf { it.isNotBlank() } ?: "<command unavailable>"
        val hint = buildCommandHint(commandPreview)
        return "Tool call stuck: id=$toolCallId, elapsed=${elapsedSeconds}s, command=$commandText$hint"
    }

    private fun buildFailFastMessage(signal: ToolCallWatchdogSignal): String {
        when (signal.toolCallId) {
            "no-response" -> {
                val elapsedSeconds = (signal.elapsedMs / 1000L).coerceAtLeast(1L)
                return "Agent did not respond within ${elapsedSeconds}s. " +
                    "The prompt may be too large or the agent may be unresponsive."
            }
            "connection-lost" -> {
                return "Agent connection lost while processing prompt."
            }
        }
        val elapsedSeconds = (signal.elapsedMs / 1000L).coerceAtLeast(1L)
        val commandText = signal.commandPreview?.takeIf { it.isNotBlank() } ?: "<command unavailable>"
        val hint = buildCommandHint(signal.commandPreview)
        return "Stopped waiting on stuck tool call ${signal.toolCallId} after ${elapsedSeconds}s. Command: $commandText$hint"
    }

    private fun clearTrackedToolCalls() {
        synchronized(toolCallStateLock) {
            trackedToolCalls.clear()
        }
    }

    fun dispose() {
        onPlanFileTouch = null
        sessionScope.cancel()
        clearTrackedToolCalls()
    }

    private fun failFastThresholdFor(tracked: TrackedToolCall): Long {
        val cmd = tracked.commandPreview?.lowercase().orEmpty()
        return if ("gradlew" in cmd || "gradle build" in cmd) {
            GRADLE_FAIL_FAST_MS
        } else {
            TOOL_WATCHDOG_FAIL_FAST_MS
        }
    }

    private fun buildCommandHint(commandPreview: String?): String {
        val cmd = commandPreview?.lowercase().orEmpty()
        if (!("gradlew" in cmd || "gradle build" in cmd)) {
            return ""
        }
        return " | Gradle hint: ensure JDK 17 is installed/detected and prefer --no-daemon --console=plain."
    }

    private fun parseAvailableCommands(element: JsonElement?): List<String> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull?.trim()
                is JsonObject -> {
                    item["name"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?: item["id"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?: item["command"]?.jsonPrimitive?.contentOrNull?.trim()
                }
                else -> null
            }
        }.filter { it.isNotEmpty() }
    }

    private fun updateAvailableCommands(commands: List<String>) {
        _availableCommands.clear()
        _availableCommands.addAll(commands)
        notifyAvailableCommandsListeners(_availableCommands.toList())
    }

    companion object {
        private const val TOOL_WATCHDOG_POLL_INTERVAL_MS = 2_000L
        private const val TOOL_WATCHDOG_WARN_AFTER_MS = 30_000L
        private const val TOOL_WATCHDOG_WARN_REPEAT_MS = 30_000L
        private const val TOOL_WATCHDOG_FAIL_FAST_MS = 5L * 60L * 1000L
        private const val GRADLE_FAIL_FAST_MS = 90_000L
        private const val TOOL_WATCHDOG_ERROR_CODE = -32001
        private const val FIRST_UPDATE_TIMEOUT_MS = 90_000L
        private const val LIVENESS_CHECK_INTERVAL_MS = 5_000L
        private const val MAX_TURN_DURATION_MS = 15L * 60L * 1000L
        private const val MAX_SUBAGENT_TASK_HISTORY = 20

        val debugLogFile: java.io.File? by lazy {
            val home = System.getProperty("user.home") ?: return@lazy null
            java.io.File(home, ".cursorj/debug/tool-calls.log")
        }
    }
}
