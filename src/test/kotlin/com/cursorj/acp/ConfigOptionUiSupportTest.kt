package com.cursorj.acp

import com.cursorj.acp.CursorConfigOptionFixtures as Fixtures
import com.cursorj.acp.messages.ConfigOption
import com.cursorj.acp.messages.ConfigOptionValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigOptionUiSupportTest {

    @Test
    fun modelDetection_byIdAndCategory() {
        val byCategory = ConfigOption(id = "x", category = "model")
        val byId = ConfigOption(id = "model")
        val neither = ConfigOption(id = "other", category = "other")

        assertTrue(ConfigOptionUiSupport.isModelSelector(byCategory))
        assertTrue(ConfigOptionUiSupport.isModelSelector(byId))
        assertFalse(ConfigOptionUiSupport.isModelSelector(neither))
        assertTrue(ConfigOptionUiSupport.isModelConfigId("model"))
        assertFalse(ConfigOptionUiSupport.isModelConfigId("other"))
    }

    @Test
    fun modeDetection_byIdAndCategory() {
        assertTrue(ConfigOptionUiSupport.isModeSelector(ConfigOption(id = "mode")))
        assertTrue(ConfigOptionUiSupport.isModeSelector(ConfigOption(id = "x", category = "mode")))
        assertFalse(ConfigOptionUiSupport.isModeSelector(ConfigOption(id = "model", category = "model")))
    }

    @Test
    fun booleanToggle_detectsToggleType() {
        val t = ConfigOption(id = "reasoning_effort", type = "toggle", currentValue = "true")
        assertTrue(ConfigOptionUiSupport.isBooleanToggle(t))
        assertTrue(ConfigOptionUiSupport.isToggleChecked(t))
    }

    @Test
    fun booleanToggle_twoOptionSelect() {
        val t = ConfigOption(
            id = "x",
            type = "select",
            currentValue = "on",
            options = listOf(
                ConfigOptionValue("off"),
                ConfigOptionValue("on"),
            ),
        )
        assertTrue(ConfigOptionUiSupport.isBooleanToggle(t))
        assertEquals(Pair("off", "on"), ConfigOptionUiSupport.toggleOffOnValues(t))
    }

    @Test
    fun optionsForInputBar_skipsMode() {
        val list = listOf(
            ConfigOption(id = "mode", category = "mode"),
            ConfigOption(id = "model", category = "model"),
            ConfigOption(id = "reasoning_effort", type = "toggle"),
        )
        val filtered = ConfigOptionUiSupport.optionsForInputBar(list)
        assertEquals(2, filtered.size)
        assertEquals("model", filtered[0].id)
        assertEquals("reasoning_effort", filtered[1].id)
    }

    @Test
    fun isGenericSelect_excludesToggles() {
        val t = ConfigOption(
            id = "x",
            type = "select",
            options = listOf(ConfigOptionValue("0"), ConfigOptionValue("1")),
        )
        assertTrue(ConfigOptionUiSupport.isBooleanToggle(t))
        assertFalse(ConfigOptionUiSupport.isGenericSelect(t))
    }

    @Test
    fun isGenericSelect_identifiesNonModelNonModeSelect() {
        val select = ConfigOption(
            id = "thought_level",
            type = "select",
            options = listOf(ConfigOptionValue("low"), ConfigOptionValue("medium"), ConfigOptionValue("high")),
        )
        assertTrue(ConfigOptionUiSupport.isGenericSelect(select))
    }

    @Test
    fun toggleChecked_fallsBackToOnValueWhenCurrentIsCustomToken() {
        val t = ConfigOption(
            id = "x",
            type = "select",
            currentValue = "enabled",
            options = listOf(
                ConfigOptionValue("disabled"),
                ConfigOptionValue("enabled"),
            ),
        )
        assertTrue(ConfigOptionUiSupport.isToggleChecked(t))
    }

    @Test
    fun truthyParsing_handlesKnownValues() {
        assertTrue(ConfigOptionUiSupport.isTruthy("on"))
        assertTrue(ConfigOptionUiSupport.isTruthy("YES"))
        assertFalse(ConfigOptionUiSupport.isTruthy("no"))
    }

    @Test
    fun parameterClassification_followsCursorCategoriesNotIds() {
        data class Expected(val thoughtLevel: Boolean, val modelConfig: Boolean, val effortSelector: Boolean)
        val testCases = mapOf(
            "thinking toggle shares thought_level but is not the effort selector" to
                Pair(thinkingOption(), Expected(thoughtLevel = true, modelConfig = false, effortSelector = false)),
            "effort select (Claude Opus)" to
                Pair(effortOption("effort", "xhigh", "low", "medium", "high", "xhigh", "max"), Expected(true, false, true)),
            "reasoning select with GPT vocabulary" to
                Pair(effortOption("reasoning", "medium", "none", "low", "medium", "high", "extra-high"), Expected(true, false, true)),
            "reasoning_effort by id when no category is sent" to
                Pair(
                    ConfigOption(id = "reasoning_effort", currentValue = "high", options = values("low", "medium", "high")),
                    Expected(true, false, true),
                ),
            "context select" to Pair(contextOption(), Expected(false, true, false)),
            "fast toggle" to Pair(fastOption(), Expected(false, true, false)),
            "model selector" to Pair(ConfigOption(id = "model", category = "model"), Expected(false, false, false)),
            "mode selector" to Pair(ConfigOption(id = "mode", category = "mode"), Expected(false, false, false)),
        )
        for ((name, case) in testCases) {
            val (option, expected) = case
            assertEquals(expected.thoughtLevel, ConfigOptionUiSupport.isThoughtLevel(option), "$name: isThoughtLevel")
            assertEquals(expected.modelConfig, ConfigOptionUiSupport.isModelConfig(option), "$name: isModelConfig")
            assertEquals(expected.effortSelector, ConfigOptionUiSupport.isEffortSelector(option), "$name: isEffortSelector")
        }
    }

    @Test
    fun labelFor_prefersAgentNameThenTitleCasedId() {
        val testCases = mapOf(
            "agent name wins" to Pair(ConfigOption(id = "effort", name = "Effort level"), "Effort level"),
            "blank name falls back to the id" to Pair(ConfigOption(id = "reasoning_effort", name = "  "), "Reasoning Effort"),
            "single word id" to Pair(ConfigOption(id = "context"), "Context"),
        )
        for ((name, case) in testCases) {
            val (option, expected) = case
            assertEquals(expected, ConfigOptionUiSupport.labelFor(option), name)
        }
    }

    @Test
    fun valueLabelFor_prefersValueNameThenRawValue() {
        val option = ConfigOption(
            id = "effort",
            options = listOf(ConfigOptionValue("high", "High"), ConfigOptionValue("xhigh")),
        )
        assertEquals("High", ConfigOptionUiSupport.valueLabelFor(option, "high"))
        assertEquals("xhigh", ConfigOptionUiSupport.valueLabelFor(option, "xhigh"))
        assertEquals("unknown", ConfigOptionUiSupport.valueLabelFor(option, "unknown"))
    }

    @Test
    fun isContextOption_matchesSizeValuedSelectsOnly() {
        val testCases = mapOf(
            "300k/1m context" to Pair(Fixtures.contextOption(), true),
            "272k/1m context" to Pair(Fixtures.contextOption("272k", listOf("272k" to "272K", "1m" to "1M")), true),
            "fast toggle" to Pair(Fixtures.fastOption(), false),
            "effort levels" to Pair(Fixtures.effortOption(), false),
            "model selector with size-like values" to
                Pair(ConfigOption(id = "model", category = "model", options = values("300k")), false),
        )
        for ((name, case) in testCases) {
            val (option, expected) = case
            assertEquals(expected, ConfigOptionUiSupport.isContextOption(option), name)
        }
    }

    @Test
    fun isAutoModelValue_matchesDefaultInBothPickerModes() {
        assertTrue(ConfigOptionUiSupport.isAutoModelValue("default"))
        assertTrue(ConfigOptionUiSupport.isAutoModelValue("default[]"))
        assertFalse(ConfigOptionUiSupport.isAutoModelValue("claude-opus-4-7"))
        assertFalse(ConfigOptionUiSupport.isAutoModelValue("composer-2.5[fast=true]"))
    }

    @Test
    fun variantSuffix_followsCursorShortNameConvention() {
        val unknownParameter = ConfigOption(
            id = "verbosity",
            name = "Verbosity",
            category = "model_config",
            currentValue = "terse",
            options = listOf(ConfigOptionValue("terse", "Terse"), ConfigOptionValue("full", "Full")),
        )
        val testCases = mapOf(
            "Opus 4.7 defaults" to Pair(Fixtures.opusOptions(), "300K Extra High"),
            "Opus 4.7 1M low fast no thinking" to
                Pair(Fixtures.opusOptions(context = "1m", effort = "low", fast = true, thinking = false), "1M Low Fast No Thinking"),
            "GPT reasoning" to Pair(Fixtures.gptOptions(), "272K Medium"),
            "Composer fast on" to Pair(Fixtures.composerOptions(fast = true), "Fast"),
            "Composer fast off" to Pair(Fixtures.composerOptions(fast = false), ""),
            "Auto has no parameters" to Pair(Fixtures.autoOptions(), ""),
            "variants mode has no parameters" to Pair(Fixtures.variantsOptions(), ""),
            "unknown parameter is appended as name: value" to
                Pair(Fixtures.composerOptions(fast = true) + unknownParameter, "Fast Verbosity: Terse"),
        )
        for ((name, case) in testCases) {
            val (options, expected) = case
            assertEquals(expected, ConfigOptionUiSupport.variantSuffix(options), name)
        }
    }

    @Test
    fun currentModelLabel_andModelSummary_useTheModelValueName() {
        assertEquals("Claude Opus 4.7", ConfigOptionUiSupport.currentModelLabel(Fixtures.opusOptions()))
        assertEquals("Claude Opus 4.7 300K Extra High", ConfigOptionUiSupport.modelSummary(Fixtures.opusOptions()))
        assertEquals("Auto", ConfigOptionUiSupport.modelSummary(Fixtures.autoOptions()))
        assertEquals("Claude Opus 4.7", ConfigOptionUiSupport.modelSummary(Fixtures.variantsOptions()))
        assertNull(ConfigOptionUiSupport.modelSummary(listOf(Fixtures.modeOption())))
    }

    @Test
    fun pickerSections_orderThoughtLevelThenOptionsThenOtherSelects() {
        val testCases = mapOf(
            "Opus" to Pair(Fixtures.opusOptions(), listOf("Effort", null, "Context")),
            "GPT" to Pair(Fixtures.gptOptions(), listOf("Reasoning", null, "Context")),
            "Composer" to Pair(Fixtures.composerOptions(), listOf(null)),
            "Auto" to Pair(Fixtures.autoOptions(), emptyList()),
            "variants mode" to Pair(Fixtures.variantsOptions(), emptyList()),
        )
        for ((name, case) in testCases) {
            val (options, expectedTitles) = case
            assertEquals(expectedTitles, ConfigOptionUiSupport.pickerSections(options).map { it.title }, name)
        }
    }

    @Test
    fun pickerSections_carryLabelsSelectionAndDescriptions() {
        val sections = ConfigOptionUiSupport.pickerSections(Fixtures.opusOptions())

        val effort = sections[0]
        assertEquals(PickerSection.Kind.RADIO, effort.kind)
        assertEquals(Fixtures.EFFORT_DESCRIPTION, effort.description)
        assertEquals(listOf("Low", "Medium", "High", "Extra High", "Max"), effort.items.map { it.label })
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), effort.items.map { it.value })
        assertEquals("xhigh", effort.items.single { it.selected }.value)

        val switches = sections[1]
        assertEquals(PickerSection.Kind.SWITCHES, switches.kind)
        assertEquals(listOf("thinking", "fast"), switches.items.map { it.configId })
        assertEquals(listOf("Thinking", "Fast"), switches.items.map { it.label })
        assertEquals(listOf(true, false), switches.items.map { it.selected })
        assertEquals(listOf("true", "true"), switches.items.map { it.value })
        assertEquals(Fixtures.FAST_DESCRIPTION, switches.items[1].description)

        val context = sections[2]
        assertEquals(PickerSection.Kind.RADIO, context.kind)
        assertEquals(listOf("300K", "1M"), context.items.map { it.label })
        assertEquals("300k", context.items.single { it.selected }.value)
    }

    @Test
    fun nextEffortSelection_stepsThroughAgentValuesAndWrapsAround() {
        val testCases = mapOf(
            "Opus xhigh -> max" to Pair(Fixtures.opusOptions(effort = "xhigh"), "effort" to "max"),
            "Opus max wraps to low" to Pair(Fixtures.opusOptions(effort = "max"), "effort" to "low"),
            "GPT medium -> high" to Pair(Fixtures.gptOptions(), "reasoning" to "high"),
            "unknown current value starts at the first" to Pair(Fixtures.opusOptions(effort = "bogus"), "effort" to "low"),
            "Composer has no effort" to Pair(Fixtures.composerOptions(), null),
            "thinking toggle alone is not an effort selector" to
                Pair(listOf(Fixtures.modeOption(), Fixtures.modelOption("claude-opus-4-7"), Fixtures.thinkingOption()), null),
        )
        for ((name, case) in testCases) {
            val (options, expected) = case
            assertEquals(expected, ConfigOptionUiSupport.nextEffortSelection(options), name)
        }
    }

    private fun thinkingOption() = ConfigOption(
        id = "thinking",
        category = "thought_level",
        currentValue = "true",
        options = values("false", "true"),
    )

    private fun effortOption(id: String, current: String?, vararg levels: String) = ConfigOption(
        id = id,
        category = "thought_level",
        currentValue = current,
        options = values(*levels),
    )

    private fun contextOption() = ConfigOption(
        id = "context",
        category = "model_config",
        currentValue = "300k",
        options = values("300k", "1m"),
    )

    private fun fastOption() = ConfigOption(
        id = "fast",
        category = "model_config",
        currentValue = "false",
        options = values("false", "true"),
    )

    private fun values(vararg raw: String) = raw.map { ConfigOptionValue(it) }
}
