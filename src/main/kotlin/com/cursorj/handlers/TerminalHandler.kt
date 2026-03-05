package com.cursorj.handlers

import com.cursorj.acp.AcpClient
import com.cursorj.acp.messages.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TerminalHandler(private val project: Project) {
    private val log = Logger.getInstance(TerminalHandler::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val nextTerminalId = AtomicInteger(1)

    private data class ManagedTerminal(
        val process: Process,
        val outputBuilder: StringBuilder = StringBuilder(),
    )

    private val terminals = ConcurrentHashMap<String, ManagedTerminal>()

    fun register(client: AcpClient) {
        client.addServerRequestHandler { method, params ->
            when (method) {
                "terminal/create" -> handleCreate(params)
                "terminal/get_output" -> handleGetOutput(params)
                "terminal/wait" -> handleWait(params)
                "terminal/kill" -> handleKill(params)
                "terminal/release" -> handleRelease(params)
                else -> null
            }
        }
    }

    private fun handleCreate(params: JsonElement): JsonElement {
        val request = json.decodeFromJsonElement<TerminalCreateParams>(params)
        val terminalId = "term-${nextTerminalId.getAndIncrement()}"

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val shell = if (isWindows) listOf("cmd.exe", "/c") else listOf("/bin/sh", "-c")

        val pb = ProcessBuilder(shell + request.command)
        request.cwd?.let { pb.directory(java.io.File(it)) }
            ?: project.basePath?.let { pb.directory(java.io.File(it)) }
        pb.redirectErrorStream(true)

        val process = pb.start()
        val managed = ManagedTerminal(process)
        terminals[terminalId] = managed

        Thread({
            try {
                process.inputStream.bufferedReader().use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        managed.outputBuilder.appendLine(line)
                        line = reader.readLine()
                    }
                }
            } catch (_: Exception) { }
        }, "CursorJ-Terminal-$terminalId").apply { isDaemon = true }.start()

        val result = TerminalCreateResult(terminalId = terminalId)
        return json.encodeToJsonElement(result)
    }

    private fun handleGetOutput(params: JsonElement): JsonElement {
        val request = json.decodeFromJsonElement<TerminalGetOutputParams>(params)
        val managed = terminals[request.terminalId]
            ?: throw IllegalArgumentException("Terminal not found: ${request.terminalId}")

        val exitCode = if (managed.process.isAlive) null else managed.process.exitValue()
        val result = TerminalGetOutputResult(
            output = managed.outputBuilder.toString(),
            exitCode = exitCode,
        )
        return json.encodeToJsonElement(result)
    }

    private fun handleWait(params: JsonElement): JsonElement {
        val request = json.decodeFromJsonElement<TerminalIdParams>(params)
        val managed = terminals[request.terminalId]
            ?: throw IllegalArgumentException("Terminal not found: ${request.terminalId}")

        val exitCode = managed.process.waitFor()
        val result = TerminalWaitResult(exitCode = exitCode)
        return json.encodeToJsonElement(result)
    }

    private fun handleKill(params: JsonElement): JsonElement {
        val request = json.decodeFromJsonElement<TerminalIdParams>(params)
        val managed = terminals[request.terminalId]
            ?: throw IllegalArgumentException("Terminal not found: ${request.terminalId}")

        managed.process.destroyForcibly()
        return JsonObject(emptyMap())
    }

    private fun handleRelease(params: JsonElement): JsonElement {
        val request = json.decodeFromJsonElement<TerminalIdParams>(params)
        val managed = terminals.remove(request.terminalId)
        managed?.process?.let {
            if (it.isAlive) it.destroyForcibly()
        }
        return JsonObject(emptyMap())
    }

    fun disposeAll() {
        terminals.values.forEach { managed ->
            if (managed.process.isAlive) managed.process.destroyForcibly()
        }
        terminals.clear()
    }
}
