package com.cursorj.handlers

import com.cursorj.acp.AcpClient
import com.cursorj.acp.messages.*
import com.cursorj.permissions.PermissionMode
import com.cursorj.permissions.PermissionPolicy
import com.cursorj.settings.CursorJSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.*
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
        ensureExecutionAllowed("terminal/create", params)
        val request = json.decodeFromJsonElement<TerminalCreateParams>(params)
        val terminalId = "term-${nextTerminalId.getAndIncrement()}"
        val commandForLog = buildCommandPreview(request)
        log.info("terminal/create: command='$commandForLog', cwd=${request.cwd}")

        val pb = ProcessBuilder(buildProcessCommand(request))
        val workDir = request.cwd?.let { java.io.File(it) }
            ?: project.basePath?.let { java.io.File(it) }
        workDir?.let { pb.directory(it) }
        pb.redirectErrorStream(true)
        for (envVar in request.env) {
            if (envVar.name.isNotBlank()) {
                pb.environment()[envVar.name] = envVar.value
            }
        }

        val process = pb.start()
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

    private fun ensureExecutionAllowed(method: String, params: JsonElement) {
        val settings = CursorJSettings.instance
        val mode = PermissionMode.fromId(settings.permissionMode)
        val approvedKeys = settings.getApprovedPermissionKeys()
        if (!PermissionPolicy.shouldAllowMethodExecution(mode, approvedKeys, method, params)) {
            throw IllegalStateException("Permission denied for $method")
        }
    }

    private fun buildProcessCommand(request: TerminalCreateParams): List<String> {
        val args = request.args.filter { it.isNotBlank() }
        if (args.isNotEmpty()) {
            return listOf(request.command) + args
        }
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val shell = if (isWindows) listOf("cmd.exe", "/c") else listOf("/bin/sh", "-c")
        return shell + request.command
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
        private const val DEFAULT_WAIT_TIMEOUT_MS = 5L * 60L * 1000L
        private const val MAX_WAIT_TIMEOUT_MS = 15L * 60L * 1000L
        private const val KILL_GRACE_TIMEOUT_MS = 5_000L
        private const val TIMEOUT_EXIT_CODE = 124
    }
}
