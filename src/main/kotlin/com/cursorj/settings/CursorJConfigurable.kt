package com.cursorj.settings

import com.cursorj.CursorJBundle
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class CursorJConfigurable : Configurable {
    private var agentPathField: TextFieldWithBrowseButton? = null
    private var apiKeyField: JBPasswordField? = null
    private var defaultModelField: JBTextField? = null
    private var autoAttachCheckbox: JBCheckBox? = null
    private var mainPanel: JPanel? = null

    override fun getDisplayName(): String = CursorJBundle.message("settings.title")

    override fun createComponent(): JComponent {
        agentPathField = TextFieldWithBrowseButton().apply {
            val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
                .withTitle("Select Cursor Agent Binary")
                .withDescription(CursorJBundle.message("settings.agent.path.tooltip"))
            addBrowseFolderListener(null, descriptor)
        }
        apiKeyField = JBPasswordField()
        defaultModelField = JBTextField()
        autoAttachCheckbox = JBCheckBox(
            CursorJBundle.message("settings.auto.attach"),
        )

        mainPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(
                JBLabel(CursorJBundle.message("settings.agent.path")),
                agentPathField!!,
                1,
                false,
            )
            .addLabeledComponent(JBLabel("API Key:"), apiKeyField!!, 1, false)
            .addLabeledComponent(
                JBLabel(CursorJBundle.message("settings.default.model")),
                defaultModelField!!,
                1,
                false,
            )
            .addComponent(autoAttachCheckbox!!, 1)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return mainPanel!!
    }

    override fun isModified(): Boolean {
        val settings = CursorJSettings.instance
        return agentPathField?.text != settings.agentPath ||
            readApiKeyInput() != settings.apiKey ||
            defaultModelField?.text != settings.defaultModel ||
            autoAttachCheckbox?.isSelected != settings.autoAttachActiveFile
    }

    override fun apply() {
        val settings = CursorJSettings.instance
        settings.agentPath = agentPathField?.text ?: ""
        settings.apiKey = readApiKeyInput()
        settings.defaultModel = defaultModelField?.text ?: ""
        settings.autoAttachActiveFile = autoAttachCheckbox?.isSelected ?: true
    }

    override fun reset() {
        val settings = CursorJSettings.instance
        agentPathField?.text = settings.agentPath
        apiKeyField?.text = settings.apiKey
        defaultModelField?.text = settings.defaultModel
        autoAttachCheckbox?.isSelected = settings.autoAttachActiveFile
    }

    private fun readApiKeyInput(): String = apiKeyField?.password?.concatToString() ?: ""

    override fun disposeUIResources() {
        agentPathField = null
        apiKeyField = null
        defaultModelField = null
        autoAttachCheckbox = null
        mainPanel = null
    }
}
