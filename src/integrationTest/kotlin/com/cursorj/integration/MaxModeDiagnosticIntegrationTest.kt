package com.cursorj.integration

import com.cursorj.acp.AcpException
import com.cursorj.acp.AgentPathResolver
import com.cursorj.acp.ConfigOptionUiSupport
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test

/**
 * Manual integration test: verifies max mode billing via `~/.cursor/cli-config.json` and fresh agent processes.
 */
class MaxModeDiagnosticIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Sends one prompt with maxMode=false, then restarts the agent with maxMode=true
     * and sends another prompt. Prints timestamps for cross-checking Cursor usage dashboard.
     */
    @Test
    fun `billing probe - send prompts with maxMode false then true for dashboard check`() {
        assumeManualOnly()

        val agentPath = resolveAgentPath()
        val workspace = Files.createTempDirectory("cursorj-maxmode-billing-it").toFile()

        val cliConfigPath = File(System.getProperty("user.home"), ".cursor/cli-config.json")
        assumeTrue(cliConfigPath.isFile) { "cli-config.json not found at $cliConfigPath" }

        val originalContent = cliConfigPath.readText()

        val report = StringBuilder()
        report.appendLine("=".repeat(80))
        report.appendLine("MAX MODE BILLING PROBE")
        report.appendLine("=".repeat(80))
        report.appendLine()
        report.appendLine("PURPOSE: Send two prompts — one with maxMode=false, one with maxMode=true.")
        report.appendLine("After this test completes, check your Cursor usage dashboard at:")
        report.appendLine("  https://www.cursor.com/settings")
        report.appendLine("Look for the two requests around the timestamps below.")
        report.appendLine("If maxMode works via ACP, the second request should be billed as premium/max.")
        report.appendLine()

        try {
            ensureMaxModeInConfig(cliConfigPath, false)
            report.appendLine("=== PROMPT 1: maxMode=false ===")
            val ts1 = java.time.Instant.now()
            report.appendLine("  Timestamp (UTC): $ts1")
            report.appendLine("  cli-config maxMode: false")

            val result1 = sendBillingProbePrompt(agentPath, workspace, report, "maxMode=false")
            report.appendLine("  Session ID: ${result1.sessionId}")
            report.appendLine("  Model used: ${result1.modelUsed}")
            report.appendLine("  Prompt RPC: ${if (result1.rpcOk) "OK" else "FAIL (${result1.rpcError})"}")
            report.appendLine("  Response: ${result1.responseText.take(200)}")
            report.appendLine("  Thought chunks present: ${result1.hasThoughts}")
            report.appendLine("  Notification types: ${result1.notificationTypes}")
            report.appendLine()

            Thread.sleep(3_000)

            ensureMaxModeInConfig(cliConfigPath, true)
            report.appendLine("=== PROMPT 2: maxMode=true ===")
            val ts2 = java.time.Instant.now()
            report.appendLine("  Timestamp (UTC): $ts2")
            report.appendLine("  cli-config maxMode: true")

            val result2 = sendBillingProbePrompt(agentPath, workspace, report, "maxMode=true")
            report.appendLine("  Session ID: ${result2.sessionId}")
            report.appendLine("  Model used: ${result2.modelUsed}")
            report.appendLine("  Prompt RPC: ${if (result2.rpcOk) "OK" else "FAIL (${result2.rpcError})"}")
            report.appendLine("  Response: ${result2.responseText.take(200)}")
            report.appendLine("  Thought chunks present: ${result2.hasThoughts}")
            report.appendLine("  Notification types: ${result2.notificationTypes}")
            report.appendLine()

            report.appendLine("=".repeat(80))
            report.appendLine("WHAT TO CHECK")
            report.appendLine("=".repeat(80))
            report.appendLine()
            report.appendLine("  1. Go to https://www.cursor.com/settings and find your usage/billing section.")
            report.appendLine("  2. Look for two requests around these times:")
            report.appendLine("     - Prompt 1 (maxMode=false): $ts1")
            report.appendLine("     - Prompt 2 (maxMode=true):  $ts2")
            report.appendLine("  3. If max mode works via ACP:")
            report.appendLine("     - Prompt 1 should be a normal/standard request")
            report.appendLine("     - Prompt 2 should be a premium/max request")
            report.appendLine("  4. If both show as normal requests, max mode does NOT work via ACP.")
            report.appendLine()
            report.appendLine("  Both prompts used model: ${result1.modelUsed}")
            report.appendLine("  Both prompts sent: \"What is 2+2? Reply with just the number.\"")
            report.appendLine()

            report.appendLine("=".repeat(80))
            report.appendLine("BEHAVIORAL COMPARISON")
            report.appendLine("=".repeat(80))
            report.appendLine()
            report.appendLine("  Response 1 (maxMode=false): ${result1.responseText.take(500)}")
            report.appendLine("  Response 2 (maxMode=true):  ${result2.responseText.take(500)}")
            report.appendLine()
            report.appendLine("  Thoughts 1 (maxMode=false): ${result1.thoughtText.take(500)}")
            report.appendLine("  Thoughts 2 (maxMode=true):  ${result2.thoughtText.take(500)}")
            report.appendLine()
            report.appendLine("  Notification count 1: ${result1.notificationCount}")
            report.appendLine("  Notification count 2: ${result2.notificationCount}")
            report.appendLine()

            if (result1.notificationTypes != result2.notificationTypes) {
                report.appendLine("  *** Notification types DIFFER — possible behavioral change ***")
                report.appendLine("    false: ${result1.notificationTypes}")
                report.appendLine("    true:  ${result2.notificationTypes}")
            } else {
                report.appendLine("  Notification types identical: ${result1.notificationTypes}")
            }
        } finally {
            cliConfigPath.writeText(originalContent)
            report.appendLine()
            report.appendLine("  cli-config.json restored to original content.")
            workspace.deleteRecursively()
        }

        report.appendLine()
        report.appendLine("=".repeat(80))
        report.appendLine("END OF MAX MODE BILLING PROBE")
        report.appendLine("=".repeat(80))

        System.out.println(report.toString())
    }

    private data class BillingProbeResult(
        val sessionId: String,
        val modelUsed: String,
        val rpcOk: Boolean,
        val rpcError: String?,
        val responseText: String,
        val thoughtText: String,
        val hasThoughts: Boolean,
        val notificationCount: Int,
        val notificationTypes: List<String>,
    )

    private fun sendBillingProbePrompt(
        agentPath: String,
        workspace: File,
        report: StringBuilder,
        label: String,
    ): BillingProbeResult {
        val command = buildAgentCommand(agentPath, "acp")
        val process = ProcessBuilder(command).directory(workspace).start()
        try {
            val rpc = JsonRpcWire(
                BufferedReader(process.inputStream.reader()),
                BufferedWriter(process.outputStream.writer()),
                json,
            )
            initializeAndAuth(rpc)

            val sessionResult = rpc.request("session/new", buildJsonObject {
                put("cwd", workspace.absolutePath)
                put("mcpServers", JsonArray(emptyList()))
            })
            val sessionId = sessionResult.jsonObject["sessionId"]?.jsonPrimitive?.content.orEmpty()

            val configOptions = sessionResult.jsonObject["configOptions"]?.jsonArray.orEmpty()
            val modelOption = configOptions.firstOrNull { element ->
                val obj = element.jsonObject
                ConfigOptionUiSupport.isModelConfigId(obj["id"]?.jsonPrimitive?.content.orEmpty()) ||
                    obj["category"]?.jsonPrimitive?.content == "model"
            }
            val modelConfigId = modelOption?.jsonObject?.get("id")?.jsonPrimitive?.content.orEmpty()
            val modelValues = modelOption?.jsonObject?.get("options")?.jsonArray
                ?.mapNotNull { it.jsonObject["value"]?.jsonPrimitive?.content }
                ?: emptyList()

            val cheapModel = modelValues.firstOrNull { it.contains("haiku") }
                ?: modelValues.firstOrNull { it.contains("flash") }
                ?: modelValues.firstOrNull { it.contains("mini") }
                ?: modelValues.firstOrNull { it.contains("nano") }
            val currentModel = modelOption?.jsonObject?.get("currentValue")?.jsonPrimitive?.content.orEmpty()

            val targetModel = cheapModel ?: currentModel
            if (cheapModel != null && cheapModel != currentModel) {
                report.appendLine("  [$label] Switching to cheap model: $cheapModel")
                try {
                    rpc.request("session/set_config_option", buildJsonObject {
                        put("sessionId", sessionId)
                        put("configId", modelConfigId)
                        put("value", cheapModel)
                    })
                } catch (e: Exception) {
                    report.appendLine("  [$label] Model switch failed (using current): ${e.message}")
                }
            }

            report.appendLine("  [$label] Sending prompt with model: $targetModel")

            var rpcOk = false
            var rpcError: String? = null

            try {
                rpc.request("session/prompt", buildJsonObject {
                    put("sessionId", sessionId)
                    put("prompt", JsonArray(listOf(buildJsonObject {
                        put("type", "text")
                        put("text", "What is 2+2? Reply with just the number.")
                    })))
                }, timeoutMs = 90_000)
                rpcOk = true
            } catch (e: AcpException) {
                rpcError = "[${e.code}] ${e.message}"
            } catch (e: Exception) {
                rpcError = e.message
            }

            val responseText = rpc.collectNotificationText("agent_message_chunk", "agent_message_end")
            val thoughtText = rpc.collectNotificationText("agent_thought_chunk")
            val types = rpc.lastNotifications.mapNotNull { n ->
                val params = n["params"]?.jsonObject ?: return@mapNotNull null
                val update = params["update"]?.jsonObject ?: params
                update["sessionUpdate"]?.jsonPrimitive?.contentOrNull
            }

            return BillingProbeResult(
                sessionId = sessionId,
                modelUsed = targetModel,
                rpcOk = rpcOk,
                rpcError = rpcError,
                responseText = responseText,
                thoughtText = thoughtText,
                hasThoughts = thoughtText.isNotBlank(),
                notificationCount = rpc.lastNotifications.size,
                notificationTypes = types,
            )
        } finally {
            process.destroyForcibly()
        }
    }

    private fun ensureMaxModeInConfig(configFile: File, enabled: Boolean) {
        val content = configFile.readText()
        val config = json.parseToJsonElement(content).jsonObject.toMutableMap()
        config["maxMode"] = JsonPrimitive(enabled)
        val model = config["model"]
        if (model is JsonObject) {
            val modelMap = model.toMutableMap()
            modelMap["maxMode"] = JsonPrimitive(enabled)
            config["model"] = JsonObject(modelMap)
        }
        val prettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true }
        configFile.writeText(prettyJson.encodeToString(JsonObject.serializer(), JsonObject(config)))
    }

    private fun initializeAndAuth(rpc: JsonRpcWire) {
        rpc.request("initialize", buildJsonObject {
            put("protocolVersion", 1)
            put("clientCapabilities", buildJsonObject {
                put("fs", buildJsonObject {})
                put("terminal", true)
                put("editor", buildJsonObject {})
            })
            put("clientInfo", buildJsonObject {
                put("name", "cursorj-max-mode-diagnostic")
                put("version", "0.0.0")
            })
        })
        rpc.request("authenticate", buildJsonObject { put("methodId", "cursor_login") })
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

    private class JsonRpcWire(
        private val reader: BufferedReader,
        private val writer: BufferedWriter,
        private val json: Json,
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
            val response = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", requestId)
                put("result", JsonObject(emptyMap()))
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
                    val text = extractTextFromUpdate(update)
                    if (text != null) sb.append(text)
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
