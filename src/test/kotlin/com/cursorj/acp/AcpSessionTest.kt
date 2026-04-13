package com.cursorj.acp

import com.cursorj.acp.messages.ConfigOption
import com.cursorj.acp.messages.ConfigOptionValue
import com.cursorj.acp.messages.PlanEntry
import com.cursorj.acp.messages.TodoItem
import com.cursorj.rollback.LocalHistoryGateway
import com.cursorj.rollback.TurnRollbackManager
import com.intellij.history.ByteContent
import com.intellij.history.Label
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AcpSessionTest {
    @Test
    fun `maps known mode values and defaults unknown values to agent`() {
        assertEquals(SessionMode.AGENT, SessionMode.fromValue("agent"))
        assertEquals(SessionMode.PLAN, SessionMode.fromValue("plan"))
        assertEquals(SessionMode.ASK, SessionMode.fromValue("ask"))
        assertEquals(SessionMode.AGENT, SessionMode.fromValue("something-else"))
    }

    @Test
    fun `builds streaming message chunks and finalizes assistant message`() = withSession { session ->
        val streamed = mutableListOf<ChatMessage>()
        session.addMessageListener { streamed.add(it) }

        session.handleSessionUpdate(update("agent_message_chunk", contentText = "Hel"))
        session.handleSessionUpdate(update("agent_message_chunk", contentText = "lo"))
        session.handleSessionUpdate(update("agent_message_end", contentText = "!"))

        assertEquals(3, streamed.size)
        assertTrue(streamed[0].isStreaming)
        assertEquals("Hel", streamed[0].content)
        assertTrue(streamed[1].isStreaming)
        assertEquals("Hello", streamed[1].content)
        assertFalse(streamed[2].isStreaming)
        assertEquals("Hello!", streamed[2].content)

        assertEquals(1, session.messages.size)
        assertEquals("assistant", session.messages[0].role)
        assertEquals("Hello!", session.messages[0].content)
    }

    @Test
    fun `captures tool output and emits activity for tool calls`() = withSession { session ->
        val activities = mutableListOf<String>()
        val toolActivities = mutableListOf<Pair<String, ToolActivity>>()
        session.addActivityListener { activities.add(it) }
        session.addToolCallListener { id, activity -> toolActivities.add(id to activity) }

        session.handleSessionUpdate(
            update(
                "tool_call",
                extra = buildJsonObject {
                    put("toolCallId", "call-1")
                    put("kind", "edit")
                    put("title", "Edit `src/main.kt`")
                    put(
                        "rawInput",
                        buildJsonObject {
                            put("path", "/tmp/src/main.kt")
                        },
                    )
                    put("content", buildJsonObject { put("text", "first") })
                },
            ),
        )
        session.handleSessionUpdate(
            update(
                "tool_call_update",
                extra = buildJsonObject {
                    put("toolCallId", "call-1")
                    put("kind", "edit")
                    put("rawOutput", buildJsonObject { put("text", " second") })
                    put(
                        "rawInput",
                        buildJsonObject {
                            put("path", "/tmp/src/main.kt")
                        },
                    )
                },
            ),
        )

        assertEquals("first second", session.toolCallContents["call-1"])
        assertTrue(activities.any { it.contains("Editing main.kt...") })
        assertEquals(2, toolActivities.size)
        assertEquals("call-1", toolActivities[0].first)
        assertEquals("/tmp/src/main.kt", toolActivities[0].second.path)
    }

    @Test
    fun `updates mode plan and config options from session updates`() = withSession { session ->
        val plans = mutableListOf<List<PlanEntry>>()
        val configs = mutableListOf<List<ConfigOption>>()
        session.addPlanListener { plans.add(it) }
        session.addConfigListener { configs.add(it) }

        val planEntriesJson = Json.encodeToJsonElement(
            ListSerializer(PlanEntry.serializer()),
            listOf(
                PlanEntry(content = "step one", priority = "high"),
                PlanEntry(content = "step two"),
            ),
        )

        session.handleSessionUpdate(
            update(
                "plan",
                extra = buildJsonObject {
                    put("entries", planEntriesJson)
                },
            ),
        )
        session.handleSessionUpdate(
            update(
                "current_mode_update",
                extra = buildJsonObject { put("modeId", "plan") },
            ),
        )

        val updatedOptions = listOf(
            ConfigOption(
                id = "model",
                category = "model",
                currentValue = "gpt-5",
                options = listOf(ConfigOptionValue(value = "gpt-5", name = "GPT-5")),
            ),
        )
        session.handleSessionUpdate(
            update(
                "config_options_update",
                extra = buildJsonObject {
                    put(
                        "configOptions",
                        Json.encodeToJsonElement(
                            ListSerializer(ConfigOption.serializer()),
                            updatedOptions,
                        ),
                    )
                },
            ),
        )

        assertEquals(SessionMode.PLAN, session.mode)
        assertEquals(2, session.planEntries.size)
        assertEquals("step one", session.planEntries[0].content)
        assertEquals(1, plans.size)

        val modelOption = session.getConfigOption("model")
        assertNotNull(modelOption)
        assertEquals("gpt-5", modelOption.currentValue)
        assertEquals(1, configs.size)
    }

    @Test
    fun `extracts thoughts and supports nested update payload`() = withSession { session ->
        val wrapped = buildJsonObject {
            put("sessionId", "session-1")
            put(
                "update",
                buildJsonObject {
                    put("sessionUpdate", "agent_thought_chunk")
                    put("content", buildJsonObject { put("text", "thinking...") })
                },
            )
        }

        session.handleSessionUpdate(wrapped)
        assertEquals("thinking...", session.thoughtContent)
    }

    @Test
    fun `create plan captures content and entries`() = withSession { session ->
        val createPlanPayload = buildJsonObject {
            put(
                "content",
                buildJsonArray {
                    add(buildJsonObject { put("text", "Draft plan heading.") })
                    add(buildJsonObject { put("content", buildJsonObject { put("text", " Next line.") }) })
                },
            )
            put(
                "entries",
                Json.encodeToJsonElement(
                    ListSerializer(PlanEntry.serializer()),
                    listOf(
                        PlanEntry(content = "inspect state"),
                        PlanEntry(content = "apply fix", status = "in_progress"),
                    ),
                ),
            )
        }

        session.handleCreatePlan(createPlanPayload)

        assertTrue(session.planCreated)
        assertEquals("Draft plan heading. Next line.", session.planContent)
        assertEquals(2, session.planEntries.size)
        assertEquals("apply fix", session.planEntries[1].content)
    }

    @Test
    fun `create plan captures cursor create_plan payload with plan string and todos`() = withSession { session ->
        val createPlanPayload = buildJsonObject {
            put("plan", "## Goals\n\n- Do the thing.\n")
            put(
                "todos",
                Json.encodeToJsonElement(
                    ListSerializer(TodoItem.serializer()),
                    listOf(
                        TodoItem(id = "a", content = "First task", status = "pending"),
                        TodoItem(id = "b", content = "Second task", status = "pending"),
                    ),
                ),
            )
        }

        session.handleCreatePlan(createPlanPayload)

        assertTrue(session.planCreated)
        assertTrue(session.planContent.contains("Goals"))
        assertEquals(2, session.planEntries.size)
        assertEquals("First task", session.planEntries[0].content)
    }

    @Test
    fun `tool result update emits activity status`() = withSession { session ->
        val activities = mutableListOf<String>()
        session.addActivityListener { activities.add(it) }

        session.handleSessionUpdate(update("tool_result"))

        assertEquals(listOf("Processing results..."), activities)
    }

    @Test
    fun `available commands update is parsed and notified`() = withSession { session ->
        val observed = mutableListOf<List<String>>()
        session.addAvailableCommandsListener { observed.add(it) }

        session.handleSessionUpdate(
            update(
                "available_commands_update",
                extra = buildJsonObject {
                    put(
                        "availableCommands",
                        buildJsonArray {
                            add(JsonPrimitive("help"))
                            add(buildJsonObject { put("name", "build") })
                            add(buildJsonObject { put("id", "test") })
                            add(buildJsonObject { put("command", "deploy") })
                        },
                    )
                },
            ),
        )

        assertEquals(listOf("help", "build", "test", "deploy"), session.availableCommands)
        assertEquals(1, observed.size)
        assertEquals(session.availableCommands, observed.first())
    }

    @Test
    fun `recordAgentPlanFileWritten sets planCreated and onPlanFileTouch only for cursor plans markdown`() =
        withSession { session ->
            val touched = mutableListOf<String>()
            session.onPlanFileTouch = { touched.add(it) }

            session.recordAgentPlanFileWritten("C:/repo/src/Main.kt")
            assertFalse(session.planCreated)
            assertEquals("C:/repo/src/Main.kt", session.agentWrittenPlanPath)
            assertTrue(touched.isEmpty())

            session.recordAgentPlanFileWritten("C:/Users/x/.cursor/plans/App.plan.md")
            assertTrue(session.planCreated)
            assertEquals("C:/Users/x/.cursor/plans/App.plan.md", session.agentWrittenPlanPath)
            assertEquals(listOf("C:/Users/x/.cursor/plans/App.plan.md"), touched)
        }

    @Test
    fun `completed edit diff under cursor plans records path planCreated and notifies touch`() = withSession { session ->
        val (root, planFile) = createTempDirWithCursorPlan("before")
        try {
            val touched = mutableListOf<String>()
            session.onPlanFileTouch = { touched.add(it) }
            val expectedPath = planFile.canonicalFile.absolutePath.replace('\\', '/')
            val diffs = mutableListOf<EditDiffContent>()
            session.addEditDiffListener { diffs.add(it) }

            session.handleSessionUpdate(
                update(
                    "tool_call_update",
                    extra = buildJsonObject {
                        put("toolCallId", "edit-plan-1")
                        put("status", "completed")
                        put(
                            "content",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "diff")
                                        put("path", planFile.absolutePath)
                                        put("oldText", "before")
                                        put("newText", "after")
                                    },
                                )
                            },
                        )
                    },
                ),
            )

            assertTrue(session.planCreated)
            assertEquals(expectedPath, session.agentWrittenPlanPath)
            assertEquals(listOf(expectedPath), touched)
            assertEquals(1, diffs.size)
            assertEquals("after", diffs[0].newText)
            assertEquals(planFile.absolutePath.replace('\\', '/'), diffs[0].path.replace('\\', '/'))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `completed edit diff outside cursor plans does not record plan path or set planCreated`() =
        withSession { session ->
            val other = Files.createTempFile("cursorj-not-plan", ".kt").toFile()
            try {
                other.writeText("a")
                session.onPlanFileTouch = { error("should not notify") }

                session.handleSessionUpdate(
                    update(
                        "tool_call_update",
                        extra = buildJsonObject {
                            put("toolCallId", "edit-src")
                            put("status", "completed")
                            put(
                                "content",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "diff")
                                            put("path", other.absolutePath)
                                            put("oldText", "a")
                                            put("newText", "b")
                                        },
                                    )
                                },
                            )
                        },
                    ),
                )

                assertFalse(session.planCreated)
                assertNull(session.agentWrittenPlanPath)
            } finally {
                other.delete()
            }
        }

    @Test
    fun `create plan then unrelated edit diff keeps planCreated true`() = withSession { session ->
        session.handleCreatePlan(buildJsonObject { put("plan", "# Plan") })
        assertTrue(session.planCreated)

        val other = Files.createTempFile("cursorj-other", ".kt").toFile()
        try {
            other.writeText("x")
            session.handleSessionUpdate(
                update(
                    "tool_call_update",
                    extra = buildJsonObject {
                        put("toolCallId", "e2")
                        put("status", "completed")
                        put(
                            "content",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "diff")
                                        put("path", other.absolutePath)
                                        put("oldText", "x")
                                        put("newText", "y")
                                    },
                                )
                            },
                        )
                    },
                ),
            )
            assertTrue(session.planCreated)
        } finally {
            other.delete()
        }
    }

    @Test
    fun `tool_call_update with plan saved text records cursor plan path when under cursor plans`() =
        withSession { session ->
            val (root, planFile) = createTempDirWithCursorPlan("stub")
            try {
                val touched = mutableListOf<String>()
                session.onPlanFileTouch = { touched.add(it) }
                val uri = planFile.toURI().toString()
                val expectedPath = planFile.canonicalFile.absolutePath.replace('\\', '/')

                session.handleSessionUpdate(
                    update(
                        "tool_call_update",
                        extra = buildJsonObject {
                            put("toolCallId", "create-plan-tool")
                            put("status", "in_progress")
                            put(
                                "content",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put(
                                                "content",
                                                buildJsonObject {
                                                    put("type", "text")
                                                    put("text", "Plan saved to $uri")
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    ),
                )

                assertTrue(session.planCreated)
                assertEquals(expectedPath, session.agentWrittenPlanPath)
                assertEquals(listOf(expectedPath), touched)
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `tool_call with plan saved text records path like tool_call_update`() = withSession { session ->
        val (root, planFile) = createTempDirWithCursorPlan("x")
        try {
            val uri = planFile.toURI().toString()
            val expectedPath = planFile.canonicalFile.absolutePath.replace('\\', '/')

            session.handleSessionUpdate(
                update(
                    "tool_call",
                    extra = buildJsonObject {
                        put("toolCallId", "t1")
                        put("kind", "other")
                        put("status", "pending")
                        put(
                            "content",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put(
                                            "content",
                                            buildJsonObject {
                                                put("type", "text")
                                                put("text", "Plan saved to $uri")
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                ),
            )

            assertTrue(session.planCreated)
            assertEquals(expectedPath, session.agentWrittenPlanPath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `model config merge preserves prior options on partial updates`() = withSession { session ->
        val snapshots = mutableListOf<List<ConfigOption>>()
        session.addConfigListener { snapshots.add(it) }

        val initial = listOf(
            ConfigOption(
                id = "model",
                category = "model",
                currentValue = "gpt-5",
                options = listOf(
                    ConfigOptionValue(value = "gpt-5", name = "GPT-5"),
                    ConfigOptionValue(value = "claude-opus", name = "Claude Opus"),
                ),
            ),
        )
        session.handleSessionUpdate(
            update(
                "config_options_update",
                extra = buildJsonObject {
                    put(
                        "configOptions",
                        Json.encodeToJsonElement(ListSerializer(ConfigOption.serializer()), initial),
                    )
                },
            ),
        )

        val partial = listOf(
            ConfigOption(
                id = "model",
                category = "model",
                currentValue = "claude-opus",
                options = listOf(
                    ConfigOptionValue(value = "claude-opus", name = "Claude Opus"),
                ),
            ),
        )
        session.handleSessionUpdate(
            update(
                "config_options_update",
                extra = buildJsonObject {
                    put(
                        "configOptions",
                        Json.encodeToJsonElement(ListSerializer(ConfigOption.serializer()), partial),
                    )
                },
            ),
        )

        val merged = session.getConfigOption("model")
        assertNotNull(merged)
        assertEquals("claude-opus", merged.currentValue)
        assertTrue(merged.options.any { it.value == "gpt-5" })
        assertTrue(merged.options.any { it.value == "claude-opus" })
        assertTrue(snapshots.isNotEmpty())
        val lastSnapshot = snapshots.last()
        val lastModel = lastSnapshot.firstOrNull { it.id == "model" }
        assertNotNull(lastModel)
        assertTrue(lastModel.options.any { it.value == "gpt-5" })
    }

    @Test
    fun `non model config options are replaced by latest payload`() = withSession { session ->
        val first = listOf(
            ConfigOption(
                id = "thought_level",
                category = "thought_level",
                currentValue = "medium",
                options = listOf(
                    ConfigOptionValue(value = "low", name = "Low"),
                    ConfigOptionValue(value = "medium", name = "Medium"),
                ),
            ),
        )
        session.handleSessionUpdate(
            update(
                "config_options_update",
                extra = buildJsonObject {
                    put(
                        "configOptions",
                        Json.encodeToJsonElement(ListSerializer(ConfigOption.serializer()), first),
                    )
                },
            ),
        )

        val second = listOf(
            ConfigOption(
                id = "thought_level",
                category = "thought_level",
                currentValue = "high",
                options = listOf(
                    ConfigOptionValue(value = "high", name = "High"),
                ),
            ),
        )
        session.handleSessionUpdate(
            update(
                "config_options_update",
                extra = buildJsonObject {
                    put(
                        "configOptions",
                        Json.encodeToJsonElement(ListSerializer(ConfigOption.serializer()), second),
                    )
                },
            ),
        )

        val replaced = session.getConfigOption("thought_level")
        assertNotNull(replaced)
        assertEquals("high", replaced.currentValue)
        assertEquals(listOf("high"), replaced.options.map { it.value })
    }

    @Test
    fun `two sessions maintain independent model config state`() {
        val disposable = Disposer.newDisposable("AcpSessionIsolationTest")
        try {
            val rollback = TurnRollbackManager(NoopLocalHistoryGateway())
            val client = AcpClient(disposable)
            val first = AcpSession("session-a", client, rollback)
            val second = AcpSession("session-b", client, rollback)

            val firstConfig = listOf(
                ConfigOption(
                    id = "model",
                    category = "model",
                    currentValue = "gpt-5",
                    options = listOf(ConfigOptionValue("gpt-5", "GPT-5")),
                ),
            )
            val secondConfig = listOf(
                ConfigOption(
                    id = "model",
                    category = "model",
                    currentValue = "claude-opus",
                    options = listOf(ConfigOptionValue("claude-opus", "Claude Opus")),
                ),
            )

            first.handleSessionUpdate(
                update(
                    "config_options_update",
                    extra = buildJsonObject {
                        put("configOptions", Json.encodeToJsonElement(ListSerializer(ConfigOption.serializer()), firstConfig))
                    },
                ),
            )
            second.handleSessionUpdate(
                update(
                    "config_options_update",
                    extra = buildJsonObject {
                        put("configOptions", Json.encodeToJsonElement(ListSerializer(ConfigOption.serializer()), secondConfig))
                    },
                ),
            )

            assertEquals("gpt-5", first.getConfigOption("model")?.currentValue)
            assertEquals("claude-opus", second.getConfigOption("model")?.currentValue)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    private fun createTempDirWithCursorPlan(initialContent: String): Pair<File, File> {
        val root = Files.createTempDirectory("cursorj-acp-plan").toFile()
        val plansDir = File(root, ".cursor/plans").apply { mkdirs() }
        val planFile = File(plansDir, "unit-test.plan.md").apply { writeText(initialContent) }
        return root to planFile
    }

    private fun withSession(block: (AcpSession) -> Unit) {
        val disposable = Disposer.newDisposable("AcpSessionTestDisposable")
        try {
            val client = AcpClient(disposable)
            val rollbackManager = TurnRollbackManager(NoopLocalHistoryGateway())
            block(AcpSession("session-1", client, rollbackManager))
        } finally {
            Disposer.dispose(disposable)
        }
    }

    private class NoopLocalHistoryGateway : LocalHistoryGateway {
        override fun putSystemLabel(name: String): Label = NoopLabel

        override fun revert(label: Label) {}
    }

    private object NoopLabel : Label {
        override fun revert(project: Project, file: VirtualFile) {}

        override fun getByteContent(path: String): ByteContent? = null
    }

    private fun update(
        type: String,
        contentText: String? = null,
        extra: kotlinx.serialization.json.JsonObject? = null,
    ) = buildJsonObject {
        put("sessionUpdate", type)
        if (contentText != null) {
            put("content", buildJsonObject { put("text", contentText) })
        }
        extra?.forEach { (key, value) -> put(key, value) }
    }
}
