package com.cursorj.history

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

class ChatHistoryStore(workspacePath: String?) {
    @Serializable
    data class ChatHistorySnapshot(
        val version: Int = VERSION,
        val entries: List<ChatHistoryEntry> = emptyList(),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val historyFile: File? = workspacePath?.let { File(it, RELATIVE_PATH) }

    fun load(): ChatHistorySnapshot {
        val file = historyFile ?: return ChatHistorySnapshot()
        if (!file.exists()) return ChatHistorySnapshot()

        return runCatching {
            val raw = file.readText(Charsets.UTF_8)
            json.decodeFromString<ChatHistorySnapshot>(raw).normalize()
        }.getOrElse {
            ChatHistorySnapshot()
        }
    }

    fun save(snapshot: ChatHistorySnapshot) {
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

    fun exists(): Boolean = historyFile?.exists() == true

    private fun ChatHistorySnapshot.normalize(): ChatHistorySnapshot {
        val normalizedEntries = entries
            .filter { it.sessionId.isNotBlank() && it.title.isNotBlank() }
            .map { entry ->
                entry.copy(
                    sessionId = entry.sessionId.trim(),
                    title = entry.title.trim(),
                )
            }
        return ChatHistorySnapshot(
            version = VERSION,
            entries = normalizedEntries,
        )
    }

    companion object {
        const val VERSION = 1
        const val RELATIVE_PATH = ".cursorj/chat-history-index-v1.json"
    }
}
