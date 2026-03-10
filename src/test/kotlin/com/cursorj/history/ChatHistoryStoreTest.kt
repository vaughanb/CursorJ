package com.cursorj.history

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatHistoryStoreTest {
    @Test
    fun `load returns empty snapshot for missing file`() {
        val workspace = Files.createTempDirectory("cursorj-chat-history-store-missing").toFile()
        try {
            val store = ChatHistoryStore(workspace.absolutePath)
            val loaded = store.load()
            assertEquals(emptyList(), loaded.entries)
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `load returns empty snapshot for malformed json`() {
        val workspace = Files.createTempDirectory("cursorj-chat-history-store-bad-json").toFile()
        try {
            val target = File(workspace, ".cursorj/chat-history-index-v1.json")
            target.parentFile.mkdirs()
            target.writeText("{ not-json", Charsets.UTF_8)

            val store = ChatHistoryStore(workspace.absolutePath)
            val loaded = store.load()
            assertEquals(emptyList(), loaded.entries)
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `save and load round trip preserves entries`() {
        val workspace = Files.createTempDirectory("cursorj-chat-history-store-roundtrip").toFile()
        try {
            val store = ChatHistoryStore(workspace.absolutePath)
            val entries = listOf(
                ChatHistoryEntry("abc-123", "First Chat", 1000L, 2000L),
                ChatHistoryEntry("def-456", "Second Chat", 3000L, 4000L),
            )
            store.save(ChatHistoryStore.ChatHistorySnapshot(entries = entries))

            val loaded = store.load()
            assertEquals(2, loaded.entries.size)
            assertEquals("abc-123", loaded.entries[0].sessionId)
            assertEquals("First Chat", loaded.entries[0].title)
            assertEquals(1000L, loaded.entries[0].createdAt)
            assertEquals(2000L, loaded.entries[0].lastActivityAt)
            assertEquals("def-456", loaded.entries[1].sessionId)
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `save normalizes entries and strips blank sessionIds`() {
        val workspace = Files.createTempDirectory("cursorj-chat-history-store-normalize").toFile()
        try {
            val store = ChatHistoryStore(workspace.absolutePath)
            val entries = listOf(
                ChatHistoryEntry("  abc  ", "  Some Title  ", 1000L, 2000L),
                ChatHistoryEntry("", "Empty Id", 3000L, 4000L),
                ChatHistoryEntry("valid", "  ", 5000L, 6000L),
            )
            store.save(ChatHistoryStore.ChatHistorySnapshot(entries = entries))

            val loaded = store.load()
            assertEquals(1, loaded.entries.size)
            assertEquals("abc", loaded.entries[0].sessionId)
            assertEquals("Some Title", loaded.entries[0].title)
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `exists returns false when file is missing and true after save`() {
        val workspace = Files.createTempDirectory("cursorj-chat-history-store-exists").toFile()
        try {
            val store = ChatHistoryStore(workspace.absolutePath)
            assertFalse(store.exists())

            store.save(ChatHistoryStore.ChatHistorySnapshot(entries = listOf(
                ChatHistoryEntry("x", "Title", 1L, 2L),
            )))
            assertTrue(store.exists())
        } finally {
            workspace.deleteRecursively()
        }
    }
}
