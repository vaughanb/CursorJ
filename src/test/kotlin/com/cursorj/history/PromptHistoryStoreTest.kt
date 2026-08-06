package com.cursorj.history

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class PromptHistoryStoreTest {
    @Test
    fun `load returns empty snapshot for malformed json`() {
        val workspace = Files.createTempDirectory("cursorj-prompt-history-store-bad-json").toFile()
        try {
            val storage = File(workspace, "store").apply { mkdirs() }
            val target = File(storage, PromptHistoryStore.FILE_NAME)
            target.writeText("{ not-json", Charsets.UTF_8)

            val store = PromptHistoryStore(storage)
            val loaded = store.load()
            assertEquals(emptyMap(), loaded.sessions)
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `save and load round trip preserves normalized sessions`() {
        val workspace = Files.createTempDirectory("cursorj-prompt-history-store-roundtrip").toFile()
        try {
            val storage = File(workspace, "store").apply { mkdirs() }
            val store = PromptHistoryStore(storage)
            store.save(
                PromptHistoryStore.PromptHistorySnapshot(
                    sessions = mapOf(
                        "session:one" to listOf("first", " second ", "   "),
                        "   " to listOf("ignored"),
                    ),
                ),
            )

            val loaded = store.load()
            assertEquals(
                mapOf("session:one" to listOf("first", "second")),
                loaded.sessions,
            )
        } finally {
            workspace.deleteRecursively()
        }
    }
}
