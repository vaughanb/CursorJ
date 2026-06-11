package com.cursorj.storage

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CentralStorageTest {
    @Test
    fun `projectKeyForPath is stable for the same directory`() {
        val root = Files.createTempDirectory("cursorj-central-key-root").toFile()
        val workspace = Files.createTempDirectory("cursorj-central-key-stable").toFile()
        try {
            val storage = CentralStorage(root)
            val a = storage.projectKeyForPath(workspace.absolutePath)
            val b = storage.projectKeyForPath(workspace.absolutePath)
            assertEquals(a, b)
        } finally {
            workspace.deleteRecursively()
            root.deleteRecursively()
        }
    }

    @Test
    fun `projectKeyForPath differs for different directories`() {
        val ws1 = Files.createTempDirectory("cursorj-central-a").toFile()
        val ws2 = Files.createTempDirectory("cursorj-central-b").toFile()
        val root = Files.createTempDirectory("cursorj-root-diff").toFile()
        try {
            val storage = CentralStorage(root)
            assertNotEquals(
                storage.projectKeyForPath(ws1.absolutePath),
                storage.projectKeyForPath(ws2.absolutePath),
            )
        } finally {
            ws1.deleteRecursively()
            ws2.deleteRecursively()
            root.deleteRecursively()
        }
    }

    @Test
    fun `sanitizeDirName replaces invalid characters`() {
        val testCases = mapOf(
            "foo:bar" to "foo_bar",
            "" to "project",
            "   " to "project",
            "a*b?c" to "a_b_c",
            "." to "project",
        )
        for ((input, expected) in testCases) {
            assertEquals(expected, CentralStorage.sanitizeDirName(input), "input=$input")
        }
    }

    @Test
    fun `projectDir migrates legacy json and index from workspace cursorj`() {
        val root = Files.createTempDirectory("cursorj-migrate-root").toFile()
        val workspace = Files.createTempDirectory("cursorj-migrate-ws").toFile()
        try {
            val legacy = File(workspace, ".cursorj")
            val legacyIndex = File(legacy, "index").apply { mkdirs() }
            File(legacyIndex, "index-v1.db").writeText("sqlite-placeholder", Charsets.UTF_8)
            File(legacy, "prompt-history-v1.json").writeText("{}", Charsets.UTF_8)
            File(legacy, "chat-history-index-v1.json").writeText("{}", Charsets.UTF_8)
            File(legacy, "chat-transcripts-v1.json").writeText("{}", Charsets.UTF_8)

            val storage = CentralStorage(root)
            val projectDir = assertNotNull(storage.projectDir(workspace.absolutePath))

            assertTrue(File(projectDir, "prompt-history-v1.json").isFile)
            assertTrue(File(projectDir, "chat-history-index-v1.json").isFile)
            assertTrue(File(projectDir, "chat-transcripts-v1.json").isFile)
            assertTrue(File(projectDir, "index/index-v1.db").isFile)
            assertFalse(File(workspace, ".cursorj/prompt-history-v1.json").exists())
            assertTrue(File(projectDir, ".legacy_cursorj_migrated").isFile)
        } finally {
            root.deleteRecursively()
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `migration does not overwrite existing central files`() {
        val root = Files.createTempDirectory("cursorj-migrate-no-clobber-root").toFile()
        val workspace = Files.createTempDirectory("cursorj-migrate-no-clobber-ws").toFile()
        try {
            val storage = CentralStorage(root)
            val projectDir = assertNotNull(storage.projectDir(workspace.absolutePath))
            File(projectDir, "prompt-history-v1.json").writeText("""{"version":1,"sessions":{}}""", Charsets.UTF_8)

            val legacy = File(workspace, ".cursorj")
            legacy.mkdirs()
            File(legacy, "prompt-history-v1.json").writeText("""{"version":1,"sessions":{"x":["y"]}}""", Charsets.UTF_8)

            File(projectDir, ".legacy_cursorj_migrated").delete()

            val projectDir2 = assertNotNull(storage.projectDir(workspace.absolutePath))
            assertEquals(projectDir.absolutePath, projectDir2.absolutePath)
            assertTrue(File(projectDir, "prompt-history-v1.json").readText().contains("sessions\":{}"))
            assertTrue(File(legacy, "prompt-history-v1.json").exists())
        } finally {
            root.deleteRecursively()
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `projectDir returns null for blank path`() {
        val root = Files.createTempDirectory("cursorj-null-ws").toFile()
        try {
            val storage = CentralStorage(root)
            assertEquals(null, storage.projectDir(null))
            assertEquals(null, storage.projectDir("   "))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `second projectDir call does not require legacy dir`() {
        val root = Files.createTempDirectory("cursorj-second-call").toFile()
        val workspace = Files.createTempDirectory("cursorj-second-ws").toFile()
        try {
            val storage = CentralStorage(root)
            val first = assertNotNull(storage.projectDir(workspace.absolutePath))
            val second = assertNotNull(storage.projectDir(workspace.absolutePath))
            assertEquals(first.absolutePath, second.absolutePath)
            assertTrue(File(first, ".legacy_cursorj_migrated").isFile)
        } finally {
            root.deleteRecursively()
            workspace.deleteRecursively()
        }
    }
}
