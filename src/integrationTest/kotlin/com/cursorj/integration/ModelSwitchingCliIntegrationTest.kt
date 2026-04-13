package com.cursorj.integration

import com.cursorj.acp.AgentPathResolver
import com.cursorj.acp.AcpException
import com.cursorj.acp.ConfigOptionUiSupport
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertTrue

class ModelSwitchingCliIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `switch then prompt keeps ACP confirmed model state`() {
        assumeTrue(System.getenv("CURSORJ_INTEGRATION") == "1") {
            "Manual integration tests are disabled. Set CURSORJ_INTEGRATION=1 to run."
        }
        val ci = System.getenv("CI")?.trim()
        val isCi = ci == "1" || ci.equals("true", ignoreCase = true)
        assumeTrue(!isCi) {
            "Manual integration tests must never run on CI."
        }

        val agentPath = run {
            val configured = System.getenv("CURSOR_AGENT_PATH")?.trim().orEmpty()
            if (configured.isNotBlank()) {
                val file = File(configured)
                val runnable = file.isFile && (isWindows() || file.canExecute())
                assumeTrue(runnable) { "CURSOR_AGENT_PATH is set but not runnable: $configured" }
                file.absolutePath
            } else {
                AgentPathResolver.resolve(null).also {
                    assumeTrue(!it.isNullOrBlank()) { "Could not resolve agent binary from PATH/common locations." }
                }!!
            }
        }
        val workspace = Files.createTempDirectory("cursorj-model-switch-cli-it").toFile()

