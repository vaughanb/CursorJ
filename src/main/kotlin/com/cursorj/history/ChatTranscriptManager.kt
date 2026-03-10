package com.cursorj.history

import com.cursorj.acp.ChatMessage
import com.cursorj.history.ChatTranscriptStore.TranscriptMessage

class ChatTranscriptManager(
    private val store: ChatTranscriptStore,
    private val maxEntriesPerSession: Int = DEFAULT_MAX_ENTRIES_PER_SESSION,
    private val maxMessageChars: Int = DEFAULT_MAX_MESSAGE_CHARS,
) {
    private val normalizedEntryCap = maxEntriesPerSession.coerceIn(10, 2_000)
    private val normalizedMessageCharCap = maxMessageChars.coerceIn(200, 50_000)
    private val transcriptBySession = LinkedHashMap<String, MutableList<TranscriptMessage>>()

    @Synchronized
    fun load() {
        transcriptBySession.clear()
        val loaded = store.load().sessions
        for ((sessionKey, messages) in loaded) {
            val normalized = messages
                .mapNotNull { normalize(it) }
                .takeLast(normalizedEntryCap)
            if (normalized.isNotEmpty()) {
                transcriptBySession[sessionKey] = normalized.toMutableList()
            }
        }
    }

    @Synchronized
    fun persist() {
        store.save(buildSnapshot())
    }

    @Synchronized
    fun addMessage(sessionKey: String, message: ChatMessage): Boolean {
        if (message.isStreaming) return false
        val key = sessionKey.trim()
        if (key.isBlank()) return false
        val normalized = normalize(message) ?: return false
        val transcript = transcriptBySession.getOrPut(key) { mutableListOf() }
        if (transcript.lastOrNull() == normalized) return false
        transcript.add(normalized)
        while (transcript.size > normalizedEntryCap) {
            transcript.removeAt(0)
        }
        store.save(buildSnapshot())
        return true
    }

    @Synchronized
    fun replaceSession(sessionKey: String, messages: List<ChatMessage>): Boolean {
        val key = sessionKey.trim()
        if (key.isBlank()) return false
        val normalized = messages
            .mapNotNull { normalize(it) }
            .takeLast(normalizedEntryCap)
        if (normalized.isEmpty()) return false
        transcriptBySession[key] = normalized.toMutableList()
        store.save(buildSnapshot())
        return true
    }

    @Synchronized
    fun migrateSessionKey(fromSessionKey: String, toSessionKey: String): Boolean {
        val from = fromSessionKey.trim()
        val to = toSessionKey.trim()
        if (from.isBlank() || to.isBlank() || from == to) return false

        val source = transcriptBySession.remove(from) ?: mutableListOf()
        val target = transcriptBySession[to] ?: mutableListOf()
        val merged = (source + target)
            .mapNotNull { normalize(it) }
            .fold(mutableListOf<TranscriptMessage>()) { acc, msg ->
                if (acc.lastOrNull() != msg) acc.add(msg)
                acc
            }
            .takeLast(normalizedEntryCap)
        if (merged.isEmpty()) {
            transcriptBySession.remove(to)
        } else {
            transcriptBySession[to] = merged.toMutableList()
        }
        val changed = source.isNotEmpty()
        if (changed) {
            store.save(buildSnapshot())
        }
        return changed
    }

    @Synchronized
    fun transcriptFor(sessionKey: String): List<ChatMessage> {
        return transcriptBySession[sessionKey.trim()]
            ?.map { ChatMessage(role = it.role, content = it.content) }
            ?: emptyList()
    }

    @Synchronized
    fun buildCarryoverContext(sessionKey: String, maxMessages: Int = 16, maxChars: Int = 12_000): String? {
        val transcript = transcriptBySession[sessionKey.trim()].orEmpty()
        if (transcript.isEmpty()) return null

        val selected = transcript
            .takeLast(maxMessages.coerceIn(2, 100))
            .filter { it.content.isNotBlank() }
        if (selected.isEmpty()) return null

        val budget = maxChars.coerceIn(500, 50_000)
        val header = "Previous conversation context restored from local transcript (server session not recovered):"
        val builder = StringBuilder(header).append('\n')
        var remaining = budget - builder.length
        var truncated = false

        for (msg in selected) {
            val roleLabel = when (msg.role.lowercase()) {
                "assistant" -> "Assistant"
                "user" -> "User"
                "system" -> "System"
                else -> msg.role.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
            val entry = "[$roleLabel]\n${msg.content}\n\n"
            if (entry.length <= remaining) {
                builder.append(entry)
                remaining -= entry.length
                continue
            }
            if (remaining > 40) {
                builder.append(entry.take(remaining))
            }
            truncated = true
            break
        }

        if (truncated) {
            builder.append("\n[Transcript truncated to fit context budget.]")
        }
        return builder.toString().trim().ifBlank { null }
    }

    private fun normalize(message: ChatMessage): TranscriptMessage? {
        val role = message.role.trim().lowercase()
        val content = message.content.trim()
        if (role.isBlank() || content.isBlank()) return null
        return TranscriptMessage(
            role = role,
            content = content.take(normalizedMessageCharCap),
        )
    }

    private fun normalize(message: TranscriptMessage): TranscriptMessage? {
        val role = message.role.trim().lowercase()
        val content = message.content.trim()
        if (role.isBlank() || content.isBlank()) return null
        return TranscriptMessage(
            role = role,
            content = content.take(normalizedMessageCharCap),
        )
    }

    private fun buildSnapshot(): ChatTranscriptStore.ChatTranscriptSnapshot {
        return ChatTranscriptStore.ChatTranscriptSnapshot(
            sessions = transcriptBySession.mapValues { (_, messages) ->
                messages.mapNotNull { normalize(it) }.takeLast(normalizedEntryCap)
            }.filterValues { it.isNotEmpty() },
        )
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES_PER_SESSION = 400
        const val DEFAULT_MAX_MESSAGE_CHARS = 12_000
    }
}
