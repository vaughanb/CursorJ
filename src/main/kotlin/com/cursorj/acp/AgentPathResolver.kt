package com.cursorj.acp

import java.io.File

object AgentPathResolver {
    fun resolve(configuredPath: String?): String? {
        resolveConfiguredPath(configuredPath)?.let { return it }
        return resolveFromEnvironment()
    }

    fun resolveConfiguredPath(
        configuredPath: String?,
        osName: String = System.getProperty("os.name"),
    ): String? {
        val raw = configuredPath?.trim().orEmpty()
        if (raw.isBlank()) return null
        val file = File(raw)
        val isWindows = osName.lowercase().contains("win")
        if (!isRunnable(file, isWindows)) return null
        return file.absolutePath
    }

    fun resolveFromEnvironment(
        osName: String = System.getProperty("os.name"),
        pathValue: String? = System.getenv("PATH") ?: System.getenv("Path"),
        userHome: String = System.getProperty("user.home"),
    ): String? {
        val lowered = osName.lowercase()
        val isWindows = lowered.contains("win")
        val isMac = lowered.contains("mac") || lowered.contains("darwin")
        val candidates = if (isWindows) {
            listOf("agent.cmd", "agent.bat", "agent.exe", "agent")
        } else {
            listOf("agent")
        }

        val pathDirs = pathValue?.split(File.pathSeparator)?.filter { it.isNotBlank() } ?: emptyList()
        for (dir in pathDirs) {
            for (name in candidates) {
                val file = File(dir, name)
                if (isRunnable(file, isWindows)) {
                    return file.absolutePath
                }
            }
        }

        val wellKnownPaths = buildList {
            if (isWindows) {
                add("$userHome\\AppData\\Local\\cursor-agent\\agent.cmd")
                add("$userHome\\AppData\\Local\\cursor-agent\\agent.exe")
                add("$userHome\\AppData\\Local\\Programs\\cursor-agent\\agent.cmd")
                add("$userHome\\AppData\\Local\\Programs\\cursor-agent\\agent.exe")
                add("$userHome\\.local\\bin\\agent.cmd")
                add("$userHome\\.local\\bin\\agent.exe")
            } else if (isMac) {
                add("$userHome/.cursor/bin/agent")
                add("$userHome/.local/bin/agent")
                add("/opt/homebrew/bin/agent")
                add("/usr/local/bin/agent")
                add("/opt/local/bin/agent")
            } else {
                add("$userHome/.local/bin/agent")
                add("$userHome/.cursor/bin/agent")
                add("/usr/local/bin/agent")
            }
        }

        for (path in wellKnownPaths) {
            val file = File(path)
            if (isRunnable(file, isWindows)) {
                return file.absolutePath
            }
        }

        return null
    }

    private fun isRunnable(file: File, isWindows: Boolean): Boolean {
        return file.isFile && (isWindows || file.canExecute())
    }
}
