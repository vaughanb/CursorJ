package com.cursorj.acp

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
    fun thoughtLevelSummary_describesTheEffortSelectorOnly() {
        val testCases = mapOf(
            "thinking toggle alone has no summary" to Pair(listOf(thinkingOption()), null),
            "model config options alone have no summary" to Pair(listOf(contextOption(), fastOption()), null),
            "effort without a current value has no summary" to
                Pair(listOf(effortOption("effort", null, "low", "high")), null),
            "effort uses the value name" to Pair(
                listOf(
                    thinkingOption(),
                    ConfigOption(
                        id = "effort",
                        category = "thought_level",
                        currentValue = "high",
                        options = listOf(ConfigOptionValue("low", "Low"), ConfigOptionValue("high", "High")),
                    ),
                ),
                "Effort: High",
            ),
            "reasoning falls back to the raw value" to
                Pair(listOf(effortOption("reasoning", "extra-high", "low", "extra-high")), "Reasoning: extra-high"),
        )
        for ((name, case) in testCases) {
            val (options, expected) = case
            if (expected == null) {
                assertNull(ConfigOptionUiSupport.thoughtLevelSummary(options), name)
            } else {
                assertEquals(expected, ConfigOptionUiSupport.thoughtLevelSummary(options), name)
            }
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
