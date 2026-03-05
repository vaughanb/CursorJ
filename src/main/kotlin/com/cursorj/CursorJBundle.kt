package com.cursorj

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.CursorJBundle"

object CursorJBundle : DynamicBundle(BUNDLE) {
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String {
        return getMessage(key, *params)
    }
}
