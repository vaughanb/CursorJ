package com.cursorj.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.util.Disposer
import java.util.concurrent.CopyOnWriteArrayList

@State(
    name = "com.cursorj.settings.CursorJSettings",
    storages = [Storage("CursorJSettings.xml")],
)
class CursorJSettings : PersistentStateComponent<CursorJSettings.State> {
    data class State(
        var agentPath: String = "",
        var defaultModel: String = "",
        var autoAttachActiveFile: Boolean = true,
        var enableProjectIndexing: Boolean = true,
        var enableLexicalPersistence: Boolean = true,
        var enableSemanticIndexing: Boolean = false,
        var retrievalMaxCandidates: Int = 40,
        var retrievalSnippetCharBudget: Int = 5000,
        var retrievalTimeoutMs: Int = 2500,
        var indexRetentionDays: Int = 30,
        var indexMaxDatabaseMb: Int = 512,
        var showIndexingStatusInChat: Boolean = true,
        // Legacy field retained for backward compatibility with existing configs.
        var defaultPermissionBehavior: String = "ask",
        var permissionMode: String = "ask-every-time",
        var approvedPermissionKeys: MutableList<String> = mutableListOf(),
        var protectExternalFileWrites: Boolean = true,
        var runEverythingConfirmationAcknowledged: Boolean = false,
        var savedSessionIds: MutableList<String> = mutableListOf(),
    )

    private val stateLock = Any()
    private val changeListeners = CopyOnWriteArrayList<() -> Unit>()
    private var myState = State()

    override fun getState(): State = synchronized(stateLock) { myState }

    override fun loadState(state: State) {
        synchronized(stateLock) {
            myState = state
        }
        migrateLegacyPermissionBehavior()
    }

    var agentPath: String
        get() = synchronized(stateLock) { myState.agentPath }
        set(value) {
            synchronized(stateLock) { myState.agentPath = value }
            fireSettingsChanged()
        }

    private fun migrateLegacyPermissionBehavior() {
        synchronized(stateLock) {
            if (myState.permissionMode.isNotBlank()) return
            myState.permissionMode = when (myState.defaultPermissionBehavior.trim().lowercase()) {
                "allow" -> "run-everything"
                else -> "ask-every-time"
            }
        }
    }

    var defaultModel: String
        get() = synchronized(stateLock) { myState.defaultModel }
        set(value) {
            synchronized(stateLock) { myState.defaultModel = value }
            fireSettingsChanged()
        }

    var autoAttachActiveFile: Boolean
        get() = synchronized(stateLock) { myState.autoAttachActiveFile }
        set(value) {
            synchronized(stateLock) { myState.autoAttachActiveFile = value }
            fireSettingsChanged()
        }

    var enableProjectIndexing: Boolean
        get() = synchronized(stateLock) { myState.enableProjectIndexing }
        set(value) {
            synchronized(stateLock) { myState.enableProjectIndexing = value }
            fireSettingsChanged()
        }

    var enableLexicalPersistence: Boolean
        get() = synchronized(stateLock) { myState.enableLexicalPersistence }
        set(value) {
            synchronized(stateLock) { myState.enableLexicalPersistence = value }
            fireSettingsChanged()
        }

    var enableSemanticIndexing: Boolean
        get() = synchronized(stateLock) { myState.enableSemanticIndexing }
        set(value) {
            synchronized(stateLock) { myState.enableSemanticIndexing = value }
            fireSettingsChanged()
        }

    var retrievalMaxCandidates: Int
        get() = synchronized(stateLock) { myState.retrievalMaxCandidates.coerceIn(1, 200) }
        set(value) {
            synchronized(stateLock) { myState.retrievalMaxCandidates = value.coerceIn(1, 200) }
            fireSettingsChanged()
        }

    var retrievalSnippetCharBudget: Int
        get() = synchronized(stateLock) { myState.retrievalSnippetCharBudget.coerceIn(500, 30000) }
        set(value) {
            synchronized(stateLock) { myState.retrievalSnippetCharBudget = value.coerceIn(500, 30000) }
            fireSettingsChanged()
        }

