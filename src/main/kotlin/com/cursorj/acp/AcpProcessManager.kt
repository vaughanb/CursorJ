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

            val settings = CursorJSettings.instance
            settings.apiKey.takeIf { it.isNotBlank() }?.let {
                pb.environment()["CURSOR_API_KEY"] = it
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

            log.info("Cursor agent ACP process started (pid=${process!!.pid()}, cmd=$command)")
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

        val settings = CursorJSettings.instance
        settings.apiKey.takeIf { it.isNotBlank() }?.let {
            command.addAll(listOf("--api-key", it))
        }
        modelOverride?.let {
            command.addAll(listOf("--model", it))
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
            val settings = CursorJSettings.instance
            val command = buildAgentCommand(agentPath, "models", settings)

            val pb = ProcessBuilder(command)
            pb.redirectErrorStream(true)
            settings.apiKey.takeIf { it.isNotBlank() }?.let {
                pb.environment()["CURSOR_API_KEY"] = it
            }

            log.info("Fetching models with command: $command")
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

    private fun buildAgentCommand(agentPath: String, subcommand: String, settings: CursorJSettings): List<String> {
        val command = mutableListOf<String>()
        if (agentPath.endsWith(".cmd", ignoreCase = true) || agentPath.endsWith(".bat", ignoreCase = true)) {
            command.addAll(listOf("cmd.exe", "/c", agentPath))
        } else {
            command.add(agentPath)
        }
        command.add(subcommand)
        settings.apiKey.takeIf { it.isNotBlank() }?.let {
            command.addAll(listOf("--api-key", it))
        }
        return command
    }

    data class ModelInfo(val id: String, val displayName: String, val isCurrent: Boolean = false)

    fun fetchAvailableModelsWithInfo(): List<ModelInfo> {
        val agentPath = resolveAgentPath() ?: return emptyList()
        return try {
            val settings = CursorJSettings.instance
            val command = buildAgentCommand(agentPath, "models", settings)
            val pb = ProcessBuilder(command)
            pb.redirectErrorStream(true)
            settings.apiKey.takeIf { it.isNotBlank() }?.let {
                pb.environment()["CURSOR_API_KEY"] = it
            }
            log.info("Fetching model info with command: $command")
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
        if (settings.agentPath.isNotBlank()) {
            val file = File(settings.agentPath)
            if (file.exists()) return settings.agentPath
        }

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val candidates = if (isWindows) {
            listOf("agent.cmd", "agent.bat", "agent.exe", "agent")
        } else {
            listOf("agent")
        }

        val pathDirs = System.getenv("PATH")?.split(File.pathSeparator) ?: emptyList()
        for (dir in pathDirs) {
            for (name in candidates) {
                val file = File(dir, name)
                if (file.exists()) {
                    log.info("Found agent in PATH: ${file.absolutePath}")
                    return file.absolutePath
                }
            }
        }

        val home = System.getProperty("user.home")
        val wellKnownPaths = buildList {
            if (isWindows) {
                add("$home\\AppData\\Local\\cursor-agent\\agent.cmd")
                add("$home\\AppData\\Local\\cursor-agent\\agent.exe")
                add("$home\\AppData\\Local\\Programs\\cursor-agent\\agent.cmd")
                add("$home\\AppData\\Local\\Programs\\cursor-agent\\agent.exe")
                add("$home\\.local\\bin\\agent.cmd")
                add("$home\\.local\\bin\\agent.exe")
            } else {
                add("$home/.local/bin/agent")
                add("$home/.cursor/bin/agent")
                add("/usr/local/bin/agent")
            }
        }
        for (path in wellKnownPaths) {
            val file = File(path)
            if (file.exists()) {
                log.info("Found agent at well-known path: ${file.absolutePath}")
                return file.absolutePath
            }
        }

        return null
    }

    override fun dispose() {
        stop()
    }
}
