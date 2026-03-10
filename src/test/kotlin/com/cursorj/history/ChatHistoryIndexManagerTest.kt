package com.cursorj.history

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatHistoryIndexManagerTest {

    private fun testClock(): Pair<AtomicLong, () -> Long> {
        val time = AtomicLong(1000L)
        return time to { time.getAndAdd(100) }
    }

    @Test
    fun `recordSession creates a new entry and persists it`() {
        val workspace = Files.createTempDirectory("cursorj-chat-history-mgr-record").toFile()
        try {
            val (_, clock) = testClock()
            val manager = ChatHistoryIndexManager(ChatHistoryStore(workspace.absolutePath), clock = clock)
            manager.load()

            assertTrue(manager.recordSession("abc-123", "First Chat"))
            assertEquals(1, manager.entryCount())

            val entries = manager.listAll()
            assertEquals(1, entries.size)
            assertEquals("abc-123", entries[0].sessionId)
            assertEquals("First Chat", entries[0].title)
            assertTrue(entries[0].createdAt > 0)
            assertTrue(entries[0].lastActivityAt >= entries[0].createdAt)
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `recordSession updates existing entry`() {
        val workspace = Files.createTempDirectory("cursorj-chat-history-mgr-update").toFile()
        try {
            val (_, clock) = testClock()
            val manager = ChatHistoryIndexManager(ChatHistoryStore(workspace.absolutePath), clock = clock)
            manager.load()

            manager.recordSession("abc-123", "First Title")
            val original = manager.listAll()[0]

            manager.recordSession("abc-123", "Updated Title")
            assertEquals(1, manager.entryCount())

            val updated = manager.listAll()[0]
            assertEquals("Updated Title", updated.title)
            assertEquals(original.createdAt, updated.createdAt)
            assertTrue(updated.lastActivityAt > original.lastActivityAt)
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `recordSession rejects blank sessionId and title`() {
        val workspace = Files.createTempDirectory("cursorj-chat-history-mgr-blank").toFile()
        try {
            val manager = ChatHistoryIndexManager(ChatHistoryStore(workspace.absolutePath))
            manager.load()

            assertFalse(manager.recordSession("", "Title"))
            assertFalse(manager.recordSession("  ", "Title"))
            assertFalse(manager.recordSession("id", ""))
            assertFalse(manager.recordSession("id", "   "))
            assertEquals(0, manager.entryCount())
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `updateTitle modifies title of existing entry`() {
        val workspace = Files.createTempDirectory("cursorj-chat-history-mgr-title").toFile()
        try {
            val (_, clock) = testClock()
            val manager = ChatHistoryIndexManager(ChatHistoryStore(workspace.absolutePath), clock = clock)
            manager.load()

            manager.recordSession("abc-123", "Old Title")
            assertTrue(manager.updateTitle("abc-123", "New Title"))

            val entries = manager.listAll()
            assertEquals("New Title", entries[0].title)
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `updateTitle returns false for unknown session`() {
        val workspace = Files.createTempDirectory("cursorj-chat-history-mgr-title-unknown").toFile()
        try {
            val manager = ChatHistoryIndexManager(ChatHistoryStore(workspace.absolutePath))
            manager.load()

            assertFalse(manager.updateTitle("nonexistent", "Title"))
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `touchActivity bumps lastActivityAt`() {
        val workspace = Files.createTempDirectory("cursorj-chat-history-mgr-touch").toFile()
        try {
            val (_, clock) = testClock()
            val manager = ChatHistoryIndexManager(ChatHistoryStore(workspace.absolutePath), clock = clock)
            manager.load()

            manager.recordSession("abc-123", "Chat")
            val original = manager.listAll()[0].lastActivityAt

            assertTrue(manager.touchActivity("abc-123"))

            val updated = manager.listAll()[0].lastActivityAt
            assertTrue(updated > original)
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `touchActivity returns false for unknown session`() {
        val workspace = Files.createTempDirectory("cursorj-chat-history-mgr-touch-unknown").toFile()
        try {
            val manager = ChatHistoryIndexManager(ChatHistoryStore(workspace.absolutePath))
            manager.load()

            assertFalse(manager.touchActivity("nonexistent"))
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `removeSession removes entry`() {
        val workspace = Files.createTempDirectory("cursorj-chat-history-mgr-remove").toFile()
        try {
            val manager = ChatHistoryIndexManager(ChatHistoryStore(workspace.absolutePath))
            manager.load()

            manager.recordSession("abc-123", "Chat")
            assertEquals(1, manager.entryCount())

            assertTrue(manager.removeSession("abc-123"))
            assertEquals(0, manager.entryCount())
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `listAll returns entries sorted by lastActivityAt descending`() {
        val workspace = Files.createTempDirectory("cursorj-chat-history-mgr-list").toFile()
        try {
            val (_, clock) = testClock()
            val manager = ChatHistoryIndexManager(ChatHistoryStore(workspace.absolutePath), clock = clock)
            manager.load()

            manager.recordSession("first", "First")
            manager.recordSession("second", "Second")
            manager.recordSession("third", "Third")

            val entries = manager.listAll()
            assertEquals(3, entries.size)
            assertEquals("third", entries[0].sessionId)
            assertEquals("second", entries[1].sessionId)
            assertEquals("first", entries[2].sessionId)
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `search filters by title case-insensitively`() {
        val workspace = Files.createTempDirectory("cursorj-chat-history-mgr-search").toFile()
        try {
            val (_, clock) = testClock()
            val manager = ChatHistoryIndexManager(ChatHistoryStore(workspace.absolutePath), clock = clock)
            manager.load()

            manager.recordSession("a", "Refactor Database")
            manager.recordSession("b", "Fix Login Bug")
            manager.recordSession("c", "Database Migration")

            val results = manager.search("database")
            assertEquals(2, results.size)
            assertTrue(results.all { "database" in it.title.lowercase() })
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `search with blank query returns all entries`() {
        val workspace = Files.createTempDirectory("cursorj-chat-history-mgr-search-blank").toFile()
        try {
            val (_, clock) = testClock()
            val manager = ChatHistoryIndexManager(ChatHistoryStore(workspace.absolutePath), clock = clock)
            manager.load()

            manager.recordSession("a", "Alpha")
            manager.recordSession("b", "Beta")

            val results = manager.search("  ")
            assertEquals(2, results.size)
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `entries are capped at maxEntries evicting oldest`() {
        val workspace = Files.createTempDirectory("cursorj-chat-history-mgr-cap").toFile()
        try {
            val (_, clock) = testClock()
            val manager = ChatHistoryIndexManager(
                store = ChatHistoryStore(workspace.absolutePath),
                maxEntries = 3,
                clock = clock,
            )
            manager.load()

            manager.recordSession("a", "One")
            manager.recordSession("b", "Two")
            manager.recordSession("c", "Three")
            manager.recordSession("d", "Four")

            assertEquals(3, manager.entryCount())
            val entries = manager.listAll()
            val ids = entries.map { it.sessionId }
            assertFalse("a" in ids)
            assertTrue("b" in ids)
            assertTrue("c" in ids)
            assertTrue("d" in ids)
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `load and persist round-trip preserves data`() {
        val workspace = Files.createTempDirectory("cursorj-chat-history-mgr-roundtrip").toFile()
        try {
            val (_, clock) = testClock()
            val first = ChatHistoryIndexManager(ChatHistoryStore(workspace.absolutePath), clock = clock)
            first.load()
            first.recordSession("abc", "My Chat")
            first.persist()

            val second = ChatHistoryIndexManager(ChatHistoryStore(workspace.absolutePath))
            second.load()
            val entries = second.listAll()
            assertEquals(1, entries.size)
            assertEquals("abc", entries[0].sessionId)
            assertEquals("My Chat", entries[0].title)
        } finally {
            workspace.deleteRecursively()
        }
    }
}