    var retrievalTimeoutMs: Int
        get() = synchronized(stateLock) { myState.retrievalTimeoutMs.coerceIn(250, 20000) }
        set(value) {
            synchronized(stateLock) { myState.retrievalTimeoutMs = value.coerceIn(250, 20000) }
            fireSettingsChanged()
        }

    var indexRetentionDays: Int
        get() = synchronized(stateLock) { myState.indexRetentionDays.coerceIn(1, 365) }
        set(value) {
            synchronized(stateLock) { myState.indexRetentionDays = value.coerceIn(1, 365) }
            fireSettingsChanged()
        }

    var indexMaxDatabaseMb: Int
        get() = synchronized(stateLock) { myState.indexMaxDatabaseMb.coerceIn(50, 4096) }
        set(value) {
            synchronized(stateLock) { myState.indexMaxDatabaseMb = value.coerceIn(50, 4096) }
            fireSettingsChanged()
        }

    var showIndexingStatusInChat: Boolean
        get() = synchronized(stateLock) { myState.showIndexingStatusInChat }
        set(value) {
            synchronized(stateLock) { myState.showIndexingStatusInChat = value }
            fireSettingsChanged()
        }

    var defaultPermissionBehavior: String
        get() = synchronized(stateLock) { myState.defaultPermissionBehavior }
        set(value) {
            synchronized(stateLock) { myState.defaultPermissionBehavior = value }
            fireSettingsChanged()
        }

    var permissionMode: String
        get() = synchronized(stateLock) { myState.permissionMode }
        set(value) {
            synchronized(stateLock) {
                myState.permissionMode = value
                myState.defaultPermissionBehavior = if (value == "run-everything") "allow" else "ask"
            }
            fireSettingsChanged()
        }

    var protectExternalFileWrites: Boolean
        get() = synchronized(stateLock) { myState.protectExternalFileWrites }
        set(value) {
            synchronized(stateLock) { myState.protectExternalFileWrites = value }
            fireSettingsChanged()
        }

    var runEverythingConfirmationAcknowledged: Boolean
        get() = synchronized(stateLock) { myState.runEverythingConfirmationAcknowledged }
        set(value) {
            synchronized(stateLock) { myState.runEverythingConfirmationAcknowledged = value }
            fireSettingsChanged()
        }

    var savedSessionIds: MutableList<String>
        get() = synchronized(stateLock) { myState.savedSessionIds }
        set(value) {
            synchronized(stateLock) { myState.savedSessionIds = value }
            fireSettingsChanged()
        }

    fun getApprovedPermissionKeys(): Set<String> = synchronized(stateLock) {
        myState.approvedPermissionKeys
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    fun setApprovedPermissionKeys(keys: Set<String>) {
        synchronized(stateLock) {
            myState.approvedPermissionKeys = keys
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
                .toMutableList()
        }
        fireSettingsChanged()
    }

    fun approvePermissionKeys(keys: Set<String>) {
        if (keys.isEmpty()) return
        synchronized(stateLock) {
            val existing = myState.approvedPermissionKeys.toMutableSet()
            existing.addAll(keys.map { it.trim() }.filter { it.isNotBlank() })
            myState.approvedPermissionKeys = existing.sorted().toMutableList()
        }
        fireSettingsChanged()
    }

    fun clearApprovedPermissionKeys() {
        synchronized(stateLock) {
            myState.approvedPermissionKeys.clear()
        }
        fireSettingsChanged()
    }

    fun addChangeListener(parentDisposable: Disposable? = null, listener: () -> Unit) {
        changeListeners.add(listener)
        if (parentDisposable != null) {
            Disposer.register(parentDisposable, Disposable {
                changeListeners.remove(listener)
            })
        }
    }

    private fun fireSettingsChanged() {
        for (listener in changeListeners) {
            try {
                listener()
            } catch (_: Exception) {
                // Keep settings robust even if a listener fails.
            }
        }
    }

    companion object {
        val instance: CursorJSettings
            get() = ApplicationManager.getApplication().getService(CursorJSettings::class.java)
    }
}
