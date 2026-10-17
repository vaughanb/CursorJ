package com.cursorj.ui.chat

import com.cursorj.CursorJBundle
import com.cursorj.acp.messages.ConfigOption
import com.cursorj.acp.messages.ConfigOptionValue
import java.awt.Component
import java.awt.Container
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InputPanelConfigOptionsTest {
    @Test
    fun `renders the Opus parameter set as labeled combos and checkboxes next to the model combo`() {
        val panel = InputPanel()
        panel.updateConfigOptions(opusOptions())

        val modelCombo = findByName<JComboBox<*>>(panel.component, "config:model")
        assertNotNull(modelCombo)
        assertTrue(modelCombo.isVisible)
        assertEquals("Claude Opus 4.7", modelCombo.selectedItem)

        val thinking = findByName<JCheckBox>(panel.component, "config:thinking")
        assertNotNull(thinking)
        assertEquals("Thinking", thinking.text)
        assertTrue(thinking.isSelected)

        val fast = findByName<JCheckBox>(panel.component, "config:fast")
        assertNotNull(fast)
        assertEquals("Fast", fast.text)
        assertFalse(fast.isSelected)
        assertEquals(FAST_DESCRIPTION, fast.toolTipText)

        val effort = findByName<JComboBox<*>>(panel.component, "config:effort")
        assertNotNull(effort)
        assertEquals("xhigh", effort.selectedItem)
        assertEquals(EFFORT_DESCRIPTION, effort.toolTipText)

        val context = findByName<JComboBox<*>>(panel.component, "config:context")
        assertNotNull(context)
        assertEquals("300k", context.selectedItem)

        val labels = descendants(panel.component).filterIsInstance<JLabel>().map { it.text }.toList()
        assertTrue("Effort:" in labels, "expected an Effort label, got $labels")
        assertTrue("Context:" in labels, "expected a Context label, got $labels")
    }

    @Test
    fun `switching to Composer leaves only the fast toggle`() {
        val panel = InputPanel()
        panel.updateConfigOptions(opusOptions())

        panel.updateConfigOptions(composerOptions())

        assertNull(findByName<JComboBox<*>>(panel.component, "config:effort"))
        assertNull(findByName<JComboBox<*>>(panel.component, "config:context"))
        assertNull(findByName<JCheckBox>(panel.component, "config:thinking"))
        val fast = findByName<JCheckBox>(panel.component, "config:fast")
        assertNotNull(fast)
        assertTrue(fast.isSelected)
    }

    @Test
    fun `user changes fire onConfigOptionChanged but programmatic updates do not`() {
        val panel = InputPanel()
        val events = mutableListOf<Pair<String, String>>()
        panel.onConfigOptionChanged = { configId, value -> events += configId to value }

        panel.updateConfigOptions(opusOptions())
        panel.updateConfigOptions(opusOptions().map { if (it.id == "effort") it.copy(currentValue = "low") else it })
        assertTrue(events.isEmpty(), "programmatic updates must not fire, got $events")

        val effort = findByName<JComboBox<*>>(panel.component, "config:effort")
        assertNotNull(effort)
        effort.selectedIndex = 1
        val fast = findByName<JCheckBox>(panel.component, "config:fast")
        assertNotNull(fast)
        fast.doClick()

        assertEquals(listOf("effort" to "medium", "fast" to "true"), events)
    }

    @Test
    fun `MAX tooltip mentions the 1M context coupling only when parameter options are present`() {
        val testCases = mapOf(
            "parameterized picker" to Pair(opusOptions(), CursorJBundle.message("chat.maxmode.tooltip.parameterized")),
            "variants mode" to Pair(variantsOptions(), CursorJBundle.message("chat.maxmode.tooltip")),
        )
        for ((name, case) in testCases) {
            val (options, expectedTooltip) = case
            val panel = InputPanel()
            panel.updateConfigOptions(options)

            val maxToggle = findByName<JCheckBox>(panel.component, InputPanel.MAX_MODE_TOGGLE_NAME)
            assertNotNull(maxToggle, name)
            assertEquals(expectedTooltip, maxToggle.toolTipText, name)
        }
    }

    private inline fun <reified T : Component> findByName(root: Component, name: String): T? =
        descendants(root).filterIsInstance<T>().firstOrNull { it.name == name }

    private fun descendants(root: Component): Sequence<Component> = sequence {
        yield(root)
        if (root is Container) {
            for (child in root.components) {
                yieldAll(descendants(child))
            }
        }
    }

    /** Claude Opus 4.7 in parameterized-picker mode (ids, categories and values as observed on the wire). */
    private fun opusOptions(): List<ConfigOption> = listOf(
        modeOption(),
        modelOption("claude-opus-4-7"),
        ConfigOption(
            id = "thinking",
            name = "Thinking",
            category = "thought_level",
            currentValue = "true",
            options = booleanValues(),
        ),
        ConfigOption(
            id = "context",
            description = "Context size the model has available.",
            category = "model_config",
            currentValue = "300k",
            options = listOf(ConfigOptionValue("300k"), ConfigOptionValue("1m")),
        ),
        ConfigOption(
            id = "effort",
            description = EFFORT_DESCRIPTION,
            category = "thought_level",
            currentValue = "xhigh",
            options = listOf("low", "medium", "high", "xhigh", "max").map { ConfigOptionValue(it) },
        ),
        ConfigOption(
            id = "fast",
            description = FAST_DESCRIPTION,
            category = "model_config",
            currentValue = "false",
            options = booleanValues(),
        ),
    )

    private fun composerOptions(): List<ConfigOption> = listOf(
        modeOption(),
        modelOption("composer-2.5"),
        ConfigOption(id = "fast", category = "model_config", currentValue = "true", options = booleanValues()),
    )

    /** Variants mode: one opaque value per model and no parameter options. */
    private fun variantsOptions(): List<ConfigOption> = listOf(
        modeOption(),
        ConfigOption(
            id = "model",
            category = "model",
            currentValue = "claude-opus-4-7[thinking=true,context=300k,effort=xhigh,fast=false]",
            options = listOf(
                ConfigOptionValue("claude-opus-4-7[thinking=true,context=300k,effort=xhigh,fast=false]", "Claude Opus 4.7"),
                ConfigOptionValue("composer-2.5[fast=true]", "Composer 2.5 Fast"),
            ),
        ),
    )

    private fun modeOption() = ConfigOption(
        id = "mode",
        category = "mode",
        currentValue = "agent",
        options = listOf("agent", "plan", "ask").map { ConfigOptionValue(it) },
    )

    private fun modelOption(current: String) = ConfigOption(
        id = "model",
        category = "model",
        currentValue = current,
        options = listOf(
            ConfigOptionValue("claude-opus-4-7", "Claude Opus 4.7"),
            ConfigOptionValue("composer-2.5", "Composer 2.5"),
        ),
    )

    private fun booleanValues() = listOf(ConfigOptionValue("false"), ConfigOptionValue("true"))

    private companion object {
        const val EFFORT_DESCRIPTION = "Effort the model uses to generate its response."
        const val FAST_DESCRIPTION = "6x more expensive, but significantly faster speeds."
    }
}
