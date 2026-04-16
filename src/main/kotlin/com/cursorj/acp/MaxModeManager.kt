package com.cursorj.acp

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Reads and writes Cursor CLI max mode in `~/.cursor/cli-config.json`.
 *
 * The Cursor agent reads this file when the `agent acp` subprocess starts; toggling
 * max mode requires restarting that process (see [com.cursorj.ui.toolwindow.SessionTabManager.reconnectTab]).
 *
 * This is separate from ACP `session/set_config_option` — max mode is not exposed as a config option on the wire.
 */
object MaxModeManager {
    private val log = Logger.getInstance(MaxModeManager::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = false
    }

    /** Default path: `~/.cursor/cli-config.json`. Tests may inject a different path. */
    val defaultConfigPath: File
        get() = File(System.getProperty("user.home") ?: ".", ".cursor/cli-config.json")

    /**
     * Returns whether max mode is effectively enabled in [configPath], or `false` if the file is missing or unreadable.
     *
     * Reads both top-level `maxMode` and `model.maxMode`. If either is `true`, max mode is considered on (matches how
     * external tools may only update one key). If both are present and disagree, a short log line is emitted and the
     * effective value is still true when either flag is true.
     */
    fun isMaxModeEnabled(configPath: File = defaultConfigPath): Boolean {
        if (!configPath.isFile) return false
        return runCatching {
            val text = configPath.readText()
            val root = json.parseToJsonElement(text).jsonObject
            effectiveMaxMode(root)
        }.getOrElse { e ->
            log.warn("Failed to read maxMode from ${configPath.absolutePath}", e)
            false
        }
    }

    /**
     * Effective max mode: `true` if top-level `maxMode` or `model.maxMode` is `true`.
     */
    internal fun effectiveMaxMode(root: JsonObject): Boolean {
        val top = root["maxMode"]?.jsonPrimitive?.booleanOrNull
        val modelObj = root["model"] as? JsonObject
        val nested = modelObj?.get("maxMode")?.jsonPrimitive?.booleanOrNull
        if (top != null && nested != null && top != nested) {
            log.info(
                "cli-config.json has mismatched maxMode (top-level=$top, model.maxMode=$nested); " +
                    "Max Mode is on if either is true.",
            )
        }
        return (top == true) || (nested == true)
    }

    /**
     * Sets top-level `maxMode` and `model.maxMode` in [configPath].
     *
     * @return `true` if the file was written successfully.
     */
    fun setMaxMode(enabled: Boolean, configPath: File = defaultConfigPath): Boolean {
        return runCatching {
            configPath.parentFile?.mkdirs()
            val root = if (configPath.isFile) {
                json.parseToJsonElement(configPath.readText()).jsonObject
            } else {
                log.info("Creating ${configPath.absolutePath} for max mode toggle")
                JsonObject(emptyMap())
            }
            val updated = applyMaxMode(root, enabled)
            configPath.writeText(json.encodeToString(JsonObject.serializer(), updated))
            true
        }.getOrElse { e ->
            log.warn("Failed to write maxMode to ${configPath.absolutePath}", e)
            false
        }
    }

    internal fun applyMaxMode(root: JsonObject, enabled: Boolean): JsonObject {
        val map = root.toMutableMap()
        map["maxMode"] = JsonPrimitive(enabled)
        val model = map["model"]
        if (model is JsonObject) {
            val modelMap = model.toMutableMap()
            modelMap["maxMode"] = JsonPrimitive(enabled)
            map["model"] = JsonObject(modelMap)
        }
        return JsonObject(map)
    }
}
