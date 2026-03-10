package com.cursorj.indexing

import com.cursorj.acp.messages.*
import com.cursorj.indexing.freshness.IndexFreshnessManager
import com.cursorj.indexing.lexical.LexicalSearchIndex
import com.cursorj.indexing.model.RetrievalHit
import com.cursorj.indexing.model.RetrievalQuery
import com.cursorj.indexing.model.RetrievalResult
import com.cursorj.indexing.rank.HybridFusionRanker
import com.cursorj.indexing.semantic.SemanticChunkIndex
import com.cursorj.indexing.storage.SQLiteIndexStore
import com.cursorj.indexing.symbol.IntelliJSymbolIndexBridge
import com.cursorj.indexing.telemetry.IndexTelemetry
import com.cursorj.settings.CursorJSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class WorkspaceIndexOrchestrator(
    private val project: Project,
    private val settingsProvider: () -> CursorJSettings = { CursorJSettings.instance },
    lexicalOverride: LexicalSearchIndex? = null,
    symbolOverride: IntelliJSymbolIndexBridge? = null,
    semanticOverride: SemanticChunkIndex? = null,
) : Disposable {
    enum class IndexLifecycleState {
        STARTUP_BUILD,
        INCREMENTAL_BUILD,
        READY,
        STALE_REBUILDING,
        FAILED,
    }

    data class IndexLifecycleUpdate(
        val state: IndexLifecycleState,
        val message: String,
        val indexedFiles: Int? = null,
        val skippedFiles: Int? = null,
    )

    private sealed interface ReindexTask {
        data class Upsert(val path: String, val reason: String) : ReindexTask
        data class Remove(val path: String, val reason: String) : ReindexTask
        data class Reconcile(val reason: String) : ReindexTask
        data object StartupWarmup : ReindexTask
    }

    private val log = Logger.getInstance(WorkspaceIndexOrchestrator::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycleListeners = CopyOnWriteArrayList<(IndexLifecycleUpdate) -> Unit>()
    private val reindexQueue = Channel<ReindexTask>(Channel.UNLIMITED)
    private val queueDepth = AtomicLong(0)

    private val settings: CursorJSettings
        get() = settingsProvider()

    private val sqliteStore = project.basePath?.let { SQLiteIndexStore(it) }
    private val lexical = lexicalOverride ?: LexicalSearchIndex(project, sqliteStore)
    private val symbols = symbolOverride ?: IntelliJSymbolIndexBridge(project)
    private val semantic = semanticOverride ?: SemanticChunkIndex()
    private val ranker = HybridFusionRanker()
    private val telemetry = IndexTelemetry()
    private val freshnessManager = IndexFreshnessManager(
        project = project,
        onFileChanged = { path -> onFileChanged(path) },
        onFileRemoved = { path -> onFileRemoved(path) },
        onBulkInvalidation = { reason -> onBulkInvalidation(reason) },
    )
    private var currentState: IndexLifecycleState? = null

    fun start() {
        telemetry.beginSession()
        if (settings.enableLexicalPersistence) {
            runCatching {
                sqliteStore?.open()
                applyStoreLimits()
            }.onFailure { e ->
                log.warn("Failed to initialize SQLite lexical store", e)
                emitLifecycle(
                    IndexLifecycleUpdate(
                        state = IndexLifecycleState.FAILED,
                        message = "Indexing failed to initialize",
                    ),
                )
            }
        }
        freshnessManager.attach()
        scope.launch(Dispatchers.IO) {
            processReindexTasks()
        }
        if (settings.enableLexicalPersistence) {
            enqueueTask(ReindexTask.StartupWarmup)
        } else {
            emitLifecycle(
                IndexLifecycleUpdate(
                    state = IndexLifecycleState.READY,
                    message = "Index ready",
                ),
            )
        }
    }

    suspend fun retrieveForPrompt(
        text: String,
        pathHint: String? = null,
        openFiles: List<String> = emptyList(),
    ): RetrievalResult {
        if (!settings.enableProjectIndexing || text.isBlank()) {
            telemetry.recordFallback("indexing-disabled-or-empty")
            return RetrievalResult()
        }

        val query = RetrievalQuery(
            text = text,
            pathHint = pathHint,
            openFiles = openFiles,
            maxCandidates = settings.retrievalMaxCandidates,
        )
        val startedAt = System.currentTimeMillis()
        val timeoutMs = settings.retrievalTimeoutMs.toLong()

        val result = withTimeoutOrNull(timeoutMs) {
            val lexicalResult = lexical.searchText(
                query = query.text,
                path = query.pathHint,
                maxResults = query.maxCandidates,
                contextLines = 2,
            )
            if (lexicalResult.cacheHit) telemetry.recordCacheHit() else telemetry.recordCacheMiss()
            val symbolHits = toSymbolHits(
                symbols.findSymbols(
                    query = query.text,
                    path = query.pathHint,
                    maxResults = query.maxCandidates,
                ),
            )
            val semanticHits = if (settings.enableSemanticIndexing) {
                semantic.search(query.text, query.maxCandidates)
            } else {
                emptyList()
            }
            val fused = ranker.fuse(
                query = query,
                lexicalHits = lexicalResult.hits,
                symbolHits = symbolHits,
                semanticHits = semanticHits,
                maxResults = query.maxCandidates,
            )
            RetrievalResult(
                hits = fused,
                truncated = lexicalResult.truncated,
            )
        }

        val latency = System.currentTimeMillis() - startedAt
        telemetry.recordQuery("hybrid", latency)
        if (result == null) {
            telemetry.recordFallback("retrieve-timeout")
            return RetrievalResult(latencyMs = latency)
        }
        return result.copy(latencyMs = latency)
    }

    suspend fun findTextInFiles(params: FindTextInFilesParams): FindTextInFilesResult {
        val query = params.query?.takeIf { it.isNotBlank() } ?: params.pattern?.takeIf { it.isNotBlank() } ?: ""
        if (query.isBlank()) {
            return FindTextInFilesResult()
        }
        val startedAt = System.currentTimeMillis()
        val lexicalResult = lexical.searchText(
            query = query,
            path = params.path,
            caseSensitive = params.caseSensitive,
            maxResults = params.maxResults.coerceIn(1, 200),
            contextLines = params.contextLines.coerceIn(0, 5),
        )
        if (lexicalResult.cacheHit) telemetry.recordCacheHit() else telemetry.recordCacheMiss()
        telemetry.recordQuery("lexical", System.currentTimeMillis() - startedAt)
        val matches = lexicalResult.hits.map { it.toTextSearchMatch() }
        return FindTextInFilesResult(matches = matches, truncated = lexicalResult.truncated)
    }

    fun getOpenFiles(): OpenFilesResult {
        val files = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
            .openFiles
            .map { it.path.replace('\\', '/') }
        return OpenFilesResult(files = files)
    }

    suspend fun querySymbols(params: SymbolQueryParams): SymbolQueryResult {
        val startedAt = System.currentTimeMillis()
        val maxResults = params.maxResults.coerceIn(1, 200)
        val symbols = symbols.findSymbols(
            query = params.query,
            path = params.path,
            maxResults = maxResults,
        )
        telemetry.recordQuery("symbol-search", System.currentTimeMillis() - startedAt)
        return SymbolQueryResult(
            symbols = symbols,
            truncated = symbols.size >= maxResults,
        )
    }

    suspend fun listFileSymbols(params: FileSymbolsParams): SymbolQueryResult {
        val startedAt = System.currentTimeMillis()
        val maxResults = params.maxResults.coerceIn(1, 500)
        val symbols = symbols.listFileSymbols(
            path = params.path,
            maxResults = maxResults,
        )
        telemetry.recordQuery("symbol-file", System.currentTimeMillis() - startedAt)
        return SymbolQueryResult(
            symbols = symbols,
            truncated = symbols.size >= maxResults,
        )
    }

    suspend fun findReferences(params: ReferencesParams): ReferencesResult {
        val startedAt = System.currentTimeMillis()
        val maxResults = params.maxResults.coerceIn(1, 500)
        val refs = symbols.findReferences(
            path = params.path,
            line = params.line,
            column = params.column,
            maxResults = maxResults,
        )
        telemetry.recordQuery("symbol-refs", System.currentTimeMillis() - startedAt)
        return ReferencesResult(
            references = refs,
            truncated = refs.size >= maxResults,
        )
    }

    fun notifyFileWritten(path: String) {
        freshnessManager.notifyFileWritten(path)
    }

    fun notifyRollback() {
        freshnessManager.notifyRollback()
    }

    fun requestRebuild(reason: String = "manual-rebuild") {
        enqueueTask(ReindexTask.Reconcile(reason))
    }

    fun telemetrySnapshot(): IndexTelemetry.Snapshot = telemetry.snapshot()

    fun addLifecycleListener(listener: (IndexLifecycleUpdate) -> Unit) {
        lifecycleListeners.add(listener)
    }

    fun removeLifecycleListener(listener: (IndexLifecycleUpdate) -> Unit) {
        lifecycleListeners.remove(listener)
    }

    private fun onFileChanged(path: String) {
        telemetry.recordReindex("file-changed")
        enqueueTask(ReindexTask.Upsert(path.replace('\\', '/'), "file-changed"))
    }

    private fun onFileRemoved(path: String) {
        telemetry.recordReindex("file-removed")
        enqueueTask(ReindexTask.Remove(path.replace('\\', '/'), "file-removed"))
        semantic.remove(path)
    }

    private fun onBulkInvalidation(reason: String) {
        telemetry.recordReindex(reason)
        enqueueTask(ReindexTask.Reconcile(reason))
    }

    private suspend fun processReindexTasks() {
        for (task in reindexQueue) {
            val depth = queueDepth.decrementAndGet().coerceAtLeast(0)
            telemetry.recordQueueDepth(depth)
            processTask(task)
        }
    }

    private suspend fun processTask(task: ReindexTask) {
        when (task) {
            is ReindexTask.StartupWarmup -> runStartupWarmup()
            is ReindexTask.Upsert -> runIncrementalUpsert(task.path)
            is ReindexTask.Remove -> runIncrementalRemove(task.path)
            is ReindexTask.Reconcile -> runReconcile(task.reason)
        }
    }

    private suspend fun runStartupWarmup() {
        val startedAt = System.currentTimeMillis()
        emitLifecycle(
            IndexLifecycleUpdate(
                state = IndexLifecycleState.STARTUP_BUILD,
                message = "Indexing project...",
            ),
        )
        val result = runCatching {
            lexical.warmupWorkspace { indexed, skipped ->
                emitLifecycle(
                    IndexLifecycleUpdate(
                        state = IndexLifecycleState.STARTUP_BUILD,
                        message = "Indexing project... ($indexed indexed, $skipped skipped)",
                        indexedFiles = indexed,
                        skippedFiles = skipped,
                    ),
                )
            }
        }
        result.onSuccess {
            val duration = System.currentTimeMillis() - startedAt
            telemetry.recordStartupIndexingDuration(duration)
            telemetry.recordReadyWithoutManualRebuild()
            applyStoreLimits()
            emitLifecycle(
                IndexLifecycleUpdate(
                    state = IndexLifecycleState.READY,
                    message = "Index ready",
                    indexedFiles = it.indexedFiles,
                    skippedFiles = it.skippedFiles,
                ),
            )
        }.onFailure { e ->
            log.warn("Startup indexing warmup failed", e)
            emitLifecycle(
                IndexLifecycleUpdate(
                    state = IndexLifecycleState.FAILED,
                    message = "Indexing failed (startup)",
                ),
            )
        }
    }

    private suspend fun runIncrementalUpsert(path: String) {
        emitLifecycle(
            IndexLifecycleUpdate(
                state = IndexLifecycleState.INCREMENTAL_BUILD,
                message = "Indexing update: ${File(path).name}",
            ),
        )
        runCatching { lexical.upsertFileFromDisk(path) }
            .onFailure { log.debug("Failed to upsert lexical index for $path: ${it.message}") }

        if (settings.enableSemanticIndexing) {
            val ioFile = File(path)
            if (ioFile.isFile && ioFile.length() <= 512L * 1024L) {
                runCatching { semantic.upsert(path, ioFile.readText()) }
                    .onFailure { log.debug("Semantic index upsert failed for $path: ${it.message}") }
            }
        }
        emitLifecycle(
            IndexLifecycleUpdate(
                state = IndexLifecycleState.READY,
                message = "Index ready",
            ),
        )
    }

    private suspend fun runIncrementalRemove(path: String) {
        runCatching { lexical.removeFile(path) }
            .onFailure { log.debug("Failed removing lexical index for $path: ${it.message}") }
        semantic.remove(path)
    }

    private suspend fun runReconcile(reason: String) {
        emitLifecycle(
            IndexLifecycleUpdate(
                state = IndexLifecycleState.STALE_REBUILDING,
                message = "Index stale - rebuilding...",
            ),
        )
        if (settings.enableSemanticIndexing) {
            semantic.clear()
        }
        runCatching { lexical.warmupWorkspace() }
            .onFailure { log.warn("Index reconcile failed for reason=$reason", it) }
        applyStoreLimits()
        emitLifecycle(
            IndexLifecycleUpdate(
                state = IndexLifecycleState.READY,
                message = "Index ready",
            ),
        )
    }

    private fun enqueueTask(task: ReindexTask) {
        queueDepth.incrementAndGet()
        telemetry.recordQueueDepth(queueDepth.get())
        reindexQueue.trySend(task)
    }

    private fun emitLifecycle(update: IndexLifecycleUpdate) {
        if (currentState == update.state && update.state == IndexLifecycleState.READY) return
        currentState = update.state
        for (listener in lifecycleListeners) {
            try {
                listener(update)
            } catch (e: Exception) {
                log.debug("Lifecycle listener error", e)
            }
        }
    }

    private fun applyStoreLimits() {
        if (!settings.enableLexicalPersistence) return
        val store = sqliteStore ?: return
        val retentionCutoff = System.currentTimeMillis() - (settings.indexRetentionDays.toLong() * 24L * 60L * 60L * 1000L)
        runCatching { store.pruneByIndexedAt(retentionCutoff) }
            .onFailure { log.debug("Failed applying retention cutoff", it) }

        val maxSizeBytes = settings.indexMaxDatabaseMb.toLong() * 1024L * 1024L
        val dbFile = File(store.databasePath())
        if (dbFile.isFile && dbFile.length() > maxSizeBytes) {
            runCatching { store.clearAll() }
                .onFailure { log.warn("Failed clearing oversized index DB", it) }
            emitLifecycle(
                IndexLifecycleUpdate(
                    state = IndexLifecycleState.STALE_REBUILDING,
                    message = "Index oversized - rebuilding",
                ),
            )
            enqueueTask(ReindexTask.Reconcile("db-size-prune"))
        }
    }

    internal suspend fun runStartupWarmupForTest() {
        runStartupWarmup()
    }

    internal suspend fun runIncrementalUpsertForTest(path: String) {
        runIncrementalUpsert(path)
    }

    internal suspend fun runIncrementalRemoveForTest(path: String) {
        runIncrementalRemove(path)
    }

    internal suspend fun runReconcileForTest(reason: String) {
        runReconcile(reason)
    }

    internal suspend fun processSingleQueuedTaskForTest(): Boolean {
        val task = reindexQueue.tryReceive().getOrNull() ?: return false
        val depth = queueDepth.decrementAndGet().coerceAtLeast(0)
        telemetry.recordQueueDepth(depth)
        processTask(task)
        return true
    }

    internal fun queueDepthForTest(): Long = queueDepth.get()

    private fun toSymbolHits(symbols: List<SymbolInfo>): List<RetrievalHit> {
        return symbols.mapNotNull { symbol ->
            val location = symbol.location ?: return@mapNotNull null
            RetrievalHit(
                path = location.path,
                startLine = location.startLine,
                endLine = location.endLine,
                snippet = "${symbol.kind} ${symbol.name}",
                score = symbol.score ?: 0.8,
                source = "symbol",
                symbolName = symbol.name,
            )
        }
    }

    private fun RetrievalHit.toTextSearchMatch(): TextSearchMatch {
        val lines = snippet.lines()
        return TextSearchMatch(
            path = path,
            line = startLine,
            column = 1,
            snippet = snippet,
            before = emptyList(),
            after = if (lines.size > 1) lines.drop(1).take(2) else emptyList(),
            score = score,
        )
    }

    override fun dispose() {
        reindexQueue.close()
        scope.cancel()
        freshnessManager.dispose()
        sqliteStore?.close()
    }
}
