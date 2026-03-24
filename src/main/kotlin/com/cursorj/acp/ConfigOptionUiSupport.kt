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
 */
object ConfigOptionUiSupport {

    private val TRUTHY = setOf("true", "1", "yes", "on", "enabled")
    private val FALSY = setOf("false", "0", "no", "off", "disabled")

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
}
