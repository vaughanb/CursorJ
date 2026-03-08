package com.cursorj.acp.messages

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AcpMessagesSerializationTest {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun `serializes polymorphic prompt content blocks with type discriminator`() {
        val params = SessionPromptParams(
            sessionId = "session-123",
            prompt = listOf(
                TextContent(text = "Hello"),
                ResourceLinkContent(uri = "file:///tmp/a.kt", name = "a.kt"),
                ImageContent(data = "base64-data", mimeType = "image/png"),
            ),
        )

        val encoded = json.encodeToString(SessionPromptParams.serializer(), params)

        assertTrue(encoded.contains("\"type\":\"text\""))
        assertTrue(encoded.contains("\"type\":\"resource_link\""))
        assertTrue(encoded.contains("\"type\":\"image\""))

        val decoded = json.decodeFromString(SessionPromptParams.serializer(), encoded)
        assertEquals(3, decoded.prompt.size)
        assertEquals("Hello", (decoded.prompt[0] as TextContent).text)
        assertEquals("file:///tmp/a.kt", (decoded.prompt[1] as ResourceLinkContent).uri)
    }

    @Test
    fun `permission request provides safe default options`() {
        val params = RequestPermissionParams(toolName = "shell")

        assertEquals(3, params.options.size)
        assertEquals("allow-once", params.options[0].optionId)
        assertEquals("allow-always", params.options[1].optionId)
        assertEquals("reject-once", params.options[2].optionId)
    }

    @Test
    fun `initialize params expose expected defaults`() {
        val params = InitializeParams()

        assertEquals(1, params.protocolVersion)
        assertTrue(params.clientCapabilities.fs.readTextFile)
        assertTrue(params.clientCapabilities.fs.writeTextFile)
        assertTrue(params.clientCapabilities.terminal)
        assertEquals("cursorj", params.clientInfo.name)
    }
}
