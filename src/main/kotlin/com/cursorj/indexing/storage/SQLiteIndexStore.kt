package com.cursorj.indexing.storage

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class StoredDocument(
    val path: String,
    val contentHash: String,
    val sizeBytes: Long,
    val mtimeMs: Long,
    val indexedAtMs: Long,
)

data class StoredLexicalHit(
    val path: String,
    val line: Int,
    val column: Int,
    val snippet: String,
    val normalizedLine: String,
    val scoreHint: Double,
    val tokenFingerprint: String?,
)

class SQLiteIndexStore(
    private val workspaceRoot: String,
) : AutoCloseable {
    private val log = Logger.getInstance(SQLiteIndexStore::class.java)
    private val dbFile = File(workspaceRoot, ".cursorj/index/index-v1.db")
    private val readLock = ReentrantLock()
    private val writeLock = ReentrantLock()
    private var readConnection: Connection? = null
    private var writeConnection: Connection? = null

    fun open() {
        writeLock.withLock {
            readLock.withLock {
                if (writeConnection != null) return
                dbFile.parentFile?.mkdirs()
                ensureDriverRegistered()
                val jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath.replace('\\', '/')}"
                writeConnection = DriverManager.getConnection(jdbcUrl).apply {
                    autoCommit = true
                }
                SqlMigrations.migrate(writeConnection!!)
                
                readConnection = DriverManager.getConnection(jdbcUrl).apply {
                    autoCommit = true
                }
                readConnection!!.createStatement().use { stmt ->
                    stmt.execute("PRAGMA journal_mode=WAL")
                    stmt.execute("PRAGMA synchronous=NORMAL")
                    stmt.execute("PRAGMA temp_store=MEMORY")
                    stmt.execute("PRAGMA foreign_keys=ON")
                }
            }
        }
    }

    fun isOpen(): Boolean = readLock.withLock { readConnection != null }

    fun existsOnDisk(): Boolean = dbFile.isFile

    fun databasePath(): String = dbFile.absolutePath

    fun clearAll() {
        withWriteConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("DELETE FROM lexical_hits")
                stmt.executeUpdate("DELETE FROM documents")
            }
        }
    }

    fun removePath(path: String) {
        val normalized = normalizePath(path)
        withWriteConnection { conn ->
            conn.prepareStatement("DELETE FROM lexical_hits WHERE path = ?").use { ps ->
                ps.setString(1, normalized)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM documents WHERE path = ?").use { ps ->
                ps.setString(1, normalized)
                ps.executeUpdate()
            }
        }
    }

    fun document(path: String): StoredDocument? {
        val normalized = normalizePath(path)
        return withReadConnection { conn ->
            conn.prepareStatement(
                """
                SELECT path, content_hash, size_bytes, mtime_ms, indexed_at_ms
                FROM documents
                WHERE path = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, normalized)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return@withReadConnection null
                    StoredDocument(
                        path = rs.getString("path"),
                        contentHash = rs.getString("content_hash"),
                        sizeBytes = rs.getLong("size_bytes"),
                        mtimeMs = rs.getLong("mtime_ms"),
                        indexedAtMs = rs.getLong("indexed_at_ms"),
                    )
                }
            }
        }
    }

    fun upsertDocument(path: String, contentHash: String, sizeBytes: Long, mtimeMs: Long, language: String?) {
        val normalized = normalizePath(path)
        withWriteConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO documents(path, content_hash, size_bytes, mtime_ms, language, indexed_at_ms)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(path) DO UPDATE SET
                    content_hash = excluded.content_hash,
                    size_bytes = excluded.size_bytes,
                    mtime_ms = excluded.mtime_ms,
                    language = excluded.language,
                    indexed_at_ms = excluded.indexed_at_ms
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, normalized)
                ps.setString(2, contentHash)
                ps.setLong(3, sizeBytes)
                ps.setLong(4, mtimeMs)
                ps.setString(5, language)
                ps.setLong(6, System.currentTimeMillis())
                ps.executeUpdate()
            }
        }
    }

    fun replaceLexicalHits(path: String, hits: List<StoredLexicalHit>) {
        val normalized = normalizePath(path)
        withWriteConnection { conn ->
            val prevAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                conn.prepareStatement("DELETE FROM lexical_hits WHERE path = ?").use { delete ->
                    delete.setString(1, normalized)
                    delete.executeUpdate()
                }
                conn.prepareStatement(
                    """
                    INSERT INTO lexical_hits(path, line, column, snippet, normalized_line, score_hint, token_fingerprint)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { insert ->
                    for (hit in hits) {
                        insert.setString(1, normalized)
                        insert.setInt(2, hit.line)
                        insert.setInt(3, hit.column)
                        insert.setString(4, hit.snippet)
                        insert.setString(5, hit.normalizedLine)
                        insert.setDouble(6, hit.scoreHint)
                        insert.setString(7, hit.tokenFingerprint)
                        insert.addBatch()
                    }
                    insert.executeBatch()
                }
                conn.commit()
            } catch (e: SQLException) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                conn.autoCommit = prevAutoCommit
            }
        }
    }

    fun searchLexical(query: String, pathPrefix: String?, maxResults: Int, caseSensitive: Boolean): List<StoredLexicalHit> {
        if (query.isBlank()) return emptyList()
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()

        val normalizedPrefix = pathPrefix?.takeIf { it.isNotBlank() }?.let { normalizePath(it) }
        val sql = buildString {
            append(
                if (caseSensitive) {
                    """
                SELECT path, line, column, snippet, normalized_line, score_hint, token_fingerprint
                FROM lexical_hits
                WHERE instr(snippet, ?) > 0
                    """.trimIndent()
                } else {
                    """
                SELECT path, line, column, snippet, normalized_line, score_hint, token_fingerprint
                FROM lexical_hits
                WHERE normalized_line LIKE ?
                    """.trimIndent()
                },
            )
            if (normalizedPrefix != null) append(" AND path LIKE ?")
            append(" ORDER BY score_hint DESC, path ASC, line ASC LIMIT ?")
        }

        return withReadConnection { conn ->
            conn.prepareStatement(sql).use { ps ->
                if (caseSensitive) {
                    ps.setString(1, needle)
                } else {
                    ps.setString(1, "%${needle.lowercase()}%")
                }
                var index = 2
                if (normalizedPrefix != null) {
                    ps.setString(index++, "${normalizedPrefix}%")
                }
                ps.setInt(index, maxResults.coerceAtLeast(1))
                ps.executeQuery().use { rs ->
                    val hits = mutableListOf<StoredLexicalHit>()
                    while (rs.next()) {
                        hits.add(
                            StoredLexicalHit(
                                path = rs.getString("path"),
                                line = rs.getInt("line"),
                                column = rs.getInt("column"),
                                snippet = rs.getString("snippet"),
                                normalizedLine = rs.getString("normalized_line"),
                                scoreHint = rs.getDouble("score_hint"),
                                tokenFingerprint = rs.getString("token_fingerprint"),
                            ),
                        )
                    }
                    hits
                }
            }
        }
    }

    fun documentCount(): Long {
        return withReadConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM documents").use { rs ->
                    if (rs.next()) rs.getLong(1) else 0L
                }
            }
        }
    }

    fun allDocuments(): List<StoredDocument> {
        return withReadConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    """
                    SELECT path, content_hash, size_bytes, mtime_ms, indexed_at_ms
                    FROM documents
                    """.trimIndent(),
                ).use { rs ->
                    val docs = mutableListOf<StoredDocument>()
                    while (rs.next()) {
                        docs.add(
                            StoredDocument(
                                path = rs.getString("path"),
                                contentHash = rs.getString("content_hash"),
                                sizeBytes = rs.getLong("size_bytes"),
                                mtimeMs = rs.getLong("mtime_ms"),
                                indexedAtMs = rs.getLong("indexed_at_ms"),
                            ),
                        )
                    }
                    docs
                }
            }
        }
    }

    fun allDocumentPaths(): List<String> {
        return withReadConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT path FROM documents").use { rs ->
                    val paths = mutableListOf<String>()
                    while (rs.next()) {
                        paths.add(rs.getString(1))
                    }
                    paths
                }
            }
        }
    }

    fun pruneByIndexedAt(minIndexedAtMs: Long) {
        withWriteConnection { conn ->
            val stalePaths = mutableListOf<String>()
            conn.prepareStatement("SELECT path FROM documents WHERE indexed_at_ms < ?").use { ps ->
                ps.setLong(1, minIndexedAtMs)
                ps.executeQuery().use { rs ->
                    while (rs.next()) stalePaths.add(rs.getString(1))
                }
            }
            for (path in stalePaths) {
                removePath(path)
            }
        }
    }

    private fun requireReadConnection(): Connection {
        return readConnection ?: throw IllegalStateException("SQLite store is not open")
    }

    private fun requireWriteConnection(): Connection {
        return writeConnection ?: throw IllegalStateException("SQLite store is not open")
    }

    private inline fun <T> withReadConnection(block: (Connection) -> T): T {
        return readLock.withLock {
            block(requireReadConnection())
        }
    }

    private inline fun <T> withWriteConnection(block: (Connection) -> T): T {
        return writeLock.withLock {
            block(requireWriteConnection())
        }
    }

    private fun normalizePath(path: String): String {
        return path.replace('\\', '/')
    }

    override fun close() {
        writeLock.withLock {
            readLock.withLock {
                runCatching { readConnection?.close() }
                    .onFailure { log.warn("Failed closing SQLite read connection", it) }
                runCatching { writeConnection?.close() }
                    .onFailure { log.warn("Failed closing SQLite write connection", it) }
                readConnection = null
                writeConnection = null
            }
        }
    }

    private fun ensureDriverRegistered() {
        if (driverLoaded.get()) return
        synchronized(driverLoaded) {
            if (driverLoaded.get()) return
            try {
                Class.forName("org.sqlite.JDBC")
                driverLoaded.set(true)
            } catch (e: ClassNotFoundException) {
                throw IllegalStateException("SQLite JDBC driver not found on classpath", e)
            }
        }
    }

    companion object {
        private val driverLoaded = AtomicBoolean(false)
    }
}
