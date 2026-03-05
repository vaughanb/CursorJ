package com.cursorj.ui.components

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class CodeBlockComponent(
    code: String,
    language: String = "",
    project: Project? = null,
) {
    private val panel = JPanel(BorderLayout())

    val component: JComponent get() = panel

    init {
        val document = EditorFactory.getInstance().createDocument(code)
        val editor = EditorFactory.getInstance().createViewer(document, project) as EditorEx

        editor.settings.apply {
            isLineNumbersShown = true
            isFoldingOutlineShown = false
            additionalLinesCount = 0
            additionalColumnsCount = 0
            isAdditionalPageAtBottom = false
        }

        val fileType = if (language.isNotBlank()) {
            FileTypeManager.getInstance().getFileTypeByExtension(mapLanguageToExtension(language))
        } else {
            PlainTextFileType.INSTANCE
        }
        val highlighter = EditorHighlighterFactory.getInstance()
            .createEditorHighlighter(project, fileType)
        editor.highlighter = highlighter

        val lineCount = code.lines().size
        val lineHeight = editor.lineHeight
        val editorHeight = (lineCount * lineHeight).coerceAtMost(400).coerceAtLeast(50)
        editor.component.preferredSize = Dimension(Int.MAX_VALUE, editorHeight)

        val copyButton = JButton("Copy").apply {
            isFocusable = false
            border = JBUI.Borders.empty(2, 6)
            addActionListener {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                clipboard.setContents(StringSelection(code), null)
            }
        }

        val topBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            if (language.isNotBlank()) {
                add(javax.swing.JLabel(language).apply {
                    border = JBUI.Borders.empty(2, 6)
                    foreground = com.intellij.ui.JBColor.GRAY
                }, BorderLayout.WEST)
            }
            add(copyButton, BorderLayout.EAST)
        }

        panel.add(topBar, BorderLayout.NORTH)
        panel.add(editor.component, BorderLayout.CENTER)
        panel.border = JBUI.Borders.empty(4, 0)
    }

    companion object {
        fun mapLanguageToExtension(language: String): String {
            return when (language.lowercase()) {
                "kotlin", "kt" -> "kt"
                "java" -> "java"
                "python", "py" -> "py"
                "javascript", "js" -> "js"
                "typescript", "ts" -> "ts"
                "rust", "rs" -> "rs"
                "go" -> "go"
                "c" -> "c"
                "cpp", "c++" -> "cpp"
                "html" -> "html"
                "css" -> "css"
                "json" -> "json"
                "xml" -> "xml"
                "yaml", "yml" -> "yaml"
                "bash", "sh", "shell" -> "sh"
                "sql" -> "sql"
                "markdown", "md" -> "md"
                else -> "txt"
            }
        }
    }
}
