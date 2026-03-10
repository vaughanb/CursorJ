package com.cursorj.handlers

import com.cursorj.acp.messages.FileSymbolsParams
import com.cursorj.acp.messages.FindTextInFilesParams
import com.cursorj.acp.messages.FindTextInFilesResult
import com.cursorj.acp.messages.OpenFilesResult
import com.cursorj.acp.messages.ReferencesParams
import com.cursorj.acp.messages.ReferencesResult
import com.cursorj.acp.messages.SymbolInfo
import com.cursorj.acp.messages.SymbolLocation
import com.cursorj.acp.messages.SymbolQueryParams
import com.cursorj.acp.messages.SymbolQueryResult
import com.cursorj.indexing.WorkspaceIndexOrchestrator
import com.cursorj.settings.CursorJSettings
import com.intellij.openapi.project.Project
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndexSearchHandlerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `find_text_in_files returns serialized matches`() {
        val root = Files.createTempDirectory("cursorj-index-handler").toFile()
        try {
            val src = File(root, "src")
            src.mkdirs()
            File(src, "Main.kt").writeText(
                """
                package demo
                fun findMe() = "ok"
                """.trimIndent(),
            )

            val settings = CursorJSettings().apply {
                enableProjectIndexing = true
                enableLexicalPersistence = false
            }
            val project = projectWithBasePath(root.absolutePath)
            val orchestrator = WorkspaceIndexOrchestrator(
                project = project,
                settingsProvider = { settings },
            )
            val handler = IndexSearchHandler(orchestrator)

            val resultElement = handler.handle(
                method = "fs/find_text_in_files",
                params = buildJsonObject {
                    put("query", "findMe")
                    put("maxResults", 10)
                },
            )
            assertNotNull(resultElement)
            val result = json.decodeFromJsonElement(FindTextInFilesResult.serializer(), resultElement)
            assertTrue(result.matches.isNotEmpty())
            assertTrue(result.matches.any { it.path.endsWith("/src/Main.kt") })
            orchestrator.dispose()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `find_text_in_files accepts pattern fallback`() {
        val root = Files.createTempDirectory("cursorj-index-handler-pattern").toFile()
        try {
            File(root, "A.kt").writeText("val tokenPattern = 1")
            val settings = CursorJSettings().apply {
                enableProjectIndexing = true
                enableLexicalPersistence = false
            }
            val orchestrator = WorkspaceIndexOrchestrator(
                project = projectWithBasePath(root.absolutePath),
                settingsProvider = { settings },
            )
            val handler = IndexSearchHandler(orchestrator)

            val resultElement = handler.handle(
                method = "fs/find_text_in_files",
                params = buildJsonObject {
                    put("pattern", "tokenPattern")
                    put("maxResults", 5)
                },
            )
            assertNotNull(resultElement)
            val result = json.decodeFromJsonElement(FindTextInFilesResult.serializer(), resultElement)
            assertEquals(1, result.matches.size)
            orchestrator.dispose()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `editor get_open_files returns encoded file list`() {
        val ops = FakeOperations(
            openFilesResult = OpenFilesResult(files = listOf("/a.kt", "/b.kt")),
        )
        val orchestrator = makeOrchestrator()
        try {
            val handler = IndexSearchHandler(orchestrator, ops)
            val resultElement = handler.handle("editor/get_open_files", buildJsonObject {})
            assertNotNull(resultElement)
            val result = json.decodeFromJsonElement(OpenFilesResult.serializer(), resultElement)
            assertEquals(listOf("/a.kt", "/b.kt"), result.files)
        } finally {
            orchestrator.dispose()
        }
    }

    @Test
    fun `editor find_symbol decodes params and encodes symbols`() {
        val expected = SymbolQueryResult(
            symbols = listOf(
                SymbolInfo(
                    id = "id-1",
                    kind = "KtNamedFunction",
                    name = "findMe",
                    location = SymbolLocation(
                        path = "/src/Main.kt",
                        startLine = 3,
                        startColumn = 1,
                        endLine = 3,
                        endColumn = 7,
                    ),
                    score = 0.9,
                ),
            ),
            truncated = false,
        )
        val ops = FakeOperations(symbolQueryResult = expected)
        val orchestrator = makeOrchestrator()
        try {
            val handler = IndexSearchHandler(orchestrator, ops)
            val resultElement = handler.handle(
                "editor/find_symbol",
                buildJsonObject {
                    put("query", "findMe")
                    put("path", "/src")
                    put("maxResults", 12)
                },
            )
            assertNotNull(resultElement)
            val result = json.decodeFromJsonElement(SymbolQueryResult.serializer(), resultElement)
            assertEquals(expected, result)
            assertEquals(SymbolQueryParams(query = "findMe", path = "/src", maxResults = 12), ops.lastSymbolQueryParams)
        } finally {
            orchestrator.dispose()
        }
    }

    @Test
    fun `editor list_file_symbols decodes params and encodes result`() {
        val expected = SymbolQueryResult(
            symbols = listOf(
                SymbolInfo(
                    id = "id-2",
                    kind = "KtClass",
                    name = "Worker",
                    location = SymbolLocation(
                        path = "/src/Worker.kt",
                        startLine = 1,
                        startColumn = 1,
                        endLine = 12,
                        endColumn = 2,
                    ),
                ),
            ),
            truncated = true,
        )
        val ops = FakeOperations(fileSymbolsResult = expected)
        val orchestrator = makeOrchestrator()
        try {
            val handler = IndexSearchHandler(orchestrator, ops)
            val resultElement = handler.handle(
                "editor/list_file_symbols",
                buildJsonObject {
                    put("path", "/src/Worker.kt")
                    put("maxResults", 3)
                },
            )
            assertNotNull(resultElement)
            val result = json.decodeFromJsonElement(SymbolQueryResult.serializer(), resultElement)
            assertEquals(expected, result)
            assertEquals(FileSymbolsParams(path = "/src/Worker.kt", maxResults = 3), ops.lastFileSymbolsParams)
        } finally {
            orchestrator.dispose()
        }
    }

    @Test
    fun `editor find_references decodes params and encodes result`() {
        val expected = ReferencesResult(
            references = listOf(
                SymbolLocation(
                    path = "/src/Use.kt",
                    startLine = 8,
                    startColumn = 5,
                    endLine = 8,
                    endColumn = 11,
                ),
            ),
            truncated = false,
        )
        val ops = FakeOperations(referencesResult = expected)
        val orchestrator = makeOrchestrator()
        try {
            val handler = IndexSearchHandler(orchestrator, ops)
            val resultElement = handler.handle(
                "editor/find_references",
                buildJsonObject {
                    put("path", "/src/Main.kt")
                    put("line", 3)
                    put("column", 2)
                    put("maxResults", 25)
                },
            )
            assertNotNull(resultElement)
            val result = json.decodeFromJsonElement(ReferencesResult.serializer(), resultElement)
            assertEquals(expected, result)
            assertEquals(
                ReferencesParams(path = "/src/Main.kt", line = 3, column = 2, maxResults = 25),
                ops.lastReferencesParams,
            )
        } finally {
            orchestrator.dispose()
        }
    }

    @Test
    fun `unknown method returns null`() {
        val orchestrator = makeOrchestrator()
        try {
            val handler = IndexSearchHandler(orchestrator)
            val result = handler.handle(
                method = "unknown/method",
                params = buildJsonObject {},
            )
            assertNull(result)
        } finally {
            orchestrator.dispose()
        }
    }

    @Test
    fun `editor find_symbol missing required query throws serialization error`() {
        val orchestrator = makeOrchestrator()
        try {
            val handler = IndexSearchHandler(orchestrator, FakeOperations())
            assertFailsWith<SerializationException> {
                handler.handle("editor/find_symbol", buildJsonObject {})
            }
        } finally {
            orchestrator.dispose()
        }
    }

    @Test
    fun `operation failure bubbles up for rpc error handling upstream`() {
        val orchestrator = makeOrchestrator()
        try {
            val failingOps = object : IndexSearchHandler.Operations {
                override suspend fun findTextInFiles(params: FindTextInFilesParams): FindTextInFilesResult {
                    error("not used")
                }

                override fun getOpenFiles(): OpenFilesResult {
                    throw IllegalStateException("boom")
                }

                override suspend fun querySymbols(params: SymbolQueryParams): SymbolQueryResult {
                    error("not used")
                }

                override suspend fun listFileSymbols(params: FileSymbolsParams): SymbolQueryResult {
                    error("not used")
                }

                override suspend fun findReferences(params: ReferencesParams): ReferencesResult {
                    error("not used")
                }
            }
            val handler = IndexSearchHandler(orchestrator, failingOps)
            val ex = assertFailsWith<IllegalStateException> {
                handler.handle("editor/get_open_files", buildJsonObject {})
            }
            assertEquals("boom", ex.message)
        } finally {
            orchestrator.dispose()
        }
    }

    private fun makeOrchestrator(basePath: String? = null): WorkspaceIndexOrchestrator {
        val settings = CursorJSettings().apply {
            enableProjectIndexing = true
            enableLexicalPersistence = false
        }
        return WorkspaceIndexOrchestrator(
            project = projectWithBasePath(basePath),
            settingsProvider = { settings },
        )
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

    private class FakeOperations(
        private val findTextResult: FindTextInFilesResult = FindTextInFilesResult(),
        private val openFilesResult: OpenFilesResult = OpenFilesResult(),
        private val symbolQueryResult: SymbolQueryResult = SymbolQueryResult(),
        private val fileSymbolsResult: SymbolQueryResult = SymbolQueryResult(),
        private val referencesResult: ReferencesResult = ReferencesResult(),
    ) : IndexSearchHandler.Operations {
        var lastFindTextParams: FindTextInFilesParams? = null
        var lastSymbolQueryParams: SymbolQueryParams? = null
        var lastFileSymbolsParams: FileSymbolsParams? = null
        var lastReferencesParams: ReferencesParams? = null

        override suspend fun findTextInFiles(params: FindTextInFilesParams): FindTextInFilesResult {
            lastFindTextParams = params
            return findTextResult
        }

        override fun getOpenFiles(): OpenFilesResult = openFilesResult

        override suspend fun querySymbols(params: SymbolQueryParams): SymbolQueryResult {
            lastSymbolQueryParams = params
            return symbolQueryResult
        }

        override suspend fun listFileSymbols(params: FileSymbolsParams): SymbolQueryResult {
            lastFileSymbolsParams = params
            return fileSymbolsResult
        }

        override suspend fun findReferences(params: ReferencesParams): ReferencesResult {
            lastReferencesParams = params
            return referencesResult
        }
    }
}
