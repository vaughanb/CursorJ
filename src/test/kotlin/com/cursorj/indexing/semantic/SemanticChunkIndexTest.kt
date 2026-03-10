package com.cursorj.indexing.semantic

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticChunkIndexTest {
    @Test
    fun `chunker splits with overlap`() {
        val content = (1..12).joinToString("\n") { "line$it" }
        val chunker = Chunker(linesPerChunk = 5, overlapLines = 2)
        val chunks = chunker.chunk(content)
        assertTrue(chunks.size >= 3)
        assertEquals(1, chunks.first().startLine)
        assertEquals(5, chunks.first().endLine)
        assertTrue(chunks[1].startLine <= chunks.first().endLine)
    }

    @Test
    fun `semantic index returns best token-overlap hits`() = runBlocking {
        val index = SemanticChunkIndex(chunker = Chunker(linesPerChunk = 4, overlapLines = 1))
        index.upsert(
            path = "/repo/src/Auth.kt",
            content = """
                fun login(user: String, pass: String) {}
                fun logout() {}
                fun refreshToken() {}
            """.trimIndent(),
        )
        index.upsert(
            path = "/repo/src/Math.kt",
            content = """
                fun add(a: Int, b: Int): Int = a + b
                fun sub(a: Int, b: Int): Int = a - b
            """.trimIndent(),
        )

        val hits = index.search("refresh token login", maxResults = 3)
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.first().path.endsWith("Auth.kt"))
    }
}
