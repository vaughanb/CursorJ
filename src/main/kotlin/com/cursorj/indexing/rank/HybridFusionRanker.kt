package com.cursorj.indexing.rank

import com.cursorj.indexing.model.RetrievalHit
import com.cursorj.indexing.model.RetrievalQuery
import java.io.File
import kotlin.math.max

class HybridFusionRanker {
    fun fuse(
        query: RetrievalQuery,
        lexicalHits: List<RetrievalHit>,
        symbolHits: List<RetrievalHit>,
        semanticHits: List<RetrievalHit>,
        maxResults: Int = query.maxCandidates,
    ): List<RetrievalHit> {
        val merged = linkedMapOf<String, RetrievalHit>()
        val normalizedOpenFiles = query.openFiles.map { normalizePath(it) }.toSet()
        val hint = query.pathHint?.let { normalizePath(it) }

        fun upsert(hit: RetrievalHit, sourceWeight: Double) {
            val key = "${normalizePath(hit.path)}:${hit.startLine}:${hit.endLine}"
            val boostedScore = boostScore(hit, sourceWeight, normalizedOpenFiles, hint)
            val candidate = hit.copy(score = boostedScore)
            val existing = merged[key]
            if (existing == null || candidate.score > existing.score) {
                merged[key] = candidate
            }
        }

        lexicalHits.forEach { upsert(it, 1.0) }
        symbolHits.forEach { upsert(it, 1.15) }
        semanticHits.forEach { upsert(it, 1.1) }

        return merged.values
            .sortedByDescending { it.score }
            .take(max(1, maxResults))
    }

    private fun boostScore(
        hit: RetrievalHit,
        sourceWeight: Double,
        normalizedOpenFiles: Set<String>,
        pathHint: String?,
    ): Double {
        var score = hit.score * sourceWeight
        val normalizedHitPath = normalizePath(hit.path)
        if (normalizedHitPath in normalizedOpenFiles) {
            score += 0.35
        }
        if (pathHint != null && normalizedHitPath.contains(pathHint, ignoreCase = true)) {
            score += 0.25
        }
        return score
    }

    private fun normalizePath(path: String): String {
        return File(path).path.replace('\\', '/').lowercase()
    }
}
