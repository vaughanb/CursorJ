package com.cursorj.handlers

import com.cursorj.acp.messages.*
import com.cursorj.indexing.WorkspaceIndexOrchestrator
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

class IndexSearchHandler(
    private val orchestrator: WorkspaceIndexOrchestrator,
    private val operations: Operations = OrchestratorOperations(orchestrator),
) {
    private val log = Logger.getInstance(IndexSearchHandler::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    fun handle(method: String, params: JsonElement): JsonElement? {
        return when (method) {
            "fs/find_text_in_files" -> {
                val request = json.decodeFromJsonElement(FindTextInFilesParams.serializer(), params)
                val result = runBlocking { operations.findTextInFiles(request) }
                json.encodeToJsonElement(FindTextInFilesResult.serializer(), result)
            }
            "editor/get_open_files" -> {
                val result = operations.getOpenFiles()
                json.encodeToJsonElement(OpenFilesResult.serializer(), result)
            }
            "editor/find_symbol" -> {
                val request = json.decodeFromJsonElement(SymbolQueryParams.serializer(), params)
                val result = runBlocking { operations.querySymbols(request) }
                json.encodeToJsonElement(SymbolQueryResult.serializer(), result)
            }
            "editor/list_file_symbols" -> {
                val request = json.decodeFromJsonElement(FileSymbolsParams.serializer(), params)
                val result = runBlocking { operations.listFileSymbols(request) }
                json.encodeToJsonElement(SymbolQueryResult.serializer(), result)
            }
            "editor/find_references" -> {
                val request = json.decodeFromJsonElement(ReferencesParams.serializer(), params)
                val result = runBlocking { operations.findReferences(request) }
                json.encodeToJsonElement(ReferencesResult.serializer(), result)
            }
            else -> null
        }
    }

    interface Operations {
        suspend fun findTextInFiles(params: FindTextInFilesParams): FindTextInFilesResult
        fun getOpenFiles(): OpenFilesResult
        suspend fun querySymbols(params: SymbolQueryParams): SymbolQueryResult
        suspend fun listFileSymbols(params: FileSymbolsParams): SymbolQueryResult
        suspend fun findReferences(params: ReferencesParams): ReferencesResult
    }

    private class OrchestratorOperations(
        private val orchestrator: WorkspaceIndexOrchestrator,
    ) : Operations {
        override suspend fun findTextInFiles(params: FindTextInFilesParams): FindTextInFilesResult {
            return orchestrator.findTextInFiles(params)
        }

        override fun getOpenFiles(): OpenFilesResult {
            return orchestrator.getOpenFiles()
        }

        override suspend fun querySymbols(params: SymbolQueryParams): SymbolQueryResult {
            return orchestrator.querySymbols(params)
        }

        override suspend fun listFileSymbols(params: FileSymbolsParams): SymbolQueryResult {
            return orchestrator.listFileSymbols(params)
        }

        override suspend fun findReferences(params: ReferencesParams): ReferencesResult {
            return orchestrator.findReferences(params)
        }
    }
}
