package com.cursorj.settings

import com.cursorj.CursorJBundle
import com.cursorj.permissions.PermissionMode
import com.cursorj.ui.toolwindow.CursorJService
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.TitledSeparator
import com.intellij.util.ui.FormBuilder
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

class CursorJConfigurable : Configurable {
    private var agentPathField: TextFieldWithBrowseButton? = null
    private var defaultModelField: JBTextField? = null
    private var globalUserRulesListPanel: GlobalUserRulesListPanel? = null
    private var projectRulesListPanel: ProjectRulesListPanel? = null
    private var autoAttachCheckbox: JBCheckBox? = null
    private var projectIndexingCheckbox: JBCheckBox? = null
    private var lexicalPersistenceCheckbox: JBCheckBox? = null
    private var semanticIndexingCheckbox: JBCheckBox? = null
    private var retrievalMaxCandidatesField: JBTextField? = null
    private var retrievalSnippetBudgetField: JBTextField? = null
    private var retrievalTimeoutField: JBTextField? = null
    private var indexRetentionDaysField: JBTextField? = null
    private var indexMaxDatabaseMbField: JBTextField? = null
    private var rebuildIndexButton: JButton? = null
    private var permissionModeCombo: JComboBox<String>? = null
    private var approvedToolsArea: JBTextArea? = null
    private var protectExternalWritesCheckbox: JBCheckBox? = null
    private var acpRawLoggingCheckbox: JBCheckBox? = null
    private var showTokenUsageCheckbox: JBCheckBox? = null
    private var mainPanel: JPanel? = null

    override fun getDisplayName(): String = CursorJBundle.message("settings.title")

    override fun createComponent(): JComponent {
        agentPathField = TextFieldWithBrowseButton().apply {
            val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
                .withTitle("Select Cursor Agent Binary")
                .withDescription(CursorJBundle.message("settings.agent.path.tooltip"))
            addBrowseFolderListener(null, descriptor)
            textField.putClientProperty(
                "JTextField.placeholderText",
                CursorJBundle.message("settings.agent.path.placeholder"),
            )
        }
        defaultModelField = JBTextField().apply {
            putClientProperty(
                "JTextField.placeholderText",
                CursorJBundle.message("settings.default.model.placeholder"),
            )
        }
        globalUserRulesListPanel = GlobalUserRulesListPanel()
        val globalRulesDescription = JBLabel(
            CursorJBundle.message("settings.globalRules.description"),
        ).apply {
            toolTipText = CursorJBundle.message("settings.globalRules.text.tooltip")
        }
        val projectRulesDescription = JBLabel(
            CursorJBundle.message("settings.projectRules.description"),
        )
        val activeProject = ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }
        projectRulesListPanel = activeProject?.let { ProjectRulesListPanel(it) }
        val projectRulesContent: JComponent = projectRulesListPanel
            ?: JBLabel(CursorJBundle.message("settings.projectRules.unavailable"))
        autoAttachCheckbox = JBCheckBox(
            CursorJBundle.message("settings.auto.attach"),
        )
        projectIndexingCheckbox = JBCheckBox(
            CursorJBundle.message("settings.indexing.enable"),
        )
        lexicalPersistenceCheckbox = JBCheckBox(
            CursorJBundle.message("settings.indexing.persistence"),
        )
        semanticIndexingCheckbox = JBCheckBox(
            CursorJBundle.message("settings.indexing.semantic"),
        )
        retrievalMaxCandidatesField = JBTextField()
        retrievalSnippetBudgetField = JBTextField()
        retrievalTimeoutField = JBTextField()
        indexRetentionDaysField = JBTextField()
        indexMaxDatabaseMbField = JBTextField()
        rebuildIndexButton = JButton(CursorJBundle.message("settings.indexing.rebuild")).apply {
            addActionListener {
                val openProjects = ProjectManager.getInstance().openProjects
                for (project in openProjects) {
                    CursorJService.getInstance(project)?.workspaceIndexOrchestrator?.requestRebuild("settings-trigger")
                }
            }
        }
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
        acpRawLoggingCheckbox = JBCheckBox(
            CursorJBundle.message("settings.acp.rawLogging"),
        )
        showTokenUsageCheckbox = JBCheckBox(
            CursorJBundle.message("settings.showTokenUsage"),
        )

        val approvedToolsScroll = JBScrollPane(approvedToolsArea).apply {
            preferredSize = Dimension(400, 130)
        }

