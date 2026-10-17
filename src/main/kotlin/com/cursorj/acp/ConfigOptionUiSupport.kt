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
 *
 * [pickerSections] and [variantSuffix] turn those options into the Cursor-style model picker and its
 * `<Context> <Effort> [Fast] [No Thinking]` short name (see `docs/plans/2026-10-17-model-picker-ui.md`).
 */
object ConfigOptionUiSupport {

    private val TRUTHY = setOf("true", "1", "yes", "on", "enabled")
    private val FALSY = setOf("false", "0", "no", "off", "disabled")
    private val THOUGHT_LEVEL_IDS = setOf("effort", "reasoning", "reasoning_effort", "thinking", "thought_level")
    private val CONTEXT_SIZE = Regex("""\d+(\.\d+)?[kmKM]""")

    /** Cursor's own short-name words for its two boolean parameters (`displayNameShort` in cli-config.json). */
    private const val FAST_ID = "fast"
    private const val THINKING_ID = "thinking"
    private const val FAST_SUFFIX = "Fast"
    private const val NO_THINKING_SUFFIX = "No Thinking"

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

    /** A select whose values are all context sizes (`300k`, `1m`, `272k`); Cursor sends it as `model_config`. */
    fun isContextOption(opt: ConfigOption): Boolean =
        !isModelSelector(opt) && !isModeSelector(opt) && !isBooleanToggle(opt) &&
            opt.options.isNotEmpty() && opt.options.all { CONTEXT_SIZE.matches(it.value.trim()) }

    /** Cursor's "Auto" model: value `default` in parameterized mode, `default[]` in variants mode. */
    fun isAutoModelValue(value: String): Boolean =
        value.trim().substringBefore('[').equals("default", ignoreCase = true)

    /** Display label: the agent-provided `name`, else the id title-cased (`reasoning_effort` -> `Reasoning Effort`). */
    fun labelFor(opt: ConfigOption): String =
        cleanLabel(opt.name) ?: titleCaseId(opt.id)

    /** Display label for one of [opt]'s values: its `name` when provided, otherwise the raw value. */
    fun valueLabelFor(opt: ConfigOption, value: String): String =
        cleanLabel(opt.options.firstOrNull { it.value == value }?.name) ?: value

    /** Display label of the current model (`Claude Opus 4.7`, `Auto`), or null without a model option. */
    fun currentModelLabel(options: List<ConfigOption>): String? {
        val model = options.firstOrNull { isModelSelector(it) } ?: return null
        val current = currentValueOf(model) ?: return null
        return valueLabelFor(model, current)
    }

    /**
     * Cursor's short-name suffix for the current parameters, as in its `displayNameShort`:
     * `<Context> <Effort> [Fast] [No Thinking]`, then any other parameter as `<Name>: <value>`.
     * Examples: `300K Extra High`, `1M Low Fast No Thinking`, `Fast` (Composer); empty for Auto and in variants mode.
     */
    fun variantSuffix(options: List<ConfigOption>): String {
        val parameters = parameterOptions(options)
        val context = parameters.firstOrNull { isContextOption(it) }
        val effort = parameters.firstOrNull { isEffortSelector(it) }
        val fast = parameters.firstOrNull { it.id.equals(FAST_ID, ignoreCase = true) && isBooleanToggle(it) }
        val thinking = parameters.firstOrNull { it.id.equals(THINKING_ID, ignoreCase = true) && isBooleanToggle(it) }

        val parts = mutableListOf<String>()
        context?.let { option -> currentLabelOf(option)?.let { parts += it.uppercase() } }
        effort?.let { option -> currentLabelOf(option)?.let { parts += it } }
        if (fast != null && isToggleChecked(fast)) parts += FAST_SUFFIX
        if (thinking != null && !isToggleChecked(thinking)) parts += NO_THINKING_SUFFIX
        for (other in parameters) {
            if (other === context || other === effort || other === fast || other === thinking) continue
            val label = currentLabelOf(other) ?: continue
            parts += "${labelFor(other)}: $label"
        }
        return parts.joinToString(" ")
    }

