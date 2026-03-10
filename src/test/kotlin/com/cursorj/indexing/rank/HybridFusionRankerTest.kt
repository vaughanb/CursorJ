package com.cursorj.indexing.rank

import com.cursorj.indexing.model.RetrievalHit
import com.cursorj.indexing.model.RetrievalQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HybridFusionRankerTest {
    @Test
    fun `fuse prefers higher weighted sources and open file boosts`() {
        val ranker = HybridFusionRanker()
        val query = RetrievalQuery(
            text = "fetch user",
            openFiles = listOf("/repo/src/UserService.kt"),
            maxCandidates = 5,
        )

        val lexical = listOf(
            RetrievalHit("/repo/src/UserService.kt", 10, 14, "fun fetchUser()", 0.8, "lexical"),
        )
        val symbol = listOf(
            RetrievalHit("/repo/src/UserService.kt", 10, 10, "UserService.fetchUser", 0.7, "symbol"),
        )
        val semantic = listOf(
            RetrievalHit("/repo/src/Other.kt", 4, 8, "user lookup helper", 0.95, "semantic"),
        )

        val fused = ranker.fuse(query, lexical, symbol, semantic)
        assertEquals(3, fused.size)
        assertTrue(fused.first().path.endsWith("UserService.kt"))
    }
}
