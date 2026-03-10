package com.cursorj.history

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatTranscriptStoreTest {
    @Test
    fun `load returns empty snapshot for malformed json`() {
        val workspace = Files.createTempDirectory("cursorj-chat-transcript-store-bad-json").toFile()
        try {
            val target = File(workspace, ".cursorj/chat-transcripts-v1.json")
            target.parentFile.mkdirs()
            target.writeText("{ not-json", Charsets.UTF_8)

            val store = ChatTranscriptStore(workspace.absolutePath)
            val loaded = store.load()
            assertEquals(emptyMap(), loaded.sessions)
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `save and load round trip preserves normalized messages`() {
        val workspace = Files.createTempDirectory("cursorj-chat-transcript-store-roundtrip").toFile()
        try {
            val store = ChatTranscriptStore(workspace.absolutePath)
            store.save(
                ChatTranscriptStore.ChatTranscriptSnapshot(
                    sessions = mapOf(
                        "session:one" to listOf(
                            ChatTranscriptStore.TranscriptMessage("USER", " first "),
                            ChatTranscriptStore.TranscriptMessage("assistant", "second"),
                            ChatTranscriptStore.TranscriptMessage("assistant", "   "),
                        ),
                        "   " to listOf(ChatTranscriptStore.TranscriptMessage("user", "ignored")),
                    ),
                ),
            )

            val loaded = store.load()
            assertEquals(
                mapOf(
                    "session:one" to listOf(
                        ChatTranscriptStore.TranscriptMessage("user", "first"),
                        ChatTranscriptStore.TranscriptMessage("assistant", "second"),
                    ),
                ),
                loaded.sessions,
            )
        } finally {
            workspace.deleteRecursively()
        }
    }
}
