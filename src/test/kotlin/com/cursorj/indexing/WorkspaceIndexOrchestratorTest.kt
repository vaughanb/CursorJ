package com.cursorj.indexing

import com.cursorj.acp.messages.FileSymbolsParams
import com.cursorj.acp.messages.ReferencesParams
import com.cursorj.acp.messages.SymbolInfo
import com.cursorj.acp.messages.SymbolLocation
import com.cursorj.acp.messages.SymbolQueryParams
import com.cursorj.indexing.lexical.LexicalSearchIndex
import com.cursorj.indexing.model.RetrievalHit
import com.cursorj.indexing.semantic.SemanticChunkIndex
import com.cursorj.indexing.symbol.IntelliJSymbolIndexBridge
import com.cursorj.settings.CursorJSettings
import com.intellij.openapi.project.Project
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceIndexOrchestratorTest {
    @Test
    fun `startup warmup emits startup then ready and records telemetry`() = runBlocking {
        val settings = CursorJSettings().apply {
            enableLexicalPersistence = true
            enableSemanticIndexing = false
        }
        val fakeLexical = FakeLexicalSearchIndex(projectWithBasePath(null))
        val orchestrator = WorkspaceIndexOrchestrator(
            project = projectWithBasePath(null),
            settingsProvider = { settings },
            lexicalOverride = fakeLexical,
        )
        val lifecycle = mutableListOf<WorkspaceIndexOrchestrator.IndexLifecycleState>()
        orchestrator.addLifecycleListener { lifecycle.add(it.state) }

        orchestrator.runStartupWarmupForTest()
        val snapshot = orchestrator.telemetrySnapshot()
        orchestrator.dispose()

        assertTrue(WorkspaceIndexOrchestrator.IndexLifecycleState.STARTUP_BUILD in lifecycle)
        assertTrue(WorkspaceIndexOrchestrator.IndexLifecycleState.READY in lifecycle)
        assertEquals(1, snapshot.startupDurationsMs.size)
        assertEquals(1, snapshot.readyWithoutManualRebuildCount)
        assertTrue(fakeLexical.warmupCalls >= 1)
    }

    @Test
    fun `incremental upsert emits incremental then ready and calls lexical upsert`() = runBlocking {
        val settings = CursorJSettings().apply {
            enableLexicalPersistence = true
            enableSemanticIndexing = false
        }
        val fakeLexical = FakeLexicalSearchIndex(projectWithBasePath(null))
        val orchestrator = WorkspaceIndexOrchestrator(
            project = projectWithBasePath(null),
            settingsProvider = { settings },
            lexicalOverride = fakeLexical,
        )
        val lifecycle = mutableListOf<WorkspaceIndexOrchestrator.IndexLifecycleState>()
        orchestrator.addLifecycleListener { lifecycle.add(it.state) }

        orchestrator.runIncrementalUpsertForTest("C:/tmp/Foo.kt")
        orchestrator.dispose()

        assertTrue(fakeLexical.upsertPaths.contains("C:/tmp/Foo.kt"))
        assertTrue(lifecycle.contains(WorkspaceIndexOrchestrator.IndexLifecycleState.INCREMENTAL_BUILD))
        assertTrue(lifecycle.contains(WorkspaceIndexOrchestrator.IndexLifecycleState.READY))
    }

    @Test
    fun `incremental remove calls lexical remove`() = runBlocking {
        val settings = CursorJSettings().apply {
            enableLexicalPersistence = true
            enableSemanticIndexing = false
        }
        val fakeLexical = FakeLexicalSearchIndex(projectWithBasePath(null))
        val orchestrator = WorkspaceIndexOrchestrator(
            project = projectWithBasePath(null),
            settingsProvider = { settings },
            lexicalOverride = fakeLexical,
        )

        orchestrator.runIncrementalRemoveForTest("C:/tmp/DeleteMe.kt")
        orchestrator.dispose()

        assertTrue(fakeLexical.removedPaths.contains("C:/tmp/DeleteMe.kt"))
    }

    @Test
    fun `reconcile emits stale then ready and warms up lexical`() = runBlocking {
        val settings = CursorJSettings().apply {
            enableLexicalPersistence = true
            enableSemanticIndexing = false
        }
        val fakeLexical = FakeLexicalSearchIndex(projectWithBasePath(null))
        val orchestrator = WorkspaceIndexOrchestrator(
            project = projectWithBasePath(null),
            settingsProvider = { settings },
            lexicalOverride = fakeLexical,
        )
        val lifecycle = mutableListOf<WorkspaceIndexOrchestrator.IndexLifecycleState>()
        orchestrator.addLifecycleListener { lifecycle.add(it.state) }

        orchestrator.runReconcileForTest("manual")
        orchestrator.dispose()

        assertTrue(lifecycle.contains(WorkspaceIndexOrchestrator.IndexLifecycleState.STALE_REBUILDING))
        assertTrue(lifecycle.contains(WorkspaceIndexOrchestrator.IndexLifecycleState.READY))
        assertTrue(fakeLexical.warmupCalls >= 1)
    }

    @Test
    fun `queued tasks process in order for rebuild and file updates`() = runBlocking {
        val settings = CursorJSettings().apply {
            enableLexicalPersistence = true
            enableSemanticIndexing = false
        }
        val fakeLexical = FakeLexicalSearchIndex(projectWithBasePath(null))
        val orchestrator = WorkspaceIndexOrchestrator(
            project = projectWithBasePath(null),
            settingsProvider = { settings },
            lexicalOverride = fakeLexical,
        )

        orchestrator.requestRebuild("manual")
        assertEquals(1, orchestrator.queueDepthForTest())
        assertTrue(orchestrator.processSingleQueuedTaskForTest())
        assertEquals(0, orchestrator.queueDepthForTest())
        assertTrue(fakeLexical.warmupCalls >= 1)

        orchestrator.notifyFileWritten("C:/tmp/Queued.kt")
        assertEquals(1, orchestrator.queueDepthForTest())
        assertTrue(orchestrator.processSingleQueuedTaskForTest())
        assertEquals(0, orchestrator.queueDepthForTest())
        assertTrue(fakeLexical.upsertPaths.contains("C:/tmp/Queued.kt"))

        assertFalse(orchestrator.processSingleQueuedTaskForTest())
        orchestrator.dispose()
    }

    @Test
    fun `duplicate rebuild requests are coalesced`() = runBlocking {
        val settings = CursorJSettings().apply {
            enableLexicalPersistence = true
            enableSemanticIndexing = false
        }
        val fakeLexical = FakeLexicalSearchIndex(projectWithBasePath(null))
        val orchestrator = WorkspaceIndexOrchestrator(
            project = projectWithBasePath(null),
            settingsProvider = { settings },
            lexicalOverride = fakeLexical,
        )

        orchestrator.requestRebuild("manual-a")
        orchestrator.requestRebuild("manual-b")
        assertEquals(1, orchestrator.queueDepthForTest())
        assertTrue(orchestrator.processSingleQueuedTaskForTest())
        assertEquals(0, orchestrator.queueDepthForTest())
        assertTrue(fakeLexical.warmupCalls >= 1)
        assertFalse(orchestrator.processSingleQueuedTaskForTest())
        orchestrator.dispose()
    }

    @Test
    fun `retrieveForPrompt fuses lexical symbol and semantic hits`() = runBlocking {
        val settings = CursorJSettings().apply {
            enableProjectIndexing = true
            enableLexicalPersistence = false
            enableSemanticIndexing = true
            retrievalMaxCandidates = 10
            retrievalTimeoutMs = 1000
        }
        val project = projectWithBasePath(null)
        val fakeLexical = FakeLexicalSearchIndex(project).apply {
            nextSearchResult = LexicalSearchIndex.SearchResult(
                hits = listOf(
                    RetrievalHit(
                        path = "/repo/src/UserService.kt",
                        startLine = 20,
                        endLine = 20,
                        snippet = "fun fetchUser(id: String)",
                        score = 0.7,
                        source = "lexical",
                    ),
                ),
                truncated = false,
                cacheHit = true,
            )
        }
        val fakeSymbols = FakeSymbolIndexBridge(project).apply {
            findSymbolsSeed = listOf(
                SymbolInfo(
                    id = "symbol-1",
                    kind = "KtNamedFunction",
                    name = "fetchUser",
                    location = SymbolLocation(
                        path = "/repo/src/UserService.kt",
                        startLine = 5,
                        startColumn = 1,
                        endLine = 5,
                        endColumn = 10,
                    ),
                    score = 0.8,
                ),
            )
        }
        val semantic = SemanticChunkIndex()
        semantic.upsert(
            "/repo/src/Auth.kt",
            """
            fun refreshToken(token: String): String = token
            fun logout() {}
            """.trimIndent(),
        )
        val orchestrator = WorkspaceIndexOrchestrator(
            project = project,
            settingsProvider = { settings },
            lexicalOverride = fakeLexical,
            symbolOverride = fakeSymbols,
            semanticOverride = semantic,
        )

        val result = orchestrator.retrieveForPrompt(
            text = "fetch user refresh token",
            pathHint = "/repo/src",
            openFiles = listOf("/repo/src/UserService.kt"),
        )
        val snapshot = orchestrator.telemetrySnapshot()
        orchestrator.dispose()

        assertTrue(result.hits.any { it.source == "lexical" })
        assertTrue(result.hits.any { it.source == "symbol" })
        assertTrue(result.hits.any { it.source == "semantic" })
        assertEquals(1L, snapshot.queryByEngine["hybrid"])
        assertEquals(1L, snapshot.cacheHitCount)
    }

    @Test
    fun `retrieveForPrompt short-circuits when indexing disabled`() = runBlocking {
        val settings = CursorJSettings().apply {
            enableProjectIndexing = false
            enableSemanticIndexing = true
        }
        val project = projectWithBasePath(null)
        val fakeLexical = FakeLexicalSearchIndex(project)
        val orchestrator = WorkspaceIndexOrchestrator(
            project = project,
            settingsProvider = { settings },
            lexicalOverride = fakeLexical,
            symbolOverride = FakeSymbolIndexBridge(project),
            semanticOverride = SemanticChunkIndex(),
        )

        val result = orchestrator.retrieveForPrompt("anything")
        val snapshot = orchestrator.telemetrySnapshot()
        orchestrator.dispose()

        assertTrue(result.hits.isEmpty())
        assertEquals(0, fakeLexical.searchCalls)
        assertEquals(1L, snapshot.fallbackByReason["indexing-disabled-or-empty"])
    }

    @Test
    fun `retrieveForPrompt timeout records fallback reason`() = runBlocking {
        val settings = CursorJSettings().apply {
            enableProjectIndexing = true
            enableSemanticIndexing = false
            retrievalTimeoutMs = 250
        }
        val project = projectWithBasePath(null)
        val fakeLexical = FakeLexicalSearchIndex(project).apply {
            searchDelayMs = 350
        }
        val orchestrator = WorkspaceIndexOrchestrator(
            project = project,
            settingsProvider = { settings },
            lexicalOverride = fakeLexical,
            symbolOverride = FakeSymbolIndexBridge(project),
        )

        val result = orchestrator.retrieveForPrompt("slow query")
        val snapshot = orchestrator.telemetrySnapshot()
        orchestrator.dispose()

        assertTrue(result.hits.isEmpty())
        assertEquals(1L, snapshot.fallbackByReason["retrieve-timeout"])
        assertEquals(1L, snapshot.queryByEngine["hybrid"])
    }

    @Test
    fun `symbol query APIs clamp max results before bridge calls`() = runBlocking {
        val settings = CursorJSettings().apply {
            enableProjectIndexing = true
            enableSemanticIndexing = false
        }
        val project = projectWithBasePath(null)
        val fakeSymbols = FakeSymbolIndexBridge(project).apply {
            findSymbolsSeed = List(3) { index ->
                SymbolInfo(
                    id = "f-$index",
                    kind = "KtNamedFunction",
                    name = "fn$index",
                    location = SymbolLocation("/repo/Foo.kt", index + 1, 1, index + 1, 5),
                )
            }
            fileSymbolsSeed = List(600) { index ->
                SymbolInfo(
                    id = "s-$index",
                    kind = "KtClass",
                    name = "C$index",
                    location = SymbolLocation("/repo/Bar.kt", index + 1, 1, index + 1, 5),
                )
            }
            referencesSeed = List(600) { index ->
                SymbolLocation("/repo/Baz.kt", index + 1, 1, index + 1, 3)
            }
        }
        val orchestrator = WorkspaceIndexOrchestrator(
            project = project,
            settingsProvider = { settings },
            lexicalOverride = FakeLexicalSearchIndex(project),
            symbolOverride = fakeSymbols,
        )

        val queryResult = orchestrator.querySymbols(SymbolQueryParams(query = "fn", maxResults = 0))
        val fileResult = orchestrator.listFileSymbols(FileSymbolsParams(path = "/repo/Bar.kt", maxResults = 999))
        val refsResult = orchestrator.findReferences(ReferencesParams(path = "/repo/Baz.kt", line = 2, column = 1, maxResults = 999))
        orchestrator.dispose()

        assertEquals(1, fakeSymbols.lastFindSymbolsMaxResults)
        assertEquals(500, fakeSymbols.lastListFileSymbolsMaxResults)
        assertEquals(500, fakeSymbols.lastFindReferencesMaxResults)
        assertTrue(queryResult.truncated)
        assertTrue(fileResult.truncated)
        assertTrue(refsResult.truncated)
    }

    private class FakeLexicalSearchIndex(project: Project) : LexicalSearchIndex(project) {
        var warmupCalls = 0
        val upsertPaths = mutableListOf<String>()
        val removedPaths = mutableListOf<String>()
        var searchCalls = 0
        var searchDelayMs = 0L
        var nextSearchResult = SearchResult(
            hits = emptyList(),
            truncated = false,
            cacheHit = false,
        )

        override suspend fun searchText(
            query: String,
            path: String?,
            caseSensitive: Boolean,
            maxResults: Int,
            contextLines: Int,
            maxFileSizeBytes: Long,
        ): SearchResult {
            searchCalls++
            if (searchDelayMs > 0) delay(searchDelayMs)
            return nextSearchResult
        }

        override suspend fun warmupWorkspace(
            maxFileSizeBytes: Long,
            onProgress: ((indexed: Int, skipped: Int) -> Unit)?,
        ): WarmupResult {
            warmupCalls++
            onProgress?.invoke(2, 1)
            return WarmupResult(indexedFiles = 2, skippedFiles = 1, removedFiles = 0)
        }

        override suspend fun upsertFileFromDisk(path: String, maxFileSizeBytes: Long): Boolean {
            upsertPaths.add(path)
            return true
        }

        override fun removeFile(path: String): Boolean {
            removedPaths.add(path)
            return true
        }
    }

    private class FakeSymbolIndexBridge(project: Project) : IntelliJSymbolIndexBridge(project) {
        var findSymbolsSeed: List<SymbolInfo> = emptyList()
        var fileSymbolsSeed: List<SymbolInfo> = emptyList()
        var referencesSeed: List<SymbolLocation> = emptyList()
        var lastFindSymbolsMaxResults: Int? = null
        var lastListFileSymbolsMaxResults: Int? = null
        var lastFindReferencesMaxResults: Int? = null

        override suspend fun findSymbols(query: String, path: String?, maxResults: Int): List<SymbolInfo> {
            lastFindSymbolsMaxResults = maxResults
            return findSymbolsSeed.take(maxResults)
        }

        override suspend fun listFileSymbols(path: String, maxResults: Int): List<SymbolInfo> {
            lastListFileSymbolsMaxResults = maxResults
            return fileSymbolsSeed.take(maxResults)
        }

        override suspend fun findReferences(path: String, line: Int, column: Int, maxResults: Int): List<SymbolLocation> {
            lastFindReferencesMaxResults = maxResults
            return referencesSeed.take(maxResults)
        }
    }

    private fun projectWithBasePath(basePath: String?): Project {
        return Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getBasePath" -> basePath
                "isDisposed" -> false
                "isDefault" -> false
                "getName" -> "test-project"
                "getLocationHash" -> "test-hash"
                else -> defaultValue(method.returnType)
            }
        } as Project
    }

    private fun defaultValue(returnType: Class<*>): Any? {
        return when (returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Double.TYPE -> 0.0
            java.lang.Float.TYPE -> 0f
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }
    }
}
