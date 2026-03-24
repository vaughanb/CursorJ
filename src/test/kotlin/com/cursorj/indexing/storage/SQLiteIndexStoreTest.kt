package com.cursorj.indexing.storage

import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SQLiteIndexStoreTest {
    @Test
    fun `operations fail before open`() {
        val workspace = Files.createTempDirectory("cursorj-sqlite-not-open").toFile()
        try {
            val store = SQLiteIndexStore(workspace.absolutePath)
            assertFailsWith<IllegalStateException> {
                store.documentCount()
            }
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `store migrates and persists lexical rows across reopen`() {
        val workspace = Files.createTempDirectory("cursorj-sqlite-store").toFile()
        try {
            val first = SQLiteIndexStore(workspace.absolutePath)
            first.open()
            first.upsertDocument(
                path = "${workspace.absolutePath.replace('\\', '/')}/src/Main.kt",
                contentHash = "abc123",
                sizeBytes = 123,
                mtimeMs = 1000L,
                language = "kt",
            )
            first.replaceLexicalHits(
                path = "${workspace.absolutePath.replace('\\', '/')}/src/Main.kt",
                hits = listOf(
                    StoredLexicalHit(
                        path = "${workspace.absolutePath.replace('\\', '/')}/src/Main.kt",
                        line = 3,
                        column = 1,
                        snippet = "fun greet() = \"hi\"",
                        normalizedLine = "fun greet() = \"hi\"",
                        scoreHint = 0.5,
                        tokenFingerprint = "fun|greet",
                    ),
                ),
            )
            assertEquals(1, first.documentCount())
            first.close()

            val second = SQLiteIndexStore(workspace.absolutePath)
            second.open()
            val results = second.searchLexical(
                query = "greet",
                pathPrefix = null,
                maxResults = 10,
                caseSensitive = false,
            )
            assertEquals(1, results.size)
            assertTrue(results.first().snippet.contains("greet"))
            second.close()
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `search supports pathPrefix and case sensitivity`() {
        val workspace = Files.createTempDirectory("cursorj-sqlite-prefix").toFile()
        try {
            val store = SQLiteIndexStore(workspace.absolutePath)
            store.open()
            val srcPath = "${workspace.absolutePath.replace('\\', '/')}/src/Main.kt"
            val testPath = "${workspace.absolutePath.replace('\\', '/')}/test/MainTest.kt"
            store.upsertDocument(srcPath, "h1", 10, 1L, "kt")
            store.upsertDocument(testPath, "h2", 10, 1L, "kt")
            store.replaceLexicalHits(
                srcPath,
                listOf(
                    StoredLexicalHit(srcPath, 1, 1, "fun HelloWorld() {}", "fun helloworld() {}", 0.9, "hello"),
                ),
            )
            store.replaceLexicalHits(
                testPath,
                listOf(
                    StoredLexicalHit(testPath, 1, 1, "fun helper() {}", "fun helper() {}", 0.2, "helper"),
                ),
            )

            val prefixHits = store.searchLexical("HelloWorld", "${workspace.absolutePath.replace('\\', '/')}/src", 20, true)
            assertEquals(1, prefixHits.size)
            assertTrue(prefixHits.first().path.contains("/src/"))

            val caseSensitiveMiss = store.searchLexical("helloworld", null, 20, true)
            assertEquals(0, caseSensitiveMiss.size)

            val caseInsensitiveHit = store.searchLexical("helloworld", null, 20, false)
            assertEquals(1, caseInsensitiveHit.size)
            store.close()
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `removePath and clearAll remove documents and hits`() {
        val workspace = Files.createTempDirectory("cursorj-sqlite-remove").toFile()
        try {
            val store = SQLiteIndexStore(workspace.absolutePath)
            store.open()
            val path = "${workspace.absolutePath.replace('\\', '/')}/src/Temp.kt"
            store.upsertDocument(path, "hash", 10, 1L, "kt")
            store.replaceLexicalHits(
                path,
                listOf(
                    StoredLexicalHit(path, 1, 1, "val a = 1", "val a = 1", 0.1, "val|a"),
                ),
            )
            assertNotNull(store.document(path))
            assertEquals(1, store.searchLexical("val", null, 10, false).size)

            store.removePath(path)
            assertNull(store.document(path))
            assertEquals(0, store.searchLexical("val", null, 10, false).size)

            val otherPath = "${workspace.absolutePath.replace('\\', '/')}/src/Other.kt"
            store.upsertDocument(otherPath, "hash2", 20, 2L, "kt")
            store.replaceLexicalHits(
                otherPath,
                listOf(StoredLexicalHit(otherPath, 1, 1, "class Other", "class other", 0.4, "class|other")),
            )
            assertEquals(1, store.documentCount())
            store.clearAll()
            assertEquals(0, store.documentCount())
            assertEquals(0, store.searchLexical("other", null, 10, false).size)
            store.close()
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `prune removes stale rows by indexed time`() {
        val workspace = Files.createTempDirectory("cursorj-sqlite-prune").toFile()
        try {
            val store = SQLiteIndexStore(workspace.absolutePath)
            store.open()
            val path = "${workspace.absolutePath.replace('\\', '/')}/Old.kt"
            store.upsertDocument(path, "hash", 10, 1L, "kt")
            store.replaceLexicalHits(
                path,
                listOf(
                    StoredLexicalHit(path, 1, 1, "old value", "old value", 0.1, "old"),
                ),
            )
            store.pruneByIndexedAt(System.currentTimeMillis() + 1000L)
            assertEquals(0, store.documentCount())
            store.close()
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `open creates db file and close updates isOpen`() {
        val workspace = Files.createTempDirectory("cursorj-sqlite-open").toFile()
        try {
            val store = SQLiteIndexStore(workspace.absolutePath)
            assertFalse(store.existsOnDisk())
            assertFalse(store.isOpen())
            store.open()
            assertTrue(store.isOpen())
            store.open() // idempotent
            assertTrue(store.isOpen())
            assertTrue(store.existsOnDisk())
            store.close()
            assertFalse(store.isOpen())
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `open applies schema migration version`() {
        val workspace = Files.createTempDirectory("cursorj-sqlite-schema").toFile()
        try {
            val store = SQLiteIndexStore(workspace.absolutePath)
            store.open()
            store.close()

            val dbPath = workspace.resolve(".cursorj/index/index-v1.db").absolutePath.replace('\\', '/')
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
                conn.prepareStatement("SELECT value FROM index_meta WHERE key='schema_version'").use { ps ->
                    ps.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        assertEquals(SqlMigrations.SCHEMA_VERSION.toString(), rs.getString(1))
                    }
                }
            }
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `blank query returns no lexical hits`() {
        val workspace = Files.createTempDirectory("cursorj-sqlite-blank").toFile()
        try {
            val store = SQLiteIndexStore(workspace.absolutePath)
            store.open()
            assertTrue(store.searchLexical("   ", null, 10, caseSensitive = false).isEmpty())
            store.close()
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `path normalization supports backslash and forward slash lookups`() {
        val workspace = Files.createTempDirectory("cursorj-sqlite-pathnorm").toFile()
        try {
            val store = SQLiteIndexStore(workspace.absolutePath)
            store.open()
            val backslashPath = "${workspace.absolutePath}\\src\\Path.kt"
            store.upsertDocument(backslashPath, "hash", 10, 1L, "kt")
            store.replaceLexicalHits(
                backslashPath,
                listOf(
                    StoredLexicalHit(backslashPath, 1, 1, "class Path", "class path", 0.5, "class|path"),
                ),
            )
            val forwardPath = "${workspace.absolutePath.replace('\\', '/')}/src/Path.kt"
            assertNotNull(store.document(forwardPath))
            assertEquals(1, store.searchLexical("path", null, 10, caseSensitive = false).size)
            store.close()
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `searchLexical respects maxResults and ordering by score`() {
        val workspace = Files.createTempDirectory("cursorj-sqlite-order").toFile()
        try {
            val store = SQLiteIndexStore(workspace.absolutePath)
            store.open()
            val path = "${workspace.absolutePath.replace('\\', '/')}/src/Order.kt"
            store.upsertDocument(path, "hash", 10, 1L, "kt")
            store.replaceLexicalHits(
                path,
                listOf(
                    StoredLexicalHit(path, 1, 1, "target one", "target one", 0.1, "target|one"),
                    StoredLexicalHit(path, 2, 1, "target two", "target two", 0.7, "target|two"),
                    StoredLexicalHit(path, 3, 1, "target three", "target three", 0.4, "target|three"),
                ),
            )

            val topTwo = store.searchLexical("target", null, maxResults = 2, caseSensitive = false)
            assertEquals(2, topTwo.size)
            assertTrue(topTwo[0].scoreHint >= topTwo[1].scoreHint)
            assertEquals("target two", topTwo[0].snippet)
            store.close()
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `allDocuments returns stored metadata for warmup planning`() {
        val workspace = Files.createTempDirectory("cursorj-sqlite-docs").toFile()
        try {
            val store = SQLiteIndexStore(workspace.absolutePath)
            store.open()
            val one = "${workspace.absolutePath.replace('\\', '/')}/src/One.kt"
            val two = "${workspace.absolutePath.replace('\\', '/')}/src/Two.kt"
            store.upsertDocument(one, "hash1", 11, 1001L, "kt")
            store.upsertDocument(two, "hash2", 22, 2002L, "kt")

            val docs = store.allDocuments().associateBy { it.path }
            assertEquals(2, docs.size)
            assertEquals(11, docs[one]?.sizeBytes)
            assertEquals(2002L, docs[two]?.mtimeMs)
            store.close()
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `concurrent writes do not fail with transaction state errors`() {
        val workspace = Files.createTempDirectory("cursorj-sqlite-concurrent").toFile()
        try {
            val store = SQLiteIndexStore(workspace.absolutePath)
            store.open()
            val normalizedRoot = workspace.absolutePath.replace('\\', '/')
            val targetPath = "$normalizedRoot/src/Race.kt"
            val failures = ConcurrentLinkedQueue<Throwable>()
            val workers = 8
            val iterationsPerWorker = 80
            val start = CountDownLatch(1)
            val done = CountDownLatch(workers)

            repeat(workers) { worker ->
                thread(start = true, name = "sqlite-race-$worker") {
                    try {
                        start.await()
                        repeat(iterationsPerWorker) { i ->
                            val contentHash = "hash-$worker-$i"
                            store.upsertDocument(targetPath, contentHash, 100, i.toLong(), "kt")
                            store.replaceLexicalHits(
                                path = targetPath,
                                hits = listOf(
                                    StoredLexicalHit(
                                        path = targetPath,
                                        line = 1,
                                        column = 1,
                                        snippet = "val worker$worker = $i",
                                        normalizedLine = "val worker$worker = $i",
                                        scoreHint = 0.1,
                                        tokenFingerprint = "worker|$worker",
                                    ),
                                ),
                            )
                        }
                    } catch (t: Throwable) {
                        failures.add(t)
                    } finally {
                        done.countDown()
                    }
                }
            }

            start.countDown()
            assertTrue(done.await(45, TimeUnit.SECONDS), "Timed out waiting for concurrent SQLite writers")
            assertTrue(
                failures.isEmpty(),
                "Concurrent writes produced failures: ${failures.firstOrNull()?.message}",
            )
            store.close()
        } finally {
            workspace.deleteRecursively()
        }
    }
}
