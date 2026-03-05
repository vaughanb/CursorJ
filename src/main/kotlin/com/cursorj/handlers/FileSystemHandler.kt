package com.cursorj.handlers

import com.cursorj.acp.AcpClient
import com.cursorj.acp.messages.ReadTextFileParams
import com.cursorj.acp.messages.ReadTextFileResult
import com.cursorj.acp.messages.WriteTextFileParams
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import kotlinx.serialization.json.*

class FileSystemHandler(private val project: Project) {
    private val log = Logger.getInstance(FileSystemHandler::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    fun register(client: AcpClient) {
        client.addServerRequestHandler { method, params ->
            when (method) {
                "fs/read_text_file" -> handleReadTextFile(params)
                "fs/write_text_file" -> handleWriteTextFile(params)
                else -> null
            }
        }
    }

    private fun handleReadTextFile(params: JsonElement): JsonElement {
        val request = json.decodeFromJsonElement<ReadTextFileParams>(params)
        val content = ReadAction.compute<String, Exception> {
            val vf = LocalFileSystem.getInstance().findFileByPath(request.path)
                ?: throw IllegalArgumentException("File not found: ${request.path}")
            VfsUtilCore.loadText(vf)
        }
        val result = ReadTextFileResult(content = content)
        return json.encodeToJsonElement(result)
    }

    private fun handleWriteTextFile(params: JsonElement): JsonElement {
        val request = json.decodeFromJsonElement<WriteTextFileParams>(params)
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project, "CursorJ: Write File", null, {
                val vf = LocalFileSystem.getInstance().findFileByPath(request.path)
                if (vf != null) {
                    vf.setBinaryContent(request.content.toByteArray(Charsets.UTF_8))
                } else {
                    val parentPath = request.path.substringBeforeLast('/')
                    val fileName = request.path.substringAfterLast('/')
                    val parentDir = LocalFileSystem.getInstance().findFileByPath(parentPath)
                        ?: throw IllegalArgumentException("Parent directory not found: $parentPath")
                    val newFile = parentDir.createChildData(this, fileName)
                    newFile.setBinaryContent(request.content.toByteArray(Charsets.UTF_8))
                }
            })
        }
        return JsonObject(emptyMap())
    }
}
