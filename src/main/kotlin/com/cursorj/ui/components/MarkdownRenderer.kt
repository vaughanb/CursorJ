package com.cursorj.ui.components

import com.intellij.ui.JBColor

object MarkdownRenderer {
    fun renderToHtml(markdown: String, baseFontSize: Int = 13): String {
        val lines = markdown.lines()
        val sb = StringBuilder()
        val codeFontSize = baseFontSize - 1
        sb.append(
            "<html><body style='font-size: ${baseFontSize}pt; margin: 0; padding: 0; line-height: 1.35; " +
                "word-wrap: break-word; overflow-wrap: anywhere;'>",
        )

        var inCodeBlock = false
        var codeLanguage = ""
        val codeBuffer = StringBuilder()
        var inTable = false
        val tableLines = mutableListOf<String>()
        var inBlockquote = false
        val blockquoteLines = mutableListOf<String>()
        var inIndentedCode = false
        val indentedCodeBuffer = StringBuilder()
        var prevLineBlank = false

        for (line in lines) {
            if (inTable && !inCodeBlock && !isTableLine(line)) {
                sb.append(renderTable(tableLines, codeFontSize))
                tableLines.clear()
                inTable = false
            }
            if (inBlockquote && !inCodeBlock && !line.trimStart().startsWith(">")) {
                sb.append(renderBlockquote(blockquoteLines, codeFontSize))
                blockquoteLines.clear()
                inBlockquote = false
            }
            if (inIndentedCode && line.isNotBlank() && !line.startsWith("    ") && !line.startsWith("\t")) {
                sb.append(renderCodeBlock(indentedCodeBuffer.toString().trimEnd(), "", codeFontSize))
                indentedCodeBuffer.clear()
                inIndentedCode = false
            }

            when {
                line.startsWith("```") && !inCodeBlock -> {
                    inCodeBlock = true
                    codeLanguage = line.removePrefix("```").trim()
                    codeBuffer.clear()
                }
                line.startsWith("```") && inCodeBlock -> {
                    inCodeBlock = false
                    sb.append(renderCodeBlock(codeBuffer.toString(), codeLanguage, codeFontSize))
                    codeBuffer.clear()
                }
                inCodeBlock -> {
                    if (codeBuffer.isNotEmpty()) codeBuffer.appendLine()
                    codeBuffer.append(line)
                }
                inIndentedCode -> {
                    if (line.isBlank()) {
                        indentedCodeBuffer.appendLine()
                    } else {
                        if (indentedCodeBuffer.isNotEmpty()) indentedCodeBuffer.appendLine()
                        val stripped = when {
                            line.startsWith("\t") -> line.substring(1)
                            line.startsWith("    ") -> line.substring(4)
                            else -> line
                        }
                        indentedCodeBuffer.append(stripped)
                    }
                }
                isTableLine(line) -> {
                    inTable = true
                    tableLines.add(line)
                }
                line.startsWith("###### ") -> {
                    sb.append(
                        "<h6 style='margin: 4px 0 2px 0; font-size: ${baseFontSize - 1}pt; " +
                            "word-wrap: break-word; overflow-wrap: anywhere;'>${escapeHtml(line.removePrefix("###### "))}</h6>",
                    )
                }
                line.startsWith("##### ") -> {
                    sb.append(
                        "<h6 style='margin: 4px 0 2px 0; font-size: ${baseFontSize - 1}pt; " +
                            "word-wrap: break-word; overflow-wrap: anywhere;'>${escapeHtml(line.removePrefix("##### "))}</h6>",
                    )
                }
                line.startsWith("#### ") -> {
                    sb.append(
                        "<h5 style='margin: 4px 0 2px 0; font-size: ${baseFontSize}pt; " +
                            "word-wrap: break-word; overflow-wrap: anywhere;'>${escapeHtml(line.removePrefix("#### "))}</h5>",
                    )
                }
                line.startsWith("### ") -> {
                    sb.append(
                        "<h4 style='margin: 4px 0 2px 0; font-size: ${baseFontSize + 1}pt; " +
                            "word-wrap: break-word; overflow-wrap: anywhere;'>${escapeHtml(line.removePrefix("### "))}</h4>",
                    )
                }
                line.startsWith("## ") -> {
                    sb.append(
                        "<h3 style='margin: 6px 0 2px 0; font-size: ${baseFontSize + 3}pt; " +
                            "word-wrap: break-word; overflow-wrap: anywhere;'>${escapeHtml(line.removePrefix("## "))}</h3>",
                    )
                }
                line.startsWith("# ") -> {
                    sb.append(
                        "<h2 style='margin: 6px 0 2px 0; font-size: ${baseFontSize + 5}pt; " +
                            "word-wrap: break-word; overflow-wrap: anywhere;'>${escapeHtml(line.removePrefix("# "))}</h2>",
                    )
                }
                isHorizontalRule(line) -> {
                    val hrColor = if (JBColor.isBright()) "#d0d7de" else "#555555"
                    sb.append(
                        "<hr style='border-top-width: 1px; border-top-style: solid; border-top-color: $hrColor; " +
                            "border-bottom-style: none; border-left-style: none; border-right-style: none; margin: 8px 0;'>",
                    )
                }
                isListItem(line) -> {
                    sb.append(renderListItem(line, codeFontSize))
                }
                line.trimStart().startsWith(">") -> {
                    inBlockquote = true
                    blockquoteLines.add(line)
                }
                prevLineBlank && (line.startsWith("    ") || line.startsWith("\t")) -> {
                    inIndentedCode = true
                    indentedCodeBuffer.clear()
                    val stripped = if (line.startsWith("\t")) line.substring(1) else line.substring(4)
                    indentedCodeBuffer.append(stripped)
                }
                line.isBlank() -> {
                    sb.append("<div style='margin: 0; padding: 0; line-height: 6px;'>&nbsp;</div>")
                }
                else -> {
                    sb.append(
                        "<p style='margin: 2px 0; word-wrap: break-word; overflow-wrap: anywhere;'>" +
                            "${renderInline(line, codeFontSize)}</p>",
                    )
                }
            }

            if (!inCodeBlock) {
                prevLineBlank = line.isBlank()
            }
        }

        if (inCodeBlock) {
            sb.append(renderCodeBlock(codeBuffer.toString(), codeLanguage, codeFontSize))
        }
        if (inTable) {
            sb.append(renderTable(tableLines, codeFontSize))
        }
        if (inBlockquote) {
            sb.append(renderBlockquote(blockquoteLines, codeFontSize))
        }
        if (inIndentedCode) {
            sb.append(renderCodeBlock(indentedCodeBuffer.toString().trimEnd(), "", codeFontSize))
        }

        sb.append("</body></html>")
        return sb.toString()
    }

