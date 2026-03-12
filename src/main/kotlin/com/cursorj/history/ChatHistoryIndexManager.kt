package com.cursorj.history

class ChatHistoryIndexManager(
    private val store: ChatHistoryStore,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val entries = mutableListOf<ChatHistoryEntry>()
    private val normalizedCap = maxEntries.coerceIn(1, 1000)

    @Synchronized
    fun load() {
        entries.clear()
        val loaded = store.load().entries
            .filter { it.sessionId.isNotBlank() && it.title.isNotBlank() }
        entries.addAll(loaded.takeLast(normalizedCap))
    }

    @Synchronized
    fun persist() {
        store.save(buildSnapshot())
    }

    @Synchronized
    fun recordSession(sessionId: String, title: String): Boolean {
        val id = sessionId.trim()
        val t = title.trim()
        if (id.isBlank() || t.isBlank()) return false

        val existing = entries.find { it.sessionId == id }
        val now = clock()
        if (existing != null) {
            existing.title = t
            existing.lastActivityAt = now
        } else {
            entries.add(ChatHistoryEntry(sessionId = id, title = t, createdAt = now, lastActivityAt = now))
            evictIfNeeded()
        }
        store.save(buildSnapshot())
        return true
    }

    @Synchronized
    fun updateTitle(sessionId: String, title: String): Boolean {
        val id = sessionId.trim()
        val t = title.trim()
        if (id.isBlank() || t.isBlank()) return false

        val entry = entries.find { it.sessionId == id } ?: return false
        entry.title = t
        store.save(buildSnapshot())
        return true
    }

    @Synchronized
    fun touchActivity(sessionId: String): Boolean {
        val entry = entries.find { it.sessionId == sessionId.trim() } ?: return false
        entry.lastActivityAt = clock()
        store.save(buildSnapshot())
        return true
    }

    @Synchronized
    fun clearAll() {
        entries.clear()
        store.save(buildSnapshot())
    }

    @Synchronized
    fun removeSession(sessionId: String): Boolean {
        val removed = entries.removeAll { it.sessionId == sessionId.trim() }
        if (removed) {
            store.save(buildSnapshot())
        }
        return removed
    }

    @Synchronized
    fun listAll(): List<ChatHistoryEntry> =
        entries.sortedByDescending { it.lastActivityAt }.map { it.copy() }

    @Synchronized
    fun search(query: String): List<ChatHistoryEntry> {
        val q = query.trim()
        if (q.isBlank()) return listAll()
        return entries
            .filter { it.title.contains(q, ignoreCase = true) }
            .sortedByDescending { it.lastActivityAt }
            .map { it.copy() }
    }

    @Synchronized
    fun entryCount(): Int = entries.size

    fun storeFileExists(): Boolean = store.exists()

    private fun evictIfNeeded() {
        if (entries.size <= normalizedCap) return
        entries.sortBy { it.lastActivityAt }
        while (entries.size > normalizedCap) {
            entries.removeAt(0)
        }
    }

    private fun buildSnapshot(): ChatHistoryStore.ChatHistorySnapshot {
        return ChatHistoryStore.ChatHistorySnapshot(
            entries = entries
                .filter { it.sessionId.isNotBlank() && it.title.isNotBlank() }
                .sortedByDescending { it.lastActivityAt },
        )
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 200
    }
}
