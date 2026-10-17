package com.cursorj.ui.chat

import com.cursorj.acp.CursorConfigOptionFixtures as Fixtures
import com.cursorj.acp.messages.ConfigOption
import java.awt.Component
import java.awt.Container
import javax.swing.JCheckBox
import javax.swing.JComboBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InputPanelConfigOptionsTest {
    @Test
    fun `trigger shows the model name and Cursor's parameter suffix`() {
        val testCases = mapOf<String, Triple<List<ConfigOption>, String, String>>(
            "Opus 4.7 defaults" to Triple(Fixtures.opusOptions(), "Claude Opus 4.7", "300K Extra High"),
            "Opus 4.7 1M low fast no thinking" to Triple(
                Fixtures.opusOptions(context = "1m", effort = "low", fast = true, thinking = false),
                "Claude Opus 4.7",
                "1M Low Fast No Thinking",
            ),
            "GPT reasoning" to Triple(Fixtures.gptOptions(), "GPT-5.5", "272K Medium"),
            "Composer fast" to Triple(Fixtures.composerOptions(fast = true), "Composer 2.5", "Fast"),
            "Auto" to Triple(Fixtures.autoOptions(), "Auto", ""),
            "variants mode" to Triple(Fixtures.variantsOptions(), "Claude Opus 4.7", ""),
            "before a session exists" to Triple(emptyList(), "Model", ""),
        )
        for ((name, case) in testCases) {
            val (options, expectedName, expectedSuffix) = case
            val panel = InputPanel()

            panel.updateConfigOptions(options)

            assertEquals(expectedName, panel.modelPickerModelName, name)
            assertEquals(expectedSuffix, panel.modelPickerSuffix, name)
        }
    }

    @Test
    fun `trigger follows a model switch`() {
        val panel = InputPanel()
        panel.updateConfigOptions(Fixtures.opusOptions())

        panel.updateConfigOptions(Fixtures.composerOptions(fast = true))

        assertEquals("Composer 2.5", panel.modelPickerModelName)
        assertEquals("Fast", panel.modelPickerSuffix)
    }

    @Test
    fun `prompt bar has the trigger and no inline parameter controls`() {
        val panel = InputPanel()
        panel.updateConfigOptions(Fixtures.opusOptions())

        val trigger = descendants(panel.component).filterIsInstance<ModelPickerTrigger>().firstOrNull()
        assertNotNull(trigger)
        assertEquals(ModelPickerTrigger.NAME, trigger.name)
        assertTrue(trigger.isVisible)
        val combos = descendants(panel.component).filterIsInstance<JComboBox<*>>().toList()
        assertEquals(1, combos.size, "only the mode combo remains in the bar")
        assertTrue(descendants(panel.component).none { it is JCheckBox }, "no MAX or parameter checkboxes")
    }

    private fun descendants(root: Component): Sequence<Component> = sequence {
        yield(root)
        if (root is Container) {
            for (child in root.components) {
                yieldAll(descendants(child))
            }
        }
    }
}
