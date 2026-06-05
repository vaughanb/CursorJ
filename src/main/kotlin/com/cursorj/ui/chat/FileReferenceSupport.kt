package com.cursorj.ui.chat

/**
 * Parses `@file` references in chat input text. Quoted form supports paths with spaces.
 */
object FileReferenceSupport {
    /** Matches `@path/to/file.ext` or `@"path with spaces.go"`. */
    val referenceRegex: Regex = Regex("""@(?:"([^"]+)"|([^\s@]+))""")

    data class ReferenceSpan(val start: Int, val end: Int, val path: String)

    fun extractPath(match: MatchResult): String {
        val raw = match.groupValues[1].takeIf { it.isNotEmpty() } ?: match.groupValues[2]
        return normalizeReferencePath(raw)
    }

    fun findValidSpans(text: String, isValidPath: (String) -> Boolean): List<ReferenceSpan> {
        return referenceRegex.findAll(text).mapNotNull { match ->
            val path = extractPath(match)
            if (isValidPath(path)) {
                ReferenceSpan(match.range.first, match.range.last + 1, path)
            } else {
                null
            }
        }.toList()
    }

    fun spanContaining(spans: List<ReferenceSpan>, offset: Int, includeEnd: Boolean = false): ReferenceSpan? {
        return spans.firstOrNull { span ->
            if (includeEnd) {
                offset in span.start..span.end
            } else {
                offset in span.start until span.end
            }
        }
    }

    /**
     * Trims trailing sentence punctuation that may follow an unquoted reference
     * (e.g. `@main.go,` at the end of a clause).
     */
    fun normalizeReferencePath(raw: String): String {
        return raw.trimEnd(',', '.', ';', ':', '!', '?', ')')
    }
}
