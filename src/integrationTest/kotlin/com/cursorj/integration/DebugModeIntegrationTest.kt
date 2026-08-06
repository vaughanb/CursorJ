package com.cursorj.integration

import com.cursorj.acp.AcpException
import com.cursorj.acp.AgentPathResolver
import com.cursorj.acp.ChatMode
import com.cursorj.acp.SessionMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.text.MessageFormat
import java.util.ResourceBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Manual-only real-agent checks for CursorJ's simulated Debug mode.
 *
 * Uses raw ACP JSON-RPC over `agent acp` (same approach as [MaxModeDiagnosticIntegrationTest])
 * so tests run without an IntelliJ Application. Gated by `CURSORJ_INTEGRATION=1` (never CI).
 *
 * Covers:
 * - ACP rejects mode id `debug`; Debug simulation stays on `agent`
 * - DebugLogServer HTTP capture is readable by the agent via fs tools
 * - A Debug-prefixed workflow prompt completes and shows instrument/reproduce behavior
 */
class DebugModeIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `ACP rejects debug mode id and Debug simulation uses agent`() {
        assumeManualOnly()
        assertEquals(SessionMode.AGENT, ChatMode.DEBUG.sessionMode)

        val agentPath = resolveAgentPath()
        val workspace = Files.createTempDirectory("cursorj-debug-mode-reject-it").toFile()
        try {
            withAcpSession(agentPath, workspace) { rpc, sessionId ->
                val debugFailure = assertFailsWith<AcpException> {
                    rpc.request(
                        "session/set_mode",
                        buildJsonObject {
                            put("sessionId", sessionId)
                            put("modeId", "debug")
                        },
                    )
                }
                // Agent may return a specific "Invalid mode ID" message or a generic internal error;
                // either way, mode id "debug" must not succeed.
                assertTrue(
                    debugFailure.message.isNotBlank(),
                    "Expected ACP to reject mode id 'debug' with an error message",
                )

                // Debug simulation stays on the supported agent wire mode.
                rpc.request(
                    "session/set_mode",
                    buildJsonObject {
                        put("sessionId", sessionId)
                        put("modeId", ChatMode.DEBUG.sessionMode.value)
                    },
                )
            }
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `agent can read DebugLogServer evidence after HTTP capture`() {
        assumeManualOnly()
        val agentPath = resolveAgentPath()
        val workspace = Files.createTempDirectory("cursorj-debug-log-read-it").toFile()
        IntegrationDebugLogSink(workspace.absolutePath).use { debugServer ->
            withAcpSession(agentPath, workspace) { rpc, sessionId ->
                rpc.request(
                    "session/set_mode",
                    buildJsonObject {
                        put("sessionId", sessionId)
                        put("modeId", ChatMode.DEBUG.sessionMode.value)
                    },
                )

                val endpoint = debugServer.beginTurn(sessionId)
                assertTrue(File(endpoint.logFilePath).isFile)
                assertTrue(endpoint.httpAvailable, "Expected localhost HTTP debug sink to bind")

                val marker = "CURSORJ_DEBUG_IT_MARKER_${System.currentTimeMillis()}"
                postLog(endpoint.url, """{"marker":"$marker","phase":"repro"}""")
                assertTrue(File(endpoint.logFilePath).readText(StandardCharsets.UTF_8).contains(marker))

                val prompt = buildString {
                    appendLine("Read the debug log file at this exact path using your file tools:")
                    appendLine(endpoint.logFilePath)
                    appendLine()
                    appendLine("Reply with EXACTLY the full file contents and nothing else.")
                }
                rpc.request(
                    "session/prompt",
                    buildJsonObject {
                        put("sessionId", sessionId)
                        put("prompt", JsonArray(listOf(buildJsonObject {
                            put("type", "text")
                            put("text", prompt)
                        })))
                    },
                    timeoutMs = 180_000,
                )

                val assistantText = rpc.collectNotificationText("agent_message_chunk", "agent_message_end")
                assertTrue(
                    assistantText.contains(marker),
                    "Agent should echo the captured debug marker. assistant='$assistantText'",
                )
            }
        }
        workspace.deleteRecursively()
    }

    @Test
    fun `debug workflow prompt completes and drives instrumentation or reproduce steps`() {
        assumeManualOnly()
        val agentPath = resolveAgentPath()
        val workspace = Files.createTempDirectory("cursorj-debug-workflow-it").toFile()
        writeBuggySumModule(workspace)
        try {
            IntegrationDebugLogSink(workspace.absolutePath).use { debugServer ->
                withAcpSession(agentPath, workspace) { rpc, sessionId ->
                    rpc.request(
                        "session/set_mode",
                        buildJsonObject {
                            put("sessionId", sessionId)
                            put("modeId", ChatMode.DEBUG.sessionMode.value)
                        },
                    )

                    val endpoint = debugServer.beginTurn(sessionId)
                    val prefix = debugPromptPrefix(endpoint.logFilePath, endpoint.url)
                    assertTrue(prefix.contains(endpoint.logFilePath))
                    assertFalse(prefix.isBlank())

                    val userText =
                        "There is a bug in buggy_sum.py: add(2, 3) returns 6 instead of 5. " +
                            "Use the Debug mode workflow from the instructions above. " +
                            "Prefer writing temporary instrumentation that appends lines to the debug log file. " +
                            "Stop after giving reproduction steps if you need me to run something."

                    // Display text must hide the prefix (same rule as ModePromptSupport).
                    assertFalse(userText.contains("Debug mode:"))

                    rpc.request(
                        "session/prompt",
                        buildJsonObject {
                            put("sessionId", sessionId)
                            put(
                                "prompt",
                                buildJsonArray {
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", prefix)
                                    })
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", userText)
                                    })
                                },
                            )
                        },
                        timeoutMs = 240_000,
                    )

                    val assistantText = rpc.collectNotificationText("agent_message_chunk", "agent_message_end")
                        .lowercase()
                    val logContents = runCatching {
                        File(endpoint.logFilePath).readText(StandardCharsets.UTF_8)
                    }.getOrDefault("")
                    val instrumentedSources = workspace.walkTopDown()
                        .filter { it.isFile && it.extension in setOf("py", "kt", "java", "js", "ts") }
                        .any { file ->
                            val text = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrDefault("")
                            text.contains("CURSORJ_DEBUG", ignoreCase = true) ||
                                text.contains(endpoint.logFilePath) ||
                                (endpoint.url.isNotBlank() && text.contains(endpoint.url))
                        }
                    val mentionsWorkflow =
                        listOf("hypothes", "instrument", "reproduc", "log file", "root cause", "debug")
                            .any { assistantText.contains(it) }

                    assertTrue(
                        instrumentedSources || logContents.isNotBlank() || mentionsWorkflow,
                        "Expected Debug-mode behavior: instrumentation, log evidence, or workflow language. " +
                            "assistant='${assistantText.take(800)}' log='${logContents.take(400)}'",
                    )
                }
            }
        } finally {
            workspace.deleteRecursively()
        }
    }

    private fun withAcpSession(
        agentPath: String,
        workspace: File,
        block: (JsonRpcWire, String) -> Unit,
    ) {
        val command = buildAgentCommand(agentPath, "acp")
        val process = ProcessBuilder(command).directory(workspace).start()
        try {
            val rpc = JsonRpcWire(
                BufferedReader(process.inputStream.reader()),
                BufferedWriter(process.outputStream.writer()),
                json,
                workspace,
            )
            initializeAndAuth(rpc)
            val sessionResult = rpc.request(
                "session/new",
                buildJsonObject {
                    put("cwd", workspace.absolutePath)
                    put("mcpServers", JsonArray(emptyList()))
                },
            )
            val sessionId = sessionResult.jsonObject["sessionId"]?.jsonPrimitive?.content.orEmpty()
            assertTrue(sessionId.isNotBlank(), "Expected non-blank session id")
            block(rpc, sessionId)
        } finally {
            process.destroyForcibly()
        }
    }

    private fun initializeAndAuth(rpc: JsonRpcWire) {
        rpc.request(
            "initialize",
            buildJsonObject {
                put("protocolVersion", 1)
                put(
                    "clientCapabilities",
                    buildJsonObject {
                        put(
                            "fs",
                            buildJsonObject {
                                put("readTextFile", true)
                                put("writeTextFile", true)
                            },
                        )
                        put("terminal", true)
                        put("editor", buildJsonObject {})
                    },
                )
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", "cursorj-debug-mode-it")
                        put("version", "0.0.0")
                    },
                )
            },
        )
        rpc.request("authenticate", buildJsonObject { put("methodId", "cursor_login") })
    }

    private fun debugPromptPrefix(logFilePath: String, httpUrl: String): String {
        val bundle = ResourceBundle.getBundle("messages.CursorJBundle")
        val template = bundle.getString("chat.debug.promptPrefix")
        val httpHint = httpUrl.ifBlank { "(HTTP endpoint unavailable — use the log file only)" }
        return MessageFormat.format(template, logFilePath, httpHint)
    }

    private fun writeBuggySumModule(workspace: File) {
        File(workspace, "buggy_sum.py").writeText(
            """
            def add(a, b):
                # Off-by-one bug for integration testing
                return a + b + 1


            if __name__ == "__main__":
                print(add(2, 3))
            """.trimIndent() + "\n",
            StandardCharsets.UTF_8,
        )
    }

    private fun postLog(url: String, body: String) {
        val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 5_000
            readTimeout = 5_000
            setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        }
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        val code = conn.responseCode
        conn.disconnect()
        assertEquals(204, code, "Expected DebugLogServer POST to return 204")
    }

    private fun buildAgentCommand(agentPath: String, subcommand: String): List<String> {
        val command = mutableListOf<String>()
        if (agentPath.endsWith(".cmd", ignoreCase = true) || agentPath.endsWith(".bat", ignoreCase = true)) {
            command.addAll(listOf("cmd.exe", "/c", agentPath))
        } else {
            command.add(agentPath)
        }
        command.add(subcommand)
        return command
    }

    private fun assumeManualOnly() {
        assumeTrue(System.getenv("CURSORJ_INTEGRATION") == "1") {
            "Manual integration tests are disabled. Set CURSORJ_INTEGRATION=1 to run."
        }
        val ci = System.getenv("CI")?.trim()
        assumeTrue(ci != "1" && !ci.equals("true", ignoreCase = true)) {
            "Manual integration tests must never run on CI."
        }
    }

    private fun resolveAgentPath(): String {
        val configured = System.getenv("CURSOR_AGENT_PATH")?.trim().orEmpty()
        if (configured.isNotBlank()) {
            val file = File(configured)
            val runnable = file.isFile && (isWindows() || file.canExecute())
            assumeTrue(runnable) { "CURSOR_AGENT_PATH is set but not runnable: $configured" }
            return file.absolutePath
        }
        val resolved = AgentPathResolver.resolve(null)
        assumeTrue(!resolved.isNullOrBlank()) { "Could not resolve agent binary from PATH/common locations." }
        return resolved!!
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    /**
     * Minimal ACP JSON-RPC client that auto-allows permissions and serves fs read/write
     * from [workspace] so the real agent can inspect DebugLogServer evidence.
     */
    private class JsonRpcWire(
        private val reader: BufferedReader,
        private val writer: BufferedWriter,
        private val json: Json,
        private val workspace: File,
    ) {
        private var nextId = 1
        val lastNotifications = mutableListOf<JsonObject>()

        fun request(method: String, params: JsonElement, timeoutMs: Long = 30_000): JsonElement {
            lastNotifications.clear()
            val id = nextId++
            val payload = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }
            writer.write(json.encodeToString(JsonObject.serializer(), payload))
            writer.newLine()
            writer.flush()

            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val line = reader.readLine()
                    ?: throw IllegalStateException("Agent process ended while awaiting response.")
                if (line.isBlank()) continue
                val message = json.parseToJsonElement(line).jsonObject
                if ("id" !in message && "method" in message) {
                    lastNotifications.add(message)
                    continue
                }
                if ("id" in message && "method" in message) {
                    handleServerRequest(message)
                    continue
                }
                val responseId = message["id"]?.jsonPrimitive?.intOrNull
                if (responseId != id) continue
                val error = message["error"]
                if (error != null && error !is JsonNull) {
                    val errObj = error.jsonObject
                    val code = errObj["code"]?.jsonPrimitive?.intOrNull ?: -1
                    val msg = errObj["message"]?.jsonPrimitive?.content ?: "Unknown error"
                    throw AcpException(code, msg)
                }
                return message["result"] ?: JsonPrimitive("")
            }
            throw IllegalStateException("Timed out waiting for JSON-RPC response to $method")
        }

        private fun handleServerRequest(message: JsonObject) {
            val requestId = message["id"]?.jsonPrimitive?.intOrNull ?: return
            val method = message["method"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val params = message["params"] ?: JsonObject(emptyMap())
            val result: JsonElement = when (method) {
                "session/request_permission" -> buildJsonObject {
                    put(
                        "outcome",
                        buildJsonObject {
                            put("outcome", "selected")
                            put("optionId", chooseAllowOptionId(params))
                        },
                    )
                }
                "fs/read_text_file" -> {
                    val path = params.jsonObject["path"]?.jsonPrimitive?.contentOrNull
                        ?: return respond(requestId, JsonObject(emptyMap()))
                    val file = resolvePath(path)
                    if (!file.isFile) {
                        respondError(requestId, -32000, "File not found: $path")
                        return
                    }
                    buildJsonObject {
                        put("content", file.readText(StandardCharsets.UTF_8))
                    }
                }
                "fs/write_text_file" -> {
                    val path = params.jsonObject["path"]?.jsonPrimitive?.contentOrNull
                        ?: return respond(requestId, JsonObject(emptyMap()))
                    val content = params.jsonObject["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val file = resolvePath(path)
                    file.parentFile?.mkdirs()
                    file.writeText(content, StandardCharsets.UTF_8)
                    JsonObject(emptyMap())
                }
                else -> JsonObject(emptyMap())
            }
            respond(requestId, result)
        }

        private fun chooseAllowOptionId(params: JsonElement): String {
            val options = params.jsonObject["options"]?.jsonArray.orEmpty()
            for (option in options) {
                val id = option.jsonObject["optionId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (id.contains("allow", ignoreCase = true) || id.contains("approve", ignoreCase = true)) {
                    return id
                }
            }
            return options.firstOrNull()?.jsonObject?.get("optionId")?.jsonPrimitive?.contentOrNull
                ?: "allow-once"
        }

        private fun resolvePath(path: String): File {
            val raw = File(path)
            return if (raw.isAbsolute) raw else File(workspace, path)
        }

        private fun respond(requestId: Int, result: JsonElement) {
            val response = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", requestId)
                put("result", result)
            }
            writer.write(json.encodeToString(JsonObject.serializer(), response))
            writer.newLine()
            writer.flush()
        }

        private fun respondError(requestId: Int, code: Int, message: String) {
            val response = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", requestId)
                put(
                    "error",
                    buildJsonObject {
                        put("code", code)
                        put("message", message)
                    },
                )
            }
            writer.write(json.encodeToString(JsonObject.serializer(), response))
            writer.newLine()
            writer.flush()
        }

        fun collectNotificationText(vararg updateTypes: String): String {
            val typeSet = updateTypes.toSet()
            val sb = StringBuilder()
            for (notification in lastNotifications) {
                val params = notification["params"]?.jsonObject ?: continue
                val update = params["update"]?.jsonObject ?: params
                val sessionUpdate = update["sessionUpdate"]?.jsonPrimitive?.contentOrNull ?: continue
                if (sessionUpdate in typeSet) {
                    extractTextFromUpdate(update)?.let { sb.append(it) }
                }
            }
            return sb.toString()
        }

        private fun extractTextFromUpdate(obj: JsonObject): String? {
            extractTextFromContent(obj["content"])?.let { return it }
            obj["text"]?.jsonPrimitive?.contentOrNull?.let { return it }
            extractTextFromContent(obj["message"])?.let { return it }
            return null
        }

        private fun extractTextFromContent(element: JsonElement?): String? {
            if (element == null || element is JsonNull) return null
            if (element is JsonPrimitive) return element.contentOrNull
            if (element is JsonObject) {
                (element["text"] as? JsonPrimitive)?.contentOrNull?.let { return it }
                val inner = element["content"]
                if (inner is JsonObject) return (inner["text"] as? JsonPrimitive)?.contentOrNull
            }
            if (element is JsonArray) {
                val sb = StringBuilder()
                for (block in element) {
                    if (block is JsonObject) {
                        val text = (block["text"] as? JsonPrimitive)?.contentOrNull
                            ?: block["content"]?.let { inner ->
                                if (inner is JsonObject) (inner["text"] as? JsonPrimitive)?.contentOrNull else null
                            }
                        text?.let { sb.append(it) }
                    }
                }
                return sb.toString().ifEmpty { null }
            }
            return null
        }
    }
}
