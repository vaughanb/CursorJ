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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
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
 * Diagnostic integration test that exercises every model name returned by the
 * Cursor CLI (`agent models`) and ACP (`session/new` configOptions) against
 * the `session/set_config_option` and `session/set_model` APIs.
 *
 * The output is a structured report written to stdout that identifies:
 * - Which model IDs exist only in the CLI output
 * - Which model values exist only in the ACP configOptions
 * - Which models are common to both sources
 * - For each model, whether `session/set_config_option` and `session/set_model` succeed or fail
 * - Exact error codes and messages for each failure
 *
 * This test is intended to be shared with the Cursor team to help diagnose
 * model-routing issues.
 */
class ModelNameValidityIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `diagnose model name validity across cli and acp`() {
        assumeManualOnly()

        val agentPath = resolveAgentPath()
        val workspace = Files.createTempDirectory("cursorj-model-validity-it").toFile()

        val report = StringBuilder()
        report.appendLine("=" .repeat(80))
        report.appendLine("MODEL NAME VALIDITY DIAGNOSTIC REPORT")
        report.appendLine("=" .repeat(80))
        report.appendLine("Timestamp: ${java.time.Instant.now()}")
        report.appendLine("Agent path: $agentPath")
        report.appendLine()

        var process: Process? = null
        try {
            val cliModels = fetchCliModels(agentPath)
            report.appendLine("--- CLI models (`agent models`) ---")
            if (cliModels.isEmpty()) {
                report.appendLine("  (none returned)")
            } else {
                for (m in cliModels) {
                    report.appendLine("  id=%-40s display=%-30s current=%s".format(m.id, m.displayName, m.isCurrent))
                }
            }
            report.appendLine()

            val command = buildAgentCommand(agentPath, "acp")
            process = ProcessBuilder(command).directory(workspace).start()
            val rpc = JsonRpcWire(
                BufferedReader(process.inputStream.reader()),
                BufferedWriter(process.outputStream.writer()),
                json,
            )

            rpc.request("initialize", buildJsonObject {
                put("protocolVersion", 1)
                put("clientCapabilities", buildJsonObject {
                    put("fs", buildJsonObject {})
                    put("terminal", true)
                    put("editor", buildJsonObject {})
                })
                put("clientInfo", buildJsonObject {
                    put("name", "cursorj-model-validity-diagnostic")
                    put("version", "0.0.0")
                })
            })
            rpc.request("authenticate", buildJsonObject { put("methodId", "cursor_login") })

            val sessionResult = rpc.request("session/new", buildJsonObject {
                put("cwd", workspace.absolutePath)
                put("mcpServers", JsonArray(emptyList()))
            })
            val sessionId = sessionResult.jsonObject["sessionId"]?.jsonPrimitive?.content.orEmpty()
            assumeTrue(sessionId.isNotBlank()) { "session/new did not return a sessionId." }

            val configOptions = sessionResult.jsonObject["configOptions"]?.jsonArray.orEmpty()
            val modelOptionElement = configOptions.firstOrNull { element ->
                val obj = element.jsonObject
                val category = obj["category"]?.jsonPrimitive?.content
                val id = obj["id"]?.jsonPrimitive?.content
                ConfigOptionUiSupport.isModelConfigId(id.orEmpty()) || category == "model"
            }

            report.appendLine("--- ACP session/new configOptions (model selector) ---")
            if (modelOptionElement == null) {
                report.appendLine("  (no model config option found)")
                report.appendLine()
            } else {
                val modelOption = modelOptionElement.jsonObject
                val configId = modelOption["id"]?.jsonPrimitive?.content.orEmpty()
                val currentValue = modelOption["currentValue"]?.jsonPrimitive?.content.orEmpty()
                val optionType = modelOption["type"]?.jsonPrimitive?.content.orEmpty()
                val category = modelOption["category"]?.jsonPrimitive?.content.orEmpty()
                report.appendLine("  configId=$configId  type=$optionType  category=$category  currentValue=$currentValue")
                val acpOptions = modelOption["options"]?.jsonArray.orEmpty()
                report.appendLine("  options (${acpOptions.size}):")
                for (opt in acpOptions) {
                    val value = opt.jsonObject["value"]?.jsonPrimitive?.content.orEmpty()
                    val name = opt.jsonObject["name"]?.jsonPrimitive?.content.orEmpty()
                    report.appendLine("    value=%-40s name=%s".format(value, name))
                }
                report.appendLine()

                val cliIds = cliModels.map { it.id }.toSet()
                val acpValues = acpOptions
                    .mapNotNull { it.jsonObject["value"]?.jsonPrimitive?.content?.trim() }
                    .filter { it.isNotBlank() }
                    .toSet()

                val cliOnly = cliIds - acpValues
                val acpOnly = acpValues - cliIds
                val common = cliIds.intersect(acpValues)

                report.appendLine("--- Set comparison ---")
                report.appendLine("  CLI model count:        ${cliIds.size}")
                report.appendLine("  ACP option count:       ${acpValues.size}")
                report.appendLine("  Common (in both):       ${common.size}")
                report.appendLine("  CLI-only (not in ACP):  ${cliOnly.size}")
                report.appendLine("  ACP-only (not in CLI):  ${acpOnly.size}")
                if (cliOnly.isNotEmpty()) {
                    report.appendLine("  CLI-only IDs: $cliOnly")
                }
                if (acpOnly.isNotEmpty()) {
                    report.appendLine("  ACP-only values: $acpOnly")
                }
                report.appendLine()

                val allModelIds = (cliIds + acpValues).sorted()

                data class ModelTestRow(
                    val modelId: String,
                    val setConfigResult: SwitchResult,
                    val setModelResult: SwitchResult,
                    val inCli: Boolean,
                    val inAcp: Boolean,
                )

                val rows = allModelIds.map { modelId ->
                    ModelTestRow(
                        modelId = modelId,
                        setConfigResult = trySetConfigOption(rpc, sessionId, configId, modelId),
                        setModelResult = trySetModel(rpc, sessionId, modelId),
                        inCli = modelId in cliIds,
                        inAcp = modelId in acpValues,
                    )
                }

                report.appendLine("--- Per-model switch results (${rows.size} models) ---")
                report.appendLine(
                    "%-45s | %-6s | %-20s | %-6s | %-20s | %-5s | %-5s"
                        .format("Model ID", "setCfg", "setCfg error", "setMdl", "setMdl error", "CLI?", "ACP?")
                )
                report.appendLine("-".repeat(140))

                for (row in rows) {
                    val setCfgStatus = if (row.setConfigResult.success) "OK" else "FAIL"
                    val setCfgError = row.setConfigResult.errorSummary ?: ""
                    val setMdlStatus = if (row.setModelResult.success) "OK" else "FAIL"
                    val setMdlError = row.setModelResult.errorSummary ?: ""
                    val inCli = if (row.inCli) "Y" else "N"
                    val inAcp = if (row.inAcp) "Y" else "N"

                    report.appendLine(
                        "%-45s | %-6s | %-20s | %-6s | %-20s | %-5s | %-5s"
                            .format(row.modelId, setCfgStatus, setCfgError.take(20), setMdlStatus, setMdlError.take(20), inCli, inAcp)
                    )
                }
                report.appendLine()

                val failedRows = rows.filter { !it.setConfigResult.success || !it.setModelResult.success }
                report.appendLine("--- Detailed error log (${failedRows.size} models with failures) ---")
                for (row in failedRows) {
                    report.appendLine("  Model: ${row.modelId}")
                    if (!row.setConfigResult.success) {
                        report.appendLine("    session/set_config_option: code=${row.setConfigResult.errorCode} message=${row.setConfigResult.errorMessage}")
                    }
                    if (!row.setModelResult.success) {
                        report.appendLine("    session/set_model: code=${row.setModelResult.errorCode} message=${row.setModelResult.errorMessage}")
                    }
                }

                report.appendLine()
                report.appendLine("--- Confirmed model after each switch (session/set_config_option) ---")
                for (row in rows) {
                    if (!row.setConfigResult.success) {
                        report.appendLine("  requested=%-40s confirmed=%-40s %s".format(row.modelId, "<FAILED>", "ERROR"))
                        continue
                    }
                    val confirmed = row.setConfigResult.confirmedModel ?: "<none>"
                    val match = if (row.setConfigResult.confirmedModel.equals(row.modelId, ignoreCase = true)) "MATCH" else "MISMATCH"
                    report.appendLine("  requested=%-40s confirmed=%-40s %s".format(row.modelId, confirmed, match))
                }
            }

            report.appendLine()
            report.appendLine("--- ACP models field from session/new ---")
            val modelsField = sessionResult.jsonObject["models"]
            if (modelsField != null) {
                report.appendLine("  raw: $modelsField")
            } else {
                report.appendLine("  (not present in session/new response)")
            }

            report.appendLine()
            report.appendLine("=" .repeat(80))
            report.appendLine("END OF DIAGNOSTIC REPORT")
            report.appendLine("=" .repeat(80))
        } finally {
            process?.destroyForcibly()
            workspace.deleteRecursively()
        }

