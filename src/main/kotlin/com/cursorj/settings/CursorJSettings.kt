package com.cursorj.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "com.cursorj.settings.CursorJSettings",
    storages = [Storage("CursorJSettings.xml")],
)
class CursorJSettings : PersistentStateComponent<CursorJSettings.State> {
    data class State(
        var agentPath: String = "",
        // Legacy field kept only to migrate existing plaintext settings to Password Safe.
        var apiKey: String = "",
        var defaultModel: String = "",
        var autoAttachActiveFile: Boolean = true,
        var defaultPermissionBehavior: String = "ask",
        var savedSessionIds: MutableList<String> = mutableListOf(),
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
        migrateLegacyApiKey()
    }

    var agentPath: String
        get() = myState.agentPath
        set(value) { myState.agentPath = value }

    var apiKey: String
        get() = CursorJSecretStore.getApiKey()
        set(value) {
            CursorJSecretStore.setApiKey(value)
            myState.apiKey = ""
        }

    private fun migrateLegacyApiKey() {
        if (myState.apiKey.isBlank()) return
        if (CursorJSecretStore.getApiKey().isBlank()) {
            CursorJSecretStore.setApiKey(myState.apiKey)
        }
        myState.apiKey = ""
    }

    var defaultModel: String
        get() = myState.defaultModel
        set(value) { myState.defaultModel = value }

    var autoAttachActiveFile: Boolean
        get() = myState.autoAttachActiveFile
        set(value) { myState.autoAttachActiveFile = value }

    var defaultPermissionBehavior: String
        get() = myState.defaultPermissionBehavior
        set(value) { myState.defaultPermissionBehavior = value }

    var savedSessionIds: MutableList<String>
        get() = myState.savedSessionIds
        set(value) { myState.savedSessionIds = value }

    companion object {
        val instance: CursorJSettings
            get() = ApplicationManager.getApplication().getService(CursorJSettings::class.java)
    }
}
