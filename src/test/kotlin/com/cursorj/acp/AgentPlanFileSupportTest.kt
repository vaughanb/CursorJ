package com.cursorj.acp

import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentPlanFileSupportTest {

    @Test
    fun `parses plan saved to file URI from tool status text`() {
        val tmp = createTempFile("cursorj-plan-", ".plan.md").toFile()
        try {
            val uri = tmp.toURI().toString()
            val text = "Plan saved to $uri"
            val path = AgentPlanFileSupport.parseLocalPathFromToolCallStatusText(text)
            assertEquals(tmp.canonicalFile.absolutePath.replace('\\', '/'), path?.replace('\\', '/'))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `returns null when no plan saved line`() {
        assertNull(AgentPlanFileSupport.parseLocalPathFromToolCallStatusText("Doing something else"))
    }

    @Test
    fun `detects cursor plans markdown path`() {
        assertEquals(
            true,
            AgentPlanFileSupport.isCursorAgentPlanMarkdownPath("C:\\Users\\x\\.cursor\\plans\\App-e6.plan.md"),
        )
        assertEquals(
            true,
            AgentPlanFileSupport.isCursorAgentPlanMarkdownPath("C:/Users/x/.cursor/plans/App.plan.md"),
        )
        assertEquals(false, AgentPlanFileSupport.isCursorAgentPlanMarkdownPath("C:/project/src/Main.kt"))
        assertEquals(false, AgentPlanFileSupport.isCursorAgentPlanMarkdownPath("C:/Users/x/.cursor/plans/readme.txt"))
    }

    @Test
    fun `detects cursor plans path case insensitively`() {
        assertEquals(
            true,
            AgentPlanFileSupport.isCursorAgentPlanMarkdownPath("D:/x/.CURSOR/PLANS/Plan.MD"),
        )
    }

    @Test
    fun `parses plan saved case insensitively on keyword`() {
        val tmp = createTempFile("cursorj-plan-", ".md").toFile()
        try {
            val uri = tmp.toURI().toString()
            val path = AgentPlanFileSupport.parseLocalPathFromToolCallStatusText("PLAN SAVED TO $uri")
            assertEquals(tmp.canonicalFile.absolutePath.replace('\\', '/'), path?.replace('\\', '/'))
        } finally {
            tmp.delete()
        }
    }
}
