package com.cursorj.acp.messages

import kotlinx.serialization.*
import kotlinx.serialization.json.*

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String,
    val params: JsonElement = JsonObject(emptyMap()),
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Int? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

@Serializable
data class JsonRpcNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement = JsonObject(emptyMap()),
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

@Serializable
data class JsonRpcServerRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String,
    val params: JsonElement = JsonObject(emptyMap()),
)

@Serializable
data class JsonRpcServerResponse(
    val jsonrpc: String = "2.0",
    val id: Int,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

@Serializable
data class ClientInfo(
    val name: String,
    val title: String? = null,
    val version: String,
)

@Serializable
data class FsCapabilities(
    val readTextFile: Boolean = true,
    val writeTextFile: Boolean = true,
)

@Serializable
data class ClientCapabilities(
    val fs: FsCapabilities = FsCapabilities(),
    val terminal: Boolean = true,
)

@Serializable
data class InitializeParams(
    val protocolVersion: Int = 1,
    val clientCapabilities: ClientCapabilities = ClientCapabilities(),
    val clientInfo: ClientInfo = ClientInfo(
        name = "cursorj",
        title = "CursorJ",
        version = "0.1.0",
    ),
)

@Serializable
data class AgentInfo(
    val name: String? = null,
    val title: String? = null,
    val version: String? = null,
)

@Serializable
data class PromptCapabilities(
    val image: Boolean = false,
    val audio: Boolean = false,
    val embeddedContext: Boolean = false,
)

@Serializable
data class McpCapabilities(
    val http: Boolean = false,
    val sse: Boolean = false,
)

@Serializable
data class AgentCapabilities(
    val loadSession: Boolean = false,
    val promptCapabilities: PromptCapabilities? = null,
    val mcp: McpCapabilities? = null,
)

@Serializable
data class AuthMethod(
    val id: String,
    val displayName: String? = null,
)

@Serializable
data class InitializeResult(
    val protocolVersion: Int,
    val agentCapabilities: AgentCapabilities = AgentCapabilities(),
    val agentInfo: AgentInfo? = null,
    val authMethods: List<AuthMethod> = emptyList(),
)

@Serializable
data class AuthenticateParams(
    val methodId: String = "cursor_login",
)

@Serializable
data class SessionNewParams(
    val cwd: String,
    val mcpServers: List<JsonElement> = emptyList(),
)

@Serializable
data class ConfigOptionValue(
    val value: String,
    val name: String? = null,
    val description: String? = null,
)

@Serializable
data class ConfigOption(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val category: String? = null,
    val type: String = "select",
    val currentValue: String? = null,
    val options: List<ConfigOptionValue> = emptyList(),
)

@Serializable
data class SessionNewResult(
    val sessionId: String,
    val configOptions: List<ConfigOption> = emptyList(),
)

@Serializable
data class SetConfigOptionParams(
    val sessionId: String,
    val configId: String,
    val value: String,
)

@Serializable
data class SetConfigOptionResult(
    val configOptions: List<ConfigOption> = emptyList(),
)

@Serializable
data class SessionLoadParams(
    val sessionId: String,
)

@Serializable
data class SessionLoadResult(
    val sessionId: String,
)

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
sealed interface ContentBlock

@Serializable
@SerialName("text")
data class TextContent(
    val text: String,
) : ContentBlock

@Serializable
@SerialName("resource_link")
data class ResourceLinkContent(
    val uri: String,
    val name: String? = null,
    val mimeType: String? = null,
) : ContentBlock

@Serializable
@SerialName("image")
data class ImageContent(
    val data: String,
    val mimeType: String,
) : ContentBlock

@Serializable
@SerialName("resource")
data class ResourceContent(
    val uri: String,
    val name: String? = null,
    val mimeType: String? = null,
    val text: String? = null,
) : ContentBlock

@Serializable
data class SessionPromptParams(
    val sessionId: String,
    val prompt: List<ContentBlock>,
)

@Serializable
data class SessionPromptResult(
    val stopReason: String? = null,
)

@Serializable
data class SessionCancelParams(
    val sessionId: String,
)

@Serializable
data class SessionSetModeParams(
    val sessionId: String,
    val modeId: String,
)

@Serializable
data class SessionUpdateContent(
    val text: String? = null,
)

@Serializable
data class ToolCallUpdate(
    val toolCallId: String? = null,
    val toolName: String? = null,
    val status: String? = null,
    val arguments: JsonElement? = null,
    val result: JsonElement? = null,
)

@Serializable
data class PlanEntry(
    val content: String,
    val priority: String = "medium",
    val status: String = "pending",
)

@Serializable
data class TodoItem(
    val id: String,
    val content: String,
    val status: String = "pending",
)

@Serializable
data class SessionUpdate(
    val sessionUpdate: String,
    val content: SessionUpdateContent? = null,
    val toolCall: ToolCallUpdate? = null,
    val entries: List<PlanEntry>? = null,
    val todos: List<TodoItem>? = null,
    val modeId: String? = null,
)

@Serializable
data class PermissionOption(
    val optionId: String,
    val label: String? = null,
)

@Serializable
data class RequestPermissionParams(
    val toolName: String? = null,
    @SerialName("tool_name")
    val toolNameSnake: String? = null,
    val tool: String? = null,
    val name: String? = null,
    val method: String? = null,
    val description: String? = null,
    val arguments: JsonElement? = null,
    val options: List<PermissionOption> = listOf(
        PermissionOption("allow-once", "Allow Once"),
        PermissionOption("allow-always", "Allow Always"),
        PermissionOption("reject-once", "Reject"),
    ),
)

@Serializable
data class PermissionOutcome(
    val outcome: String = "selected",
    val optionId: String,
)

@Serializable
data class PermissionResult(
    val outcome: PermissionOutcome,
)

@Serializable
data class ReadTextFileParams(
    val path: String,
)

@Serializable
data class ReadTextFileResult(
    val content: String,
)

@Serializable
data class WriteTextFileParams(
    val path: String,
    val content: String,
)

@Serializable
data class TerminalCreateParams(
    val command: String,
    val cwd: String? = null,
)

@Serializable
data class TerminalCreateResult(
    val terminalId: String,
)

@Serializable
data class TerminalGetOutputParams(
    val terminalId: String,
)

@Serializable
data class TerminalGetOutputResult(
    val output: String,
    val exitCode: Int? = null,
)

@Serializable
data class TerminalIdParams(
    val terminalId: String,
)

@Serializable
data class TerminalWaitResult(
    val exitCode: Int,
)
