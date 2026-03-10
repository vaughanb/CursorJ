package com.cursorj.settings

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CursorJSettingsTest {
    @Test
    fun `clamps retrieval and persistence limits`() {
        val settings = CursorJSettings()

        settings.retrievalMaxCandidates = 999
        settings.retrievalSnippetCharBudget = 999_999
        settings.retrievalTimeoutMs = 10
        settings.indexRetentionDays = 0
        settings.indexMaxDatabaseMb = 999_999

        assertEquals(200, settings.retrievalMaxCandidates)
        assertEquals(30_000, settings.retrievalSnippetCharBudget)
        assertEquals(250, settings.retrievalTimeoutMs)
        assertEquals(1, settings.indexRetentionDays)
        assertEquals(4096, settings.indexMaxDatabaseMb)
    }

    @Test
    fun `supports lexical persistence and status toggles`() {
        val settings = CursorJSettings()
        settings.enableLexicalPersistence = false
        settings.showIndexingStatusInChat = false

        assertFalse(settings.enableLexicalPersistence)
        assertFalse(settings.showIndexingStatusInChat)

        settings.enableLexicalPersistence = true
        settings.showIndexingStatusInChat = true
        assertTrue(settings.enableLexicalPersistence)
        assertTrue(settings.showIndexingStatusInChat)
    }

    @Test
    fun `approved permission keys are normalized and sorted`() {
        val settings = CursorJSettings()
        settings.setApprovedPermissionKeys(setOf("  shell:npm  ", "fs/read_text_file", "shell:npm"))
        assertEquals(listOf("fs/read_text_file", "shell:npm"), settings.getApprovedPermissionKeys().sorted())
    }

    @Test
    fun `effective agent path uses configured path when valid`() {
        val settings = CursorJSettings()
        val dir = Files.createTempDirectory("cursorj-settings-agent").toFile()
        try {
            val file = dir.resolve(if (isWindows()) "agent.cmd" else "agent")
            file.writeText("@echo off")
            if (!isWindows()) {
                file.setExecutable(true)
            }
            settings.agentPath = file.absolutePath
            assertNotNull(settings.effectiveAgentPath)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `default model supports empty and explicit values`() {
        val settings = CursorJSettings()
        assertEquals("", settings.defaultModel)
        settings.defaultModel = ""
        assertEquals("", settings.defaultModel)
        settings.defaultModel = "gpt-5"
        assertEquals("gpt-5", settings.defaultModel)
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name").lowercase().contains("win")
    }
}
