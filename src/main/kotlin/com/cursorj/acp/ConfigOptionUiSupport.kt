package com.cursorj.acp

import com.cursorj.acp.messages.ConfigOption

/**
 * Helpers for interpreting ACP [com.cursorj.acp.messages.ConfigOption] payloads
 * returned by `session/new` and `session/set_config_option`.
 *
 * Exact option ids/types may vary by agent version; enable **ACP raw JSON
 * debug logging** in CursorJ settings to inspect live payloads.
 *
 * Typical shapes:
 * - `type: "toggle"` with `currentValue` `"true"` / `"false"`
 * - `type: "select"` with two options (on/off, enabled/disabled, etc.)
 * - Cursor's parameterized model picker: `thought_level` options (`effort`, `reasoning` or
 *   `reasoning_effort` depending on the model, plus the boolean `thinking`) and `model_config`
 *   options (`context`, `fast`). Classification is by category because the ids vary per model.
 */
object ConfigOptionUiSupport {

    private val TRUTHY = setOf("true", "1", "yes", "on", "enabled")
    private val FALSY = setOf("false", "0", "no", "off", "disabled")
    private val THOUGHT_LEVEL_IDS = setOf("effort", "reasoning", "reasoning_effort", "thinking", "thought_level")

    fun isModelSelector(opt: ConfigOption): Boolean =
        opt.category == "model" || opt.id == "model"

    fun isModelConfigId(configId: String): Boolean =
        configId == "model"

    /**
     * Session mode is driven by the Agent/Plan/Ask combo and `session/set_mode`;
     * duplicate mode rows from configOptions are skipped for layout.
     */
    fun isModeSelector(opt: ConfigOption): Boolean =
        opt.category == "mode" || opt.id == "mode"

    fun isBooleanToggle(opt: ConfigOption): Boolean {
        if (opt.type.equals("toggle", ignoreCase = true)) return true
        if (opt.type.equals("boolean", ignoreCase = true)) return true
        if (!opt.type.equals("select", ignoreCase = true) || opt.options.size != 2) {
            return false
        }
        return opt.options.all { v ->
            val l = v.value.lowercase()
            l in TRUTHY || l in FALSY
        }
    }

    /**
     * Returns (offValue, onValue) as required by `session/set_config_option`.
     */
    fun toggleOffOnValues(opt: ConfigOption): Pair<String, String> {
        if (opt.options.size >= 2) {
            val a = opt.options[0]
            val b = opt.options[1]
            val aOn = isTruthy(a.value)
            val bOn = isTruthy(b.value)
            return when {
                !aOn && bOn -> Pair(a.value, b.value)
                !bOn && aOn -> Pair(b.value, a.value)
                else -> Pair(a.value, b.value)
            }
        }
        return Pair("false", "true")
    }

    fun isToggleChecked(opt: ConfigOption): Boolean {
        val cur = opt.currentValue?.lowercase() ?: return false
        if (cur in TRUTHY) return true
        if (cur in FALSY) return false
        val (_, on) = toggleOffOnValues(opt)
        return opt.currentValue == on
    }

    fun isTruthy(raw: String): Boolean = raw.lowercase() in TRUTHY

    /**
     * Non-model, non-mode, non-boolean-toggle select controls (e.g. thought level).
     */
    fun isGenericSelect(opt: ConfigOption): Boolean {
        if (isModelSelector(opt) || isModeSelector(opt)) return false
        if (isBooleanToggle(opt)) return false
        return opt.type.equals("select", ignoreCase = true) && opt.options.isNotEmpty()
    }

    /** Config rows for the input bar, preserving agent-provided order (ACP recommends this). */
    fun optionsForInputBar(agentOrder: List<ConfigOption>): List<ConfigOption> =
        agentOrder.filter { !isModeSelector(it) }

    /** ACP `thought_level` category, with the known Cursor ids as a fallback when no category is sent. */
    fun isThoughtLevel(opt: ConfigOption): Boolean =
        opt.category.equals("thought_level", ignoreCase = true) || opt.id.lowercase() in THOUGHT_LEVEL_IDS

    /** ACP `model_config` category (Cursor: `context`, `fast`); the spec places these next to the model selector. */
    fun isModelConfig(opt: ConfigOption): Boolean =
        opt.category.equals("model_config", ignoreCase = true)

    /** The multi-valued thought-level selector (effort/reasoning), as opposed to the boolean `thinking` toggle. */
    fun isEffortSelector(opt: ConfigOption): Boolean =
        isThoughtLevel(opt) && !isBooleanToggle(opt)

    /** Display label: the agent-provided `name`, else the id title-cased (`reasoning_effort` -> `Reasoning Effort`). */
    fun labelFor(opt: ConfigOption): String =
        opt.name?.trim()?.takeIf { it.isNotBlank() } ?: titleCaseId(opt.id)

    /** Display label for one of [opt]'s values: its `name` when provided, otherwise the raw value. */
    fun valueLabelFor(opt: ConfigOption, value: String): String =
        opt.options.firstOrNull { it.value == value }?.name?.trim()?.takeIf { it.isNotBlank() } ?: value

    /** Status text such as `Effort: High` for the effort selector's current value, or null when there is none. */
    fun thoughtLevelSummary(options: List<ConfigOption>): String? {
        val effort = options.firstOrNull { isEffortSelector(it) } ?: return null
        val current = effort.currentValue?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return "${labelFor(effort)}: ${valueLabelFor(effort, current)}"
    }

    private fun titleCaseId(id: String): String =
        id.split('_', '-', ' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercaseChar() } }
}
