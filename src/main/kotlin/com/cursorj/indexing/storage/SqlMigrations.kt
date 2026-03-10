package com.cursorj.indexing.storage

import java.sql.Connection

object SqlMigrations {
    const val SCHEMA_VERSION = 1

    fun migrate(connection: Connection) {
        connection.createStatement().use { stmt ->
            stmt.execute("PRAGMA journal_mode=WAL")
            stmt.execute("PRAGMA synchronous=NORMAL")
            stmt.execute("PRAGMA temp_store=MEMORY")
            stmt.execute("PRAGMA foreign_keys=ON")
        }

        val current = currentVersion(connection)
        if (current == 0) {
            applySchemaV1(connection)
            setVersion(connection, SCHEMA_VERSION)
        }
    }

    private fun currentVersion(connection: Connection): Int {
        connection.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS index_meta(
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """.trimIndent(),
            )
        }
        connection.prepareStatement("SELECT value FROM index_meta WHERE key = 'schema_version'").use { ps ->
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    return rs.getString(1)?.toIntOrNull() ?: 0
                }
            }
        }
        return 0
    }

    private fun setVersion(connection: Connection, version: Int) {
        connection.prepareStatement(
            """
            INSERT INTO index_meta(key, value) VALUES('schema_version', ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, version.toString())
            ps.executeUpdate()
        }
    }

    private fun applySchemaV1(connection: Connection) {
        connection.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS documents(
                    path TEXT PRIMARY KEY,
                    content_hash TEXT NOT NULL,
                    size_bytes INTEGER NOT NULL,
                    mtime_ms INTEGER NOT NULL,
                    language TEXT,
                    indexed_at_ms INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS lexical_hits(
                    path TEXT NOT NULL,
                    line INTEGER NOT NULL,
                    column INTEGER NOT NULL,
                    snippet TEXT NOT NULL,
                    normalized_line TEXT NOT NULL,
                    score_hint REAL NOT NULL,
                    token_fingerprint TEXT,
                    PRIMARY KEY(path, line, column)
                )
                """.trimIndent(),
            )
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_lexical_hits_path ON lexical_hits(path)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_lexical_hits_norm ON lexical_hits(normalized_line)")
        }
    }
}
