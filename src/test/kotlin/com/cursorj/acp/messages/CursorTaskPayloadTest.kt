package com.cursorj.acp.messages

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CursorTaskPayloadTest {

    @Test
    fun parse_minimalExplore() {
        val params = buildJsonObject {
            put("toolCallId", "call_1")
            put("description", "Find auth files")
            put("prompt", "Search the repo")
            put("subagentType", JsonPrimitive("explore"))
        }
        val ev = CursorTaskPayload.parse(params)!!
        assertEquals("call_1", ev.toolCallId)
        assertEquals("Find auth files", ev.description)
        assertTrue(ev.promptSummary!!.startsWith("Search"))
        assertEquals("explore", ev.subagentTypeLabel)
        assertNull(ev.durationMs)
        assertTrue(!ev.isComplete)
    }

    @Test
    fun parse_customSubagentTypeObject() {
        val params = buildJsonObject {
            put("toolCallId", "c2")
            put("description", "Custom job")
            put("prompt", "x")
            put("subagentType", buildJsonObject { put("custom", "my_runner") })
        }
        val ev = CursorTaskPayload.parse(params)!!
        assertEquals("my_runner", ev.subagentTypeLabel)
    }

    @Test
    fun parse_completionWithDuration() {
        val params = buildJsonObject {
            put("toolCallId", "call_1")
            put("description", "Find auth files")
            put("prompt", "p")
            put("subagentType", JsonPrimitive("shell"))
            put("durationMs", 1250L)
            put("agentId", "agent-abc")
            put("model", "fast")
        }
        val ev = CursorTaskPayload.parse(params)!!
        assertEquals(1250L, ev.durationMs)
        assertEquals("agent-abc", ev.agentId)
        assertEquals("fast", ev.model)
        assertTrue(ev.isComplete)
    }

    @Test
    fun parse_rejectsMissingToolCallId() {
        val params = buildJsonObject {
            put("description", "x")
        }
        assertNull(CursorTaskPayload.parse(params))
    }

    @Test
    fun formatSubagentType_knownStrings() {
        assertEquals("browser", CursorTaskPayload.formatSubagentType(JsonPrimitive("browser_use")))
        assertEquals("VM setup", CursorTaskPayload.formatSubagentType(JsonPrimitive("vm_setup_helper")))
    }

    @Test
    fun promptForLog_truncates() {
        val long = "a".repeat(300)
        val out = CursorTaskPayload.promptForLog(long)
        assertTrue(out.endsWith("..."))
        assertEquals(200 + "...".length, out.length)
    }
}
