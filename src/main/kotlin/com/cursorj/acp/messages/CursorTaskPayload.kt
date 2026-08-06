package com.cursorj.acp.messages

import com.cursorj.acp.SubagentTaskEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Parses Cursor ACP `cursor/task` / `_cursor/task` params into [SubagentTaskEvent].
 */
object CursorTaskPayload {
    private const val PROMPT_LOG_MAX = 200
    private const val PROMPT_UI_MAX = 120

    fun parse(params: JsonElement): SubagentTaskEvent? {
        val obj = params as? JsonObject ?: return null
        val toolCallId = obj["toolCallId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (toolCallId.isEmpty()) return null
        val description = obj["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            .ifEmpty { "(no description)" }
        val promptRaw = obj["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val promptSummary = promptRaw.takeIf { it.isNotEmpty() }?.let { summarizePrompt(it) }
        val subagentTypeLabel = formatSubagentType(obj["subagentType"])
        val model = obj["model"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val agentId = obj["agentId"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val durationMs = obj["durationMs"]?.jsonPrimitive?.longOrNull
        return SubagentTaskEvent(
            toolCallId = toolCallId,
            description = description,
            promptSummary = promptSummary,
            subagentTypeLabel = subagentTypeLabel,
            model = model,
            agentId = agentId,
            durationMs = durationMs,
        )
    }

    /** Truncated prompt for logs (caller adds prefix). */
    fun promptForLog(promptRaw: String): String =
        if (promptRaw.length <= PROMPT_LOG_MAX) promptRaw else promptRaw.take(PROMPT_LOG_MAX) + "..."

    private fun summarizePrompt(raw: String): String =
        if (raw.length <= PROMPT_UI_MAX) raw else raw.take(PROMPT_UI_MAX) + "..."

    fun formatSubagentType(element: JsonElement?): String {
        if (element == null || element is JsonNull) return "unspecified"
        when (element) {
            is JsonPrimitive -> {
                val s = element.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return "unspecified"
                return humanizeSubagentType(s)
            }
            is JsonObject -> {
                element["custom"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
                for ((k, v) in element) {
                    if (k == "custom") continue
                    val inner = (v as? JsonPrimitive)?.contentOrNull?.trim()
                    if (!inner.isNullOrEmpty()) return "$k:$inner"
                }
                return element.toString().take(80)
            }
            is JsonArray -> return element.toString().take(80)
        }
    }

    private fun humanizeSubagentType(raw: String): String =
        when (raw.lowercase()) {
            "unspecified" -> "unspecified"
            "computer_use" -> "computer use"
            "explore" -> "explore"
            "video_review" -> "video review"
            "browser_use" -> "browser"
            "shell" -> "shell"
            "vm_setup_helper" -> "VM setup"
            else -> raw.replace('_', ' ').lowercase().replaceFirstChar { c ->
                if (c.isLowerCase()) c.titlecase() else c.toString()
            }
        }
}