    /** `<model> <suffix>` such as `Claude Opus 4.7 300K Extra High`, or null without a model option. */
    fun modelSummary(options: List<ConfigOption>): String? {
        val model = currentModelLabel(options) ?: return null
        val suffix = variantSuffix(options)
        return if (suffix.isBlank()) model else "$model $suffix"
    }

    /**
     * The model picker's sections in Cursor's order: thought-level selects (Effort / Reasoning), then every
     * boolean parameter as one switches section, then the remaining selects (Context); agent order within each.
     */
    fun pickerSections(options: List<ConfigOption>): List<PickerSection> {
        val parameters = parameterOptions(options)
        val switches = parameters.filter { isBooleanToggle(it) }
        val selects = parameters.filter { !isBooleanToggle(it) && it.options.isNotEmpty() }
        val (thoughtLevel, others) = selects.partition { isThoughtLevel(it) }

        val sections = mutableListOf<PickerSection>()
        thoughtLevel.mapTo(sections, ::radioSection)
        if (switches.isNotEmpty()) {
            sections += PickerSection(
                title = null,
                kind = PickerSection.Kind.SWITCHES,
                description = null,
                items = switches.map { option ->
                    PickerItem(
                        configId = option.id,
                        value = toggleOffOnValues(option).second,
                        label = labelFor(option),
                        selected = isToggleChecked(option),
                        description = cleanLabel(option.description),
                    )
                },
            )
        }
        others.mapTo(sections, ::radioSection)
        return sections
    }

    /** The effort selector's next value, wrapping around, as `configId to value`; null without an effort selector. */
    fun nextEffortSelection(options: List<ConfigOption>): Pair<String, String>? {
        val effort = options.firstOrNull { isEffortSelector(it) } ?: return null
        val values = effort.options.map { it.value }
        if (values.isEmpty()) return null
        val index = values.indexOf(effort.currentValue?.trim())
        return effort.id to values[(index + 1) % values.size]
    }

    private fun radioSection(option: ConfigOption): PickerSection {
        val current = currentValueOf(option)
        return PickerSection(
            title = labelFor(option),
            kind = PickerSection.Kind.RADIO,
            description = cleanLabel(option.description),
            items = option.options.map { value ->
                PickerItem(
                    configId = option.id,
                    value = value.value,
                    label = valueLabelFor(option, value.value),
                    selected = value.value == current,
                    description = cleanLabel(value.description) ?: cleanLabel(option.description),
                )
            },
        )
    }

    /** Everything but the model and mode selectors, in agent order. */
    private fun parameterOptions(options: List<ConfigOption>): List<ConfigOption> =
        options.filter { !isModelSelector(it) && !isModeSelector(it) }

    private fun currentValueOf(option: ConfigOption): String? =
        option.currentValue?.trim()?.takeIf { it.isNotBlank() }

    private fun currentLabelOf(option: ConfigOption): String? =
        currentValueOf(option)?.let { valueLabelFor(option, it) }

    /** Agent names sometimes carry zero-width spaces (`Grok 4.7  High Fast`); drop them, and blank names. */
    private fun cleanLabel(raw: String?): String? =
        raw?.replace("\u200B", "")?.trim()?.takeIf { it.isNotBlank() }

    private fun titleCaseId(id: String): String =
        id.split('_', '-', ' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercaseChar() } }
}

/** One titled block of the model picker: a radio group over a select option, or all boolean switches. */
data class PickerSection(
    /** The option's display name for radio sections; null for the switches section (the UI titles it "Options"). */
    val title: String?,
    val kind: Kind,
    val description: String?,
    val items: List<PickerItem>,
) {
    enum class Kind { RADIO, SWITCHES }
}

/** One picker row: a value of a select option, or a boolean option (then [value] is its "on" value). */
data class PickerItem(
    val configId: String,
    val value: String,
    val label: String,
    val selected: Boolean,
    val description: String?,
)
