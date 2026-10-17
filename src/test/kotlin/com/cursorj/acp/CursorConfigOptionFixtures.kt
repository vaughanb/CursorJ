package com.cursorj.acp

import com.cursorj.acp.messages.ConfigOption
import com.cursorj.acp.messages.ConfigOptionValue

/**
 * ACP config options as the Cursor agent (build 2026.10.16) returns them in parameterized-picker mode and in
 * variants mode, for tests of the model picker, the parameter suffix and the input bar.
 */
internal object CursorConfigOptionFixtures {
    const val EFFORT_DESCRIPTION = "Effort the model uses to generate its response."
    const val FAST_DESCRIPTION = "6x more expensive, but significantly faster speeds."
    const val CONTEXT_DESCRIPTION = "Context size the model has available."

    fun modeOption() = ConfigOption(
        id = "mode",
        name = "Mode",
        category = "mode",
        currentValue = "agent",
        options = namedValues("agent" to "Agent", "plan" to "Plan", "ask" to "Ask"),
    )

    fun modelOption(current: String) = ConfigOption(
        id = "model",
        name = "Model",
        category = "model",
        currentValue = current,
        description = "Controls which model is used for responses",
        options = namedValues(
            "default" to "Auto",
            "composer-2.5" to "Composer 2.5",
            "claude-opus-4-7" to "Claude Opus 4.7",
            "gpt-5.5" to "GPT-5.5",
        ),
    )

    fun variantModelOption() = ConfigOption(
        id = "model",
        name = "Model",
        category = "model",
        currentValue = "claude-opus-4-7[thinking=true,context=300k,effort=xhigh,fast=false]",
        description = "Controls which model variant is used for responses",
        options = namedValues(
            "default[]" to "Auto",
            "composer-2.5[fast=true]" to "Composer 2.5",
            "claude-opus-4-7[thinking=true,context=300k,effort=xhigh,fast=false]" to "Claude Opus 4.7",
            "grok-4.7[context=256k,reasoning_effort=high,fast=true]" to "Grok 4.7  High Fast​​",
        ),
    )

    fun thinkingOption(on: Boolean = true) = ConfigOption(
        id = "thinking",
        name = "Thinking",
        category = "thought_level",
        currentValue = on.toString(),
        description = "Does the model use thinking to generate its response?",
        options = namedValues("false" to "Off", "true" to "On"),
    )

    fun contextOption(
        current: String = "300k",
        sizes: List<Pair<String, String>> = listOf("300k" to "300K", "1m" to "1M"),
    ) = ConfigOption(
        id = "context",
        name = "Context",
        category = "model_config",
        currentValue = current,
        description = CONTEXT_DESCRIPTION,
        options = namedValues(*sizes.toTypedArray()),
    )

    fun effortOption(id: String = "effort", name: String = "Effort", current: String = "xhigh") = ConfigOption(
        id = id,
        name = name,
        category = "thought_level",
        currentValue = current,
        description = EFFORT_DESCRIPTION,
        options = namedValues("low" to "Low", "medium" to "Medium", "high" to "High", "xhigh" to "Extra High", "max" to "Max"),
    )

    fun reasoningOption(current: String = "medium") = ConfigOption(
        id = "reasoning",
        name = "Reasoning",
        category = "thought_level",
        currentValue = current,
        description = "Reasoning effort the model uses to generate its response.",
        options = namedValues("none" to "None", "low" to "Low", "medium" to "Medium", "high" to "High", "extra-high" to "Extra High"),
    )

    fun fastOption(on: Boolean = false, description: String = FAST_DESCRIPTION) = ConfigOption(
        id = "fast",
        name = "Fast",
        category = "model_config",
        currentValue = on.toString(),
        description = description,
        options = namedValues("false" to "Off", "true" to "Fast"),
    )

    /** Claude Opus 4.7: thinking, context, effort and fast, in the agent's order. */
    fun opusOptions(
        context: String = "300k",
        effort: String = "xhigh",
        fast: Boolean = false,
        thinking: Boolean = true,
    ): List<ConfigOption> = listOf(
        modeOption(),
        modelOption("claude-opus-4-7"),
        thinkingOption(thinking),
        contextOption(context),
        effortOption(current = effort),
        fastOption(fast),
    )

    /** GPT-5.5: context, reasoning (GPT vocabulary) and fast; no thinking toggle. */
    fun gptOptions(): List<ConfigOption> = listOf(
        modeOption(),
        modelOption("gpt-5.5"),
        contextOption("272k", listOf("272k" to "272K", "1m" to "1M")),
        reasoningOption(),
        fastOption(description = "2.5x more expensive, but faster speeds."),
    )

    /** Composer 2.5: only the fast switch. */
    fun composerOptions(fast: Boolean = true): List<ConfigOption> = listOf(
        modeOption(),
        modelOption("composer-2.5"),
        fastOption(fast, description = "Significantly faster but consumes more usage"),
    )

    /** Auto: no parameters at all. */
    fun autoOptions(): List<ConfigOption> = listOf(modeOption(), modelOption("default"))

    /** Variants mode (no `_meta` flag): one opaque value per model and no parameter options. */
    fun variantsOptions(): List<ConfigOption> = listOf(modeOption(), variantModelOption())

    fun namedValues(vararg pairs: Pair<String, String>) = pairs.map { ConfigOptionValue(it.first, it.second) }
}
