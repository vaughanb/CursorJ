package com.cursorj.handlers

import com.cursorj.acp.AcpClient
import com.cursorj.acp.messages.ReadTextFileParams
import com.cursorj.acp.messages.ReadTextFileResult
import com.cursorj.acp.messages.WriteTextFileParams
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

class FileSystemHandler(private val project: Project) {
    private val log = Logger.getInstance(FileSystemHandler::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    fun register(client: AcpClient) {
        client.addServerRequestHandler { method, params ->
            when (method) {
                "fs/read_text_file" -> handleReadTextFile(params)
                "fs/write_text_file" -> handleWriteTextFile(params)
                "fs/list_directory" -> handleListDirectory(params)
                "fs/get_file_info" -> handleGetFileInfo(params)
                "fs/search_files" -> handleSearchFiles(params)
                "fs/create_directory" -> handleCreateDirectory(params)
                else -> null
            }
        }
    }

    private fun resolvePath(path: String): String {
        val file = File(path)
        if (file.isAbsolute) return file.absolutePath
        val basePath = project.basePath ?: return file.absolutePath
        return File(basePath, path).absolutePath
    }

    private fun toVfsPath(absolutePath: String): String {
        return absolutePath.replace('\\', '/')
    }

    private fun handleReadTextFile(params: JsonElement): JsonElement {
        val request = json.decodeFromJsonElement<ReadTextFileParams>(params)
        val resolvedPath = resolvePath(request.path)
        val vfsPath = toVfsPath(resolvedPath)
        log.info("fs/read_text_file: ${request.path} -> $vfsPath")

        val vf = LocalFileSystem.getInstance().findFileByPath(vfsPath)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(vfsPath)
            ?: throw IllegalArgumentException("File not found: $vfsPath")
        val content = VfsUtilCore.loadText(vf)
        val result = ReadTextFileResult(content = content)
        return json.encodeToJsonElement(result)
    }

    private fun handleWriteTextFile(params: JsonElement): JsonElement {
        val request = json.decodeFromJsonElement<WriteTextFileParams>(params)
        val resolvedPath = resolvePath(request.path)
        log.info("fs/write_text_file: ${request.path} -> $resolvedPath")

        val ioFile = File(resolvedPath)
        ioFile.parentFile?.mkdirs()
        ioFile.writeText(request.content, Charsets.UTF_8)

        VfsUtil.markDirtyAndRefresh(false, true, true, ioFile, ioFile.parentFile)

        ApplicationManager.getApplication().invokeLater {
            LocalFileSystem.getInstance().refreshAndFindFileByPath(toVfsPath(resolvedPath))
            val projectDir = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
            projectDir?.refresh(true, true)
        }

        return JsonObject(emptyMap())
    }

    private fun handleListDirectory(params: JsonElement): JsonElement {
        val path = params.jsonObject["path"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Missing 'path' parameter")
        val resolvedPath = resolvePath(path)
        log.info("fs/list_directory: $path -> $resolvedPath")

        val dir = File(resolvedPath)
        if (!dir.exists() || !dir.isDirectory) {
            throw IllegalArgumentException("Directory not found: $resolvedPath")
        }

        val entries = buildJsonArray {
            dir.listFiles()?.sortedBy { it.name }?.forEach { child ->
                addJsonObject {
                    put("name", child.name)
                    put("type", if (child.isDirectory) "directory" else "file")
                    put("path", toVfsPath(child.absolutePath))
                }
            }
        }

        return buildJsonObject { put("entries", entries) }
    }

    private fun handleGetFileInfo(params: JsonElement): JsonElement {
        val path = params.jsonObject["path"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Missing 'path' parameter")
        val resolvedPath = resolvePath(path)
        log.info("fs/get_file_info: $path -> $resolvedPath")

        val ioFile = File(resolvedPath)
        if (!ioFile.exists()) {
            throw IllegalArgumentException("File not found: $resolvedPath")
        }

        val attrs = Files.readAttributes(ioFile.toPath(), BasicFileAttributes::class.java)
        return buildJsonObject {
            put("path", toVfsPath(ioFile.absolutePath))
            put("name", ioFile.name)
            put("type", if (ioFile.isDirectory) "directory" else "file")
            put("size", attrs.size())
            put("lastModified", attrs.lastModifiedTime().toMillis())
            put("exists", true)
        }
    }

    private fun handleSearchFiles(params: JsonElement): JsonElement {
        val pattern = params.jsonObject["pattern"]?.jsonPrimitive?.contentOrNull
            ?: params.jsonObject["query"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Missing 'pattern' parameter")
        val searchPath = params.jsonObject["path"]?.jsonPrimitive?.contentOrNull
        val resolvedBase = if (searchPath != null) resolvePath(searchPath) else project.basePath
            ?: throw IllegalArgumentException("No base path for search")
        log.info("fs/search_files: pattern='$pattern' in $resolvedBase")

        val baseDir = File(resolvedBase)
        val results = mutableListOf<String>()
        val globRegex = globToRegex(pattern)

        searchFilesRecursive(baseDir, globRegex, results, maxResults = 100)

        return buildJsonObject {
            put("files", buildJsonArray {
                results.forEach { add(toVfsPath(it)) }
            })
        }
    }

    private fun handleCreateDirectory(params: JsonElement): JsonElement {
        val path = params.jsonObject["path"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Missing 'path' parameter")
        val resolvedPath = resolvePath(path)
        log.info("fs/create_directory: $path -> $resolvedPath")

        val dir = File(resolvedPath)
        dir.mkdirs()

        VfsUtil.markDirtyAndRefresh(false, true, true, dir)

        return JsonObject(emptyMap())
    }

    private fun searchFilesRecursive(dir: File, pattern: Regex, results: MutableList<String>, maxResults: Int) {
        if (results.size >= maxResults) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (results.size >= maxResults) return
            if (child.name.startsWith(".") || child.name == "node_modules" || child.name == "build" || child.name == "out") continue
            if (child.isFile && pattern.matches(child.name)) {
                results.add(child.absolutePath)
            } else if (child.isDirectory) {
                searchFilesRecursive(child, pattern, results, maxResults)
            }
        }
    }

    private fun globToRegex(glob: String): Regex {
        val regex = buildString {
            append("^")
            for (ch in glob) {
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append(".")
                    '.' -> append("\\.")
                    else -> append(ch)
                }
            }
            append("$")
        }
        return Regex(regex, RegexOption.IGNORE_CASE)
    }
}
