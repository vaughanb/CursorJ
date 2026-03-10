package com.cursorj.indexing.semantic

interface EmbeddingProvider {
    suspend fun embed(texts: List<String>): List<List<Double>>
}
