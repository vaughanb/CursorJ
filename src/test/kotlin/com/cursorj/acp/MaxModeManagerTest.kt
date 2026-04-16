package com.cursorj.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MaxModeManagerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `applyMaxMode sets top level and nested model maxMode`() {
        val root = JsonObject(
            mapOf(
                "version" to JsonPrimitive(1),
                "model" to JsonObject(
                    mapOf(
                        "modelId" to JsonPrimitive("gpt-5"),
                        "maxMode" to JsonPrimitive(false),
                    ),
                ),
            ),
        )
        val updated = MaxModeManager.applyMaxMode(root, true)
        assertTrue(updated["maxMode"]!!.jsonPrimitive.booleanOrNull == true)
        val model = updated["model"]!!.jsonObject
        assertTrue(model["maxMode"]!!.jsonPrimitive.booleanOrNull == true)
        assertEquals("gpt-5", model["modelId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `setMaxMode round trip preserves unrelated fields`() {
        val dir = Files.createTempDirectory("cursorj-maxmode-test").toFile()
        val file = dir.resolve("cli-config.json")
        file.writeText(
            """
            {
              "version": 1,
              "permissions": { "allow": ["Shell(ls)"], "deny": [] },
              "model": { "modelId": "x", "maxMode": false },
              "maxMode": false,
              "other": 42
            }
            """.trimIndent(),
        )

        assertFalse(MaxModeManager.isMaxModeEnabled(file))
        assertTrue(MaxModeManager.setMaxMode(true, file))
        assertTrue(MaxModeManager.isMaxModeEnabled(file))

        val parsed = json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(1, parsed["version"]!!.jsonPrimitive.intOrNull)
        assertTrue(parsed["permissions"]!!.jsonObject.isNotEmpty())
        assertTrue(MaxModeManager.setMaxMode(false, file))
        assertFalse(MaxModeManager.isMaxModeEnabled(file))

        dir.deleteRecursively()
    }

    @Test
    fun `effectiveMaxMode is true when only model maxMode is true`() {
        val root = JsonObject(
            mapOf(
                "model" to JsonObject(mapOf("maxMode" to JsonPrimitive(true))),
            ),
        )
        assertTrue(MaxModeManager.effectiveMaxMode(root))
    }

    @Test
    fun `effectiveMaxMode is false when both keys absent or false`() {
        assertFalse(MaxModeManager.effectiveMaxMode(JsonObject(emptyMap())))
        val bothFalse = JsonObject(
            mapOf(
                "maxMode" to JsonPrimitive(false),
                "model" to JsonObject(mapOf("maxMode" to JsonPrimitive(false))),
            ),
        )
        assertFalse(MaxModeManager.effectiveMaxMode(bothFalse))
    }

    @Test
    fun `effectiveMaxMode is true if either top or nested is true`() {
        val topOnly = JsonObject(mapOf("maxMode" to JsonPrimitive(true)))
        assertTrue(MaxModeManager.effectiveMaxMode(topOnly))
        val conflict = JsonObject(
            mapOf(
                "maxMode" to JsonPrimitive(false),
                "model" to JsonObject(mapOf("maxMode" to JsonPrimitive(true))),
            ),
        )
        assertTrue(MaxModeManager.effectiveMaxMode(conflict))
    }
}
