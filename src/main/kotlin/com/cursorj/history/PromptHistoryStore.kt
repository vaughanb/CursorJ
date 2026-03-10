package com.cursorj.history

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

class PromptHistoryStore(workspacePath: String?) {
    @Serializable
    data class PromptHistorySnapshot(
        val version: Int = VERSION,
        val sessions: Map<String, List<String>> = emptyMap(),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val historyFile: File? = workspacePath?.let { File(it, RELATIVE_PATH) }

    fun load(): PromptHistorySnapshot {
        val file = historyFile ?: return PromptHistorySnapshot()
        if (!file.exists()) return PromptHistorySnapshot()

        return runCatching {
            val raw = file.readText(Charsets.UTF_8)
            json.decodeFromString<PromptHistorySnapshot>(raw).normalize()
        }.getOrElse {
            PromptHistorySnapshot()
        }
    }

    fun save(snapshot: PromptHistorySnapshot) {
        val file = historyFile ?: return
        val payload = json.encodeToString(snapshot.normalize())
        val parent = file.parentFile ?: return
        if (!parent.exists()) {
            parent.mkdirs()
        }

        val targetPath = file.toPath()
        val tempPath = targetPath.resolveSibling("${file.name}.tmp")
        runCatching {
            Files.writeString(
                tempPath,
                payload,
                Charsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            runCatching {
                Files.move(
                    tempPath,
                    targetPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.recoverCatching {
                Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }.getOrThrow()
        }.onFailure {
            runCatching { Files.deleteIfExists(tempPath) }
        }
    }

    private fun PromptHistorySnapshot.normalize(): PromptHistorySnapshot {
        val normalizedSessions = sessions
            .mapKeys { it.key.trim() }
            .filterKeys { it.isNotBlank() }
            .mapValues { (_, prompts) ->
                prompts
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
            }
            .filterValues { it.isNotEmpty() }
        return PromptHistorySnapshot(
            version = VERSION,
            sessions = normalizedSessions,
        )
    }

    companion object {
        const val VERSION = 1
        const val RELATIVE_PATH = ".cursorj/prompt-history-v1.json"
    }
}