        System.out.println(report.toString())
    }

    @Test
    fun `report model ids that fail set_config_option but exist in cli`() {
        assumeManualOnly()

        val agentPath = resolveAgentPath()
        val workspace = Files.createTempDirectory("cursorj-model-cli-fail-it").toFile()

        var process: Process? = null
        try {
            val cliModels = fetchCliModels(agentPath)
            assumeTrue(cliModels.isNotEmpty()) { "No models returned from agent models CLI" }

            val command = buildAgentCommand(agentPath, "acp")
            process = ProcessBuilder(command).directory(workspace).start()
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
            assumeTrue(sessionId.isNotBlank()) { "session/new did not return a sessionId." }

            val configId = extractModelConfigId(sessionResult)
            assumeTrue(configId != null) { "No model config option in session/new." }

            val failures = mutableListOf<String>()
            val successes = mutableListOf<String>()

            for (model in cliModels) {
                val result = trySetConfigOption(rpc, sessionId, configId!!, model.id)
                if (result.success) {
                    successes.add(model.id)
                } else {
                    failures.add("${model.id} -> code=${result.errorCode} message=${result.errorMessage}")
                }
            }

            System.out.println()
            System.out.println("CLI model switch via session/set_config_option results:")
            System.out.println("  Successes (${successes.size}): $successes")
            System.out.println("  Failures  (${failures.size}):")
            for (f in failures) {
                System.out.println("    $f")
            }
            System.out.println()

            if (failures.isNotEmpty()) {
                System.out.println(
                    "WARNING: ${failures.size} out of ${cliModels.size} CLI model IDs failed " +
                        "session/set_config_option. These model names from `agent models` are " +
                        "not accepted by the ACP model-switch API."
                )
            }
        } finally {
            process?.destroyForcibly()
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `report acp config option model values that fail switching`() {
        assumeManualOnly()

        val agentPath = resolveAgentPath()
        val workspace = Files.createTempDirectory("cursorj-model-acp-fail-it").toFile()

        var process: Process? = null
        try {
            val command = buildAgentCommand(agentPath, "acp")
            process = ProcessBuilder(command).directory(workspace).start()
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
            assumeTrue(sessionId.isNotBlank()) { "session/new did not return a sessionId." }

            val configId = extractModelConfigId(sessionResult)
            assumeTrue(configId != null) { "No model config option in session/new." }

            val acpOptions = extractModelOptionValues(sessionResult)
            assumeTrue(acpOptions.isNotEmpty()) { "No model option values in session/new." }

            val failures = mutableListOf<String>()
            val successes = mutableListOf<String>()
            val mismatches = mutableListOf<String>()

            for (modelValue in acpOptions) {
                val result = trySetConfigOption(rpc, sessionId, configId!!, modelValue)
                if (result.success) {
                    successes.add(modelValue)
                    if (!result.confirmedModel.equals(modelValue, ignoreCase = true)) {
                        mismatches.add("requested=$modelValue confirmed=${result.confirmedModel}")
                    }
                } else {
                    failures.add("$modelValue -> code=${result.errorCode} message=${result.errorMessage}")
                }
            }

            System.out.println()
            System.out.println("ACP configOption model switch via session/set_config_option results:")
            System.out.println("  Successes  (${successes.size}): $successes")
            System.out.println("  Failures   (${failures.size}):")
            for (f in failures) {
                System.out.println("    $f")
            }
            System.out.println("  Mismatches (${mismatches.size}):")
            for (m in mismatches) {
                System.out.println("    $m")
            }
            System.out.println()

            if (failures.isNotEmpty()) {
                System.out.println(
                    "WARNING: ${failures.size} out of ${acpOptions.size} ACP configOption model values failed " +
                        "session/set_config_option. The agent's own configOptions contain values that it rejects."
                )
            }
            if (mismatches.isNotEmpty()) {
                System.out.println(
                    "WARNING: ${mismatches.size} model switches returned a different confirmed model " +
                        "than the one requested."
                )
            }
        } finally {
            process?.destroyForcibly()
            workspace.deleteRecursively()
        }
    }

    /**
     * Switch to each ACP model, send a simple prompt, and capture the response text.
     * Models whose response text contains an error (e.g. "Model Not Found") are flagged.
     *
     * This reproduces the bug where `session/set_config_option` succeeds but the
     * subsequent prompt returns a backend error embedded in the response text.
     */
    @Test
    fun `diagnose prompt errors after model switch for all acp models`() {
        assumeManualOnly()

        val agentPath = resolveAgentPath()
        val workspace = Files.createTempDirectory("cursorj-model-prompt-it").toFile()

        val report = StringBuilder()
        report.appendLine("=" .repeat(80))
        report.appendLine("POST-SWITCH PROMPT DIAGNOSTIC REPORT")
        report.appendLine("=" .repeat(80))
        report.appendLine("Timestamp: ${java.time.Instant.now()}")
        report.appendLine("Agent path: $agentPath")
        report.appendLine()
        report.appendLine("Test: for each ACP model, switch via session/set_config_option,")
        report.appendLine("then send a trivial prompt and inspect the response for errors.")
        report.appendLine()

        var process: Process? = null
        try {
            val command = buildAgentCommand(agentPath, "acp")
            process = ProcessBuilder(command).directory(workspace).start()
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
            assumeTrue(sessionId.isNotBlank()) { "session/new did not return a sessionId." }

            val configId = extractModelConfigId(sessionResult)
            assumeTrue(configId != null) { "No model config option in session/new." }

            val acpOptions = extractModelOptionValuesWithNames(sessionResult)
            assumeTrue(acpOptions.isNotEmpty()) { "No model option values in session/new." }

            report.appendLine("Models to test: ${acpOptions.size}")
            report.appendLine()

            data class PromptTestRow(
                val modelValue: String,
                val modelName: String,
                val switchOk: Boolean,
                val switchError: String?,
                val promptOk: Boolean,
                val promptRpcError: String?,
                val responseText: String,
                val responseContainsError: Boolean,
            )

            val errorPatterns = listOf(
                "Model Not Found",
                "Model name is not valid",
                "not available",
                "does not exist",
                "invalid model",
            )

            val rows = mutableListOf<PromptTestRow>()

            for ((modelValue, modelName) in acpOptions) {
                report.appendLine("--- Testing: $modelValue ($modelName) ---")

                val switchResult = trySetConfigOption(rpc, sessionId, configId!!, modelValue)
                if (!switchResult.success) {
                    report.appendLine("  SWITCH FAILED: code=${switchResult.errorCode} message=${switchResult.errorMessage}")
                    rows.add(PromptTestRow(
                        modelValue = modelValue,
                        modelName = modelName,
                        switchOk = false,
                        switchError = switchResult.errorSummary,
                        promptOk = false,
                        promptRpcError = "skipped (switch failed)",
                        responseText = "",
                        responseContainsError = false,
                    ))
                    continue
                }

                report.appendLine("  Switch OK (confirmed: ${switchResult.confirmedModel})")

                var promptRpcError: String? = null
                var responseText = ""
                var promptOk = false

                try {
                    rpc.request("session/prompt", buildJsonObject {
                        put("sessionId", sessionId)
                        put("prompt", JsonArray(listOf(buildJsonObject {
                            put("type", "text")
                            put("text", "Reply with exactly: HELLO")
                        })))
                    }, timeoutMs = 60_000)
                    promptOk = true
                    responseText = rpc.collectNotificationText()
                } catch (e: AcpException) {
                    promptRpcError = "[${e.code}] ${e.message}"
                    responseText = rpc.collectNotificationText()
                } catch (e: Exception) {
                    promptRpcError = e.message
                    responseText = rpc.collectNotificationText()
                }

                val hasError = errorPatterns.any { responseText.contains(it, ignoreCase = true) }
                val notificationDump = rpc.dumpNotifications()

                report.appendLine("  Prompt RPC: ${if (promptOk) "OK" else "FAIL ($promptRpcError)"}")
                report.appendLine("  Notifications received: ${rpc.lastNotifications.size}")
                report.appendLine("  Response text (first 300 chars): ${responseText.take(300)}")
                if (responseText.isBlank() && rpc.lastNotifications.isNotEmpty()) {
                    report.appendLine("  Raw notification sample: ${notificationDump.take(500)}")
                }
                report.appendLine("  Contains error pattern: $hasError")
                report.appendLine()

                rows.add(PromptTestRow(
                    modelValue = modelValue,
                    modelName = modelName,
                    switchOk = true,
                    switchError = null,
                    promptOk = promptOk,
                    promptRpcError = promptRpcError,
                    responseText = responseText.take(500),
                    responseContainsError = hasError,
                ))
            }

            report.appendLine()
            report.appendLine("=" .repeat(80))
            report.appendLine("SUMMARY")
            report.appendLine("=" .repeat(80))

            val switchFailed = rows.filter { !it.switchOk }
            val promptFailed = rows.filter { it.switchOk && !it.promptOk }
            val backendError = rows.filter { it.switchOk && it.responseContainsError }
            val fullyWorking = rows.filter { it.switchOk && it.promptOk && !it.responseContainsError }

            report.appendLine("  Total models tested:                ${rows.size}")
            report.appendLine("  Switch failed (RPC error):          ${switchFailed.size}")
            report.appendLine("  Prompt failed (RPC error):          ${promptFailed.size}")
            report.appendLine("  Prompt OK but response has error:   ${backendError.size}")
            report.appendLine("  Fully working:                      ${fullyWorking.size}")
            report.appendLine()

            if (backendError.isNotEmpty()) {
                report.appendLine("!!! MODELS WHERE SWITCH SUCCEEDS BUT PROMPT RETURNS ERROR IN RESPONSE TEXT !!!")
                report.appendLine("These model values are provided by the agent's own configOptions but are")
                report.appendLine("rejected by the backend when a prompt is actually sent.")
                report.appendLine()
                for (row in backendError) {
                    report.appendLine("  Model: ${row.modelValue} (${row.modelName})")
                    report.appendLine("    Response: ${row.responseText.take(200)}")
                    report.appendLine()
                }
            }

            if (promptFailed.isNotEmpty()) {
                report.appendLine("MODELS WHERE PROMPT RPC CALL ITSELF FAILED:")
                for (row in promptFailed) {
                    report.appendLine("  ${row.modelValue} -> ${row.promptRpcError}")
                }
                report.appendLine()
            }

            if (switchFailed.isNotEmpty()) {
                report.appendLine("MODELS WHERE SWITCH FAILED:")
                for (row in switchFailed) {
                    report.appendLine("  ${row.modelValue} -> ${row.switchError}")
                }
                report.appendLine()
            }

            report.appendLine("FULLY WORKING MODELS:")
            for (row in fullyWorking) {
                report.appendLine("  ${row.modelValue} (${row.modelName})")
            }

            report.appendLine()
            report.appendLine("=" .repeat(80))
            report.appendLine("END OF POST-SWITCH PROMPT DIAGNOSTIC REPORT")
            report.appendLine("=" .repeat(80))
        } finally {
            process?.destroyForcibly()
            workspace.deleteRecursively()
        }

        System.out.println(report.toString())
    }

    // -- Helpers --

    private data class SwitchResult(
        val success: Boolean,
        val confirmedModel: String? = null,
        val errorCode: Int? = null,
        val errorMessage: String? = null,
        val errorSummary: String? = null,
    )

    private fun trySetConfigOption(rpc: JsonRpcWire, sessionId: String, configId: String, modelId: String): SwitchResult {
        return try {
            val result = rpc.request("session/set_config_option", buildJsonObject {
                put("sessionId", sessionId)
                put("configId", configId)
                put("value", modelId)
            })
            val confirmed = modelCurrentValueFromConfigOptions(result)
            SwitchResult(success = true, confirmedModel = confirmed)
        } catch (e: AcpException) {
            SwitchResult(
                success = false,
                errorCode = e.code,
                errorMessage = e.message,
                errorSummary = "[${e.code}] ${e.message?.take(50)}",
            )
        } catch (e: Exception) {
            SwitchResult(
                success = false,
                errorCode = -1,
                errorMessage = e.message,
                errorSummary = e.message?.take(50),
            )
        }
    }

    private fun trySetModel(rpc: JsonRpcWire, sessionId: String, modelId: String): SwitchResult {
        return try {
            rpc.request("session/set_model", buildJsonObject {
                put("sessionId", sessionId)
                put("modelId", modelId)
            })
            SwitchResult(success = true)
        } catch (e: AcpException) {
            SwitchResult(
                success = false,
                errorCode = e.code,
                errorMessage = e.message,
                errorSummary = "[${e.code}] ${e.message?.take(50)}",
            )
        } catch (e: Exception) {
            SwitchResult(
                success = false,
                errorCode = -1,
                errorMessage = e.message,
                errorSummary = e.message?.take(50),
            )
        }
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

    private fun extractModelConfigId(sessionResult: JsonElement): String? {
        val configOptions = sessionResult.jsonObject["configOptions"]?.jsonArray.orEmpty()
        val modelOption = configOptions.firstOrNull { element ->
            val obj = element.jsonObject
            val category = obj["category"]?.jsonPrimitive?.content
            val id = obj["id"]?.jsonPrimitive?.content
            ConfigOptionUiSupport.isModelConfigId(id.orEmpty()) || category == "model"
        } ?: return null
        return modelOption.jsonObject["id"]?.jsonPrimitive?.content
    }

    private fun extractModelOptionValues(sessionResult: JsonElement): List<String> {
        val configOptions = sessionResult.jsonObject["configOptions"]?.jsonArray.orEmpty()
        val modelOption = configOptions.firstOrNull { element ->
            val obj = element.jsonObject
            val category = obj["category"]?.jsonPrimitive?.content
            val id = obj["id"]?.jsonPrimitive?.content
            ConfigOptionUiSupport.isModelConfigId(id.orEmpty()) || category == "model"
        } ?: return emptyList()
        return modelOption.jsonObject["options"]?.jsonArray
            ?.mapNotNull { it.jsonObject["value"]?.jsonPrimitive?.content?.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }

    private fun extractModelOptionValuesWithNames(sessionResult: JsonElement): List<Pair<String, String>> {
        val configOptions = sessionResult.jsonObject["configOptions"]?.jsonArray.orEmpty()
        val modelOption = configOptions.firstOrNull { element ->
            val obj = element.jsonObject
            val category = obj["category"]?.jsonPrimitive?.content
            val id = obj["id"]?.jsonPrimitive?.content
            ConfigOptionUiSupport.isModelConfigId(id.orEmpty()) || category == "model"
        } ?: return emptyList()
        return modelOption.jsonObject["options"]?.jsonArray
            ?.mapNotNull { opt ->
                val value = opt.jsonObject["value"]?.jsonPrimitive?.content?.trim() ?: return@mapNotNull null
                val name = opt.jsonObject["name"]?.jsonPrimitive?.content?.trim() ?: value
                if (value.isBlank()) null else Pair(value, name)
            }
            ?: emptyList()
    }

    private data class CliModelInfo(val id: String, val displayName: String, val isCurrent: Boolean)

    private fun fetchCliModels(agentPath: String): List<CliModelInfo> {
        val command = buildAgentCommand(agentPath, "models")
        val pb = ProcessBuilder(command).redirectErrorStream(true)
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText()
        val exited = proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
        if (!exited) {
            proc.destroyForcibly()
            return emptyList()
        }
        return parseCliModels(output)
    }

    private fun parseCliModels(output: String): List<CliModelInfo> {
        val cleaned = output.replace(Regex("\u001B\\[[0-9;]*[A-Za-z]"), "")
        val modelPattern = Regex("""^(\S+)\s+-\s+(.+)$""")
        return cleaned.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val match = modelPattern.matchEntire(line) ?: return@mapNotNull null
                val id = match.groupValues[1]
                var displayName = match.groupValues[2].trim()
                val isCurrent = displayName.contains("(current")
                displayName = displayName.replace(Regex("""\s*\(current[^)]*\)"""), "").trim()
                CliModelInfo(id, displayName, isCurrent)
            }
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
                put("name", "cursorj-model-validity-diagnostic")
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

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

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
                val responseId = message["id"]?.jsonPrimitive?.intOrNull
                if (responseId != id) continue
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

        fun collectNotificationText(): String {
            val sb = StringBuilder()
            for (notification in lastNotifications) {
                val params = notification["params"]?.jsonObject ?: continue
                val update = params["update"]?.jsonObject ?: params
                val sessionUpdate = update["sessionUpdate"]?.jsonPrimitive?.contentOrNull
                if (sessionUpdate == "agent_message_chunk" || sessionUpdate == "agent_message_end") {
                    val text = extractTextFromUpdate(update)
                    if (text != null) sb.append(text)
                }
            }
            return sb.toString()
        }

        fun dumpNotifications(): String {
            return lastNotifications.joinToString("\n") { it.toString().take(500) }
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
