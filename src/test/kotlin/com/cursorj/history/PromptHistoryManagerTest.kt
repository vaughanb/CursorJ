package com.cursorj.history

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptHistoryManagerTest {
    @Test
    fun `previous and next navigate history and restore draft`() {
        val workspace = Files.createTempDirectory("cursorj-prompt-history-nav").toFile()
        try {
            val manager = PromptHistoryManager(PromptHistoryStore(workspace.absolutePath))
            manager.load()

            manager.addPrompt("session:one", "first prompt")
            manager.addPrompt("session:one", "second prompt")

            assertEquals("second prompt", manager.previous("session:one", "draft prompt"))
            assertEquals("first prompt", manager.previous("session:one", "ignored"))
            assertEquals("first prompt", manager.previous("session:one", "ignored"))

            assertEquals("second prompt", manager.next("session:one", "ignored"))
            assertEquals("draft prompt", manager.next("session:one", "ignored"))
            assertNull(manager.next("session:one", "ignored"))
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `addPrompt ignores blank and immediate duplicate entries`() {
        val workspace = Files.createTempDirectory("cursorj-prompt-history-dedup").toFile()
        try {
            val manager = PromptHistoryManager(PromptHistoryStore(workspace.absolutePath))
            manager.load()

            assertFalse(manager.addPrompt("session:one", ""))
            assertFalse(manager.addPrompt("session:one", "   "))
            assertTrue(manager.addPrompt("session:one", "first"))
            assertFalse(manager.addPrompt("session:one", "first"))
            assertTrue(manager.addPrompt("session:one", "second"))
            assertTrue(manager.addPrompt("session:one", "first"))

            assertEquals(listOf("first", "second", "first"), manager.historyFor("session:one"))
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `history is capped to max entries per session`() {
        val workspace = Files.createTempDirectory("cursorj-prompt-history-cap").toFile()
        try {
            val manager = PromptHistoryManager(
                store = PromptHistoryStore(workspace.absolutePath),
                maxEntriesPerSession = 3,
            )
            manager.load()

            manager.addPrompt("session:one", "one")
            manager.addPrompt("session:one", "two")
            manager.addPrompt("session:one", "three")
            manager.addPrompt("session:one", "four")

            assertEquals(listOf("two", "three", "four"), manager.historyFor("session:one"))
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `migrateSessionKey moves history and persists merged results`() {
        val workspace = Files.createTempDirectory("cursorj-prompt-history-migrate").toFile()
        try {
            val first = PromptHistoryManager(PromptHistoryStore(workspace.absolutePath))
            first.load()
            first.addPrompt("tab:temp", "first")
            first.addPrompt("tab:temp", "second")
            first.addPrompt("session:new", "third")

            assertTrue(first.migrateSessionKey("tab:temp", "session:new"))
            assertEquals(emptyList(), first.historyFor("tab:temp"))
            assertEquals(listOf("first", "second", "third"), first.historyFor("session:new"))

            val second = PromptHistoryManager(PromptHistoryStore(workspace.absolutePath))
            second.load()
            assertEquals(emptyList(), second.historyFor("tab:temp"))
            assertEquals(listOf("first", "second", "third"), second.historyFor("session:new"))
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `isEmpty returns true before any prompts are added`() {
        val workspace = Files.createTempDirectory("cursorj-prompt-history-manager-empty").toFile()
        try {
            val manager = PromptHistoryManager(PromptHistoryStore(workspace.absolutePath))
            manager.load()
            assertTrue(manager.isEmpty())
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `isEmpty returns false after adding a prompt`() {
        val workspace = Files.createTempDirectory("cursorj-prompt-history-manager-notempty").toFile()
        try {
            val manager = PromptHistoryManager(PromptHistoryStore(workspace.absolutePath))
            manager.load()
            manager.addPrompt("session:one", "hello")
            assertFalse(manager.isEmpty())
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `isEmpty returns true when loaded from missing file`() {
        val workspace = Files.createTempDirectory("cursorj-prompt-history-manager-no-file").toFile()
        try {
            val manager = PromptHistoryManager(PromptHistoryStore(workspace.absolutePath))
            manager.load()
            assertTrue(manager.isEmpty())
        } finally {
            workspace.deleteRecursively()
        }
    }
}
