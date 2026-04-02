package com.cursorj.acp

import java.io.File
import java.net.URI

/**
 * Parses plan file locations from agent tool-call status text (e.g. session/update tool_call_update).
 *
 * Example: `Plan saved to file:///C:/Users/x/.cursor/plans/App-name.plan.md`
 */
object AgentPlanFileSupport {

    /** JVM may emit `file:/C:/...` or `file:///C:/...` after `Plan saved to `. */
    private val PLAN_SAVED_TO_FILE = Regex("""(?i)plan\s+saved\s+to\s+(file:\S+)""")

    /**
     * Returns a normalized absolute path (`/`), or null if [text] does not contain a recognizable
     * `file:` URI (e.g. not a "plan saved" message).
     */
    fun parseLocalPathFromToolCallStatusText(text: String): String? {
        val m = PLAN_SAVED_TO_FILE.find(text.trim()) ?: return null
        return fileUriToNormalizedPath(m.groupValues[1])
    }

    /**
     * True when [path] looks like a Cursor agent plan markdown file under the user's `.cursor/plans/`
     * directory (including edits delivered as `tool_call_update` diff `path` values).
     */
    fun isCursorAgentPlanMarkdownPath(path: String): Boolean {
        val n = path.replace('\\', '/')
        if (!n.endsWith(".md", ignoreCase = true)) return false
        return n.contains("/.cursor/plans/", ignoreCase = true)
    }

    private fun fileUriToNormalizedPath(uriString: String): String? {
        return try {
            val uri = URI(uriString)
            if (uri.scheme != "file") null
            else File(uri).canonicalFile.absolutePath.replace('\\', '/')
        } catch (_: Exception) {
            null
        }
    }
}
