package com.cursorj.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

object CursorJSecretStore {
    private val apiKeyAttributes = CredentialAttributes(generateServiceName("CursorJ", "api-key"))

    fun getApiKey(): String = PasswordSafe.instance.get(apiKeyAttributes)?.getPasswordAsString().orEmpty()

    fun setApiKey(value: String) {
        val normalized = value.trim()
        if (normalized.isBlank()) {
            PasswordSafe.instance.set(apiKeyAttributes, null)
            return
        }

        PasswordSafe.instance.set(apiKeyAttributes, Credentials("cursorj", normalized))
    }
}
