package com.cursorj.ui.chat

import com.cursorj.acp.ChatMode
import com.cursorj.acp.SessionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InputPanelModeTest {
    @Test
    fun `setMode selects Debug without firing onModeChanged`() {
        val panel = InputPanel()
        var changed: ChatMode? = null
        panel.onModeChanged = { changed = it }
        panel.setMode(ChatMode.DEBUG)
        assertEquals(ChatMode.DEBUG, panel.selectedMode())
        assertEquals(null, changed)
    }

    @Test
    fun `ChatMode combo index mapping covers all modes`() {
        assertEquals(ChatMode.AGENT, ChatMode.fromComboIndex(0))
        assertEquals(ChatMode.PLAN, ChatMode.fromComboIndex(1))
        assertEquals(ChatMode.ASK, ChatMode.fromComboIndex(2))
        assertEquals(ChatMode.DEBUG, ChatMode.fromComboIndex(3))
        assertEquals(ChatMode.AGENT, ChatMode.fromComboIndex(-1))

        assertEquals(0, ChatMode.AGENT.toComboIndex())
        assertEquals(1, ChatMode.PLAN.toComboIndex())
        assertEquals(2, ChatMode.ASK.toComboIndex())
        assertEquals(3, ChatMode.DEBUG.toComboIndex())
    }

    @Test
    fun `Debug maps to agent on the wire`() {
        assertEquals(SessionMode.AGENT, ChatMode.DEBUG.sessionMode)
        assertEquals(ChatMode.AGENT, ChatMode.fromSessionMode(SessionMode.AGENT))
        assertTrue(ChatMode.DEBUG.sessionMode == SessionMode.AGENT)
    }
}
