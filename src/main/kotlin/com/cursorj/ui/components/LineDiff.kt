package com.cursorj.ui.components

enum class DiffLineType { CONTEXT, ADD, REMOVE }

data class DiffLine(val type: DiffLineType, val text: String)

data class DiffHunk(val lines: List<DiffLine>)

data class DiffResult(
    val hunks: List<DiffHunk>,
    val linesAdded: Int,
    val linesRemoved: Int,
    val firstChangedLine: Int,
    val addedLineNumbers: List<Int>,
)

object LineDiff {
    private const val CONTEXT_LINES = 3

    fun compute(oldText: String, newText: String): DiffResult {
        val oldLines = oldText.lines()
        val newLines = newText.lines()
        val lcs = lcs(oldLines, newLines)

        val diffLines = mutableListOf<DiffLine>()
        val addedNewLines = mutableListOf<Int>()
        var oi = 0
        var ni = 0
        var li = 0
        var firstChanged = -1
        while (oi < oldLines.size || ni < newLines.size) {
            if (li < lcs.size && oi < oldLines.size && ni < newLines.size &&
                oldLines[oi] == lcs[li] && newLines[ni] == lcs[li]
            ) {
                diffLines.add(DiffLine(DiffLineType.CONTEXT, oldLines[oi]))
                oi++; ni++; li++
            } else {
                if (firstChanged < 0) firstChanged = ni
                if (oi < oldLines.size && (li >= lcs.size || oldLines[oi] != lcs[li])) {
                    diffLines.add(DiffLine(DiffLineType.REMOVE, oldLines[oi]))
                    oi++
                } else if (ni < newLines.size && (li >= lcs.size || newLines[ni] != lcs[li])) {
                    diffLines.add(DiffLine(DiffLineType.ADD, newLines[ni]))
                    addedNewLines.add(ni)
                    ni++
                }
            }
        }

        val added = diffLines.count { it.type == DiffLineType.ADD }
        val removed = diffLines.count { it.type == DiffLineType.REMOVE }
        val hunks = buildHunks(diffLines)
        val firstLine = if (firstChanged >= 0) firstChanged + 1 else 1
        return DiffResult(hunks, added, removed, firstLine, addedNewLines)
    }

    private fun buildHunks(allLines: List<DiffLine>): List<DiffHunk> {
        val changeIndices = allLines.indices.filter { allLines[it].type != DiffLineType.CONTEXT }
        if (changeIndices.isEmpty()) return emptyList()

        val regions = mutableListOf<IntRange>()
        var start = (changeIndices.first() - CONTEXT_LINES).coerceAtLeast(0)
        var end = (changeIndices.first() + CONTEXT_LINES).coerceAtMost(allLines.lastIndex)

        for (i in 1 until changeIndices.size) {
            val cStart = (changeIndices[i] - CONTEXT_LINES).coerceAtLeast(0)
            val cEnd = (changeIndices[i] + CONTEXT_LINES).coerceAtMost(allLines.lastIndex)
            if (cStart <= end + 1) {
                end = cEnd
            } else {
                regions.add(start..end)
                start = cStart
                end = cEnd
            }
        }
        regions.add(start..end)

        return regions.map { range -> DiffHunk(allLines.subList(range.first, range.last + 1)) }
    }

    private fun lcs(a: List<String>, b: List<String>): List<String> {
        val m = a.size
        val n = b.size
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1] + 1
                else maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }
        val result = mutableListOf<String>()
        var i = m; var j = n
        while (i > 0 && j > 0) {
            when {
                a[i - 1] == b[j - 1] -> { result.add(a[i - 1]); i--; j-- }
                dp[i - 1][j] >= dp[i][j - 1] -> i--
                else -> j--
            }
        }
        return result.reversed()
    }
}
