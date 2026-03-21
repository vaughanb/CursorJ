package com.cursorj.acp

import com.cursorj.acp.messages.ConfigOption
import com.cursorj.acp.messages.ConfigOptionValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigOptionUiSupportTest {

    @Test
    fun merge_appendsSyntheticModelWhenAgentOmitsModel() {
        val agent = listOf(
            ConfigOption(
                id = "max_mode",
                name = "MAX Mode",
                type = "toggle",
                currentValue = "false",
            ),
        )
        val synthetic = listOf(
            ConfigOption(
                id = "model",
                category = "model",
                currentValue = "m1",
                options = listOf(ConfigOptionValue("m1", "M1")),
            ),
        )
        val merged = ConfigOptionUiSupport.mergeWithSyntheticModel(agent, synthetic)
        assertEquals(2, merged.size)
        assertEquals("max_mode", merged[0].id)
        assertEquals("model", merged[1].id)
    }

    @Test
    fun merge_keepsAgentListWhenModelPresent() {
        val agent = listOf(
            ConfigOption(id = "model", category = "model", options = listOf(ConfigOptionValue("a"))),
        )
        val synthetic = listOf(ConfigOption(id = "model", category = "model", options = listOf(ConfigOptionValue("b"))))
        val merged = ConfigOptionUiSupport.mergeWithSyntheticModel(agent, synthetic)
        assertEquals(1, merged.size)
        assertEquals("a", merged[0].options.first().value)
    }

    @Test
    fun merge_modelByIdWithoutCategory() {
        val agent = listOf(ConfigOption(id = "model", options = listOf(ConfigOptionValue("x"))))
        assertEquals(1, ConfigOptionUiSupport.mergeWithSyntheticModel(agent, emptyList()).size)
    }

    @Test
    fun booleanToggle_detectsToggleType() {
        val t = ConfigOption(id = "max_mode", type = "toggle", currentValue = "true")
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
            ConfigOption(id = "max_mode", type = "toggle"),
        )
        assertEquals(1, ConfigOptionUiSupport.optionsForInputBar(list).size)
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
    fun maxModelSelection_detectsByDisplayNameWhenValueLacksMaxToken() {
        val model = ConfigOption(
            id = "model",
            category = "model",
            options = listOf(
                ConfigOptionValue(
                    value = "claude-opus-4-6-thinking",
                    name = "Opus 4.6 Max Thinking",
                ),
            ),
        )
        assertTrue(
            ConfigOptionUiSupport.isLikelyMaxModelSelection(
                modelOption = model,
                selectedValue = "claude-opus-4-6-thinking",
            ),
        )
    }

    @Test
    fun maxModeOption_matchesCompactId() {
        val opt = ConfigOption(
            id = "maxMode",
            type = "toggle",
            currentValue = "false",
        )
        assertTrue(ConfigOptionUiSupport.isLikelyMaxModeOption(opt))
    }

    @Test
    fun maxModelSelection_notMaxWhenDisplayNameLacksMaxToken() {
        val model = ConfigOption(
            id = "model",
            category = "model",
            options = listOf(
                ConfigOptionValue(
                    value = "claude-sonnet",
                    name = "Claude Sonnet",
                ),
            ),
        )
        assertFalse(
            ConfigOptionUiSupport.isLikelyMaxModelSelection(
                modelOption = model,
                selectedValue = "claude-sonnet",
            ),
        )
    }

    @Test
    fun maxModelSelection_detectsByValueWhenIdContainsMax() {
        val model = ConfigOption(
            id = "model",
            category = "model",
            options = listOf(
                ConfigOptionValue(
                    value = "opus-max-thinking",
                    name = "Opus Thinking",
                ),
            ),
        )
        assertTrue(
            ConfigOptionUiSupport.isLikelyMaxModelSelection(
                modelOption = model,
                selectedValue = "opus-max-thinking",
            ),
        )
    }
}
