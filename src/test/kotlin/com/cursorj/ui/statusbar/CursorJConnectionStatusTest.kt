package com.cursorj.ui.statusbar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CursorJConnectionStatusTest {
    @Test
    fun `status text includes indexing detail when provided`() {
        CursorJConnectionStatus.update(connected = true, detail = "Connected (model-x)")
        CursorJConnectionStatus.updateIndexing("Indexing project...")
        assertTrue(CursorJConnectionStatus.text.contains("Connected (model-x)"))
        assertTrue(CursorJConnectionStatus.text.contains("Indexing project..."))
        CursorJConnectionStatus.updateIndexing("Index ready")
        assertTrue(CursorJConnectionStatus.text.contains("Index ready"))
        CursorJConnectionStatus.updateIndexing(null)
    }

    @Test
    fun `connection status strips prefixed label`() {
        CursorJConnectionStatus.update(connected = true, detail = "CursorJ: Connected (model-y)")
        assertTrue(CursorJConnectionStatus.text.contains("Connected (model-y)"))
    }

    @Test
    fun `listeners are called on status updates`() {
        var calls = 0
        val listener: () -> Unit = { calls += 1 }
        CursorJConnectionStatus.addListener(listener)
        try {
            CursorJConnectionStatus.update(connected = false)
            CursorJConnectionStatus.updateIndexing("Indexing project...")
            CursorJConnectionStatus.updateIndexing(null)
        } finally {
            CursorJConnectionStatus.removeListener(listener)
        }
        assertEquals(3, calls)
    }

    @Test
    fun `blank indexing detail is treated as absent`() {
        CursorJConnectionStatus.update(connected = true, detail = "Connected")
        CursorJConnectionStatus.updateIndexing("   ")
        assertEquals("CursorJ: Connected", CursorJConnectionStatus.text)
    }
}
