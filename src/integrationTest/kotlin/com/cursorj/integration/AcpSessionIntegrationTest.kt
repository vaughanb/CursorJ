package com.cursorj.integration

import com.cursorj.acp.AcpException
import com.cursorj.acp.AcpProcessManager
import com.cursorj.acp.AgentConnection
import com.cursorj.acp.ConfigOptionUiSupport
import com.cursorj.acp.messages.TextContent
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class AcpSessionIntegrationTest {
    @Test
    fun `prompt path is live and cancel does not deadlock`() = runBlocking {
        AgentCliIntegrationSupport.assumeManualOnlyEnabled()
        AgentCliIntegrationSupport.assumeIdeApplicationAvailable()
        val agentPath = AgentCliIntegrationSupport.resolveAgentPathOrSkip()

        val workspace = Files.createTempDirectory("cursorj-acp-session-it").toFile()
        val project = AgentCliIntegrationSupport.projectWithBasePath(workspace.absolutePath)
        val disposable = AgentCliIntegrationSupport.newDisposable("AcpSessionIntegrationTest")
        try {
            AgentCliIntegrationSupport.withConfiguredAgentPathSuspending(agentPath) {
                val connection = AgentConnection(
                    project = project,
                    parentDisposable = disposable,
                    modelInfos = emptyList<AcpProcessManager.ModelInfo>(),
                    workspaceIndexOrchestrator = null,
                )
                connection.connect()
                assertTrue(connection.isConnected, "Expected successful ACP connect/auth. lastError=${connection.lastError}")
                val session = connection.createSession()

                val promptJob = async {
                    runCatching {
                        session.sendPrompt(
                            content = listOf(TextContent("Reply with exactly: OK")),
                            displayUserText = "Reply with exactly: OK",
                        )
                    }
                }

                delay(1_500)
                session.cancel()

                val result = withTimeout(90_000) { promptJob.await() }
                val exception = result.exceptionOrNull()
                if (exception != null) {
                    assertTrue(
                        exception is AcpException,
                        "Expected AcpException when prompt is cancelled, got ${exception::class.java.name}",
                    )
                }
            }
        } finally {
            Disposer.dispose(disposable)
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `model switch api compatibility matches ui path`() = runBlocking {
        AgentCliIntegrationSupport.assumeManualOnlyEnabled()
        AgentCliIntegrationSupport.assumeIdeApplicationAvailable()
        val agentPath = AgentCliIntegrationSupport.resolveAgentPathOrSkip()

        val workspace = Files.createTempDirectory("cursorj-acp-model-switch-it").toFile()
        val project = AgentCliIntegrationSupport.projectWithBasePath(workspace.absolutePath)
        val disposable = AgentCliIntegrationSupport.newDisposable("AcpSessionModelSwitchIntegrationTest")
        try {
            AgentCliIntegrationSupport.withConfiguredAgentPathSuspending(agentPath) {
                val connection = AgentConnection(
                    project = project,
                    parentDisposable = disposable,
                    modelInfos = emptyList<AcpProcessManager.ModelInfo>(),
                    workspaceIndexOrchestrator = null,
                )
                connection.connect()
                assertTrue(connection.isConnected, "Expected successful ACP connect/auth. lastError=${connection.lastError}")
                val session = connection.createSession()

                val modelOption = session.configOptions.firstOrNull { ConfigOptionUiSupport.isModelSelector(it) }
                assumeTrue(modelOption != null) {
                    "Agent did not expose a model config option in session/new."
                }
                val option = modelOption!!
                val currentModel = option.currentValue?.trim().orEmpty()
                assumeTrue(currentModel.isNotBlank()) {
                    "Model config option has no current value."
                }
                val alternateModel = option.options
                    .map { it.value.trim() }
                    .firstOrNull { it.isNotBlank() && !it.equals(currentModel, ignoreCase = true) }
                assumeTrue(alternateModel != null) {
                    "Need at least two model options to validate switching."
                }
                val targetModel = alternateModel!!

                val configResult = runCatching {
                    connection.client.sessionSetConfigOption(session.sessionId, option.id, targetModel)
                }
                val setModelResult = runCatching {
                    connection.client.sessionSetModel(session.sessionId, targetModel)
                }

                if (configResult.isFailure && setModelResult.isSuccess) {
                    val configError = configResult.exceptionOrNull()
                    val code = (configError as? AcpException)?.code
                    val message = buildString {
                        append("Model switch compatibility mismatch: UI path uses session/set_config_option, ")
                        append("but that call failed while session/set_model succeeded. ")
                        append("configId='${option.id}', current='$currentModel', target='$targetModel', ")
                        append("configErrorCode=$code, configError='${configError?.message}'.")
                    }
                    throw AssertionError(message)
                }

                val configError = configResult.exceptionOrNull()
                assertTrue(
                    configResult.isSuccess || setModelResult.isFailure,
                    "Expected session/set_config_option to be supported for model switching. " +
                        "configId='${option.id}', current='$currentModel', target='$targetModel', " +
                        "configError='${configError?.message}', setModelError='${setModelResult.exceptionOrNull()?.message}'",
                )

                // Restore original model where possible to avoid side effects between manual test runs.
                val restoreAttempt = runCatching {
                    connection.client.sessionSetConfigOption(session.sessionId, option.id, currentModel)
                }.recoverCatching {
                    connection.client.sessionSetModel(session.sessionId, currentModel)
                }
                restoreAttempt.getOrNull()
                Unit
            }
        } finally {
            Disposer.dispose(disposable)
            workspace.deleteRecursively()
        }
    }
}
