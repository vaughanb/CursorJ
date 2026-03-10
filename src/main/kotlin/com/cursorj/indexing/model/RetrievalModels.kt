package com.cursorj.indexing.model

data class RetrievalQuery(
    val text: String,
    val pathHint: String? = null,
    val openFiles: List<String> = emptyList(),
    val maxCandidates: Int = 40,
)

data class RetrievalHit(
    val path: String,
    val startLine: Int,
    val endLine: Int,
    val snippet: String,
    val score: Double,
    val source: String,
    val symbolName: String? = null,
)

data class RetrievalResult(
    val hits: List<RetrievalHit> = emptyList(),
    val truncated: Boolean = false,
    val latencyMs: Long = 0,
)

data class SymbolNode(
    val id: String,
    val kind: String,
    val name: String,
    val filePath: String,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
)
