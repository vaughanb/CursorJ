package com.cursorj.acp

/**
 * A [cursor/task](https://cursor.com/docs/cli/acp) subagent notification parsed for UI and logging.
 */
data class SubagentTaskEvent(
    val toolCallId: String,
    val description: String,
    /** Truncated prompt excerpt for tooltips / debug; full prompt is not stored. */
    val promptSummary: String?,
    val subagentTypeLabel: String,
    val model: String?,
    val agentId: String?,
    /** When set, the agent reports the task finished and ran for this long. */
    val durationMs: Long?,
    val receivedAtEpochMs: Long = System.currentTimeMillis(),
) {
    val isComplete: Boolean get() = durationMs != null
}
