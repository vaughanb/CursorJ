package com.cursorj.acp

import com.cursorj.acp.messages.ConfigOption
import com.cursorj.acp.messages.ConfigOptionValue
import com.cursorj.rollback.LocalHistoryGateway
import com.cursorj.rollback.TurnRollbackManager
import com.intellij.history.ByteContent
import com.intellij.history.Label
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentConnectionSessionLoadTest {
    @Test
    fun `extractSessionId reads top level and nested fields`() {
        fixture(modelInfos = emptyList()).use { fixture ->
            val connection = fixture.connection
            val topLevel = invokeExtractSessionId(
                connection,
                buildJsonObject {
                    put("sessionId", "session-top")
                },
            )
            val snakeCase = invokeExtractSessionId(
                connection,
                buildJsonObject {
                    put("session_id", "session-snake")
                },
            )
            val nested = invokeExtractSessionId(
                connection,
                buildJsonObject {
                    put(
                        "update",
                        buildJsonObject {
                            put("sessionId", "session-nested")
                        },
                    )
                },
            )

            assertEquals("session-top", topLevel)
            assertEquals("session-snake", snakeCase)
            assertEquals("session-nested", nested)
        }
    }

    @Test
    fun `selectedModelDisplayName prefers session model option label`() {
        fixture(modelInfos = listOf(AcpProcessManager.ModelInfo(id = "gpt-5", displayName = "CLI GPT-5"))).use { fixture ->
            val connection = fixture.connection
            connection.setSelectedModel("gpt-5")
            setSession(
                connection,
                AcpSession(
                    sessionId = "session-1",
                    client = connection.client,
                    rollbackManager = TurnRollbackManager(NoopLocalHistoryGateway()),
                    initialConfigOptions = listOf(
                        ConfigOption(
                            id = "model",
                            category = "model",
                            currentValue = "gpt-5",
                            options = listOf(
                                ConfigOptionValue(value = "gpt-5", name = "Session GPT-5"),
                            ),
                        ),
                    ),
                ),
            )
            assertEquals("Session GPT-5", connection.selectedModelDisplayName())
        }
    }

    @Test
    fun `selectedModelDisplayName falls back to CLI display name then model id`() {
        fixture(modelInfos = listOf(AcpProcessManager.ModelInfo(id = "gpt-4o", displayName = "CLI GPT-4o"))).use { fixture ->
            val connection = fixture.connection
            connection.setSelectedModel("gpt-4o")
            assertEquals("CLI GPT-4o", connection.selectedModelDisplayName())

            connection.setSelectedModel("custom-model")
            assertEquals("custom-model", connection.selectedModelDisplayName())
        }
    }

    @Test
    fun `setSelectedModel normalizes blanks to null`() {
        fixture(modelInfos = emptyList()).use { fixture ->
            val connection = fixture.connection
            connection.setSelectedModel("   ")
            assertNull(connection.selectedModel)
            assertNull(connection.selectedModelDisplayName())
        }
    }

    @Test
    fun `selectedModelDisplayName prefers ACP confirmed current value over stale selected model`() {
        fixture(
            modelInfos = listOf(
                AcpProcessManager.ModelInfo(id = "gpt-5", displayName = "CLI GPT-5"),
                AcpProcessManager.ModelInfo(id = "claude-opus", displayName = "CLI Claude Opus"),
            ),
        ).use { fixture ->
            val connection = fixture.connection
            connection.setSelectedModel("gpt-5")
            setSession(
                connection,
                AcpSession(
                    sessionId = "session-confirmed",
                    client = connection.client,
                    rollbackManager = TurnRollbackManager(NoopLocalHistoryGateway()),
                    initialConfigOptions = listOf(
                        ConfigOption(
                            id = "model",
                            category = "model",
                            currentValue = "claude-opus",
                            options = listOf(
                                ConfigOptionValue(value = "claude-opus", name = "Session Claude Opus"),
                            ),
                        ),
                    ),
                ),
            )

            assertEquals("Session Claude Opus", connection.selectedModelDisplayName())
        }
    }

    @Test
    fun `selectedModelDisplayName matches session options case insensitively`() {
        fixture(modelInfos = emptyList()).use { fixture ->
            val connection = fixture.connection
            connection.setSelectedModel("GPT-5")
            setSession(
                connection,
                AcpSession(
                    sessionId = "session-case",
                    client = connection.client,
                    rollbackManager = TurnRollbackManager(NoopLocalHistoryGateway()),
                    initialConfigOptions = listOf(
                        ConfigOption(
                            id = "model",
                            category = "model",
                            currentValue = "gpt-5",
                            options = listOf(
                                ConfigOptionValue(value = "gpt-5", name = "GPT-5 Session"),
                            ),
                        ),
                    ),
                ),
            )

            assertEquals("GPT-5 Session", connection.selectedModelDisplayName())
        }
    }

    @Test
    fun `connectedStatusDetail uses ACP confirmed session model`() {
        fixture(modelInfos = listOf(AcpProcessManager.ModelInfo(id = "gpt-5", displayName = "CLI GPT-5"))).use { fixture ->
            val connection = fixture.connection
            connection.setSelectedModel("gpt-5")
            setSession(
                connection,
                AcpSession(
                    sessionId = "session-status",
                    client = connection.client,
                    rollbackManager = TurnRollbackManager(NoopLocalHistoryGateway()),
                    initialConfigOptions = listOf(
                        ConfigOption(
                            id = "model",
                            category = "model",
                            currentValue = "claude-opus",
                            options = listOf(
                                ConfigOptionValue(value = "claude-opus", name = "Session Claude Opus"),
                            ),
                        ),
                    ),
                ),
            )
            assertEquals("Connected (Session Claude Opus)", connection.connectedStatusDetail())
        }
    }

    private class AgentConnectionFixture(
        val connection: AgentConnection,
        private val disposable: com.intellij.openapi.Disposable,
    ) : AutoCloseable {
        override fun close() {
            runCatching { Disposer.dispose(disposable) }
        }
    }

    private fun fixture(modelInfos: List<AcpProcessManager.ModelInfo>): AgentConnectionFixture {
        val project = projectWithBasePath("C:/dev/cursorj")
        val disposable = Disposer.newDisposable("AgentConnectionSessionLoadTest")
        val connection = AgentConnection(project, disposable, modelInfos = modelInfos)
        return AgentConnectionFixture(
            connection = connection,
            disposable = disposable,
        )
    }

    private fun invokeExtractSessionId(connection: AgentConnection, params: kotlinx.serialization.json.JsonElement): String? {
        val method = AgentConnection::class.java.getDeclaredMethod(
            "extractSessionId",
            kotlinx.serialization.json.JsonElement::class.java,
        ).apply {
            isAccessible = true
        }
        return method.invoke(connection, params) as String?
    }

    private fun setSession(connection: AgentConnection, session: AcpSession) {
        val field = AgentConnection::class.java.getDeclaredField("session").apply { isAccessible = true }
        field.set(connection, session)
    }

    private fun projectWithBasePath(basePath: String?): Project {
        return Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getBasePath" -> basePath
                "isDisposed" -> false
                "isDefault" -> false
                "getName" -> "test-project"
                "getLocationHash" -> "test-hash"
                else -> defaultValue(method.returnType)
            }
        } as Project
    }

    private fun defaultValue(returnType: Class<*>): Any? {
        return when (returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Double.TYPE -> 0.0
            java.lang.Float.TYPE -> 0f
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }
    }

    private class NoopLocalHistoryGateway : LocalHistoryGateway {
        override fun putSystemLabel(name: String): Label = NoopLabel

        override fun revert(label: Label) {}
    }

    private object NoopLabel : Label {
        override fun revert(project: Project, file: com.intellij.openapi.vfs.VirtualFile) {}

        override fun getByteContent(path: String): ByteContent? = null
    }
}
