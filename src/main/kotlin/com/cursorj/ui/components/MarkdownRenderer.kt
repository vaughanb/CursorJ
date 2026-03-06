package com.cursorj.ui.components

import com.intellij.ui.JBColor

object MarkdownRenderer {
    fun renderToHtml(markdown: String): String {
        val lines = markdown.lines()
        val sb = StringBuilder()
        sb.append("<html><body style='font-family: sans-serif; font-size: 13pt; margin: 0; padding: 0;'>")

        var inCodeBlock = false
        var codeLanguage = ""
        val codeBuffer = StringBuilder()

        for (line in lines) {
            when {
                line.startsWith("```") && !inCodeBlock -> {
                    inCodeBlock = true
                    codeLanguage = line.removePrefix("```").trim()
                    codeBuffer.clear()
                }
                line.startsWith("```") && inCodeBlock -> {
                    inCodeBlock = false
                    sb.append(renderCodeBlock(codeBuffer.toString(), codeLanguage))
                    codeBuffer.clear()
                }
                inCodeBlock -> {
                    if (codeBuffer.isNotEmpty()) codeBuffer.appendLine()
                    codeBuffer.append(line)
                }
                line.startsWith("### ") -> {
                    sb.append("<h4>${escapeHtml(line.removePrefix("### "))}</h4>")
                }
                line.startsWith("## ") -> {
                    sb.append("<h3>${escapeHtml(line.removePrefix("## "))}</h3>")
                }
                line.startsWith("# ") -> {
                    sb.append("<h2>${escapeHtml(line.removePrefix("# "))}</h2>")
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    sb.append("<p style='margin: 2px 0 2px 16px;'>&bull; ${renderInline(line.drop(2))}</p>")
                }
                line.isBlank() -> {
                    sb.append("<br/>")
                }
                else -> {
                    sb.append("<p style='margin: 2px 0;'>${renderInline(line)}</p>")
                }
            }
        }

        if (inCodeBlock) {
            sb.append(renderCodeBlock(codeBuffer.toString(), codeLanguage))
        }

        sb.append("</body></html>")
        return sb.toString()
    }

    private fun renderCodeBlock(code: String, language: String): String {
        val bgColor = if (JBColor.isBright()) "#f6f8fa" else "#1e1e1e"
        val textColor = if (JBColor.isBright()) "#24292e" else "#d4d4d4"
        return """
            <div style='background: $bgColor; padding: 8px; margin: 4px 0;'>
                <pre style='font-family: monospace; font-size: 12pt; color: $textColor; margin: 0;'>${escapeHtml(code)}</pre>
            </div>
        """.trimIndent()
    }

    private fun renderInline(text: String): String {
        var result = escapeHtml(text)

        result = Regex("`([^`]+)`").replace(result) { match ->
            val bgColor = if (JBColor.isBright()) "#f0f0f0" else "#3c3c3c"
            "<code style='background: $bgColor; padding: 1px 4px; font-family: monospace; font-size: 12pt;'>${match.groupValues[1]}</code>"
        }

        result = Regex("\\*\\*(.+?)\\*\\*").replace(result) { match ->
            "<b>${match.groupValues[1]}</b>"
        }
        result = Regex("\\*(.+?)\\*").replace(result) { match ->
            "<i>${match.groupValues[1]}</i>"
        }

        result = Regex("\\[(.+?)]\\((.+?)\\)").replace(result) { match ->
            "<a href='${match.groupValues[2]}'>${match.groupValues[1]}</a>"
        }

        return result
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
