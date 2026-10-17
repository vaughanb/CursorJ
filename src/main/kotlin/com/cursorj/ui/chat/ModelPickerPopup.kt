package com.cursorj.ui.chat

import com.cursorj.CursorJBundle
import com.cursorj.acp.ConfigOptionUiSupport
import com.cursorj.acp.PickerItem
import com.cursorj.acp.PickerSection
import com.cursorj.acp.messages.ConfigOption
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeepPopupOnPerform
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import javax.swing.JComponent

/**
 * Cursor-style model picker (after Cursor IDE 3.25.9): a `Model` submenu row (Auto first, MAX Mode when the
 * agent exposes no context option, then the models with type-to-filter), followed by the current model's
 * parameters as titled sections in Cursor's order (thought level, Options for the boolean switches, then the
 * remaining selects). Everything comes from the session's ACP config options; nothing is hard-coded.
 *
 * [buildMenu] and the selection methods are plain Kotlin so they are unit-testable; [show] converts the menu
 * into IntelliJ actions, which need a running IDE. See `docs/plans/2026-10-17-model-picker-ui.md`.
 */
internal class ModelPickerPopup(
    private val options: () -> List<ConfigOption>,
    private val maxModeEnabled: () -> Boolean,
    private val itemsEnabled: () -> Boolean,
    private val onConfigOptionChanged: (configId: String, value: String) -> Unit,
    private val onMaxModeToggled: (Boolean) -> Unit,
) {
    fun show(anchor: JComponent) {
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            null,
            toActionGroup(buildMenu()),
            DataManager.getInstance().getDataContext(anchor),
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true,
        )
        popup.showUnderneathOf(anchor)
    }

    /** The menu's content from the current options; [description] replaces the sections when there are none. */
    internal fun buildMenu(): PickerMenu {
        val current = options()
        val sections = ConfigOptionUiSupport.pickerSections(current)
        return PickerMenu(
            modelRow = buildModelRow(current),
            sections = sections,
            description = if (sections.isEmpty()) modelDescription(current) else null,
        )
    }

    /** Switches the model; re-selecting the current one sends nothing. */
    fun selectModel(value: String) {
        val model = options().firstOrNull { ConfigOptionUiSupport.isModelSelector(it) } ?: return
        if (!isModelSelected(value)) onConfigOptionChanged(model.id, value)
    }

    /** Sets a select parameter (radio semantics); re-selecting the current value sends nothing. */
    fun selectValue(configId: String, value: String) {
        if (!isValueSelected(configId, value)) onConfigOptionChanged(configId, value)
    }

    /** Sets a boolean parameter using the agent's own on/off spelling (`true`/`false`, `on`/`off`, ...). */
    fun toggleSwitch(configId: String, state: Boolean) {
        val fresh = options().firstOrNull { it.id == configId } ?: return
        val (off, on) = ConfigOptionUiSupport.toggleOffOnValues(fresh)
        onConfigOptionChanged(configId, if (state) on else off)
    }

    /** Cursor CLI max mode (cli-config.json + reconnect); offered only when no context option controls it. */
    fun toggleMaxMode(state: Boolean) = onMaxModeToggled(state)

    internal fun isModelSelected(value: String): Boolean =
        options().firstOrNull { ConfigOptionUiSupport.isModelSelector(it) }?.currentValue?.trim() == value

    internal fun isValueSelected(configId: String, value: String): Boolean =
        options().firstOrNull { it.id == configId }?.currentValue?.trim() == value

    internal fun isSwitchOn(configId: String): Boolean =
        options().firstOrNull { it.id == configId }?.let(ConfigOptionUiSupport::isToggleChecked) ?: false

    private fun buildModelRow(current: List<ConfigOption>): ModelRow {
        val model = current.firstOrNull { ConfigOptionUiSupport.isModelSelector(it) }
        val currentLabel = ConfigOptionUiSupport.currentModelLabel(current)
        val text = if (currentLabel == null) {
            CursorJBundle.message("chat.modelPicker.model")
        } else {
            CursorJBundle.message("chat.modelPicker.modelRow", currentLabel)
        }
        val suffix = ConfigOptionUiSupport.variantSuffix(current)
        val values = model?.options.orEmpty()
        val (auto, named) = values.partition { ConfigOptionUiSupport.isAutoModelValue(it.value) }

        val entries = mutableListOf<ModelEntry>()
        if (model != null) {
            auto.forEach { entries += modelEntry(model, it.value, suffix) }
        }
        if (current.none { ConfigOptionUiSupport.isContextOption(it) }) {
            entries += ModelEntry.MaxMode(maxModeEnabled())
        }
        if (model != null && named.isNotEmpty()) {
            entries += ModelEntry.Divider
            named.forEach { entries += modelEntry(model, it.value, suffix) }
        }
        return ModelRow(text, entries)
    }

    /** The current model's row carries Cursor's parameter suffix (`Claude Opus 4.7  300K Extra High`). */
    private fun modelEntry(model: ConfigOption, value: String, suffix: String): ModelEntry.Model {
        val label = ConfigOptionUiSupport.valueLabelFor(model, value)
        val selected = model.currentValue?.trim() == value
        return ModelEntry.Model(
            value = value,
            label = if (selected && suffix.isNotBlank()) "$label  $suffix" else label,
            selected = selected,
        )
    }

    private fun modelDescription(current: List<ConfigOption>): String {
        val model = current.firstOrNull { ConfigOptionUiSupport.isModelSelector(it) }
        val currentValue = model?.currentValue?.trim().orEmpty()
        val description = model?.options
            ?.firstOrNull { it.value == currentValue }
            ?.description
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        return when {
            description != null -> description
            ConfigOptionUiSupport.isAutoModelValue(currentValue) -> CursorJBundle.message("chat.modelPicker.auto.description")
            else -> CursorJBundle.message("chat.modelPicker.noParameters")
        }
    }

    private fun toActionGroup(menu: PickerMenu): DefaultActionGroup {
        val group = DefaultActionGroup()
        val modelGroup = DefaultActionGroup(menu.modelRow.text, true)
        for (entry in menu.modelRow.entries) {
            when (entry) {
                is ModelEntry.Model -> modelGroup.add(ModelAction(entry))
                is ModelEntry.MaxMode -> modelGroup.add(MaxModeAction())
                ModelEntry.Divider -> modelGroup.addSeparator()
            }
        }
        group.add(modelGroup)
        if (menu.sections.isEmpty()) {
            group.addSeparator()
            group.add(DescriptionAction(menu.description.orEmpty()))
            return group
        }
        for (section in menu.sections) {
            group.add(Separator.create(section.title ?: CursorJBundle.message("chat.modelPicker.options")))
            for (item in section.items) {
                group.add(
                    when (section.kind) {
                        PickerSection.Kind.RADIO -> ValueAction(item)
                        PickerSection.Kind.SWITCHES -> SwitchAction(item)
                    },
                )
            }
        }
        return group
    }

    private abstract inner class PickerToggle(
        text: String,
        description: String?,
        keepPopup: KeepPopupOnPerform,
    ) : ToggleAction(text, description, null) {
        init {
            templatePresentation.keepPopupOnPerform = keepPopup
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.isEnabled = itemsEnabled()
        }
    }

    private inner class ModelAction(private val entry: ModelEntry.Model) :
        PickerToggle(entry.label, null, KeepPopupOnPerform.Never) {
        override fun isSelected(e: AnActionEvent): Boolean = isModelSelected(entry.value)

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) selectModel(entry.value)
        }
    }

    private inner class ValueAction(private val item: PickerItem) :
        PickerToggle(item.label, item.description, keepPopupOnPerform(PickerSection.Kind.RADIO)) {
        override fun isSelected(e: AnActionEvent): Boolean = isValueSelected(item.configId, item.value)

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) selectValue(item.configId, item.value)
        }
    }

    private inner class SwitchAction(private val item: PickerItem) :
        PickerToggle(item.label, item.description, keepPopupOnPerform(PickerSection.Kind.SWITCHES)) {
        override fun isSelected(e: AnActionEvent): Boolean = isSwitchOn(item.configId)

        override fun setSelected(e: AnActionEvent, state: Boolean) = toggleSwitch(item.configId, state)
    }

    private inner class MaxModeAction : PickerToggle(
        CursorJBundle.message("chat.modelPicker.maxMode"),
        CursorJBundle.message("chat.maxmode.tooltip"),
        KeepPopupOnPerform.Never,
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = maxModeEnabled()

        override fun setSelected(e: AnActionEvent, state: Boolean) = toggleMaxMode(state)
    }

    /** Muted, non-selectable text line (e.g. Auto's description). */
    private class DescriptionAction(text: String) : AnAction(text) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = false
        }

        override fun actionPerformed(e: AnActionEvent) = Unit
    }

    companion object {
        /** Radio values close the popup; boolean switches keep it open so several can be flipped in one go. */
        internal fun keepPopupOnPerform(kind: PickerSection.Kind): KeepPopupOnPerform = when (kind) {
            PickerSection.Kind.RADIO -> KeepPopupOnPerform.Never
            PickerSection.Kind.SWITCHES -> KeepPopupOnPerform.Always
        }
    }
}

/** Plain-data content of the picker: the Model submenu row, then parameter sections or a description line. */
internal data class PickerMenu(
    val modelRow: ModelRow,
    val sections: List<PickerSection>,
    val description: String?,
)

/** The `Model: <current>` submenu row and its entries in display order. */
internal data class ModelRow(
    val text: String,
    val entries: List<ModelEntry>,
)

internal sealed interface ModelEntry {
    data class Model(val value: String, val label: String, val selected: Boolean) : ModelEntry

    data class MaxMode(val enabled: Boolean) : ModelEntry

    data object Divider : ModelEntry
}
