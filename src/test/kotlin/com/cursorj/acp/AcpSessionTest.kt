package com.cursorj.acp

import com.cursorj.acp.messages.ConfigOption
import com.cursorj.acp.messages.ConfigOptionValue
import com.cursorj.acp.messages.PlanEntry
import com.intellij.openapi.util.Disposer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
                    put("rawOutput", " second")
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
    fun `tool result update emits activity status`() = withSession { session ->
        val activities = mutableListOf<String>()
        session.addActivityListener { activities.add(it) }

        session.handleSessionUpdate(update("tool_result"))

        assertEquals(listOf("Processing results..."), activities)
    }

    private fun withSession(block: (AcpSession) -> Unit) {
        val disposable = Disposer.newDisposable("AcpSessionTestDisposable")
        try {
            val client = AcpClient(disposable)
            block(AcpSession("session-1", client))
        } finally {
            Disposer.dispose(disposable)
        }
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
