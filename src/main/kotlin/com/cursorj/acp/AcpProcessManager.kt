package com.cursorj.acp

import com.cursorj.settings.CursorJSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import java.io.BufferedReader
import java.io.BufferedWriter

class AcpProcessManager(private val parentDisposable: Disposable) : Disposable {
    private val log = Logger.getInstance(AcpProcessManager::class.java)
    private var process: Process? = null
    var reader: BufferedReader? = null
        private set
    var writer: BufferedWriter? = null
        private set

    val isRunning: Boolean
        get() = process?.isAlive == true

    init {
        Disposer.register(parentDisposable, this)
    }

    fun start(): Boolean {
        if (isRunning) return true

        val agentPath = resolveAgentPath() ?: run {
            log.warn("Cursor agent binary not found")
            return false
        }

        return try {
            val command = mutableListOf(agentPath, "acp")
            val settings = CursorJSettings.instance
            settings.apiKey.takeIf { it.isNotBlank() }?.let {
                command.addAll(listOf("--api-key", it))
            }

            val pb = ProcessBuilder(command)
            pb.redirectErrorStream(false)
            pb.environment()["CURSOR_API_KEY"]?.let { /* already set */ }
                ?: settings.apiKey.takeIf { it.isNotBlank() }?.let {
                    pb.environment()["CURSOR_API_KEY"] = it
                }

            process = pb.start()
            reader = process!!.inputStream.bufferedReader()
            writer = process!!.outputStream.bufferedWriter()
            log.info("Cursor agent ACP process started (pid=${process!!.pid()})")
            true
        } catch (e: Exception) {
            log.error("Failed to start Cursor agent ACP process", e)
            false
        }
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
        process = null
        reader = null
        writer = null
    }

    fun restart(): Boolean {
        stop()
        return start()
    }

    private fun resolveAgentPath(): String? {
        val settings = CursorJSettings.instance
        if (settings.agentPath.isNotBlank()) {
            val file = java.io.File(settings.agentPath)
            if (file.exists() && file.canExecute()) return settings.agentPath
        }

        val pathDirs = System.getenv("PATH")?.split(java.io.File.pathSeparator) ?: emptyList()
        val candidates = if (System.getProperty("os.name").lowercase().contains("win")) {
            listOf("agent.exe", "agent.cmd", "agent.bat", "agent")
        } else {
            listOf("agent")
        }

        for (dir in pathDirs) {
            for (name in candidates) {
                val file = java.io.File(dir, name)
                if (file.exists() && file.canExecute()) return file.absolutePath
            }
        }

        val home = System.getProperty("user.home")
        val homePaths = listOf(
            "$home/.local/bin/agent",
            "$home/.cursor/bin/agent",
            "$home/AppData/Local/Programs/cursor-agent/agent.exe",
        )
        for (path in homePaths) {
            val file = java.io.File(path)
            if (file.exists()) return file.absolutePath
        }

        return null
    }

    override fun dispose() {
        stop()
    }
}
