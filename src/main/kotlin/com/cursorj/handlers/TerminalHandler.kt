package com.cursorj.handlers

import com.cursorj.acp.AcpClient
import com.cursorj.acp.messages.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.*
import org.jetbrains.plugins.terminal.TerminalProjectOptionsProvider
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TerminalHandler(private val project: Project) {
    private val log = Logger.getInstance(TerminalHandler::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val nextTerminalId = AtomicInteger(1)

    private data class ManagedTerminal(
        val process: Process,
        val outputBuilder: StringBuilder = StringBuilder(),
        val outputByteLimit: Int? = null,
        var truncated: Boolean = false,
    )

    private val terminals = ConcurrentHashMap<String, ManagedTerminal>()

    fun register(client: AcpClient) {
        client.addServerRequestHandler { method, params ->
            when (method) {
                "terminal/create" -> handleCreate(params)
                "terminal/get_output", "terminal/output" -> handleGetOutput(params)
                "terminal/wait", "terminal/wait_for_exit" -> handleWait(params)
                "terminal/kill" -> handleKill(params)
                "terminal/release" -> handleRelease(params)
                else -> null
            }
        }
    }

    private fun handleCreate(params: JsonElement): JsonElement {
        log.info("terminal/create raw params: $params")
        val request = json.decodeFromJsonElement<TerminalCreateParams>(params)
        val terminalId = "term-${nextTerminalId.getAndIncrement()}"
        val commandForLog = buildCommandPreview(request)
        log.info("terminal/create: id=$terminalId command='$commandForLog' args=${request.args} cwd=${request.cwd}")

        val process = startProcessWithFallback(request)
        val managed = ManagedTerminal(
            process = process,
            outputByteLimit = request.outputByteLimit?.takeIf { it > 0 },
        )
        terminals[terminalId] = managed

        Thread({
            try {
                process.inputStream.reader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(4096)
                    var count = reader.read(buffer)
                    while (count >= 0) {
                        synchronized(managed) {
                            managed.outputBuilder.append(buffer, 0, count)
                            trimOutputIfNeeded(managed)
                        }
                        count = reader.read(buffer)
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
        val output = synchronized(managed) {
            managed.outputBuilder.toString()
        }
        val result = TerminalGetOutputResult(
            output = output,
            exitCode = exitCode,
            truncated = managed.truncated,
            exitStatus = if (exitCode != null) TerminalExitStatus(exitCode = exitCode, signal = null) else null,
        )
        return json.encodeToJsonElement(result)
    }

    private fun handleWait(params: JsonElement): JsonElement {
        val request = json.decodeFromJsonElement<TerminalIdParams>(params)
        val managed = terminals[request.terminalId]
            ?: throw IllegalArgumentException("Terminal not found: ${request.terminalId}")

        val requestedTimeoutMs = extractTimeoutMs(params)
        val effectiveTimeoutMs = (requestedTimeoutMs ?: DEFAULT_WAIT_TIMEOUT_MS)
            .coerceIn(1L, MAX_WAIT_TIMEOUT_MS)
        log.info(
            "terminal/wait: terminalId=${request.terminalId}, timeoutMs=$effectiveTimeoutMs" +
                if (requestedTimeoutMs == null) " (default)" else "",
        )

        val finished = managed.process.waitFor(effectiveTimeoutMs, TimeUnit.MILLISECONDS)
        val result = if (finished) {
            TerminalWaitResult(exitCode = managed.process.exitValue(), signal = null)
        } else {
            log.warn("terminal/wait timed out for ${request.terminalId} after ${effectiveTimeoutMs}ms; terminating process")
            managed.process.destroy()
            if (!managed.process.waitFor(KILL_GRACE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                managed.process.destroyForcibly()
            }
            val exitCode = runCatching { managed.process.exitValue() }.getOrDefault(TIMEOUT_EXIT_CODE)
            TerminalWaitResult(exitCode = exitCode, signal = "timeout")
        }
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

    private fun buildProcessCommand(request: TerminalCreateParams): List<String> {
        val args = request.args.filter { it.isNotBlank() }
        if (args.isNotEmpty()) {
            return listOf(request.command) + args
        }
        return buildShellCommand(request.command)
    }

    private fun buildCommandPreview(request: TerminalCreateParams): String {
        val args = request.args.filter { it.isNotBlank() }
        return if (args.isEmpty()) {
            request.command
        } else {
            (sequenceOf(request.command) + args.asSequence())
                .joinToString(" ")
                .take(300)
        }
    }

    private fun extractTimeoutMs(params: JsonElement): Long? {
        val obj = params as? JsonObject ?: return null
        val timeoutElement = obj["timeoutMs"] ?: obj["timeout_ms"] ?: return null
        return timeoutElement.jsonPrimitive.longOrNull
    }

    private fun startProcessWithFallback(request: TerminalCreateParams): Process {
        val primaryCommand = buildProcessCommand(request)
        return try {
            processBuilderFor(primaryCommand, request).start()
        } catch (primaryError: IOException) {
            val fallbackCommand = buildShellCommand(
                command = request.command,
                args = request.args.filter { it.isNotBlank() },
            )
            if (fallbackCommand == primaryCommand) throw primaryError
            log.warn(
                "terminal/create primary launch failed; retrying via shell. " +
                    "primary='${primaryCommand.joinToString(" ")}' error='${primaryError.message}'",
            )
            try {
                processBuilderFor(fallbackCommand, request).start()
            } catch (fallbackError: IOException) {
                fallbackError.addSuppressed(primaryError)
                throw fallbackError
            }
        }
    }

    private fun processBuilderFor(command: List<String>, request: TerminalCreateParams): ProcessBuilder {
        val pb = ProcessBuilder(command)
        val workDir = request.cwd?.let { File(it) }
            ?: project.basePath?.let { File(it) }
        workDir?.let { pb.directory(it) }
        pb.redirectErrorStream(true)
        mergeIdeTerminalEnvironment(pb)
        for (envVar in request.env) {
            if (envVar.name.isNotBlank()) {
                pb.environment()[envVar.name] = envVar.value
            }
        }
        return pb
    }

    private fun buildShellCommand(command: String, args: List<String> = emptyList()): List<String> {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val tokens = sequenceOf(command) + args.asSequence()
        return if (isWindows) {
            listOf("cmd.exe", "/d", "/s", "/c", tokens.joinToString(" ") { quoteForCmd(it) })
        } else {
            val shellPath = resolveShellPath()
            val shellName = File(shellPath).name.lowercase()
            val shellFlag = if (shellName in loginCapableShellNames) "-lc" else "-c"
            listOf(shellPath, shellFlag, tokens.joinToString(" ") { quoteForPosixShell(it) })
        }
    }

    private fun resolveShellPath(): String {
        try {
            val idePath = TerminalProjectOptionsProvider.getInstance(project).shellPath
            if (idePath.isNotBlank()) {
                log.info("Using IntelliJ terminal shell path: $idePath")
                return idePath
            }
        } catch (e: Exception) {
            log.info("Could not read IntelliJ terminal shell path, using fallback: ${e.message}")
        }
        return resolveShellPathFallback()
    }

    private fun resolveShellPathFallback(): String {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        if (isWindows) return "powershell.exe"
        val configured = System.getenv("SHELL")
        if (!configured.isNullOrBlank() && File(configured).exists()) {
            return configured
        }
        val bash = File("/bin/bash")
        if (bash.exists()) return bash.path
        return "/bin/sh"
    }

    private fun mergeIdeTerminalEnvironment(pb: ProcessBuilder) {
        try {
            val envData = TerminalProjectOptionsProvider.getInstance(project).getEnvData()
            for ((key, value) in envData.envs) {
                if (key.isNotBlank()) {
                    pb.environment()[key] = value
                }
            }
            if (envData.envs.isNotEmpty()) {
                log.info("Merged ${envData.envs.size} env var(s) from IntelliJ terminal settings")
            }
        } catch (e: Exception) {
            log.info("Could not read IntelliJ terminal environment: ${e.message}")
        }
    }

    private fun quoteForPosixShell(value: String): String {
        if (value.isEmpty()) return "''"
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private fun quoteForCmd(value: String): String {
        if (value.isEmpty()) return "\"\""
        if (value.none { it.isWhitespace() || it == '"' }) return value
        return "\"" + value.replace("\"", "\\\"") + "\""
    }

    private fun trimOutputIfNeeded(managed: ManagedTerminal) {
        val maxBytes = managed.outputByteLimit ?: return
        val output = managed.outputBuilder
        while (output.toString().toByteArray(Charsets.UTF_8).size > maxBytes && output.isNotEmpty()) {
            val newlineIdx = output.indexOf("\n")
            if (newlineIdx >= 0) {
                output.delete(0, newlineIdx + 1)
            } else {
                val toDelete = (output.length / 4).coerceAtLeast(1)
                output.delete(0, toDelete)
            }
            managed.truncated = true
        }
    }

    companion object {
        private val loginCapableShellNames = setOf("bash", "zsh", "ksh", "fish")
        private const val DEFAULT_WAIT_TIMEOUT_MS = 5L * 60L * 1000L
        private const val MAX_WAIT_TIMEOUT_MS = 15L * 60L * 1000L
        private const val KILL_GRACE_TIMEOUT_MS = 5_000L
        private const val TIMEOUT_EXIT_CODE = 124
    }
}