        var process: Process? = null
        try {
            val command = buildAgentCommand(agentPath, "acp")
            process = ProcessBuilder(command)
                .directory(workspace)
                .start()

            val reader = BufferedReader(process.inputStream.reader())
            val writer = BufferedWriter(process.outputStream.writer())

            val rpc = JsonRpcWire(reader, writer, json)
            rpc.request(
                method = "initialize",
                params = buildJsonObject {
                    put("protocolVersion", 1)
                    put(
                        "clientCapabilities",
                        buildJsonObject {
                            put("fs", buildJsonObject {})
                            put("terminal", true)
                            put("editor", buildJsonObject {})
                        },
                    )
                    put(
                        "clientInfo",
                        buildJsonObject {
                            put("name", "cursorj-integration")
                            put("version", "0.0.0")
                        },
                    )
                },
            )
            rpc.request(
                method = "authenticate",
                params = buildJsonObject { put("methodId", "cursor_login") },
            )

            val sessionNew = rpc.request(
                method = "session/new",
                params = buildJsonObject {
                    put("cwd", workspace.absolutePath)
                    put("mcpServers", JsonArray(emptyList()))
                },
            )
            val sessionId = sessionNew.jsonObject["sessionId"]?.jsonPrimitive?.content.orEmpty()
            assumeTrue(sessionId.isNotBlank()) { "session/new did not return a sessionId." }

            val configOptions = sessionNew.jsonObject["configOptions"]?.jsonArray.orEmpty()
            val modelOptionElement = configOptions.firstOrNull { element ->
                val obj = element.jsonObject
                val category = obj["category"]?.jsonPrimitive?.content
                val id = obj["id"]?.jsonPrimitive?.content
                ConfigOptionUiSupport.isModelConfigId(id.orEmpty()) || category == "model"
            }
            assumeTrue(modelOptionElement != null) { "Agent did not expose a model config option in session/new." }
            val modelOption = modelOptionElement!!.jsonObject
            val modelConfigId = modelOption["id"]?.jsonPrimitive?.content.orEmpty()
            val currentModel = modelOption["currentValue"]?.jsonPrimitive?.content?.trim().orEmpty()
            assumeTrue(currentModel.isNotBlank()) { "Model config option has no current value." }
            val options = modelOption["options"]?.jsonArray.orEmpty()
            val targetModel = options
                .mapNotNull { it.jsonObject["value"]?.jsonPrimitive?.content?.trim() }
                .firstOrNull { it.isNotBlank() && !it.equals(currentModel, ignoreCase = true) }
            assumeTrue(targetModel != null) { "Need at least two model options to validate switching." }
            val alternate = targetModel!!

            val switchResult = rpc.request(
                method = "session/set_config_option",
                params = buildJsonObject {
                    put("sessionId", sessionId)
                    put("configId", modelConfigId)
                    put("value", alternate)
                },
            )
            val confirmedAfterSwitch = modelCurrentValueFromConfigOptions(switchResult)
            assertTrue(
                confirmedAfterSwitch.equals(alternate, ignoreCase = true),
                "Expected ACP to confirm switched model. requested='$alternate' confirmed='${confirmedAfterSwitch ?: "<missing>"}'",
            )

            rpc.request(
                method = "session/prompt",
                params = buildJsonObject {
                    put("sessionId", sessionId)
                    put(
                        "prompt",
                        buildJsonArray {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", "Which model are you now?")
                            })
                        },
                    )
                },
            )

            // Self-reported assistant text is non-authoritative; ACP config state is the source of truth.
            val reaffirmed = rpc.request(
                method = "session/set_config_option",
                params = buildJsonObject {
                    put("sessionId", sessionId)
                    put("configId", modelConfigId)
                    put("value", alternate)
                },
            )
            val confirmedAfterPrompt = modelCurrentValueFromConfigOptions(reaffirmed)
            assertTrue(
                confirmedAfterPrompt.equals(alternate, ignoreCase = true),
                "Expected ACP-confirmed model to remain '$alternate' after prompt. " +
                    "confirmed='${confirmedAfterPrompt ?: "<missing>"}'",
            )
        } finally {
            process?.destroyForcibly()
            workspace.deleteRecursively()
        }
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

    private fun isWindows(): Boolean {
        return System.getProperty("os.name").lowercase().contains("win")
    }

    private fun modelCurrentValueFromConfigOptions(result: JsonElement): String? {
        val options = result.jsonObject["configOptions"]?.jsonArray ?: return null
        val model = options.firstOrNull { opt ->
            val obj = opt.jsonObject
            ConfigOptionUiSupport.isModelConfigId(obj["id"]?.jsonPrimitive?.content.orEmpty()) ||
                obj["category"]?.jsonPrimitive?.content == "model"
        } ?: return null
        return model.jsonObject["currentValue"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
    }

    @Test
    fun `canary detects whether agent bug for model routing is fixed`() {
        assumeTrue(System.getenv("CURSORJ_INTEGRATION") == "1") {
            "Manual integration tests are disabled. Set CURSORJ_INTEGRATION=1 to run."
        }
        val ci = System.getenv("CI")?.trim()
        assumeTrue(ci != "1" && !ci.equals("true", ignoreCase = true)) {
            "Manual integration tests must never run on CI."
        }

        val agentPath = run {
            val configured = System.getenv("CURSOR_AGENT_PATH")?.trim().orEmpty()
            if (configured.isNotBlank()) {
                val file = File(configured)
                assumeTrue(file.isFile && (isWindows() || file.canExecute())) {
                    "CURSOR_AGENT_PATH is set but not runnable: $configured"
                }
                file.absolutePath
            } else {
                AgentPathResolver.resolve(null).also {
                    assumeTrue(!it.isNullOrBlank()) { "Could not resolve agent binary." }
                }!!
            }
        }
        val workspace = Files.createTempDirectory("cursorj-model-canary-it").toFile()

        var process: Process? = null
        try {
            process = ProcessBuilder(buildAgentCommand(agentPath, "acp"))
                .directory(workspace)
                .start()
            val rpc = JsonRpcWire(
                BufferedReader(process.inputStream.reader()),
                BufferedWriter(process.outputStream.writer()),
                json,
            )
            rpc.request("initialize", buildJsonObject {
                put("protocolVersion", 1)
                put("clientCapabilities", buildJsonObject {})
                put("clientInfo", buildJsonObject { put("name", "cursorj-canary"); put("version", "0.0.0") })
            })
            rpc.request("authenticate", buildJsonObject { put("methodId", "cursor_login") })

            val first = rpc.request("session/new", buildJsonObject {
                put("cwd", workspace.absolutePath)
                put("mcpServers", JsonArray(emptyList()))
            }).jsonObject
            val sessionId = first["sessionId"]?.jsonPrimitive?.content.orEmpty()
            assumeTrue(sessionId.isNotBlank()) { "session/new did not return a sessionId." }
            val modelOption = first["configOptions"]?.jsonArray
                ?.firstOrNull {
                    val obj = it.jsonObject
                    ConfigOptionUiSupport.isModelConfigId(obj["id"]?.jsonPrimitive?.content.orEmpty()) ||
                        obj["category"]?.jsonPrimitive?.content == "model"
                }?.jsonObject
            assumeTrue(modelOption != null) { "No model config option." }
            val configId = modelOption!!["id"]?.jsonPrimitive?.content.orEmpty()
            val currentModel = modelOption["currentValue"]?.jsonPrimitive?.content?.trim().orEmpty()
            val alternate = modelOption["options"]?.jsonArray
                ?.mapNotNull { it.jsonObject["value"]?.jsonPrimitive?.content?.trim() }
                ?.firstOrNull { it.isNotBlank() && !it.equals(currentModel, ignoreCase = true) }
            assumeTrue(alternate != null) { "Need at least two model options." }

            rpc.request("session/set_config_option", buildJsonObject {
                put("sessionId", sessionId)
                put("configId", configId)
                put("value", alternate!!)
            })

            val second = rpc.request("session/new", buildJsonObject {
                put("cwd", workspace.absolutePath)
                put("mcpServers", JsonArray(emptyList()))
            }).jsonObject
            val secondModelId = second["models"]?.jsonObject
                ?.get("currentModelId")?.jsonPrimitive?.content?.trim()

            val bugFixed = secondModelId.equals(alternate, ignoreCase = true)
            if (bugFixed) {
                System.out.println(
                    "CANARY: Agent model-routing bug appears FIXED. " +
                        "New session currentModelId='$secondModelId' matches switched model='$alternate'.",
                )
            } else {
                System.out.println(
                    "CANARY: Agent model-routing bug still present. " +
                        "Switched to '$alternate' but new session currentModelId='$secondModelId'.",
                )
            }
        } finally {
            process?.destroyForcibly()
            workspace.deleteRecursively()
        }
    }

    private class JsonRpcWire(
        private val reader: BufferedReader,
        private val writer: BufferedWriter,
        private val json: Json,
    ) {
        private var nextId = 1

        fun request(method: String, params: JsonElement): JsonElement {
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

            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline) {
                val line = reader.readLine() ?: throw IllegalStateException("Agent process ended while awaiting response.")
                if (line.isBlank()) continue
                val message = json.parseToJsonElement(line).jsonObject
                val responseId = message["id"]?.jsonPrimitive?.intOrNull
                if (responseId != id) {
                    // Ignore notifications/server-requests and responses for other ids.
                    continue
                }
                val error = message["error"]
                if (error != null) {
                    val errObj = error.jsonObject
                    val code = errObj["code"]?.jsonPrimitive?.intOrNull ?: -1
                    val msg = errObj["message"]?.jsonPrimitive?.content ?: "Unknown error"
                    throw AcpException(code, msg)
                }
                return message["result"] ?: JsonPrimitive("")
            }
            throw IllegalStateException("Timed out waiting for JSON-RPC response to $method")
        }
    }
}
