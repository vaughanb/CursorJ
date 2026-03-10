package com.cursorj.indexing.semantic

class Chunker(
    private val linesPerChunk: Int = 40,
    private val overlapLines: Int = 10,
) {
    data class Chunk(
        val startLine: Int,
        val endLine: Int,
        val text: String,
    )

    fun chunk(content: String): List<Chunk> {
        if (content.isBlank()) return emptyList()
        val lines = content.lines()
        val result = mutableListOf<Chunk>()
        var index = 0
        while (index < lines.size) {
            val endExclusive = (index + linesPerChunk).coerceAtMost(lines.size)
            val chunkLines = lines.subList(index, endExclusive)
            if (chunkLines.any { it.isNotBlank() }) {
                result.add(
                    Chunk(
                        startLine = index + 1,
                        endLine = endExclusive,
                        text = chunkLines.joinToString("\n"),
                    ),
                )
            }
            if (endExclusive == lines.size) break
            index = (endExclusive - overlapLines).coerceAtLeast(index + 1)
        }
        return result
    }
}
