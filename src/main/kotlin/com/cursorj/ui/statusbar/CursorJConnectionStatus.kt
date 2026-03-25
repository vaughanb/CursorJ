package com.cursorj.ui.statusbar

import com.cursorj.CursorJBundle
import java.util.concurrent.CopyOnWriteArrayList

object CursorJConnectionStatus {
    private var connectionDetail: String = normalizeStatusText(CursorJBundle.message("status.disconnected"))
    private var indexingDetail: String? = null
    private var _text = "CursorJ: $connectionDetail"
    val text: String get() = _text

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun update(connected: Boolean, detail: String? = null) {
        connectionDetail = normalizeStatusText(when {
            detail != null -> detail
            connected -> CursorJBundle.message("status.connected")
            else -> CursorJBundle.message("status.disconnected")
        })
        refreshText()
    }

    fun updateIndexing(detail: String?) {
        indexingDetail = detail?.takeIf { it.isNotBlank() }
        refreshText()
    }

    private fun refreshText() {
        _text = when {
            indexingDetail != null -> "CursorJ: $connectionDetail | ${indexingDetail!!}"
            else -> "CursorJ: $connectionDetail"
        }
        for (listener in listeners) {
            listener()
        }
    }

    private fun normalizeStatusText(value: String): String {
        return value.removePrefix("CursorJ: ").trim()
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
}
