package com.cursorj.history

class PromptHistoryManager(
    private val store: PromptHistoryStore,
    private val maxEntriesPerSession: Int = DEFAULT_MAX_ENTRIES_PER_SESSION,
) {
    private data class NavigationState(
        var cursor: Int? = null,
        var draft: String = "",
    )

    private val promptsBySession = LinkedHashMap<String, MutableList<String>>()
    private val navigationBySession = HashMap<String, NavigationState>()
    private val normalizedCap = maxEntriesPerSession.coerceIn(1, 1000)

    @Synchronized
    fun load() {
        promptsBySession.clear()
        navigationBySession.clear()

        val loaded = store.load().sessions
        for ((sessionKey, prompts) in loaded) {
            val normalized = prompts
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .takeLast(normalizedCap)
            if (normalized.isNotEmpty()) {
                promptsBySession[sessionKey] = normalized.toMutableList()
            }
        }
    }

    @Synchronized
    fun persist() {
        store.save(buildSnapshot())
    }

    @Synchronized
    fun addPrompt(sessionKey: String, prompt: String): Boolean {
        val key = sessionKey.trim()
        val normalizedPrompt = prompt.trim()
        if (key.isBlank() || normalizedPrompt.isBlank()) return false

        val history = promptsBySession.getOrPut(key) { mutableListOf() }
        if (history.lastOrNull() == normalizedPrompt) return false
        history.add(normalizedPrompt)
        while (history.size > normalizedCap) {
            history.removeAt(0)
        }

        navigationBySession.remove(key)
        store.save(buildSnapshot())
        return true
    }

    @Synchronized
    fun previous(sessionKey: String, currentInput: String): String? {
        val key = sessionKey.trim()
        val history = promptsBySession[key] ?: return null
        if (history.isEmpty()) return null

        val navigation = navigationBySession.getOrPut(key) { NavigationState() }
        val cursor = navigation.cursor
        if (cursor == null) {
            navigation.draft = currentInput
            navigation.cursor = history.lastIndex
            return history.last()
        }

        val updatedCursor = (cursor - 1).coerceAtLeast(0)
        navigation.cursor = updatedCursor
        return history[updatedCursor]
    }

    @Synchronized
    fun next(sessionKey: String, currentInput: String): String? {
        val key = sessionKey.trim()
        val history = promptsBySession[key] ?: return null
        if (history.isEmpty()) return null

        val navigation = navigationBySession[key] ?: return null
        val cursor = navigation.cursor ?: return null
        val lastIndex = history.lastIndex
        if (cursor >= lastIndex) {
            navigation.cursor = null
            val draft = navigation.draft
            navigation.draft = ""
            return draft
        }

        val updatedCursor = (cursor + 1).coerceAtMost(lastIndex)
        navigation.cursor = updatedCursor
        return history[updatedCursor]
    }

    @Synchronized
    fun clearNavigation(sessionKey: String) {
        navigationBySession.remove(sessionKey.trim())
    }

    @Synchronized
    fun migrateSessionKey(fromSessionKey: String, toSessionKey: String): Boolean {
        val from = fromSessionKey.trim()
        val to = toSessionKey.trim()
        if (from.isBlank() || to.isBlank() || from == to) return false

        val sourceHistory = promptsBySession.remove(from) ?: mutableListOf()
        val targetHistory = promptsBySession[to] ?: mutableListOf()
        val merged = (sourceHistory + targetHistory)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .fold(mutableListOf<String>()) { acc, value ->
                if (acc.lastOrNull() != value) acc.add(value)
                acc
            }
            .takeLast(normalizedCap)

        if (merged.isEmpty()) {
            promptsBySession.remove(to)
        } else {
            promptsBySession[to] = merged.toMutableList()
        }

        val sourceNav = navigationBySession.remove(from)
        if (sourceNav != null) {
            navigationBySession[to] = sourceNav
        }

        val changed = sourceHistory.isNotEmpty() || sourceNav != null
        if (changed) {
            store.save(buildSnapshot())
        }
        return changed
    }

    @Synchronized
    fun snapshot(): Map<String, List<String>> =
        promptsBySession.mapValues { (_, prompts) -> prompts.toList() }

    @Synchronized
    fun historyFor(sessionKey: String): List<String> =
        promptsBySession[sessionKey.trim()]?.toList() ?: emptyList()

    private fun buildSnapshot(): PromptHistoryStore.PromptHistorySnapshot {
        return PromptHistoryStore.PromptHistorySnapshot(
            sessions = promptsBySession.mapValues { (_, prompts) ->
                prompts.map { it.trim() }.filter { it.isNotBlank() }.takeLast(normalizedCap)
            }.filterValues { it.isNotEmpty() },
        )
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES_PER_SESSION = 200
    }
}
