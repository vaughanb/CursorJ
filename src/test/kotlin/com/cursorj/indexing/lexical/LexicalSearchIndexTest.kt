package com.cursorj.indexing.lexical

import com.cursorj.indexing.storage.SQLiteIndexStore
import com.intellij.openapi.project.Project
import kotlinx.coroutines.runBlocking
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LexicalSearchIndexTest {
    @Test
    fun `searchText finds matching lines with context`() = runBlocking {
        val root = Files.createTempDirectory("cursorj-lexical").toFile()
        try {
            File(root, "src").mkdirs()
            File(root, "src/Main.kt").writeText(
                """
                package demo
                fun helper() = 1
                fun targetFunction() = helper()
                """.trimIndent(),
            )
            File(root, "README.md").writeText("no relevant symbols")

            val index = LexicalSearchIndex(projectWithBasePath(root.absolutePath))
            val result = index.searchText(
                query = "targetFunction",
                maxResults = 10,
                contextLines = 1,
            )

            assertTrue(result.hits.isNotEmpty())
            assertTrue(result.hits.first().snippet.contains("targetFunction"))
            assertTrue(result.hits.first().path.endsWith("src/Main.kt"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `searchText respects maxResults and truncation flag`() = runBlocking {
        val root = Files.createTempDirectory("cursorj-lexical-truncate").toFile()
        try {
            File(root, "a.txt").writeText("needle\nneedle\nneedle")
            val index = LexicalSearchIndex(projectWithBasePath(root.absolutePath))
            val result = index.searchText(query = "needle", maxResults = 2, contextLines = 0)
            assertEquals(2, result.hits.size)
            assertTrue(result.truncated)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `searchText uses persisted lexical cache across index instances`() = runBlocking {
        val root = Files.createTempDirectory("cursorj-lexical-cache").toFile()
        try {
            File(root, "src").mkdirs()
            val source = File(root, "src/UserService.kt")
            source.writeText(
                """
                package demo
                fun fetchUser(id: String) = id
                """.trimIndent(),
            )

            val store = SQLiteIndexStore(root.absolutePath)
            store.open()
            val first = LexicalSearchIndex(projectWithBasePath(root.absolutePath), store)
            first.warmupWorkspace()
            store.close()

            val storeReloaded = SQLiteIndexStore(root.absolutePath)
            storeReloaded.open()
            val second = LexicalSearchIndex(projectWithBasePath(root.absolutePath), storeReloaded)
            val result = second.searchText("fetchUser", maxResults = 10)
            assertTrue(result.cacheHit)
            assertTrue(result.hits.any { it.path.endsWith("UserService.kt") })
            storeReloaded.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `searchText cache honors caseSensitive`() = runBlocking {
        val root = Files.createTempDirectory("cursorj-lexical-case-sensitive").toFile()
        try {
            File(root, "src").mkdirs()
            val source = File(root, "src/Main.kt")
            source.writeText("fun HelloWorld() = 42")

            val store = SQLiteIndexStore(root.absolutePath)
            store.open()
            val index = LexicalSearchIndex(projectWithBasePath(root.absolutePath), store)
            index.warmupWorkspace()

            val sensitiveMiss = index.searchText("helloworld", maxResults = 10, caseSensitive = true, contextLines = 0)
            assertTrue(sensitiveMiss.hits.isEmpty())

            val sensitiveHit = index.searchText("HelloWorld", maxResults = 10, caseSensitive = true, contextLines = 0)
            assertTrue(sensitiveHit.hits.isNotEmpty())
            assertTrue(sensitiveHit.cacheHit)
            store.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `warmupWorkspace removes stale document entries`() = runBlocking {
        val root = Files.createTempDirectory("cursorj-lexical-warmup").toFile()
        try {
            val store = SQLiteIndexStore(root.absolutePath)
            store.open()
            val stalePath = "${root.absolutePath.replace('\\', '/')}/stale/Old.kt"
            store.upsertDocument(stalePath, "stale-hash", 20, 1L, "kt")
            store.replaceLexicalHits(
                stalePath,
                listOf(
                    com.cursorj.indexing.storage.StoredLexicalHit(
                        stalePath, 1, 1, "class Old", "class old", 0.1, "class|old",
                    ),
                ),
            )

            File(root, "src").mkdirs()
            File(root, "src/New.kt").writeText("class New")
            val index = LexicalSearchIndex(projectWithBasePath(root.absolutePath), store)
            val warmup = index.warmupWorkspace()
            assertTrue(warmup.removedFiles >= 1)
            assertNull(store.document(stalePath))
            assertTrue(store.documentCount() >= 1)
            store.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `upsertFileFromDisk removes missing file from store`() = runBlocking {
        val root = Files.createTempDirectory("cursorj-lexical-upsert-missing").toFile()
        try {
            val store = SQLiteIndexStore(root.absolutePath)
            store.open()
            val existing = File(root, "src/ToDelete.kt")
            existing.parentFile.mkdirs()
            existing.writeText("class ToDelete")
            val index = LexicalSearchIndex(projectWithBasePath(root.absolutePath), store)
            index.upsertFileFromDisk(existing.absolutePath)
            assertNotNull(store.document(existing.absolutePath))
            assertTrue(existing.delete())

            val updated = index.upsertFileFromDisk(existing.absolutePath)
            assertFalse(updated)
            assertNull(store.document(existing.absolutePath))
            store.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `searchText supports scoped path filtering`() = runBlocking {
        val root = Files.createTempDirectory("cursorj-lexical-path-filter").toFile()
        try {
            File(root, "src").mkdirs()
            File(root, "test").mkdirs()
            File(root, "src/Main.kt").writeText("fun scopedNeedle() = 1")
            File(root, "test/MainTest.kt").writeText("fun scopedNeedle() = 2")

            val store = SQLiteIndexStore(root.absolutePath)
            store.open()
            val index = LexicalSearchIndex(projectWithBasePath(root.absolutePath), store)
            index.warmupWorkspace()

            val scoped = index.searchText("scopedNeedle", path = "src", maxResults = 10, contextLines = 0)
            assertTrue(scoped.hits.isNotEmpty())
            assertTrue(scoped.hits.all { it.path.contains("/src/") })
            store.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `searchText defaults to case insensitive matching`() = runBlocking {
        val root = Files.createTempDirectory("cursorj-lexical-case-default").toFile()
        try {
            File(root, "src").mkdirs()
            File(root, "src/Main.kt").writeText("fun HelloWorld() = 1")
            val index = LexicalSearchIndex(projectWithBasePath(root.absolutePath))
            val result = index.searchText("helloworld", maxResults = 10, contextLines = 0)
            assertTrue(result.hits.isNotEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `removeFile returns false when persistence store unavailable`() {
        val index = LexicalSearchIndex(projectWithBasePath(null))
        assertFalse(index.removeFile("anything"))
    }

    @Test
    fun `warmup reports progress callback`() = runBlocking {
        val root = Files.createTempDirectory("cursorj-lexical-progress").toFile()
        try {
            File(root, "src").mkdirs()
            repeat(3) { idx ->
                File(root, "src/File$idx.kt").writeText("fun fn$idx() = $idx")
            }
            val store = SQLiteIndexStore(root.absolutePath)
            store.open()
            val index = LexicalSearchIndex(projectWithBasePath(root.absolutePath), store)
            var callbackCount = 0
            index.warmupWorkspace { _, _ -> callbackCount++ }
            assertTrue(callbackCount >= 1)
            store.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `upsertFileFromDisk skips binary and oversized files`() = runBlocking {
        val root = Files.createTempDirectory("cursorj-lexical-skip").toFile()
        try {
            val store = SQLiteIndexStore(root.absolutePath)
            store.open()
            val index = LexicalSearchIndex(projectWithBasePath(root.absolutePath), store)

            val binary = File(root, "src/data.bin")
            binary.parentFile.mkdirs()
            binary.writeBytes(byteArrayOf(0, 1, 2, 3, 4))
            val binaryResult = index.upsertFileFromDisk(binary.absolutePath)
            assertFalse(binaryResult)

            val large = File(root, "src/large.txt")
            large.writeText("x".repeat(2048))
            val largeResult = index.upsertFileFromDisk(large.absolutePath, maxFileSizeBytes = 10)
            assertFalse(largeResult)
            store.close()
        } finally {
            root.deleteRecursively()
        }
    }

    private fun projectWithBasePath(basePath: String?): Project {
        return Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getBasePath" -> basePath
                "isDisposed" -> false
                "isDefault" -> false
                "getName" -> "test-project"
                "getLocationHash" -> "test-hash"
                else -> defaultValue(method.returnType)
            }
        } as Project
    }

    private fun defaultValue(returnType: Class<*>): Any? {
        return when (returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Double.TYPE -> 0.0
            java.lang.Float.TYPE -> 0f
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }
    }
}
