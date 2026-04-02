package com.cursorj.handlers

import com.cursorj.acp.messages.PermissionOption
import com.cursorj.acp.messages.PermissionToolCallRef
import com.cursorj.acp.messages.RequestPermissionParams
import com.cursorj.permissions.PermissionMode
import com.cursorj.permissions.PermissionPolicy
import com.cursorj.settings.CursorJSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PermissionHandlerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `run everything mode auto allows without prompting user`() {
        val settings = CursorJSettings().apply {
            permissionMode = PermissionMode.RUN_EVERYTHING.id
        }
        var promptCalled = false
        val handler = PermissionHandler(settingsProvider = { settings })
        handler.setPromptResolver {
            promptCalled = true
            CompletableFuture.completedFuture("reject-once")
        }

        val request = RequestPermissionParams(toolName = "Shell", description = "Run command")
        val result = handler.handlePermissionRequest(
            json.encodeToJsonElement(RequestPermissionParams.serializer(), request),
        )
        val optionId = extractOptionId(result)

        assertTrue(PermissionPolicy.isAllowOption(optionId))
        assertFalse(promptCalled)
    }

    @Test
    fun `run everything still invokes resolver for interactive plan question`() {
        val settings = CursorJSettings().apply {
            permissionMode = PermissionMode.RUN_EVERYTHING.id
        }
        var promptCalled = false
        val handler = PermissionHandler(settingsProvider = { settings })
        handler.setPromptResolver {
            promptCalled = true
            CompletableFuture.completedFuture("cwd")
        }

        val request = RequestPermissionParams(
            toolCall = PermissionToolCallRef(
                kind = "other",
                title = "Persistence behavior",
            ),
            options = listOf(
                PermissionOption("cwd", name = "Keep in cwd"),
                PermissionOption("user_home", name = "User home"),
            ),
        )
        val result = handler.handlePermissionRequest(
            json.encodeToJsonElement(RequestPermissionParams.serializer(), request),
        )

        assertTrue(promptCalled)
        assertEquals("cwd", extractOptionId(result))
    }

    @Test
    fun `invalid resolver option falls back to reject`() {
        val settings = CursorJSettings().apply {
            permissionMode = PermissionMode.ASK_EVERY_TIME.id
        }
        val handler = PermissionHandler(settingsProvider = { settings })
        handler.setPromptResolver {
            CompletableFuture.completedFuture("definitely-not-valid")
        }

        val request = RequestPermissionParams(
            toolName = "Shell",
            description = "Run command",
        )
        val result = handler.handlePermissionRequest(
            json.encodeToJsonElement(RequestPermissionParams.serializer(), request),
        )
        val optionId = extractOptionId(result)

        assertEquals(PermissionPolicy.chooseRejectOption(request.options), optionId)
        assertTrue(settings.getApprovedPermissionKeys().isEmpty())
    }

    @Test
    fun `allow decision persists approved permission keys`() {
        val settings = CursorJSettings().apply {
            permissionMode = PermissionMode.ASK_EVERY_TIME.id
        }
        val handler = PermissionHandler(settingsProvider = { settings })
        handler.setPromptResolver {
            CompletableFuture.completedFuture("allow-once")
        }

        val request = RequestPermissionParams(
            toolName = "Shell",
            description = "Run tests",
            arguments = buildJsonObject {
                put("command", "npm test")
            },
        )
        val normalizedRequest = PermissionPolicy.withResolvedToolName(request)
        val expectedKeys = PermissionPolicy.approvedKeysForRequest(normalizedRequest)

        val result = handler.handlePermissionRequest(
            json.encodeToJsonElement(RequestPermissionParams.serializer(), request),
        )
        val optionId = extractOptionId(result)

        assertTrue(PermissionPolicy.isAllowOption(optionId))
        val approvedKeys = settings.getApprovedPermissionKeys()
        assertTrue(expectedKeys.all { it in approvedKeys })
    }

    @Test
    fun `allow selection is accepted when request has empty option list`() {
        val settings = CursorJSettings().apply {
            permissionMode = PermissionMode.ASK_EVERY_TIME.id
        }
        val handler = PermissionHandler(settingsProvider = { settings })
        handler.setPromptResolver {
            CompletableFuture.completedFuture("allow-once")
        }

        val request = RequestPermissionParams(
            toolName = "Shell",
            description = "Run command",
            options = emptyList(),
        )
        val result = handler.handlePermissionRequest(
            json.encodeToJsonElement(RequestPermissionParams.serializer(), request),
        )

        assertEquals("allow-once", extractOptionId(result))
    }

    private fun extractOptionId(result: kotlinx.serialization.json.JsonElement): String {
        return result.jsonObject["outcome"]
            ?.jsonObject
            ?.get("optionId")
            ?.jsonPrimitive
            ?.content
            ?: error("Missing permission option id")
    }
}
