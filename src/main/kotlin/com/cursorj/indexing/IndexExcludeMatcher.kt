package com.cursorj.indexing

object IndexExcludeMatcher {
    fun isPathExcluded(path: String, excludePatterns: String): Boolean {
        if (excludePatterns.isBlank()) return false
        val normalizedPath = path.replace('\\', '/')
        return excludePatterns.split(',')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any { glob ->
                runCatching { globToRegex(glob).matches(normalizedPath) }.getOrDefault(false)
            }
    }

    private fun globToRegex(glob: String): Regex {
        val normalized = glob.replace('\\', '/').trim()
        val isAbsolutePattern = normalized.startsWith("/")
        val regexString = buildString {
            if (!isAbsolutePattern) {
                append("(^|.*/)")
            } else {
                append("^")
            }
            var i = 0
            val limit = normalized.length
            while (i < limit) {
                val c = normalized[i]
                when (c) {
                    '*' -> {
                        if (i + 1 < limit && normalized[i + 1] == '*') {
                            append(".*")
                            i++
                            if (i + 1 < limit && normalized[i + 1] == '/') {
                                i++
                            }
                        } else {
                            append("[^/]*")
                        }
                    }
                    '?' -> append("[^/]")
                    '.', '(', ')', '[', ']', '{', '}', '+', '$', '^', '|', '\\' -> {
                        append("\\").append(c)
                    }
                    else -> append(c)
                }
                i++
            }
            append("($|/.*)")
        }
        return Regex(regexString, RegexOption.IGNORE_CASE)
    }
}
