package com.cursorj.ui.chat

/** ParsedOption is a single selectable choice within a [ParsedQuestion]. */
data class ParsedOption(
    val marker: String,
    val label: String,
)

/** ParsedQuestion is a multiple-choice question recovered from agent plan-mode prose. */
data class ParsedQuestion(
    val prompt: String?,
    val options: List<ParsedOption>,
)

/**
 * PlanQuestionParser recovers multiple-choice questions that plan-mode agents emit as plain
 * markdown text (e.g. `A) ...`, `1) ...`) so the chat UI can render them as selectable options.
 *
 * Current `cursor-agent` ACP builds do not expose the structured `ask_question` tool over the
 * protocol, so clarifying questions arrive as prose. This parser is a best-effort fallback and is
 * intentionally conservative: it only treats a run of lines as options when the markers form a
 * consecutive sequence (A/B/C… or 1/2/3…) and there is a question-like prompt or selection cue,
 * which avoids turning ordinary lettered/numbered lists into fake buttons.
 */
object PlanQuestionParser {
    private val optionLineRegex = Regex(
        """^\s*(?:[-*+]\s+)?\**\s*([A-Za-z]|\d{1,2})\s*\**\s*[.)]\s*\**\s*(\S.*?)\s*$""",
    )

    private val selectionCues = listOf(
        "reply with",
        "pick one",
        "pick a",
        "choose one",
        "select one",
        "rank them",
        "do you prefer",
        "would you prefer",
        "which option",
        "let me know",
        "your call",
    )

    private const val INDENT_TOLERANCE = 1
    private const val MAX_PROMPT_LOOKBACK = 3

    /** Parses [text] into the multiple-choice questions it contains, or an empty list when none are found. */
    fun parse(text: String): List<ParsedQuestion> {
        if (text.isBlank()) return emptyList()
        val lines = text.lines()
        val hasCue = selectionCues.any { text.contains(it, ignoreCase = true) }
        val questions = mutableListOf<ParsedQuestion>()

        var i = 0
        while (i < lines.size) {
            val first = matchOption(lines[i])
            if (first == null) {
                i++
                continue
            }

            val baseIndent = indentOf(lines[i])
            val run = mutableListOf(first)
            var lastConsumed = i
            var j = i + 1
            while (j < lines.size) {
                val line = lines[j]
                if (line.isBlank()) {
                    j++
                    continue
                }
                val indent = indentOf(line)
                val option = matchOption(line)
                if (option != null &&
                    indent <= baseIndent + INDENT_TOLERANCE &&
                    isNextMarker(run.last().marker, option.marker)
                ) {
                    run.add(option)
                    lastConsumed = j
                    j++
                } else if (indent > baseIndent) {
                    j++
                } else {
                    break
                }
            }

            if (isValidOptionRun(run)) {
                val prompt = findPrompt(lines, i)
                if (isLetterRun(run) || hasCue || promptLooksLikeQuestion(prompt)) {
                    questions.add(ParsedQuestion(prompt = prompt, options = run.toList()))
                }
            }
            i = lastConsumed + 1
        }

        return questions
    }

    private fun matchOption(line: String): ParsedOption? {
        val match = optionLineRegex.find(line) ?: return null
        val marker = match.groupValues[1]
        val label = stripInlineMarkdown(match.groupValues[2])
        if (label.isBlank()) return null
        return ParsedOption(marker = marker, label = label)
    }

    private fun isValidOptionRun(run: List<ParsedOption>): Boolean {
        if (run.size < 2) return false
        return startsAtSequenceHead(run.first().marker)
    }

    private fun startsAtSequenceHead(marker: String): Boolean {
        if (marker.equals("a", ignoreCase = true)) return true
        return marker == "1"
    }

    private fun isLetterRun(run: List<ParsedOption>): Boolean =
        run.all { it.marker.length == 1 && it.marker[0].isLetter() }

    private fun isNextMarker(previous: String, current: String): Boolean {
        if (previous.length == 1 && previous[0].isLetter() && current.length == 1 && current[0].isLetter()) {
            return current.lowercaseChar() == previous.lowercaseChar() + 1
        }
        val prevNum = previous.toIntOrNull()
        val curNum = current.toIntOrNull()
        if (prevNum != null && curNum != null) {
            return curNum == prevNum + 1
        }
        return false
    }

    private fun String.lowercaseChar(): Char = this[0].lowercaseChar()

    private fun findPrompt(lines: List<String>, runStart: Int): String? {
        var seen = 0
        var idx = runStart - 1
        while (idx >= 0 && seen < MAX_PROMPT_LOOKBACK) {
            val line = lines[idx]
            if (line.isBlank()) {
                idx--
                continue
            }
            seen++
            if (matchOption(line) == null) {
                val cleaned = stripInlineMarkdown(line.trim().removePrefix("-").removePrefix("*").trim())
                return cleaned.takeIf { it.isNotBlank() }
            }
            idx--
        }
        return null
    }

    private fun promptLooksLikeQuestion(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        if (prompt.trimEnd().endsWith("?")) return true
        return selectionCues.any { prompt.contains(it, ignoreCase = true) }
    }

    private fun stripInlineMarkdown(raw: String): String =
        raw.replace("**", "")
            .replace("`", "")
            .trim()
            .trim('*')
            .trim()

    private fun indentOf(line: String): Int = line.takeWhile { it == ' ' || it == '\t' }.length
}
