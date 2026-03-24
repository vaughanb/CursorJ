package com.cursorj.acp

import com.cursorj.acp.messages.ConfigOption
import com.cursorj.acp.messages.ConfigOptionValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