    private fun renderCodeBlock(code: String, language: String, codeFontSize: Int): String {
        val bgColor = if (JBColor.isBright()) "#f6f8fa" else "#1e1e1e"
        val textColor = if (JBColor.isBright()) "#24292e" else "#d4d4d4"
        return """
            <div style='background: $bgColor; padding: 8px; margin: 4px 0;'>
                <pre style='font-family: monospace; font-size: ${codeFontSize}pt; color: $textColor; margin: 0; white-space: pre-wrap; word-wrap: break-word;'>${escapeHtml(code)}</pre>
            </div>
        """.trimIndent()
    }

    private fun renderBlockquote(lines: List<String>, codeFontSize: Int): String {
        val borderColor = if (JBColor.isBright()) "#d0d7de" else "#555555"
        val sb = StringBuilder()
        var currentDepth = 0

        for (line in lines) {
            var content = line.trimStart()
            var depth = 0
            while (content.startsWith(">")) {
                depth++
                content = content.removePrefix(">").trimStart()
            }
            depth = maxOf(depth, 1)

            while (currentDepth < depth) {
                sb.append(
                    "<div style='margin: 2px 0 2px 4px; padding-left: 8px; " +
                        "border-left-width: 3px; border-left-style: solid; border-left-color: $borderColor;'>",
                )
                currentDepth++
            }
            while (currentDepth > depth) {
                sb.append("</div>")
                currentDepth--
            }

            if (content.isBlank()) {
                sb.append("<div style='margin: 0; padding: 0; line-height: 6px;'>&nbsp;</div>")
            } else {
                sb.append(
                    "<p style='margin: 2px 0; word-wrap: break-word; overflow-wrap: anywhere;'>" +
                        "${renderInline(content, codeFontSize)}</p>",
                )
            }
        }

        while (currentDepth > 0) {
            sb.append("</div>")
            currentDepth--
        }

        return sb.toString()
    }

