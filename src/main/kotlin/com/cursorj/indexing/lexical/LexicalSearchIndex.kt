package com.cursorj.indexing.lexical

import com.cursorj.indexing.model.RetrievalHit
import com.cursorj.indexing.storage.SQLiteIndexStore
import com.cursorj.indexing.storage.StoredLexicalHit
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

open class LexicalSearchIndex(
    private val project: Project,
    private val store: SQLiteIndexStore? = null,
) {
    data class SearchResult(
        val hits: List<RetrievalHit>,
        val truncated: Boolean,
        val cacheHit: Boolean = false,
    )

    data class WarmupResult(
        val indexedFiles: Int,
        val skippedFiles: Int,
        val removedFiles: Int,
    )

    private val ignoredDirectories = setOf(
        ".git", ".idea", ".gradle", "node_modules", "build", "out", "dist", "target",
        "venv", "__pycache__", "coverage",
    )

    open suspend fun searchText(
        query: String,
        path: String? = null,
        caseSensitive: Boolean = false,
        maxResults: Int = 50,
        contextLines: Int = 2,
        maxFileSizeBytes: Long = 1024L * 1024L,
    ): SearchResult = withContext(Dispatchers.IO) {
        val basePath = resolveBasePath(path) ?: return@withContext SearchResult(emptyList(), truncated = false)
        val normalizedPathPrefix = path?.takeIf { it.isNotBlank() }?.let { normalizePath(resolvePathForSearch(it)) }

        val activeStore = store?.takeIf { it.isOpen() }
        val persistedHits = runCatching {
            activeStore
                ?.searchLexical(
                    query = query,
                    pathPrefix = normalizedPathPrefix,
                    maxResults = maxResults,
                    caseSensitive = caseSensitive,
                )
                ?.map { it.toRetrievalHit() }
                ?: emptyList()
        }.getOrDefault(emptyList())
        if (persistedHits.size >= maxResults) {
            return@withContext SearchResult(
                hits = persistedHits.take(maxResults),
                truncated = true,
                cacheHit = true,
            )
        }

        val hits = mutableListOf<RetrievalHit>()
        hits.addAll(persistedHits)
        val matcher = buildMatcher(query, caseSensitive)
        var truncated = hits.size >= maxResults
        val dedupeKeys = hits.map { "${it.path}:${it.startLine}:${it.endLine}" }.toMutableSet()
        walkFiles(basePath) { file ->
            if (hits.size >= maxResults) {
                truncated = true
                return@walkFiles false
            }
            if (file.length() > maxFileSizeBytes || looksBinary(file)) {
                return@walkFiles true
            }

            val lines = runCatching { file.readText(StandardCharsets.UTF_8).lines() }.getOrNull() ?: return@walkFiles true
            maybePersistFile(file, lines)
            for ((lineIndex, lineText) in lines.withIndex()) {
                if (!matcher(lineText)) continue
                val start = (lineIndex - contextLines).coerceAtLeast(0)
                val end = (lineIndex + contextLines).coerceAtMost(lines.lastIndex)
                val snippet = lines.subList(start, end + 1).joinToString("\n")
                val normalizedPath = normalizePath(file.path)
                val key = "$normalizedPath:${start + 1}:${end + 1}"
                if (key in dedupeKeys) continue
                dedupeKeys.add(key)
                val score = scoreLine(query, lineText, file.path)
                hits.add(
                    RetrievalHit(
                        path = normalizedPath,
                        startLine = start + 1,
                        endLine = end + 1,
                        snippet = snippet,
                        score = score,
                        source = "lexical",
                    ),
                )
                if (hits.size >= maxResults) {
                    truncated = true
                    return@walkFiles false
                }
            }
            true
        }

        SearchResult(
            hits = hits.sortedByDescending { it.score },
            truncated = truncated,
            cacheHit = persistedHits.isNotEmpty(),
        )
    }

    open fun indexFile(path: String, content: String) {
        val activeStore = store?.takeIf { it.isOpen() } ?: return
        val normalizedPath = normalizePath(path)
        val lines = content.lines()
        val metadata = runCatching {
            val ioFile = File(path)
            val size = if (ioFile.exists()) ioFile.length() else content.toByteArray(StandardCharsets.UTF_8).size.toLong()
            val modified = if (ioFile.exists()) ioFile.lastModified() else System.currentTimeMillis()
            Triple(size, modified, computeHash(content))
        }.getOrNull() ?: return

        runCatching {
            activeStore.upsertDocument(
                path = normalizedPath,
                contentHash = metadata.third,
                sizeBytes = metadata.first,
                mtimeMs = metadata.second,
                language = extension(path),
            )
            activeStore.replaceLexicalHits(
                path = normalizedPath,
                hits = buildStoredHits(normalizedPath, lines),
            )
        }
    }

    open fun removeFile(path: String): Boolean {
        val activeStore = store?.takeIf { it.isOpen() } ?: return false
        return runCatching {
            activeStore.removePath(path)
            true
        }.getOrDefault(false)
    }

    open suspend fun warmupWorkspace(
        maxFileSizeBytes: Long = 1024L * 1024L,
        onProgress: ((indexed: Int, skipped: Int) -> Unit)? = null,
    ): WarmupResult = withContext(Dispatchers.IO) {
        val activeStore = store?.takeIf { it.isOpen() } ?: return@withContext WarmupResult(0, 0, 0)
        val workspaceRoot = project.basePath?.let { File(it) } ?: return@withContext WarmupResult(0, 0, 0)
        val existingDocumentsByPath = runCatching {
            activeStore.allDocuments().associateBy { it.path }
        }.getOrDefault(emptyMap())
        val seenPaths = linkedSetOf<String>()
        var indexed = 0
        var skipped = 0

        walkFiles(workspaceRoot) { file ->
            if (store?.isOpen() != true) return@walkFiles false
            val sizeBytes = file.length()
            if (sizeBytes > maxFileSizeBytes || looksBinary(file)) {
                skipped++
                return@walkFiles true
            }
            val normalizedPath = normalizePath(file.path)
            seenPaths.add(normalizedPath)
            val mtimeMs = file.lastModified()
            val existing = existingDocumentsByPath[normalizedPath]
            if (existing != null && existing.sizeBytes == sizeBytes && existing.mtimeMs == mtimeMs) {
                skipped++
                return@walkFiles true
            }
            val content = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull()
            if (content == null) {
                skipped++
                return@walkFiles true
            }
            runCatching { indexFile(normalizedPath, content) }
            indexed++
            if ((indexed + skipped) % 100 == 0) {
                onProgress?.invoke(indexed, skipped)
            }
            true
        }

        val removedCount = runCatching {
            val existingPaths = activeStore.allDocumentPaths().toSet()
            val removed = existingPaths.subtract(seenPaths)
            for (removedPath in removed) {
                activeStore.removePath(removedPath)
            }
            removed.size
        }.getOrDefault(0)
        onProgress?.invoke(indexed, skipped)
        WarmupResult(indexedFiles = indexed, skippedFiles = skipped, removedFiles = removedCount)
    }

    open suspend fun upsertFileFromDisk(path: String, maxFileSizeBytes: Long = 1024L * 1024L): Boolean = withContext(Dispatchers.IO) {
        if (store?.isOpen() != true) return@withContext false
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            runCatching { store?.removePath(path) }
            return@withContext false
        }
        if (file.length() > maxFileSizeBytes || looksBinary(file)) {
            return@withContext false
        }
        val content = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull() ?: return@withContext false
        indexFile(file.absolutePath, content)
        true
    }

    private fun resolveBasePath(path: String?): File? {
        val requested = path?.takeIf { it.isNotBlank() }?.let { File(it) }
        if (requested != null) {
            return if (requested.isAbsolute) requested else File(project.basePath ?: return null, path)
        }
        val projectBase = project.basePath ?: return null
        return File(projectBase)
    }

    private fun resolvePathForSearch(path: String): String {
        val file = File(path)
        if (file.isAbsolute) return file.absolutePath
        val base = project.basePath ?: return file.absolutePath
        return File(base, path).absolutePath
    }

    private fun buildMatcher(query: String, caseSensitive: Boolean): (String) -> Boolean {
        val raw = query.trim()
        if (raw.isEmpty()) return { false }
        val needle = if (caseSensitive) raw else raw.lowercase()
        return { line ->
            if (caseSensitive) line.contains(needle) else line.lowercase().contains(needle)
        }
    }

    private fun scoreLine(query: String, line: String, filePath: String): Double {
        val q = query.trim().lowercase()
        val l = line.lowercase()
        var score = 0.0
        if (l.contains(q)) score += 1.0
        val tokenOverlap = tokenize(q).intersect(tokenize(l)).size
        score += tokenOverlap * 0.05
        val fileName = File(filePath).name.lowercase()
        if (fileName.contains(q)) score += 0.3
        return score
    }

    private fun tokenize(value: String): Set<String> {
        return Regex("[a-z0-9_./-]+").findAll(value).map { it.value }.filter { it.length > 1 }.toSet()
    }

    private fun maybePersistFile(file: File, lines: List<String>) {
        val activeStore = store?.takeIf { it.isOpen() } ?: return
        val normalizedPath = normalizePath(file.path)
        val content = lines.joinToString("\n")
        val hash = computeHash(content)
        val current = runCatching { activeStore.document(normalizedPath) }.getOrNull()
        if (current != null && current.contentHash == hash && current.sizeBytes == file.length() && current.mtimeMs == file.lastModified()) {
            return
        }
        runCatching {
            activeStore.upsertDocument(
                path = normalizedPath,
                contentHash = hash,
                sizeBytes = file.length(),
                mtimeMs = file.lastModified(),
                language = extension(file.name),
            )
            activeStore.replaceLexicalHits(
                path = normalizedPath,
                hits = buildStoredHits(normalizedPath, lines),
            )
        }
    }

    private fun buildStoredHits(path: String, lines: List<String>): List<StoredLexicalHit> {
        return lines.mapIndexedNotNull { index, rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) return@mapIndexedNotNull null
            val normalized = rawLine.lowercase()
            StoredLexicalHit(
                path = path,
                line = index + 1,
                column = 1,
                snippet = rawLine,
                normalizedLine = normalized,
                scoreHint = 0.1,
                tokenFingerprint = fingerprintTokens(normalized),
            )
        }
    }

    private fun fingerprintTokens(line: String): String {
        return tokenize(line).sorted().take(8).joinToString("|")
    }

    private fun extension(path: String): String {
        return path.substringAfterLast('.', "").lowercase()
    }

    private fun walkFiles(root: File, onFile: (File) -> Boolean) {
        if (!root.exists()) return
        if (root.isFile) {
            onFile(root)
            return
        }
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    if (shouldSkipDirectory(child)) continue
                    stack.add(child)
                } else if (!onFile(child)) {
                    return
                }
            }
        }
    }

    private fun shouldSkipDirectory(dir: File): Boolean {
        val name = dir.name
        return name.startsWith(".") || name in ignoredDirectories
    }

    private fun looksBinary(file: File): Boolean {
        val lower = file.name.lowercase()
        val binaryExt = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "ico",
            "class", "jar", "zip", "gz", "tar", "7z",
            "pdf", "exe", "dll", "so", "dylib", "bin",
        )
        val ext = lower.substringAfterLast('.', "")
        return ext in binaryExt
    }

    private fun normalizePath(path: String): String {
        return path.replace('\\', '/')
    }

    private fun computeHash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(content.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun StoredLexicalHit.toRetrievalHit(): RetrievalHit {
        return RetrievalHit(
            path = path,
            startLine = line,
            endLine = line,
            snippet = snippet,
            score = scoreHint,
            source = "lexical-cache",
        )
    }
}
