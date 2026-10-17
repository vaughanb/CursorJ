package com.cursorj.ui.chat

import com.cursorj.acp.CursorConfigOptionFixtures as Fixtures
import com.cursorj.acp.PickerSection
import com.cursorj.acp.messages.ConfigOption
import com.intellij.openapi.actionSystem.KeepPopupOnPerform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelPickerPopupTest {
    private val changes = mutableListOf<Pair<String, String>>()
    private val maxToggles = mutableListOf<Boolean>()

    @Test
    fun `Opus menu is the Model row then Effort, Options and Context sections in agent value order`() {
        val menu = popup(Fixtures.opusOptions()).buildMenu()

        assertEquals("Model: Claude Opus 4.7", menu.modelRow.text)
        assertNull(menu.description)
        assertEquals(listOf("Effort", null, "Context"), menu.sections.map { it.title })
        assertEquals(
            listOf(PickerSection.Kind.RADIO, PickerSection.Kind.SWITCHES, PickerSection.Kind.RADIO),
            menu.sections.map { it.kind },
        )

        val effort = menu.sections[0]
        assertEquals(listOf("Low", "Medium", "High", "Extra High", "Max"), effort.items.map { it.label })
        assertEquals("xhigh", effort.items.single { it.selected }.value)
        assertEquals(Fixtures.EFFORT_DESCRIPTION, effort.items.first().description)

        val switches = menu.sections[1]
        assertEquals(listOf("Thinking", "Fast"), switches.items.map { it.label })
        assertEquals(listOf(true, false), switches.items.map { it.selected })
        assertEquals(Fixtures.FAST_DESCRIPTION, switches.items[1].description)

        val context = menu.sections[2]
        assertEquals(listOf("300K", "1M"), context.items.map { it.label })
        assertEquals("300k", context.items.single { it.selected }.value)
    }

    @Test
    fun `Model submenu puts Auto first, hides MAX Mode when a context option exists and suffixes the current row`() {
        val row = popup(Fixtures.opusOptions()).buildMenu().modelRow

        val models = row.entries.filterIsInstance<ModelEntry.Model>()
        assertEquals("default", models.first().value)
        assertTrue(row.entries.none { it is ModelEntry.MaxMode }, "MAX Mode is controlled by the 1M context option")
        assertEquals(ModelEntry.Divider, row.entries[1])
        assertEquals(
            listOf("Auto", "Composer 2.5", "Claude Opus 4.7  300K Extra High", "GPT-5.5"),
            models.map { it.label },
        )
        assertEquals(listOf(false, false, true, false), models.map { it.selected })
    }

    @Test
    fun `Composer menu offers MAX Mode in the Model submenu and only the Options section`() {
        val menu = popup(Fixtures.composerOptions(fast = true), maxMode = true).buildMenu()

        val entries = menu.modelRow.entries
        assertEquals("default", (entries[0] as ModelEntry.Model).value)
        assertEquals(ModelEntry.MaxMode(enabled = true), entries[1])
        assertEquals(ModelEntry.Divider, entries[2])
        assertEquals(listOf<String?>(null), menu.sections.map { it.title })
        assertEquals(PickerSection.Kind.SWITCHES, menu.sections.single().kind)
        assertEquals(listOf("Fast"), menu.sections.single().items.map { it.label })
        assertTrue(menu.sections.single().items.single().selected)
    }

    @Test
    fun `GPT menu titles the thought level section Reasoning with the GPT vocabulary`() {
        val menu = popup(Fixtures.gptOptions()).buildMenu()

        assertEquals(listOf("Reasoning", null, "Context"), menu.sections.map { it.title })
        assertEquals(listOf("None", "Low", "Medium", "High", "Extra High"), menu.sections[0].items.map { it.label })
        assertEquals(listOf("272K", "1M"), menu.sections[2].items.map { it.label })
    }

    @Test
    fun `models without parameters show the Model row and a muted description only`() {
        val testCases = mapOf(
            "Auto" to Pair(Fixtures.autoOptions(), "Cursor picks the model."),
            "variants mode" to Pair(Fixtures.variantsOptions(), "No adjustable parameters."),
        )
        for ((name, case) in testCases) {
            val (options, expectedDescription) = case

            val menu = popup(options).buildMenu()

            assertTrue(menu.sections.isEmpty(), name)
            assertEquals(expectedDescription, menu.description, name)
        }
    }

    @Test
    fun `variants mode lists the variant display names without zero width spaces and offers MAX Mode`() {
        val row = popup(Fixtures.variantsOptions()).buildMenu().modelRow

        val models = row.entries.filterIsInstance<ModelEntry.Model>()
        assertEquals("Model: Claude Opus 4.7", row.text)
        assertEquals(listOf("Auto", "Composer 2.5", "Claude Opus 4.7", "Grok 4.7  High Fast"), models.map { it.label })
        assertEquals("default[]", models.first().value)
        assertEquals(listOf(false, false, true, false), models.map { it.selected })
        assertEquals(ModelEntry.MaxMode(enabled = false), row.entries[1])
    }

    @Test
    fun `before a session exists the Model row offers only MAX Mode`() {
        val row = popup(emptyList(), maxMode = true).buildMenu().modelRow

        assertEquals("Model", row.text)
        assertEquals(listOf<ModelEntry>(ModelEntry.MaxMode(enabled = true)), row.entries)
    }

    @Test
    fun `selecting values reports config changes and re-selecting the current value sends nothing`() {
        val popup = popup(Fixtures.opusOptions())

        popup.selectValue("effort", "xhigh")
        popup.selectModel("claude-opus-4-7")
        assertTrue(changes.isEmpty(), "re-selecting the current effort or model sends nothing")

        popup.selectValue("effort", "medium")
        popup.toggleSwitch("fast", true)
        popup.toggleSwitch("thinking", false)
        popup.selectValue("context", "1m")
        popup.selectModel("composer-2.5")

        assertEquals(
            listOf("effort" to "medium", "fast" to "true", "thinking" to "false", "context" to "1m", "model" to "composer-2.5"),
            changes,
        )
        assertTrue(maxToggles.isEmpty())
    }

    @Test
    fun `selection state is read from the live options`() {
        val popup = popup(Fixtures.opusOptions())

        assertTrue(popup.isModelSelected("claude-opus-4-7"))
        assertTrue(popup.isValueSelected("effort", "xhigh"))
        assertTrue(popup.isSwitchOn("thinking"))
        assertTrue(!popup.isSwitchOn("fast"))
        assertTrue(!popup.isValueSelected("context", "1m"))
    }

    @Test
    fun `MAX Mode toggle reports through the max mode callback only`() {
        val popup = popup(Fixtures.composerOptions())

        popup.toggleMaxMode(true)

        assertEquals(listOf(true), maxToggles)
        assertTrue(changes.isEmpty())
    }

    @Test
    fun `radio values close the popup and switches keep it open`() {
        assertEquals(KeepPopupOnPerform.Never, ModelPickerPopup.keepPopupOnPerform(PickerSection.Kind.RADIO))
        assertEquals(KeepPopupOnPerform.Always, ModelPickerPopup.keepPopupOnPerform(PickerSection.Kind.SWITCHES))
    }

    private fun popup(options: List<ConfigOption>, maxMode: Boolean = false, enabled: Boolean = true) = ModelPickerPopup(
        options = { options },
        maxModeEnabled = { maxMode },
        itemsEnabled = { enabled },
        onConfigOptionChanged = { configId, value -> changes += configId to value },
        onMaxModeToggled = { maxToggles += it },
    )
}
