package com.cursorj.history

import com.cursorj.acp.ChatMessage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatTranscriptManagerTest {
    @Test
    fun `addMessage ignores streaming and deduplicates sequential messages`() {
        val workspace = Files.createTempDirectory("cursorj-chat-transcript-manager-add").toFile()
        try {
            val manager = ChatTranscriptManager(ChatTranscriptStore(workspace.absolutePath), maxEntriesPerSession = 5)
            manager.load()

            assertFalse(manager.addMessage("session:one", ChatMessage("assistant", "stream", isStreaming = true)))
            assertTrue(manager.addMessage("session:one", ChatMessage("user", "hello")))
            assertFalse(manager.addMessage("session:one", ChatMessage("user", "hello")))
            assertTrue(manager.addMessage("session:one", ChatMessage("assistant", "hi there")))

            assertEquals(
                listOf(
                    ChatMessage("user", "hello"),
                    ChatMessage("assistant", "hi there"),
                ),
                manager.transcriptFor("session:one"),
            )
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `migrateSessionKey moves transcript and keeps order`() {
        val workspace = Files.createTempDirectory("cursorj-chat-transcript-manager-migrate").toFile()
        try {
            val manager = ChatTranscriptManager(ChatTranscriptStore(workspace.absolutePath))
            manager.load()

            manager.addMessage("tab:temp", ChatMessage("user", "one"))
            manager.addMessage("tab:temp", ChatMessage("assistant", "two"))
            manager.addMessage("session:new", ChatMessage("user", "three"))

            assertTrue(manager.migrateSessionKey("tab:temp", "session:new"))
            assertEquals(emptyList(), manager.transcriptFor("tab:temp"))
            assertEquals(
                listOf(
                    ChatMessage("user", "one"),
                    ChatMessage("assistant", "two"),
                    ChatMessage("user", "three"),
                ),
                manager.transcriptFor("session:new"),
            )
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `buildCarryoverContext includes recent transcript entries`() {
        val workspace = Files.createTempDirectory("cursorj-chat-transcript-manager-carryover").toFile()
        try {
            val manager = ChatTranscriptManager(ChatTranscriptStore(workspace.absolutePath))
            manager.load()

            manager.addMessage("session:one", ChatMessage("user", "first question"))
            manager.addMessage("session:one", ChatMessage("assistant", "first answer"))
            manager.addMessage("session:one", ChatMessage("user", "second question"))

            val context = manager.buildCarryoverContext("session:one")
            assertTrue(context != null && "first question" in context)
            assertTrue("first answer" in context!!)
            assertTrue("second question" in context)
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `isEmpty returns true before any messages are added`() {
        val workspace = Files.createTempDirectory("cursorj-chat-transcript-manager-empty").toFile()
        try {
            val manager = ChatTranscriptManager(ChatTranscriptStore(workspace.absolutePath))
            manager.load()
            assertTrue(manager.isEmpty())
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `isEmpty returns false after adding a message`() {
        val workspace = Files.createTempDirectory("cursorj-chat-transcript-manager-notempty").toFile()
        try {
            val manager = ChatTranscriptManager(ChatTranscriptStore(workspace.absolutePath))
            manager.load()
            manager.addMessage("session:one", ChatMessage("user", "hello"))
            assertFalse(manager.isEmpty())
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `isEmpty returns true when loaded from missing file`() {
        val workspace = Files.createTempDirectory("cursorj-chat-transcript-manager-no-file").toFile()
        try {
            val manager = ChatTranscriptManager(ChatTranscriptStore(workspace.absolutePath))
            manager.load()
            assertTrue(manager.isEmpty())
        } finally {
            workspace.deleteRecursively()
        }
    }
}
