package com.cursorj.indexing.semantic

import com.cursorj.indexing.model.RetrievalHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

class SemanticChunkIndex(
    private val chunker: Chunker = Chunker(),
    private val embeddingProvider: EmbeddingProvider? = null,
) {
    private data class IndexedChunk(
        val path: String,
        val startLine: Int,
        val endLine: Int,
        val text: String,
        val embedding: List<Double>? = null,
    )

    private val chunksByPath = ConcurrentHashMap<String, List<IndexedChunk>>()

    suspend fun upsert(path: String, content: String) {
        val normalized = normalizePath(path)
        val chunks = chunker.chunk(content)
        if (chunks.isEmpty()) {
            chunksByPath.remove(normalized)
            return
        }

        val provider = embeddingProvider
        val embeddings = if (provider != null) {
            runCatching { provider.embed(chunks.map { it.text }) }.getOrNull()
        } else {
            null
        }

        val indexed = chunks.mapIndexed { idx, chunk ->
            IndexedChunk(
                path = normalized,
                startLine = chunk.startLine,
                endLine = chunk.endLine,
                text = chunk.text,
                embedding = embeddings?.getOrNull(idx),
            )
        }
        chunksByPath[normalized] = indexed
    }

    fun remove(path: String) {
        chunksByPath.remove(normalizePath(path))
    }

    fun clear() {
        chunksByPath.clear()
    }

    suspend fun search(query: String, maxResults: Int): List<RetrievalHit> = withContext(Dispatchers.Default) {
        val allChunks = chunksByPath.values.flatten()
        if (allChunks.isEmpty() || query.isBlank()) return@withContext emptyList()

        val queryEmbedding = embeddingProvider?.let {
            runCatching { it.embed(listOf(query)).firstOrNull() }.getOrNull()
        }

        allChunks.asSequence()
            .map { chunk ->
                val score = if (queryEmbedding != null && chunk.embedding != null) {
                    cosineSimilarity(queryEmbedding, chunk.embedding)
                } else {
                    tokenOverlapScore(query, chunk.text)
                }
                RetrievalHit(
                    path = chunk.path,
                    startLine = chunk.startLine,
                    endLine = chunk.endLine,
                    snippet = chunk.text,
                    score = score,
                    source = "semantic",
                )
            }
            .filter { it.score > 0.0 }
            .sortedByDescending { it.score }
            .take(maxResults.coerceAtLeast(1))
            .toList()
    }

    private fun tokenOverlapScore(query: String, text: String): Double {
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return 0.0
        val textTokens = tokenize(text)
        if (textTokens.isEmpty()) return 0.0
        val overlap = queryTokens.intersect(textTokens).size
        return overlap.toDouble() / queryTokens.size.toDouble()
    }

    private fun tokenize(value: String): Set<String> {
        return Regex("[A-Za-z0-9_./-]+")
            .findAll(value.lowercase())
            .map { it.value }
            .filter { it.length > 1 }
            .toSet()
    }

    private fun cosineSimilarity(a: List<Double>, b: List<Double>): Double {
        val size = minOf(a.size, b.size)
        if (size == 0) return 0.0
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in 0 until size) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA <= 0.0 || normB <= 0.0) return 0.0
        return dot / (sqrt(normA) * sqrt(normB))
    }

    private fun normalizePath(path: String): String {
        return File(path).path.replace('\\', '/')
    }
}
