package com.cursorj.acp

import com.cursorj.acp.messages.ConfigOption

/**
 * Helpers for interpreting ACP [com.cursorj.acp.messages.ConfigOption] payloads
 * returned by `session/new` and `session/set_config_option`.
 *
 * MAX-tier models (e.g. "Opus 4.6 Max Thinking") are detected by scanning the
 * model value **and** display name for a `max` token. This drives the "MAX"
 * badge shown next to the model dropdown.  MAX Mode and model selection are
 * treated as independent concepts — selecting a Max model variant does not
 * auto-toggle a separate MAX Mode config option.
 *
 * Exact option ids/types may vary by agent version; enable **ACP raw JSON
 * debug logging** in CursorJ settings to inspect live payloads.
 *
 * Typical shapes:
 * - `type: "toggle"` with `currentValue` `"true"` / `"false"`
 * - `type: "select"` with two options (on/off, enabled/disabled, etc.)
 */
object ConfigOptionUiSupport {

    private val TRUTHY = setOf("true", "1", "yes", "on", "enabled")
    private val FALSY = setOf("false", "0", "no", "off", "disabled")
    private val MAX_TOKEN_REGEX = Regex("""(^|[\s_-])max($|[\s_-])""")

    fun mergeWithSyntheticModel(
        agentOptions: List<ConfigOption>,
        syntheticModelOption: List<ConfigOption>,
    ): List<ConfigOption> {
        val hasModel =
            agentOptions.any { it.category == "model" || it.id == "model" }
        return if (hasModel) agentOptions else agentOptions + syntheticModelOption
    }

    fun isModelSelector(opt: ConfigOption): Boolean =
        opt.category == "model" || opt.id == "model"

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

    fun isLikelyMaxModeOption(opt: ConfigOption): Boolean {
        val id = opt.id
        val name = opt.name.orEmpty()
        val description = opt.description.orEmpty()
        return containsMaxToken(id) || containsMaxToken(name) || containsMaxToken(description)
    }

    fun isLikelyMaxModelSelection(modelOption: ConfigOption, selectedValue: String): Boolean {
        val trimmedValue = selectedValue.trim()
        if (trimmedValue.isBlank()) return false
        if (containsMaxToken(trimmedValue)) return true

        val matchedOption = modelOption.options.firstOrNull { option ->
            option.value.equals(trimmedValue, ignoreCase = true)
        } ?: return false

        val displayName = matchedOption.name.orEmpty()
        val description = matchedOption.description.orEmpty()
        return containsMaxToken(displayName) || containsMaxToken(description)
    }

    private fun containsMaxToken(text: String): Boolean {
        val normalized = text.trim().lowercase()
        if (normalized.isBlank()) return false
        if ("maxmode" in normalized) return true
        return MAX_TOKEN_REGEX.containsMatchIn(normalized)
    }

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
}
