package com.cursorj.integration

import com.cursorj.acp.AcpProcessManager
import com.intellij.openapi.util.Disposer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AcpProcessManagerIntegrationTest {
    @Test
    fun `starts and stops real agent acp process`() {
        AgentCliIntegrationSupport.assumeManualOnlyEnabled()
        AgentCliIntegrationSupport.assumeIdeApplicationAvailable()
        val agentPath = AgentCliIntegrationSupport.resolveAgentPathOrSkip()

        val tempDir = Files.createTempDirectory("cursorj-acp-manager-it").toFile()
        val disposable = AgentCliIntegrationSupport.newDisposable("AcpProcessManagerIntegrationTest")
        try {
            AgentCliIntegrationSupport.withConfiguredAgentPath(agentPath) {
                val manager = AcpProcessManager(disposable)
                manager.workingDirectory = tempDir.absolutePath
                assertTrue(manager.start(), "Expected agent process to start successfully")
                assertTrue(manager.isRunning, "Expected ACP process to be alive")
                assertNotNull(manager.reader, "Expected ACP reader to be initialized")
                assertNotNull(manager.writer, "Expected ACP writer to be initialized")
                manager.stop()
                assertFalse(manager.isRunning, "Expected ACP process to stop cleanly")
            }
        } finally {
            Disposer.dispose(disposable)
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `fetches model info from real agent cli`() {
        AgentCliIntegrationSupport.assumeManualOnlyEnabled()
        AgentCliIntegrationSupport.assumeIdeApplicationAvailable()
        val agentPath = AgentCliIntegrationSupport.resolveAgentPathOrSkip()

        val disposable = AgentCliIntegrationSupport.newDisposable("AcpProcessManagerModelsIntegrationTest")
        try {
            AgentCliIntegrationSupport.withConfiguredAgentPath(agentPath) {
                val manager = AcpProcessManager(disposable)
                val models = manager.fetchAvailableModelsWithInfo()
                assertNotNull(models, "Model list should never be null")
                assertTrue(models.all { it.id.isNotBlank() }, "Model IDs should be non-blank")
            }
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `fails fast when configured agent path is invalid`() {
        AgentCliIntegrationSupport.assumeManualOnlyEnabled()
        AgentCliIntegrationSupport.assumeIdeApplicationAvailable()

        val disposable = AgentCliIntegrationSupport.newDisposable("AcpProcessManagerInvalidPathIntegrationTest")
        try {
            AgentCliIntegrationSupport.withConfiguredAgentPath("/definitely/not/a/real/agent") {
                val manager = AcpProcessManager(disposable)
                assertFalse(manager.start(), "Invalid configured agent path should fail startup")
            }
        } finally {
            Disposer.dispose(disposable)
        }
    }
}
