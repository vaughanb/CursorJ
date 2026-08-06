package com.cursorj.settings

/**
 * Parses a minimal subset of YAML from the first `---` / `---` frontmatter block in a markdown skill file.
 */
object SkillFrontmatterParser {

    data class Result(
        val name: String?,
        val description: String?,
        val paths: List<String>,
        val disableModelInvocation: Boolean,
    )

    fun parse(markdown: String): Result {
        val body = extractFrontmatterBody(markdown) ?: return Result(null, null, emptyList(), false)
        return parseYamlLikeBlock(body)
    }

    private fun extractFrontmatterBody(text: String): String? {
        val lines = text.lines()
        if (lines.isEmpty() || lines.first().trim() != "---") {
            return null
        }
        val buf = StringBuilder()
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") {
                return buf.toString().trimEnd()
            }
            buf.append(lines[i]).append('\n')
        }
        return null
    }

    private fun parseYamlLikeBlock(block: String): Result {
        var name: String? = null
        var description: String? = null
        val paths = mutableListOf<String>()
        var disableModelInvocation = false
        var inPathsList = false

        for (rawLine in block.lines()) {
            val line = rawLine.trimEnd()
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (inPathsList) {
                if (trimmed.startsWith("-")) {
                    val item = trimmed.removePrefix("-").trim().trim('"', '\'')
                    if (item.isNotEmpty()) paths.add(item)
                    continue
                }
                if (!trimmed.startsWith("#") && trimmed.contains(":")) {
                    inPathsList = false
                } else {
                    continue
                }
            }

            if (!trimmed.startsWith("#") && trimmed.contains(":")) {
                val colon = trimmed.indexOf(':')
                val key = trimmed.substring(0, colon).trim().lowercase()
                val valuePart = trimmed.substring(colon + 1).trim()

                when (key) {
                    "name" -> name = unquote(valuePart).takeIf { it.isNotBlank() }
                    "description" -> description = unquote(valuePart).takeIf { it.isNotBlank() }
                    "disable-model-invocation", "disable_model_invocation" -> {
                        disableModelInvocation = parseBool(valuePart)
                    }
                    "paths" -> {
                        if (valuePart.isEmpty() || valuePart == "|" || valuePart == ">") {
                            inPathsList = true
                        } else {
                            paths.addAll(splitPaths(valuePart))
                        }
                    }
                }
            }
        }

        return Result(name, description, paths, disableModelInvocation)
    }

    private fun unquote(s: String): String {
        val t = s.trim()
        if (t.length >= 2) {
            if ((t.startsWith('"') && t.endsWith('"')) || (t.startsWith('\'') && t.endsWith('\''))) {
                return t.substring(1, t.length - 1)
            }
        }
        return t
    }

    private fun parseBool(s: String): Boolean {
        val v = s.trim().lowercase()
        return v == "true" || v == "yes" || v == "1"
    }

    private fun splitPaths(valuePart: String): List<String> {
        if (valuePart.isBlank()) return emptyList()
        return valuePart.split(',')
            .map { unquote(it.trim()) }
            .filter { it.isNotBlank() }
    }
}
