package com.cursorj.history

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

class ChatTranscriptStore(workspacePath: String?) {
    @Serializable
    data class TranscriptMessage(
        val role: String,
        val content: String,
    )

    @Serializable
    data class ChatTranscriptSnapshot(
        val version: Int = VERSION,
        val sessions: Map<String, List<TranscriptMessage>> = emptyMap(),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val transcriptFile: File? = workspacePath?.let { File(it, RELATIVE_PATH) }

    fun load(): ChatTranscriptSnapshot {
        val file = transcriptFile ?: return ChatTranscriptSnapshot()
        if (!file.exists()) return ChatTranscriptSnapshot()

        return runCatching {
            val raw = file.readText(Charsets.UTF_8)
            json.decodeFromString<ChatTranscriptSnapshot>(raw).normalize()
        }.getOrElse {
            ChatTranscriptSnapshot()
        }
    }

    fun save(snapshot: ChatTranscriptSnapshot) {
        val file = transcriptFile ?: return
        val payload = json.encodeToString(snapshot.normalize())
        val parent = file.parentFile ?: return
        if (!parent.exists()) parent.mkdirs()

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

    private fun ChatTranscriptSnapshot.normalize(): ChatTranscriptSnapshot {
        val normalizedSessions = sessions
            .mapKeys { it.key.trim() }
            .filterKeys { it.isNotBlank() }
            .mapValues { (_, messages) ->
                messages
                    .mapNotNull { msg ->
                        val role = msg.role.trim().lowercase()
                        val content = msg.content.trim()
                        if (role.isBlank() || content.isBlank()) null
                        else TranscriptMessage(role = role, content = content)
                    }
            }
            .filterValues { it.isNotEmpty() }
        return ChatTranscriptSnapshot(
            version = VERSION,
            sessions = normalizedSessions,
        )
    }

    companion object {
        const val VERSION = 1
        const val RELATIVE_PATH = ".cursorj/chat-transcripts-v1.json"
    }
}
