package com.cursorj.integration

import com.cursorj.acp.AgentConnection
import com.cursorj.acp.AcpProcessManager
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class AgentConnectionReconnectIntegrationTest {
    @Test
    fun `reconnects after ACP process exit and keeps usable state`() = runBlocking {
        AgentCliIntegrationSupport.assumeManualOnlyEnabled()
        AgentCliIntegrationSupport.assumeIdeApplicationAvailable()
        val agentPath = AgentCliIntegrationSupport.resolveAgentPathOrSkip()

        val workspace = Files.createTempDirectory("cursorj-agent-reconnect-it").toFile()
        val project = AgentCliIntegrationSupport.projectWithBasePath(workspace.absolutePath)
        val disposable = AgentCliIntegrationSupport.newDisposable("AgentConnectionReconnectIntegrationTest")
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
                val initialSession = connection.createSession()
                assertTrue(initialSession.sessionId.isNotBlank())

                connection.processManager.stop()

                val reconnected = withTimeoutOrNull(30_000) {
                    while (!connection.isConnected) {
                        delay(250)
                    }
                    true
                } ?: false
                assertTrue(reconnected, "Expected AgentConnection to reconnect after process stop")

                val postReconnectSession = connection.createSession()
                assertTrue(postReconnectSession.sessionId.isNotBlank(), "Expected usable session after reconnect")
            }
        } finally {
            Disposer.dispose(disposable)
            workspace.deleteRecursively()
        }
    }
}
