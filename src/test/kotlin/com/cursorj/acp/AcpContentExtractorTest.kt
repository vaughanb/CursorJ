package com.cursorj.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AcpContentExtractorTest {
    @Test
    fun `returns null when content is null or JsonNull`() {
        assertNull(AcpContentExtractor.extractTextFromContent(null))
        assertNull(AcpContentExtractor.extractTextFromContent(JsonNull))
    }

    @Test
    fun `extracts direct text field from object`() {
        val content = buildJsonObject {
            put("text", "hello")
            put("content", buildJsonObject { put("text", "nested") })
        }

        assertEquals("hello", AcpContentExtractor.extractTextFromContent(content))
    }

    @Test
    fun `extracts nested text when top level has no text`() {
        val content = buildJsonObject {
            put("content", buildJsonObject { put("text", "nested text") })
        }

        assertEquals("nested text", AcpContentExtractor.extractTextFromContent(content))
    }

    @Test
    fun `extracts and concatenates text blocks from arrays`() {
        val content = Json.parseToJsonElement(
            """
            [
              {"text":"first "},
              {"content":{"text":"second "}},
              {"ignored":"value"},
              "not-an-object",
              {"text":"third"}
            ]
            """.trimIndent(),
        ) as JsonArray

        assertEquals("first second third", AcpContentExtractor.extractTextFromContent(content))
    }

    @Test
    fun `returns null for empty text arrays`() {
        val content = JsonArray(
            listOf(
                JsonObject(emptyMap()),
                JsonPrimitive("string block is ignored for arrays"),
            ),
        )

        assertNull(AcpContentExtractor.extractTextFromContent(content))
    }

    @Test
    fun `returns primitive content when element is primitive`() {
        assertEquals("plain text", AcpContentExtractor.extractTextFromContent(JsonPrimitive("plain text")))
        assertEquals("42", AcpContentExtractor.extractTextFromContent(JsonPrimitive(42)))
    }

    @Test
    fun `returns null when object has no supported text fields`() {
        val content = buildJsonObject {
            put("message", "no text key")
        }

        assertNull(AcpContentExtractor.extractTextFromContent(content))
    }
}