    private fun isListItem(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") ||
            Regex("^\\d+\\.\\s").containsMatchIn(trimmed)
    }

    private fun renderListItem(line: String, codeFontSize: Int): String {
        val indent = line.length - line.trimStart().length
        val marginLeft = 16 + (indent / 2) * 16
        val trimmed = line.trimStart()
        val breakStyle = "word-wrap: break-word; overflow-wrap: anywhere;"

        val taskMatch = Regex("^[-*+]\\s+\\[([ xX])\\]\\s+(.*)$").find(trimmed)
        if (taskMatch != null) {
            val checked = taskMatch.groupValues[1].lowercase() == "x"
            val content = taskMatch.groupValues[2]
            val checkbox = if (checked) "&#9745;" else "&#9744;"
            return "<p style='margin: 2px 0 2px ${marginLeft}px; $breakStyle'>$checkbox ${renderInline(content, codeFontSize)}</p>"
        }

        val orderedMatch = Regex("^(\\d+)\\.\\s+(.*)$").find(trimmed)
        if (orderedMatch != null) {
            val number = orderedMatch.groupValues[1]
            val content = orderedMatch.groupValues[2]
            return "<p style='margin: 2px 0 2px ${marginLeft}px; $breakStyle'>$number. ${renderInline(content, codeFontSize)}</p>"
        }

        val content = when {
            trimmed.startsWith("- ") -> trimmed.removePrefix("- ")
            trimmed.startsWith("* ") -> trimmed.removePrefix("* ")
            trimmed.startsWith("+ ") -> trimmed.removePrefix("+ ")
            else -> trimmed
        }
        return "<p style='margin: 2px 0 2px ${marginLeft}px; $breakStyle'>&bull; ${renderInline(content, codeFontSize)}</p>"
    }

    private fun isHorizontalRule(line: String): Boolean {
        val stripped = line.trim().replace(" ", "")
        return stripped.length >= 3 &&
            (stripped.all { it == '-' } || stripped.all { it == '*' } || stripped.all { it == '_' })
    }

