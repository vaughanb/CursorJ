package com.cursorj.handlers

import com.cursorj.CursorJBundle
import com.cursorj.acp.AcpClient
import com.cursorj.acp.messages.PermissionOutcome
import com.cursorj.acp.messages.PermissionResult
import com.cursorj.acp.messages.RequestPermissionParams
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
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

class PermissionHandler {
    private val log = Logger.getInstance(PermissionHandler::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val alwaysAllowed = mutableSetOf<String>()

    fun register(client: AcpClient) {
        client.addServerRequestHandler { method, params ->
            when (method) {
                "session/request_permission" -> handlePermissionRequest(params, client)
                else -> null
            }
        }
    }

    private fun handlePermissionRequest(params: JsonElement, client: AcpClient): JsonElement {
        val request = json.decodeFromJsonElement<RequestPermissionParams>(params)
        val toolName = request.toolName ?: "unknown"

        val defaultBehavior = CursorJSettings.instance.defaultPermissionBehavior
        if (defaultBehavior == "allow" || toolName in alwaysAllowed) {
            return buildPermissionResult("allow-once")
        }
        if (defaultBehavior == "deny") {
            return buildPermissionResult("reject-once")
        }

        val future = CompletableFuture<String>()
        ApplicationManager.getApplication().invokeLater {
            val dialog = PermissionDialog(request)
            if (dialog.showAndGet()) {
                val optionId = dialog.selectedOptionId
                if (optionId == "allow-always") {
                    alwaysAllowed.add(toolName)
                }
                future.complete(optionId)
            } else {
                future.complete("reject-once")
            }
        }

        val optionId = future.get()
        return buildPermissionResult(optionId)
    }

    private fun buildPermissionResult(optionId: String): JsonElement {
        val result = PermissionResult(
            outcome = PermissionOutcome(outcome = "selected", optionId = optionId),
        )
        return json.encodeToJsonElement(result)
    }

    private class PermissionDialog(
        private val request: RequestPermissionParams,
    ) : DialogWrapper(true) {
        var selectedOptionId: String = "allow-once"
            private set

        init {
            title = CursorJBundle.message("permission.title")
            init()
        }

        override fun createCenterPanel(): JComponent {
            val toolLabel = JBLabel("Tool: ${request.toolName ?: "Unknown"}")
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
            val allowOnce = object : AbstractAction(CursorJBundle.message("permission.allow.once")) {
                override fun actionPerformed(e: ActionEvent?) {
                    selectedOptionId = "allow-once"
                    close(OK_EXIT_CODE)
                }
            }
            val allowAlways = object : AbstractAction(CursorJBundle.message("permission.allow.always")) {
                override fun actionPerformed(e: ActionEvent?) {
                    selectedOptionId = "allow-always"
                    close(OK_EXIT_CODE)
                }
            }
            val reject = object : AbstractAction(CursorJBundle.message("permission.reject")) {
                override fun actionPerformed(e: ActionEvent?) {
                    selectedOptionId = "reject-once"
                    close(CANCEL_EXIT_CODE)
                }
            }
            return arrayOf(allowOnce, allowAlways, reject)
        }
    }
}
