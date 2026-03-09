package com.cursorj.settings

import com.cursorj.CursorJBundle
import com.cursorj.permissions.PermissionMode
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.FormBuilder
import java.awt.Dimension
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

class CursorJConfigurable : Configurable {
    private var agentPathField: TextFieldWithBrowseButton? = null
    private var defaultModelField: JBTextField? = null
    private var autoAttachCheckbox: JBCheckBox? = null
    private var permissionModeCombo: JComboBox<String>? = null
    private var approvedToolsArea: JBTextArea? = null
    private var protectExternalWritesCheckbox: JBCheckBox? = null
    private var mainPanel: JPanel? = null

    override fun getDisplayName(): String = CursorJBundle.message("settings.title")

    override fun createComponent(): JComponent {
        agentPathField = TextFieldWithBrowseButton().apply {
            val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
                .withTitle("Select Cursor Agent Binary")
                .withDescription(CursorJBundle.message("settings.agent.path.tooltip"))
            addBrowseFolderListener(null, descriptor)
        }
        defaultModelField = JBTextField()
        autoAttachCheckbox = JBCheckBox(
            CursorJBundle.message("settings.auto.attach"),
        )
        permissionModeCombo = JComboBox(arrayOf(
            CursorJBundle.message("permission.mode.askEveryTime"),
            CursorJBundle.message("permission.mode.runEverything"),
        ))
        approvedToolsArea = JBTextArea().apply {
            lineWrap = true
            wrapStyleWord = true
            rows = 6
        }
        protectExternalWritesCheckbox = JBCheckBox(
            CursorJBundle.message("settings.permission.protectExternalWrites"),
        )

        val approvedToolsScroll = JBScrollPane(approvedToolsArea).apply {
            preferredSize = Dimension(400, 130)
        }

        mainPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(
                JBLabel(CursorJBundle.message("settings.agent.path")),
                agentPathField!!,
                1,
                false,
            )
            .addLabeledComponent(
                JBLabel(CursorJBundle.message("settings.default.model")),
                defaultModelField!!,
                1,
                false,
            )
            .addComponent(autoAttachCheckbox!!, 1)
            .addLabeledComponent(
                JBLabel(CursorJBundle.message("settings.permission.mode")),
                permissionModeCombo!!,
                1,
                false,
            )
            .addComponent(protectExternalWritesCheckbox!!, 1)
            .addLabeledComponent(
                JBLabel(CursorJBundle.message("settings.permission.approvedTools")),
                approvedToolsScroll,
                1,
                false,
            )
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return mainPanel!!
    }

    override fun isModified(): Boolean {
        val settings = CursorJSettings.instance
        return agentPathField?.text != settings.agentPath ||
            defaultModelField?.text != settings.defaultModel ||
            autoAttachCheckbox?.isSelected != settings.autoAttachActiveFile ||
            selectedPermissionMode() != settings.permissionMode ||
            protectExternalWritesCheckbox?.isSelected != settings.protectExternalFileWrites ||
            readApprovedTools() != settings.getApprovedPermissionKeys()
    }

    override fun apply() {
        val settings = CursorJSettings.instance
        settings.agentPath = agentPathField?.text ?: ""
        settings.defaultModel = defaultModelField?.text ?: ""
        settings.autoAttachActiveFile = autoAttachCheckbox?.isSelected ?: true
        settings.permissionMode = selectedPermissionMode()
        settings.protectExternalFileWrites = protectExternalWritesCheckbox?.isSelected ?: true
        settings.setApprovedPermissionKeys(readApprovedTools())
    }

    override fun reset() {
        val settings = CursorJSettings.instance
        agentPathField?.text = settings.agentPath
        defaultModelField?.text = settings.defaultModel
        autoAttachCheckbox?.isSelected = settings.autoAttachActiveFile
        permissionModeCombo?.selectedIndex = when (PermissionMode.fromId(settings.permissionMode)) {
            PermissionMode.RUN_EVERYTHING -> 1
            else -> 0
        }
        protectExternalWritesCheckbox?.isSelected = settings.protectExternalFileWrites
        approvedToolsArea?.text = settings.getApprovedPermissionKeys().sorted().joinToString("\n")
    }

    private fun selectedPermissionMode(): String {
        return when (permissionModeCombo?.selectedIndex) {
            1 -> PermissionMode.RUN_EVERYTHING.id
            else -> PermissionMode.ASK_EVERY_TIME.id
        }
    }

    private fun readApprovedTools(): Set<String> {
        return approvedToolsArea?.text
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()
    }

    override fun disposeUIResources() {
        agentPathField = null
        defaultModelField = null
        autoAttachCheckbox = null
        permissionModeCombo = null
        approvedToolsArea = null
        protectExternalWritesCheckbox = null
        mainPanel = null
    }
}
