package com.cursorj.integration

import com.cursorj.acp.AcpException
import com.cursorj.acp.AgentPathResolver
import com.cursorj.acp.ConfigOptionUiSupport
import com.cursorj.acp.messages.ClientCapabilities
import com.cursorj.acp.messages.ConfigOption
import com.cursorj.acp.messages.InitializeParams
import com.cursorj.acp.messages.JsonRpcError
import com.cursorj.acp.messages.SessionNewResult
import com.cursorj.acp.messages.SetConfigOptionResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Manual integration test for Cursor's parameterized model picker
 * (`clientCapabilities._meta.parameterizedModelPicker`, see `docs/plans/2026-10-17-effort-level-support.md`).
 *
 * Each case spawns `agent acp` with `CURSOR_CONFIG_DIR` pointing at a scratch copy of `~/.cursor/cli-config.json`,
 * so the parameter changes it makes never touch the user's CLI defaults. The copy carries the user's auth info
 * and is deleted afterwards. Run with `CURSORJ_INTEGRATION=1 ./gradlew integrationTest --tests '*ModelParameter*'`.
 *
 * Besides the ACP-confirmed values, the first case runs `agent about` against the same config dir and checks that
 * the CLI itself reports the model and effort chosen over ACP (its `Model` line, e.g. `Claude Opus 4.7 300K Medium`),
 * which confirms the selection reached the CLI's own state.
 */
class ModelParameterCliIntegrationTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `parameterized picker exposes a settable thought level option for a Claude model`() {
        assumeManualOnly()
        val agentPath = resolveAgentPath()
        withAgent(agentPath, parameterizedModelPicker = true) { rpc, sessionId, initialOptions, configDir ->
            val modelOption = modelOption(initialOptions)
            val modelIds = modelOption.options.map { it.value }
            assertTrue(
                modelIds.none { it.contains('[') },
                "Parameterized mode should list plain model ids, got $modelIds",
            )
            val claudeModel = modelIds.firstOrNull { it == "claude-opus-4-7" }
                ?: modelIds.firstOrNull { it.startsWith("claude-") }
            assumeTrue(claudeModel != null) { "The agent offered no Claude model id." }

            val afterModelSwitch = setConfigOption(rpc, sessionId, modelOption.id, claudeModel!!)
            assertEquals(claudeModel, modelOption(afterModelSwitch).currentValue)
            val effort = afterModelSwitch.firstOrNull { ConfigOptionUiSupport.isEffortSelector(it) }
            assertNotNull(
                effort,
                "Expected a thought_level select for $claudeModel; got ${afterModelSwitch.map { "${it.id}(${it.category})" }}",
            )
            val target = effort.options.map { it.value }.first { it != effort.currentValue }

            val afterEffortChange = setConfigOption(rpc, sessionId, effort.id, target)

            val confirmed = afterEffortChange.firstOrNull { it.id == effort.id }?.currentValue
            assertEquals(target, confirmed, "The agent did not confirm ${effort.id}=$target")
            println(
                "MODEL-PARAMS: $claudeModel ${effort.id}: ${effort.currentValue} -> $confirmed " +
                    "(values=${effort.options.map { it.value }})",
            )

            val modelName = modelOption.options.first { it.value == claudeModel }.name ?: claudeModel
            val targetName = effort.options.first { it.value == target }.name ?: target
            val aboutAfterEffort = agentAboutModelLine(agentPath, configDir)
            assertTrue(
                aboutAfterEffort.contains(modelName, ignoreCase = true) &&
                    aboutAfterEffort.contains(targetName, ignoreCase = true),
                "`agent about` should report $modelName at $targetName after the ACP change, got '$aboutAfterEffort'",
            )
            println("MODEL-PARAMS: agent about reports '$aboutAfterEffort'")

            val modelWithoutEffort = listOf("composer-2.5", "default").firstOrNull { it in modelIds }
            if (modelWithoutEffort == null) {
                println("MODEL-PARAMS: no model without an effort option was offered; skipping the rejection check")
                return@withAgent
            }
            val afterSecondSwitch = setConfigOption(rpc, sessionId, modelOption.id, modelWithoutEffort)
            assertTrue(
                afterSecondSwitch.none { ConfigOptionUiSupport.isEffortSelector(it) },
                "$modelWithoutEffort should expose no effort option, got ${afterSecondSwitch.map { it.id }}",
            )
            val secondName = modelOption.options.first { it.value == modelWithoutEffort }.name ?: modelWithoutEffort
            val aboutAfterSwitch = agentAboutModelLine(agentPath, configDir)
            assertTrue(
                aboutAfterSwitch.contains(secondName, ignoreCase = true),
                "`agent about` should report $secondName after switching models, got '$aboutAfterSwitch'",
            )
            println("MODEL-PARAMS: agent about reports '$aboutAfterSwitch'")
            val error = assertFailsWith<AcpException> { setConfigOption(rpc, sessionId, effort.id, target) }
            assertTrue(
                error.message.contains("Unknown model config option", ignoreCase = true),
                "Unexpected error for ${effort.id} on $modelWithoutEffort: ${error.message}",
            )
        }
    }

    @Test
    fun `without the meta flag the agent stays in variants mode`() {
        assumeManualOnly()
        withAgent(resolveAgentPath(), parameterizedModelPicker = false) { _, _, initialOptions, _ ->
            val modelIds = modelOption(initialOptions).options.map { it.value }
            assertTrue(
                modelIds.any { BRACKETED_VARIANT.containsMatchIn(it) },
                "Variants mode should offer bracketed parameter variants, got $modelIds",
            )
            assertTrue(
                initialOptions.none { ConfigOptionUiSupport.isThoughtLevel(it) || ConfigOptionUiSupport.isModelConfig(it) },
                "Variants mode should expose no parameter options, got ${initialOptions.map { it.id }}",
            )
        }
    }

    private fun withAgent(
        agentPath: String,
        parameterizedModelPicker: Boolean,
        block: (rpc: JsonRpcWire, sessionId: String, configOptions: List<ConfigOption>, configDir: File) -> Unit,
    ) {
        val userConfig = File(System.getProperty("user.home"), ".cursor/cli-config.json")
        assumeTrue(userConfig.isFile) { "cli-config.json not found at $userConfig" }
        val configDir = Files.createTempDirectory("cursorj-model-params-config").toFile()
        val workspace = Files.createTempDirectory("cursorj-model-params-ws").toFile()
        var process: Process? = null
        try {
            userConfig.copyTo(File(configDir, "cli-config.json"))
            process = ProcessBuilder(buildAgentCommand(agentPath, "acp"))
                .directory(workspace)
                .apply { environment()["CURSOR_CONFIG_DIR"] = configDir.absolutePath }
                .start()
            val rpc = JsonRpcWire(
                BufferedReader(process.inputStream.reader()),
                BufferedWriter(process.outputStream.writer()),
                json,
            )
            val initializeParams = InitializeParams(
                clientCapabilities = ClientCapabilities.forInitialize(parameterizedModelPicker),
            )
            rpc.request("initialize", json.encodeToJsonElement(InitializeParams.serializer(), initializeParams))
            rpc.request("authenticate", buildJsonObject { put("methodId", "cursor_login") })
            val sessionNew = rpc.request(
                "session/new",
                buildJsonObject {
                    put("cwd", workspace.absolutePath)
                    put("mcpServers", JsonArray(emptyList()))
                },
            )
            val result = json.decodeFromJsonElement(SessionNewResult.serializer(), sessionNew)
            assumeTrue(result.sessionId.isNotBlank()) { "session/new did not return a sessionId." }
            block(rpc, result.sessionId, result.configOptions, configDir)
        } finally {
            process?.let { stop(it) }
            deleteWithRetry(workspace)
            deleteWithRetry(configDir)
        }
    }

    private fun setConfigOption(rpc: JsonRpcWire, sessionId: String, configId: String, value: String): List<ConfigOption> {
        val result = rpc.request(
            "session/set_config_option",
            buildJsonObject {
                put("sessionId", sessionId)
                put("configId", configId)
                put("value", value)
            },
        )
        return json.decodeFromJsonElement(SetConfigOptionResult.serializer(), result).configOptions
    }

    private fun modelOption(options: List<ConfigOption>): ConfigOption {
        val option = options.firstOrNull { ConfigOptionUiSupport.isModelSelector(it) }
        assumeTrue(option != null) { "The agent exposed no model config option." }
        return option!!
    }

    /**
     * Runs `agent about` against [configDir] and returns its `Model` line. The CLI builds that line from the same
     * `cli-config.json` selection that ACP `session/set_config_option` writes, so it is the CLI's own report of
     * the active model and parameters.
     */
    private fun agentAboutModelLine(agentPath: String, configDir: File): String {
        val process = ProcessBuilder(buildAgentCommand(agentPath, "about"))
            .apply { environment()["CURSOR_CONFIG_DIR"] = configDir.absolutePath }
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            stop(process)
            throw IllegalStateException("`agent about` timed out")
        }
        val cleaned = output.replace(ANSI_ESCAPE, "")
        return cleaned.lines()
            .map { it.trim() }
            .firstOrNull { ABOUT_MODEL_LINE.matches(it) }
            ?: throw IllegalStateException("`agent about` printed no Model line:\n$cleaned")
    }

    /** `cmd.exe /c agent.cmd` leaves the node child running after the parent is killed; stop the whole tree. */
    private fun stop(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
    }

    /** The scratch config dir holds a copy of the user's auth info; retry so it is never left behind. */
    private fun deleteWithRetry(dir: File) {
        repeat(10) {
            if (dir.deleteRecursively()) return
            Thread.sleep(500)
        }
        System.err.println("WARNING: could not delete ${dir.absolutePath}; remove it manually.")
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

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

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
                val line = reader.readLine()
                    ?: throw IllegalStateException("Agent process ended while awaiting the response to $method.")
                if (line.isBlank()) continue
                val message = json.parseToJsonElement(line).jsonObject
                // Notifications have no id; agent-to-client requests carry a method and their own id space.
                val isOurResponse = "method" !in message && message["id"]?.jsonPrimitive?.intOrNull == id
                if (!isOurResponse) continue
                val error = message["error"]
                if (error != null) {
                    // Same mapping as AcpClient: Cursor's reason lives in `error.data.message`.
                    val rpcError = json.decodeFromJsonElement(JsonRpcError.serializer(), error)
                    throw AcpException(rpcError.code, rpcError.detailedMessage())
                }
                return message["result"] ?: JsonPrimitive("")
            }
            throw IllegalStateException("Timed out waiting for the JSON-RPC response to $method")
        }
    }

    private companion object {
        /** Variants-mode model ids look like `composer-2.5[fast=true]`. */
        val BRACKETED_VARIANT = Regex("""\[[^\]]*=[^\]]*]""")

        /** `agent about` prints `Model               Claude Opus 4.7 300K Medium` (label, padding, value). */
        val ABOUT_MODEL_LINE = Regex("""Model\s{2,}\S.*""")
        val ANSI_ESCAPE = Regex("\u001B\\[[0-9;]*[A-Za-z]")
    }
}
