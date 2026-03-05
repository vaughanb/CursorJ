package com.cursorj.ui.components

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

class DiffViewComponent(
    before: String,
    after: String,
    filePath: String = "",
    project: Project? = null,
) {
    private val panel = JPanel(BorderLayout())

    val component: JComponent get() = panel

    init {
        if (project != null) {
            try {
                val factory = DiffContentFactory.getInstance()
                val beforeContent = factory.create(project, before)
                val afterContent = factory.create(project, after)

                val request = SimpleDiffRequest(
                    filePath.ifBlank { "Diff" },
                    beforeContent,
                    afterContent,
                    "Before",
                    "After",
                )

                val diffPanel = DiffManager.getInstance().createRequestPanel(project, {}, null)
                diffPanel.setRequest(request)

                panel.add(diffPanel.component, BorderLayout.CENTER)
                panel.preferredSize = Dimension(Int.MAX_VALUE, 300)
            } catch (e: Exception) {
                val fallback = FallbackDiffPanel(before, after)
                panel.add(fallback, BorderLayout.CENTER)
            }
        } else {
            val fallback = FallbackDiffPanel(before, after)
            panel.add(fallback, BorderLayout.CENTER)
        }

        panel.border = JBUI.Borders.empty(4, 0)
    }

    private class FallbackDiffPanel(before: String, after: String) : JPanel(BorderLayout()) {
        init {
            val html = buildString {
                append("<html><body style='font-family: monospace; font-size: 9pt;'>")
                append("<b>Before:</b><br/>")
                append("<pre>${escapeHtml(before)}</pre>")
                append("<hr/>")
                append("<b>After:</b><br/>")
                append("<pre>${escapeHtml(after)}</pre>")
                append("</body></html>")
            }
            val editorPane = javax.swing.JEditorPane("text/html", html).apply {
                isEditable = false
                border = JBUI.Borders.empty(8)
            }
            add(editorPane, BorderLayout.CENTER)
        }

        private fun escapeHtml(text: String): String {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        }
    }
}
