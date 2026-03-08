package com.cursorj.rollback

import com.intellij.history.ByteContent
import com.intellij.history.Label
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TurnRollbackManagerTest {
    @Test
    fun `completed turn can roll back and consumes checkpoint`() {
        val gateway = FakeLocalHistoryGateway()
        val manager = TurnRollbackManager(gateway)

        val turnId = manager.beginTurn("session-a", "Refactor parser")
        assertNotNull(turnId)

        manager.completeTurn("session-a", turnId, interrupted = false)

        assertTrue(manager.canRollback("session-a", isProcessing = false))
        val result = manager.rollbackLastTurn("session-a", isProcessing = false)

        assertEquals(RollbackStatus.SUCCESS, result.status)
        assertEquals(1, gateway.revertedLabels.size)
        assertFalse(manager.canRollback("session-a", isProcessing = false))
    }

    @Test
    fun `rollback is unavailable while processing`() {
        val gateway = FakeLocalHistoryGateway()
        val manager = TurnRollbackManager(gateway)

        val turnId = manager.beginTurn("session-b", "Update docs")
        manager.completeTurn("session-b", turnId, interrupted = false)

        assertFalse(manager.canRollback("session-b", isProcessing = true))
        val result = manager.rollbackLastTurn("session-b", isProcessing = true)
        assertEquals(RollbackStatus.PROCESSING, result.status)
    }

    @Test
    fun `in-progress or interrupted turns are not rollbackable`() {
        val gateway = FakeLocalHistoryGateway()
        val manager = TurnRollbackManager(gateway)

        val inProgressTurnId = manager.beginTurn("session-c", "Generate tests")
        assertNotNull(inProgressTurnId)
        val inProgressResult = manager.rollbackLastTurn("session-c", isProcessing = false)
        assertEquals(RollbackStatus.IN_PROGRESS, inProgressResult.status)

        manager.completeTurn("session-c", inProgressTurnId, interrupted = true)
        val interruptedResult = manager.rollbackLastTurn("session-c", isProcessing = false)
        assertEquals(RollbackStatus.INTERRUPTED, interruptedResult.status)
    }

    @Test
    fun `failed revert preserves checkpoint`() {
        val gateway = FakeLocalHistoryGateway(failRevert = true)
        val manager = TurnRollbackManager(gateway)

        val turnId = manager.beginTurn("session-d", "Rename symbols")
        manager.completeTurn("session-d", turnId, interrupted = false)

        val failed = manager.rollbackLastTurn("session-d", isProcessing = false)
        assertEquals(RollbackStatus.FAILED, failed.status)
        assertTrue(manager.canRollback("session-d", isProcessing = false))
    }

    private class FakeLocalHistoryGateway(
        private val failRevert: Boolean = false,
    ) : LocalHistoryGateway {
        val revertedLabels = mutableListOf<String>()

        override fun putSystemLabel(name: String): Label {
            return FakeLabel(name)
        }

        override fun revert(label: Label) {
            if (failRevert) throw IllegalStateException("boom")
            revertedLabels.add((label as FakeLabel).name)
        }
    }

    private class FakeLabel(
        val name: String,
    ) : Label {
        override fun revert(project: Project, file: VirtualFile) {}

        override fun getByteContent(path: String): ByteContent? = null
    }
}