        mainPanel = FormBuilder.createFormBuilder()
            .addComponent(TitledSeparator(CursorJBundle.message("settings.section.agent")), 1)
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
            .addComponent(TitledSeparator(CursorJBundle.message("settings.section.rules")), 1)
            .addComponent(TitledSeparator(CursorJBundle.message("settings.rules.global.title")), 1)
            .addComponent(globalRulesDescription, 1)
            .addComponent(globalUserRulesListPanel!!, 1)
            .addComponent(TitledSeparator(CursorJBundle.message("settings.rules.project.title")), 1)
            .addComponent(projectRulesDescription, 1)
            .addComponent(projectRulesContent, 1)
            .addComponent(TitledSeparator(CursorJBundle.message("settings.section.indexing")), 1)
            .addComponent(autoAttachCheckbox!!, 1)
            .addComponent(projectIndexingCheckbox!!, 1)
            .addComponent(lexicalPersistenceCheckbox!!, 1)
            .addComponent(semanticIndexingCheckbox!!, 1)
            .addLabeledComponent(
                JBLabel(CursorJBundle.message("settings.indexing.maxCandidates")),
                retrievalMaxCandidatesField!!,
                1,
                false,
            )
            .addLabeledComponent(
                JBLabel(CursorJBundle.message("settings.indexing.snippetBudget")),
                retrievalSnippetBudgetField!!,
                1,
                false,
            )
            .addLabeledComponent(
                JBLabel(CursorJBundle.message("settings.indexing.timeoutMs")),
                retrievalTimeoutField!!,
                1,
                false,
            )
            .addLabeledComponent(
                JBLabel(CursorJBundle.message("settings.indexing.retentionDays")),
                indexRetentionDaysField!!,
                1,
                false,
            )
            .addLabeledComponent(
                JBLabel(CursorJBundle.message("settings.indexing.maxDbMb")),
                indexMaxDatabaseMbField!!,
                1,
                false,
            )
            .addComponent(rebuildIndexButton!!, 1)
            .addComponent(TitledSeparator(CursorJBundle.message("settings.section.permissions")), 1)
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
            .addComponent(TitledSeparator(CursorJBundle.message("settings.section.advanced")), 1)
            .addComponent(acpRawLoggingCheckbox!!, 1)
            .addComponent(showTokenUsageCheckbox!!, 1)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return mainPanel!!
    }

    override fun isModified(): Boolean {
        val settings = CursorJSettings.instance
        return (agentPathField?.text ?: "") != displayAgentPath(settings) ||
            defaultModelField?.text != settings.defaultModel ||
            readGlobalUserRules() != settings.getGlobalUserRules() ||
            autoAttachCheckbox?.isSelected != settings.autoAttachActiveFile ||
            projectIndexingCheckbox?.isSelected != settings.enableProjectIndexing ||
            lexicalPersistenceCheckbox?.isSelected != settings.enableLexicalPersistence ||
            semanticIndexingCheckbox?.isSelected != settings.enableSemanticIndexing ||
            readIntField(retrievalMaxCandidatesField, settings.retrievalMaxCandidates, 1, 200) != settings.retrievalMaxCandidates ||
            readIntField(retrievalSnippetBudgetField, settings.retrievalSnippetCharBudget, 500, 30000) != settings.retrievalSnippetCharBudget ||
            readIntField(retrievalTimeoutField, settings.retrievalTimeoutMs, 250, 20000) != settings.retrievalTimeoutMs ||
            readIntField(indexRetentionDaysField, settings.indexRetentionDays, 1, 365) != settings.indexRetentionDays ||
            readIntField(indexMaxDatabaseMbField, settings.indexMaxDatabaseMb, 50, 4096) != settings.indexMaxDatabaseMb ||
            selectedPermissionMode() != settings.permissionMode ||
            protectExternalWritesCheckbox?.isSelected != settings.protectExternalFileWrites ||
            acpRawLoggingCheckbox?.isSelected != settings.enableAcpRawLogging ||
            showTokenUsageCheckbox?.isSelected != settings.showTokenUsage ||
            readApprovedTools() != settings.getApprovedPermissionKeys()
    }

    override fun apply() {
        val settings = CursorJSettings.instance
        settings.agentPath = agentPathField?.text?.trim().orEmpty()
        val defaultModel = defaultModelField?.text?.trim().orEmpty()
        settings.defaultModel = defaultModel
        settings.setGlobalUserRules(readGlobalUserRules())
        settings.autoAttachActiveFile = autoAttachCheckbox?.isSelected ?: true
        settings.enableProjectIndexing = projectIndexingCheckbox?.isSelected ?: true
        settings.enableLexicalPersistence = lexicalPersistenceCheckbox?.isSelected ?: true
        settings.enableSemanticIndexing = semanticIndexingCheckbox?.isSelected ?: false
        settings.retrievalMaxCandidates = readIntField(retrievalMaxCandidatesField, settings.retrievalMaxCandidates, 1, 200)
        settings.retrievalSnippetCharBudget = readIntField(retrievalSnippetBudgetField, settings.retrievalSnippetCharBudget, 500, 30000)
        settings.retrievalTimeoutMs = readIntField(retrievalTimeoutField, settings.retrievalTimeoutMs, 250, 20000)
        settings.indexRetentionDays = readIntField(indexRetentionDaysField, settings.indexRetentionDays, 1, 365)
        settings.indexMaxDatabaseMb = readIntField(indexMaxDatabaseMbField, settings.indexMaxDatabaseMb, 50, 4096)
        settings.permissionMode = selectedPermissionMode()
        settings.protectExternalFileWrites = protectExternalWritesCheckbox?.isSelected ?: true
        settings.enableAcpRawLogging = acpRawLoggingCheckbox?.isSelected ?: false
        settings.showTokenUsage = showTokenUsageCheckbox?.isSelected ?: true
        settings.setApprovedPermissionKeys(readApprovedTools())
        val knownModelIds = knownModelIdsFromOpenProjects()
        if (defaultModel.isNotBlank() && knownModelIds.isNotEmpty() && defaultModel !in knownModelIds) {
            Messages.showWarningDialog(
                CursorJBundle.message("settings.default.model.invalid.warning", defaultModel),
                CursorJBundle.message("settings.title"),
            )
        }
    }

    override fun reset() {
        val settings = CursorJSettings.instance
        agentPathField?.text = displayAgentPath(settings)
        defaultModelField?.text = settings.defaultModel
        globalUserRulesListPanel?.setRules(settings.getGlobalUserRules())
        autoAttachCheckbox?.isSelected = settings.autoAttachActiveFile
        projectIndexingCheckbox?.isSelected = settings.enableProjectIndexing
        lexicalPersistenceCheckbox?.isSelected = settings.enableLexicalPersistence
        semanticIndexingCheckbox?.isSelected = settings.enableSemanticIndexing
        retrievalMaxCandidatesField?.text = settings.retrievalMaxCandidates.toString()
        retrievalSnippetBudgetField?.text = settings.retrievalSnippetCharBudget.toString()
        retrievalTimeoutField?.text = settings.retrievalTimeoutMs.toString()
        indexRetentionDaysField?.text = settings.indexRetentionDays.toString()
        indexMaxDatabaseMbField?.text = settings.indexMaxDatabaseMb.toString()
        permissionModeCombo?.selectedIndex = when (PermissionMode.fromId(settings.permissionMode)) {
            PermissionMode.RUN_EVERYTHING -> 1
            else -> 0
        }
        protectExternalWritesCheckbox?.isSelected = settings.protectExternalFileWrites
        acpRawLoggingCheckbox?.isSelected = settings.enableAcpRawLogging
        showTokenUsageCheckbox?.isSelected = settings.showTokenUsage
        approvedToolsArea?.text = settings.getApprovedPermissionKeys().sorted().joinToString("\n")
        projectRulesListPanel?.refresh()
    }

    private fun selectedPermissionMode(): String {
        return when (permissionModeCombo?.selectedIndex) {
            1 -> PermissionMode.RUN_EVERYTHING.id
            else -> PermissionMode.ASK_EVERY_TIME.id
        }
    }

    private fun readGlobalUserRules(): List<String> {
        return globalUserRulesListPanel?.getRules() ?: emptyList()
    }

    private fun readApprovedTools(): Set<String> {
        return approvedToolsArea?.text
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()
    }

    private fun readIntField(field: JBTextField?, fallback: Int, min: Int, max: Int): Int {
        val parsed = field?.text?.trim()?.toIntOrNull() ?: fallback
        return parsed.coerceIn(min, max)
    }

    private fun displayAgentPath(settings: CursorJSettings): String {
        val configured = settings.agentPath.trim()
        if (configured.isNotBlank()) return configured
        return settings.effectiveAgentPath.orEmpty()
    }

    private fun knownModelIdsFromOpenProjects(): Set<String> {
        return ProjectManager.getInstance().openProjects
            .asSequence()
            .mapNotNull { CursorJService.getInstance(it) }
            .flatMap { service -> service.availableModelInfos.asSequence().map { it.id } }
            .toSet()
    }

    override fun disposeUIResources() {
        agentPathField = null
        defaultModelField = null
        globalUserRulesListPanel = null
        projectRulesListPanel = null
        autoAttachCheckbox = null
        projectIndexingCheckbox = null
        lexicalPersistenceCheckbox = null
        semanticIndexingCheckbox = null
        retrievalMaxCandidatesField = null
        retrievalSnippetBudgetField = null
        retrievalTimeoutField = null
        indexRetentionDaysField = null
        indexMaxDatabaseMbField = null
        rebuildIndexButton = null
        permissionModeCombo = null
        approvedToolsArea = null
        protectExternalWritesCheckbox = null
        acpRawLoggingCheckbox = null
        showTokenUsageCheckbox = null
        mainPanel = null
    }
}
