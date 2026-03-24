package com.cursorj.handlers

import com.cursorj.CursorJBundle
import com.cursorj.acp.AcpClient
import com.cursorj.acp.messages.PermissionOption
import com.cursorj.acp.messages.RequestPermissionParams
import com.cursorj.permissions.PermissionMode
import com.cursorj.permissions.PermissionPolicy
import com.cursorj.settings.CursorJSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import kotlinx.serialization.json.*
import java.awt.event.ActionEvent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent

class PermissionHandler(
    private val settingsProvider: () -> CursorJSettings = { CursorJSettings.instance },
    private val promptTimeoutMinutes: Long = DEFAULT_PROMPT_TIMEOUT_MINUTES,
) {
    private val log = Logger.getInstance(PermissionHandler::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var promptResolver: ((RequestPermissionParams) -> CompletableFuture<String>)? = null

    fun setPromptResolver(resolver: ((RequestPermissionParams) -> CompletableFuture<String>)?) {
        promptResolver = resolver
    }

    fun register(client: AcpClient) {
        client.addServerRequestHandler { method, params ->
            when (method) {
                "session/request_permission" -> handlePermissionRequest(params)
                else -> null
            }
        }
    }

    internal fun handlePermissionRequest(params: JsonElement): JsonElement {
        val request = PermissionPolicy.withResolvedToolName(json.decodeFromJsonElement<RequestPermissionParams>(params))
        val settings = settingsProvider()
        val mode = PermissionMode.fromId(settings.permissionMode)
        val approvedKeys = settings.getApprovedPermissionKeys()
        val autoAllowOption = PermissionPolicy.shouldAutoAllowRequest(mode, approvedKeys, request)
        if (autoAllowOption != null) {
            return buildPermissionResult(autoAllowOption)
        }

        val selected = requestUserDecision(request)
        val optionId = sanitizeOptionSelection(request, selected)
        if (PermissionPolicy.isAllowOption(optionId)) {
            settings.approvePermissionKeys(PermissionPolicy.approvedKeysForRequest(request))
        }
        return buildPermissionResult(optionId)
    }

    private fun requestUserDecision(request: RequestPermissionParams): String {
        val resolver = promptResolver
        if (resolver != null) {
            try {
                return resolver(request).get(promptTimeoutMinutes, TimeUnit.MINUTES)
            } catch (e: Exception) {
                log.warn("Permission resolver failed or timed out, falling back to dialog", e)
            }
        }
        return showFallbackDialog(request)
    }

    private fun showFallbackDialog(request: RequestPermissionParams): String {
        val future = CompletableFuture<String>()
        ApplicationManager.getApplication().invokeLater {
            val dialog = PermissionDialog(request)
            if (dialog.showAndGet()) {
                future.complete(dialog.selectedOptionId)
            } else {
                future.complete(PermissionPolicy.chooseRejectOption(request.options))
            }
        }
        return try {
            future.get(promptTimeoutMinutes, TimeUnit.MINUTES)
        } catch (e: Exception) {
            log.warn("Permission dialog timed out; rejecting request", e)
            PermissionPolicy.chooseRejectOption(request.options)
        }
    }

    private fun sanitizeOptionSelection(request: RequestPermissionParams, selectedOptionId: String): String {
        val validIds = request.options.map { it.optionId }.toSet()
        if (selectedOptionId in validIds) return selectedOptionId
        return if (PermissionPolicy.isAllowOption(selectedOptionId) && validIds.isEmpty()) {
            selectedOptionId
        } else {
            PermissionPolicy.chooseRejectOption(request.options)
        }
    }

    private fun buildPermissionResult(optionId: String): JsonElement {
        return buildJsonObject {
            putJsonObject("outcome") {
                put("outcome", "selected")
                put("optionId", optionId)
            }
        }
    }

    private class PermissionDialog(
        private val request: RequestPermissionParams,
    ) : DialogWrapper(true) {
        private val options: List<PermissionOption> = if (request.options.isEmpty()) {
            listOf(
                PermissionOption("allow-once", CursorJBundle.message("permission.allow.once")),
                PermissionOption("allow-always", CursorJBundle.message("permission.allow.always")),
                PermissionOption("reject-once", CursorJBundle.message("permission.reject")),
            )
        } else {
            request.options
        }

        var selectedOptionId: String = PermissionPolicy.chooseRejectOption(options)
            private set

        init {
            title = CursorJBundle.message("permission.title")
            init()
        }

        override fun createCenterPanel(): JComponent {
            val toolLabel = JBLabel("Tool: ${PermissionPolicy.displayToolName(request)}")
            val descLabel = JBLabel(request.description ?: "The agent wants to perform an action.")

            val argsText = request.arguments?.let {
                try {
                    Json.encodeToString(JsonElement.serializer(), it)
                } catch (_: Exception) {
                    it.toString()
                }
            }
            val argsLabel = if (argsText != null) JBLabel("<html><pre>$argsText</pre></html>") else null

            val builder = FormBuilder.createFormBuilder()
                .addComponent(toolLabel)
                .addComponent(descLabel)
            argsLabel?.let { builder.addComponent(it) }

            return builder.panel.apply {
                border = JBUI.Borders.empty(10)
                preferredSize = java.awt.Dimension(450, 200)
            }
        }

        override fun createActions(): Array<Action> {
            return options.map { option ->
                object : AbstractAction(option.label ?: option.optionId) {
                    override fun actionPerformed(e: ActionEvent?) {
                        selectedOptionId = option.optionId
                        close(OK_EXIT_CODE)
                    }
                }
            }.toTypedArray()
        }

        override fun doCancelAction() {
            selectedOptionId = PermissionPolicy.chooseRejectOption(options)
            super.doCancelAction()
        }

    }

    companion object {
        private const val DEFAULT_PROMPT_TIMEOUT_MINUTES = 10L
    }
}
