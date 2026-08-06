package com.cursorj.integration

import com.cursorj.acp.AgentConnection
import com.cursorj.acp.AcpProcessManager
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Manual-only: verifies whether the real `agent acp` runtime advertises workspace skills
 * via ACP `available_commands_update` after a skill folder is present on disk.
 *
 * If the agent does not surface skills as slash commands, this test is **skipped** (not failed)
 * so local runs stay informative without blocking unrelated changes.
 */
class SkillsAcpIntegrationTest {
    @Test
    fun `workspace skill may surface in ACP available commands`() = runBlocking {
        AgentCliIntegrationSupport.assumeManualOnlyEnabled()
        AgentCliIntegrationSupport.assumeIdeApplicationAvailable()
        val agentPath = AgentCliIntegrationSupport.resolveAgentPathOrSkip()

        val workspace = Files.createTempDirectory("cursorj-skills-acp-it").toFile()
        val skillDir = File(workspace, ".cursor/skills/cursorj_it_skill").apply { mkdirs() }
        val skillFile = File(skillDir, "SKILL.md")
        skillFile.writeText(
            """
            ---
            name: cursorj_it_skill
            description: CursorJ integration test skill marker
            ---

            # cursorj_it_skill

            This file exists only for manual ACP integration verification.
            """.trimIndent() + "\n",
        )

        val project = AgentCliIntegrationSupport.projectWithBasePath(workspace.absolutePath)
        val disposable = AgentCliIntegrationSupport.newDisposable("SkillsAcpIntegrationTest")
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

                var found = false
                repeat(120) {
                    val cmds = session.availableCommands
                    if (cmds.any { cmd ->
                            val n = cmd.trim().removePrefix("/").lowercase()
                            n == "cursorj_it_skill"
                        }) {
                        found = true
                        return@repeat
                    }
                    delay(500L)
                }

                assumeTrue(found) {
                    "Agent did not advertise skill 'cursorj_it_skill' in ACP availableCommands within ~60s. " +
                        "Last commands=${session.availableCommands}. " +
                        "This may be expected if the CLI does not map skills to available_commands_update."
                }

                session.cancel()
            }
        } finally {
            Disposer.dispose(disposable)
            workspace.deleteRecursively()
        }
    }
}