    private fun isTableLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("|") && trimmed.count { it == '|' } >= 2
    }

    private fun isTableSeparator(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.startsWith("|")) return false
        val inner = trimmed.trim('|')
        return inner.isNotBlank() && inner.all { it == '-' || it == ':' || it == '|' || it == ' ' }
    }

    private fun renderTable(lines: List<String>, codeFontSize: Int): String {
        val separatorIndex = lines.indexOfFirst { isTableSeparator(it) }
        val headerRows = if (separatorIndex > 0) lines.subList(0, separatorIndex) else emptyList()
        val bodyStartIndex = if (separatorIndex >= 0) separatorIndex + 1 else 0
        val bodyRows = lines.subList(bodyStartIndex, lines.size)

        fun parseCells(row: String): List<String> {
            return row.trim().trim('|').split("|").map { it.trim() }
        }

        val borderColor = if (JBColor.isBright()) "#d0d7de" else "#444444"
        val headerBgColor = if (JBColor.isBright()) "#f0f0f0" else "#383838"

        val sb = StringBuilder()
        sb.append("<table style='border-collapse: collapse; margin: 4px 0;'>")

        if (headerRows.isNotEmpty()) {
            sb.append("<thead>")
            for (row in headerRows) {
                sb.append("<tr>")
                for (cell in parseCells(row)) {
                    sb.append(
                        "<th style='border: 1px solid $borderColor; padding: 4px 8px; background: $headerBgColor; text-align: left; font-weight: bold;'>",
                    )
                    sb.append(renderInline(cell, codeFontSize))
                    sb.append("</th>")
                }
                sb.append("</tr>")
            }
            sb.append("</thead>")
        }

        if (bodyRows.isNotEmpty()) {
            sb.append("<tbody>")
            for (row in bodyRows) {
                if (row.isBlank() || isTableSeparator(row)) continue
                sb.append("<tr>")
                for (cell in parseCells(row)) {
                    sb.append("<td style='border: 1px solid $borderColor; padding: 4px 8px;'>")
                    sb.append(renderInline(cell, codeFontSize))
                    sb.append("</td>")
                }
                sb.append("</tr>")
            }
            sb.append("</tbody>")
        }

        sb.append("</table>")
        return sb.toString()
    }

    private val EMOJI_MAP = mapOf(
        "smile" to "\uD83D\uDE04", "laughing" to "\uD83D\uDE06", "blush" to "\uD83D\uDE0A",
        "smiley" to "\uD83D\uDE03", "grinning" to "\uD83D\uDE00", "wink" to "\uD83D\uDE09",
        "joy" to "\uD83D\uDE02", "rofl" to "\uD83E\uDD23", "thinking" to "\uD83E\uDD14",
        "sunglasses" to "\uD83D\uDE0E", "nerd_face" to "\uD83E\uDD13", "skull" to "\uD83D\uDC80",
        "eyes" to "\uD83D\uDC40", "brain" to "\uD83E\uDDE0",
        "+1" to "\uD83D\uDC4D", "thumbsup" to "\uD83D\uDC4D",
        "-1" to "\uD83D\uDC4E", "thumbsdown" to "\uD83D\uDC4E",
        "clap" to "\uD83D\uDC4F", "wave" to "\uD83D\uDC4B", "pray" to "\uD83D\uDE4F",
        "handshake" to "\uD83E\uDD1D", "muscle" to "\uD83D\uDCAA",
        "point_up" to "\u261D\uFE0F", "point_down" to "\uD83D\uDC47",
        "point_left" to "\uD83D\uDC48", "point_right" to "\uD83D\uDC49",
        "raised_hands" to "\uD83D\uDE4C",
        "heart" to "\u2764\uFE0F", "broken_heart" to "\uD83D\uDC94",
        "fire" to "\uD83D\uDD25", "star" to "\u2B50", "star2" to "\uD83C\uDF1F",
        "sparkles" to "\u2728", "zap" to "\u26A1", "boom" to "\uD83D\uDCA5",
        "bulb" to "\uD83D\uDCA1", "rocket" to "\uD83D\uDE80", "tada" to "\uD83C\uDF89",
        "trophy" to "\uD83C\uDFC6", "crown" to "\uD83D\uDC51", "gem" to "\uD83D\uDC8E",
        "bell" to "\uD83D\uDD14", "balloon" to "\uD83C\uDF88", "gift" to "\uD83C\uDF81",
        "white_check_mark" to "\u2705", "heavy_check_mark" to "\u2714\uFE0F",
        "x" to "\u274C", "warning" to "\u26A0\uFE0F", "no_entry" to "\u26D4",
        "question" to "\u2753", "exclamation" to "\u2757",
        "heavy_exclamation_mark" to "\u2757",
        "information_source" to "\u2139\uFE0F",
        "arrow_right" to "\u27A1\uFE0F", "arrow_left" to "\u2B05\uFE0F",
        "arrow_up" to "\u2B06\uFE0F", "arrow_down" to "\u2B07\uFE0F",
        "recycle" to "\u267B\uFE0F",
        "wrench" to "\uD83D\uDD27", "hammer" to "\uD83D\uDD28", "gear" to "\u2699\uFE0F",
        "key" to "\uD83D\uDD11", "lock" to "\uD83D\uDD12", "unlock" to "\uD83D\uDD13",
        "link" to "\uD83D\uDD17", "pushpin" to "\uD83D\uDCCC", "paperclip" to "\uD83D\uDCCE",
        "pencil" to "\u270F\uFE0F", "pencil2" to "\u270F\uFE0F", "memo" to "\uD83D\uDCDD",
        "book" to "\uD83D\uDCD6", "books" to "\uD83D\uDCDA", "clipboard" to "\uD83D\uDCCB",
        "calendar" to "\uD83D\uDCC5", "package" to "\uD83D\uDCE6",
        "chart_with_upwards_trend" to "\uD83D\uDCC8",
        "chart_with_downwards_trend" to "\uD83D\uDCC9",
        "computer" to "\uD83D\uDCBB", "keyboard" to "\u2328\uFE0F",
        "desktop_computer" to "\uD83D\uDDA5\uFE0F", "floppy_disk" to "\uD83D\uDCBE",
        "robot" to "\uD83E\uDD16",
        "bug" to "\uD83D\uDC1B", "seedling" to "\uD83C\uDF31", "rainbow" to "\uD83C\uDF08",
        "sunny" to "\u2600\uFE0F", "cloud" to "\u2601\uFE0F", "snowflake" to "\u2744\uFE0F",
        "coffee" to "\u2615", "pizza" to "\uD83C\uDF55", "beer" to "\uD83C\uDF7A",
        "cake" to "\uD83C\uDF82", "moneybag" to "\uD83D\uDCB0",
        "100" to "\uD83D\uDCAF", "poop" to "\uD83D\uDCA9",
    )

    private fun renderInline(text: String, codeFontSize: Int): String {
        var result = escapeHtml(text)

        result = Regex(":([\\w+-]+):").replace(result) { m ->
            EMOJI_MAP[m.groupValues[1]] ?: m.value
        }

        val codeBgColor = if (JBColor.isBright()) "#f0f0f0" else "#3c3c3c"
        val codeStyle = "background: $codeBgColor; padding: 1px 4px; font-family: monospace; font-size: ${codeFontSize}pt;"

        result = Regex("``(.+?)``").replace(result) { match ->
            "<code style='$codeStyle'>${match.groupValues[1].trim()}</code>"
        }
        result = Regex("`([^`]+)`").replace(result) { match ->
            "<code style='$codeStyle'>${match.groupValues[1]}</code>"
        }

        result = Regex("~~(.+?)~~").replace(result) { m ->
            "<strike>${m.groupValues[1]}</strike>"
        }
        result = Regex("\\*\\*(.+?)\\*\\*").replace(result) { m ->
            "<span style=\"font-weight: bold;\">${m.groupValues[1]}</span>"
        }
        result = Regex("__(.+?)__").replace(result) { m ->
            "<span style=\"font-weight: bold;\">${m.groupValues[1]}</span>"
        }
        result = Regex("\\*(.+?)\\*").replace(result) { m ->
            "<i>${m.groupValues[1]}</i>"
        }
        result = Regex("_(.+?)_").replace(result) { m ->
            "<i>${m.groupValues[1]}</i>"
        }

        result = Regex("!\\[(.*?)]\\(([^)]+)\\)").replace(result) { match ->
            val alt = match.groupValues[1]
            val src = match.groupValues[2].trim().split("\\s+".toRegex(), limit = 2)[0]
            "<img src='$src' alt='$alt' style='max-width: 100%;'>"
        }

        result = Regex("\\[(.+?)]\\((.+?)\\)").replace(result) { match ->
            "<a href='${match.groupValues[2]}'>${match.groupValues[1]}</a>"
        }

        result = Regex("&lt;(https?://.*?)&gt;").replace(result) { m ->
            "<a href='${m.groupValues[1]}'>${m.groupValues[1]}</a>"
        }

        result = Regex("(?<![='\">/])(https?://[^\\s<>]+)").replace(result) { m ->
            val url = m.groupValues[1].replace(Regex("[.,;:!]+$"), "")
            val trailing = m.groupValues[1].removePrefix(url)
            "<a href='$url'>$url</a>$trailing"
        }

        return result
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
