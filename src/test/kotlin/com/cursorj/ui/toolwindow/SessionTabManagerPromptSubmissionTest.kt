package com.cursorj.ui.toolwindow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTabManagerPromptSubmissionTest {
    @Test
    fun `default title prompt renames tab and records session`() {
        val result = SessionTabManager.resolvePromptSubmission(
            currentTitle = "New Chat",
            defaultTitle = "New Chat",
            prompt = "please fix login flow",
            sessionId = "session-1",
            heuristicTitleBuilder = { "Fix Login Flow" },
        )

        assertTrue(result.shouldRenameTab)
        assertEquals("Fix Login Flow", result.titleToPersist)
        assertEquals("session-1", result.sessionIdToRecord)
    }

    @Test
    fun `custom title prompt keeps title and still records session`() {
        var builderInvoked = false
        val result = SessionTabManager.resolvePromptSubmission(
            currentTitle = "My Existing Title",
            defaultTitle = "New Chat",
            prompt = "anything",
            sessionId = "session-2",
            heuristicTitleBuilder = {
                builderInvoked = true
                "Should Not Be Used"
            },
        )

        assertFalse(result.shouldRenameTab)
        assertEquals("My Existing Title", result.titleToPersist)
        assertEquals("session-2", result.sessionIdToRecord)
        assertFalse(builderInvoked)
    }

    @Test
    fun `default title prompt without session id renames tab but skips history record`() {
        val result = SessionTabManager.resolvePromptSubmission(
            currentTitle = "New Chat",
            defaultTitle = "New Chat",
            prompt = "refactor auth middleware",
            sessionId = "   ",
            heuristicTitleBuilder = { "Refactor Auth Middleware" },
        )

        assertTrue(result.shouldRenameTab)
        assertEquals("Refactor Auth Middleware", result.titleToPersist)
        assertNull(result.sessionIdToRecord)
    }

    @Test
    fun `blank heuristic title falls back to default and trims session id`() {
        val result = SessionTabManager.resolvePromptSubmission(
            currentTitle = "New Chat",
            defaultTitle = "New Chat",
            prompt = "prompt text",
            sessionId = "  session-3  ",
            heuristicTitleBuilder = { "" },
        )

        assertTrue(result.shouldRenameTab)
        assertEquals("New Chat", result.titleToPersist)
        assertEquals("session-3", result.sessionIdToRecord)
    }

    @Test
    fun `normalizeTabTitleInput trims and collapses whitespace`() {
        val normalized = SessionTabManager.normalizeTabTitleInput("   API    cleanup   plan   ")

        assertEquals("API cleanup plan", normalized)
    }

    @Test
    fun `normalizeTabTitleInput rejects blank values`() {
        assertNull(SessionTabManager.normalizeTabTitleInput("   "))
    }

    @Test
    fun `resolveHistorySessionId prefers live session id`() {
        val resolved = SessionTabManager.resolveHistorySessionId(
            sessionId = "  session-live  ",
            historyKey = "session:session-fallback",
        )

        assertEquals("session-live", resolved)
    }

    @Test
    fun `resolveHistorySessionId falls back to history key and rejects non session keys`() {
        val fromHistory = SessionTabManager.resolveHistorySessionId(
            sessionId = null,
            historyKey = "session:session-from-history",
        )
        val fromNonSessionKey = SessionTabManager.resolveHistorySessionId(
            sessionId = null,
            historyKey = "tab-123",
        )

        assertEquals("session-from-history", fromHistory)
        assertNull(fromNonSessionKey)
    }
}
