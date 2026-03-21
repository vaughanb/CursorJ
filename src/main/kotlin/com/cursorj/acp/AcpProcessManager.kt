package com.cursorj.acp

import com.cursorj.settings.CursorJSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File

class AcpProcessManager(private val parentDisposable: Disposable) : Disposable {
    private val log = Logger.getInstance(AcpProcessManager::class.java)
    private var process: Process? = null
    private var stderrThread: Thread? = null
    var reader: BufferedReader? = null
        private set
    var writer: BufferedWriter? = null
        private set

    val isRunning: Boolean
        get() = process?.isAlive == true

    init {
        Disposer.register(parentDisposable, this)
    }

    var workingDirectory: String? = null
    var modelOverride: String? = null
    var maxModeEnabled: Boolean = false

    fun start(): Boolean {
        if (isRunning) return true

        val agentPath = resolveAgentPath() ?: run {
            log.warn("Cursor agent binary not found. Searched PATH and common install locations.")
            return false
        }
        log.info("Resolved agent path: $agentPath")

        return try {
            val command = buildCommand(agentPath)

            val pb = ProcessBuilder(command)
            pb.redirectErrorStream(false)

            workingDirectory?.let { cwd ->
                val dir = File(cwd)
                if (dir.isDirectory) {
                    pb.directory(dir)
                    log.info("Agent working directory: $cwd")
                }
            }

            process = pb.start()
            reader = process!!.inputStream.bufferedReader()
            writer = process!!.outputStream.bufferedWriter()

            stderrThread = Thread({
                try {
                    process!!.errorStream.bufferedReader().use { err ->
                        var line = err.readLine()
                        while (line != null) {
                            log.info("agent stderr: $line")
                            line = err.readLine()
                        }
                    }
                } catch (_: Exception) { }
            }, "CursorJ-Agent-Stderr").apply { isDaemon = true }
            stderrThread!!.start()

            log.info("Cursor agent ACP process started (pid=${process!!.pid()})")
            true
        } catch (e: Exception) {
            log.error("Failed to start Cursor agent ACP process", e)
            false
        }
    }

    private fun buildCommand(agentPath: String): List<String> {
        val command = mutableListOf<String>()
        if (agentPath.endsWith(".cmd", ignoreCase = true) || agentPath.endsWith(".bat", ignoreCase = true)) {
            command.addAll(listOf("cmd.exe", "/c", agentPath))
        } else {
            command.add(agentPath)
        }
        command.add("acp")

        modelOverride?.let {
            command.addAll(listOf("--model", it))
        }
        if (maxModeEnabled) {
            command.add("--max-mode")
        }
        return command
    }

    fun stop() {
        process?.let { proc ->
            try {
                writer?.close()
                reader?.close()
                proc.destroyForcibly()
                proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                log.info("Cursor agent ACP process stopped")
            } catch (e: Exception) {
                log.warn("Error stopping agent process", e)
            }
        }
        stderrThread?.interrupt()
        process = null
        reader = null
        writer = null
        stderrThread = null
    }

    fun restart(): Boolean {
        stop()
        return start()
    }

    fun waitForExit(): Int? {
        return try {
            process?.waitFor()
        } catch (_: InterruptedException) {
            null
        }
    }

    fun fetchAvailableModels(): List<String> {
        val agentPath = resolveAgentPath() ?: return emptyList()
        return try {
            val command = buildAgentCommand(agentPath, "models")

            val pb = ProcessBuilder(command)
            pb.redirectErrorStream(true)

            log.info("Fetching models from agent CLI")
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            val exited = proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            if (!exited) {
                proc.destroyForcibly()
                log.warn("agent models command timed out")
                return emptyList()
            }

            log.info("agent models raw output:\n$output")

            val models = parseModelList(output)
            log.info("Parsed ${models.size} models: $models")
            models
        } catch (e: Exception) {
            log.warn("Failed to fetch models from agent CLI", e)
            emptyList()
        }
    }

    private fun buildAgentCommand(agentPath: String, subcommand: String): List<String> {
        val command = mutableListOf<String>()
        if (agentPath.endsWith(".cmd", ignoreCase = true) || agentPath.endsWith(".bat", ignoreCase = true)) {
            command.addAll(listOf("cmd.exe", "/c", agentPath))
        } else {
            command.add(agentPath)
        }
        command.add(subcommand)
        if (maxModeEnabled) {
            command.add("--max-mode")
        }
        return command
    }

    data class ModelInfo(val id: String, val displayName: String, val isCurrent: Boolean = false)

    fun fetchAvailableModelsWithInfo(): List<ModelInfo> {
        val agentPath = resolveAgentPath() ?: return emptyList()
        return try {
            val command = buildAgentCommand(agentPath, "models")
            val pb = ProcessBuilder(command)
            pb.redirectErrorStream(true)
            log.info("Fetching model info from agent CLI")
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            val exited = proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            if (!exited) {
                proc.destroyForcibly()
                return emptyList()
            }
            parseModelInfoList(output)
        } catch (e: Exception) {
            log.warn("Failed to fetch model info", e)
            emptyList()
        }
    }

    private fun stripAnsi(text: String): String {
        return text.replace(Regex("\u001B\\[[0-9;]*[A-Za-z]"), "")
    }

    private fun parseModelInfoList(output: String): List<ModelInfo> {
        val cleaned = stripAnsi(output)
        val modelPattern = Regex("""^(\S+)\s+-\s+(.+)$""")
        return cleaned.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val match = modelPattern.matchEntire(line) ?: return@mapNotNull null
                val id = match.groupValues[1]
                var displayName = match.groupValues[2].trim()
                val isCurrent = displayName.contains("(current")
                displayName = displayName.replace(Regex("""\s*\(current[^)]*\)"""), "").trim()
                ModelInfo(id, displayName, isCurrent)
            }
    }

    private fun parseModelList(output: String): List<String> {
        return parseModelInfoList(output).map { it.id }
    }

    private fun resolveAgentPath(): String? {
        val settings = CursorJSettings.instance
        return AgentPathResolver.resolve(settings.agentPath)
    }

    override fun dispose() {
        stop()
    }
}
