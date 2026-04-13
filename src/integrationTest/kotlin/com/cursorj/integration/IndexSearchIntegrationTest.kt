package com.cursorj.integration

import com.cursorj.handlers.IndexSearchHandler
import com.cursorj.indexing.WorkspaceIndexOrchestrator
import com.cursorj.settings.CursorJSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class IndexSearchIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `index search handler returns lexical matches and picks up file updates`() {
        AgentCliIntegrationSupport.assumeManualOnlyEnabled()
        AgentCliIntegrationSupport.assumeIdeApplicationAvailable()

        val workspace = Files.createTempDirectory("cursorj-index-search-it").toFile()
        val sourceFile = File(workspace, "src/IntegrationSample.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package sample

                fun integrationSymbol(): String = "initial-token"
                """.trimIndent(),
            )
        }
        val project = AgentCliIntegrationSupport.projectWithBasePath(workspace.absolutePath)
        val settings = CursorJSettings().apply {
            enableProjectIndexing = true
            enableLexicalPersistence = true
            enableSemanticIndexing = false
        }

        val orchestrator = WorkspaceIndexOrchestrator(
            project = project,
            settingsProvider = { settings },
        )
        try {
            orchestrator.start()
            val handler = IndexSearchHandler(orchestrator)

            val initial = waitForLexicalMatch(
                handler = handler,
                query = "initial-token",
                path = workspace.absolutePath,
                timeoutMs = 20_000L,
            )
            assertTrue(initial.isNotEmpty(), "Expected lexical search to find initial token")

            sourceFile.writeText(
                """
                package sample

                fun integrationSymbol(): String = "updated-token"
                """.trimIndent(),
            )
            orchestrator.notifyFileWritten(sourceFile.absolutePath)

            val updated = waitForLexicalMatch(
                handler = handler,
                query = "updated-token",
                path = workspace.absolutePath,
                timeoutMs = 20_000L,
            )
            assertTrue(updated.any { it.contains("IntegrationSample.kt") }, "Expected updated content to be indexed")

            val symbolResponse = handler.handle(
                "editor/find_symbol",
                buildJsonObject {
                    put("query", "integrationSymbol")
                    put("path", workspace.absolutePath)
                    put("maxResults", 10)
                },
            )
            assertTrue(symbolResponse != null, "Expected symbol search request to return a JSON payload")
        } finally {
            orchestrator.dispose()
            workspace.deleteRecursively()
        }
    }

    private fun waitForLexicalMatch(
        handler: IndexSearchHandler,
        query: String,
        path: String,
        timeoutMs: Long,
    ): List<String> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val response = handler.handle(
                "fs/find_text_in_files",
                buildJsonObject {
                    put("query", query)
                    put("path", path)
                    put("maxResults", 20)
                },
            ) ?: error("Handler unexpectedly returned null")
            val matches = response
                .jsonObject["matches"]
                ?.jsonArray
                ?.mapNotNull { it.jsonObject["path"]?.jsonPrimitive?.content }
                .orEmpty()
            if (matches.isNotEmpty()) {
                return matches
            }
            Thread.sleep(100)
        }
        return emptyList()
    }
}
