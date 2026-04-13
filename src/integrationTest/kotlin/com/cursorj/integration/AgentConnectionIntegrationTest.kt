package com.cursorj.integration

import com.cursorj.acp.AgentConnection
import com.cursorj.acp.AcpProcessManager
import com.cursorj.acp.ConfigOptionUiSupport
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentConnectionIntegrationTest {
    @Test
    fun `connects and creates session against real agent`() = runBlocking {
        AgentCliIntegrationSupport.assumeManualOnlyEnabled()
        AgentCliIntegrationSupport.assumeIdeApplicationAvailable()
        val agentPath = AgentCliIntegrationSupport.resolveAgentPathOrSkip()

        val workspace = Files.createTempDirectory("cursorj-agent-connection-it").toFile()
        val project = AgentCliIntegrationSupport.projectWithBasePath(workspace.absolutePath)
        val disposable = AgentCliIntegrationSupport.newDisposable("AgentConnectionIntegrationTest")
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
                assertTrue(session.sessionId.isNotBlank(), "Expected non-blank session id")
                session.cancel()
            }
        } finally {
            Disposer.dispose(disposable)
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `sessions keep independent model config state`() = runBlocking {
        AgentCliIntegrationSupport.assumeManualOnlyEnabled()
        AgentCliIntegrationSupport.assumeIdeApplicationAvailable()
        val agentPath = AgentCliIntegrationSupport.resolveAgentPathOrSkip()

        val workspace = Files.createTempDirectory("cursorj-agent-connection-isolation-it").toFile()
        val project = AgentCliIntegrationSupport.projectWithBasePath(workspace.absolutePath)
        val disposable = AgentCliIntegrationSupport.newDisposable("AgentConnectionSessionIsolationIntegrationTest")
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

                val first = connection.createSession()
                val firstModelOption = first.configOptions.firstOrNull { ConfigOptionUiSupport.isModelSelector(it) }
                assumeTrue(firstModelOption != null) { "Agent did not expose model selector config options." }
                val firstCurrent = firstModelOption!!.currentValue?.trim().orEmpty()
                val alternate = firstModelOption.options
                    .map { it.value.trim() }
                    .firstOrNull { it.isNotBlank() && !it.equals(firstCurrent, ignoreCase = true) }
                assumeTrue(alternate != null) { "Need at least two model options for isolation check." }
                first.setConfigOption(firstModelOption.id, alternate!!)
                val firstConfirmed = first.configOptions.firstOrNull { ConfigOptionUiSupport.isModelSelector(it) }?.currentValue
                assertNotNull(firstConfirmed)

                val second = connection.createSession()
                assertNotEquals(first.sessionId, second.sessionId, "Each created session should have a distinct id")
                val secondModel = second.configOptions.firstOrNull { ConfigOptionUiSupport.isModelSelector(it) }?.currentValue
                assertNotNull(secondModel)
            }
        } finally {
            Disposer.dispose(disposable)
            workspace.deleteRecursively()
        }
    }
}
