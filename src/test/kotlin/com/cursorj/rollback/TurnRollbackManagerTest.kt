package com.cursorj.rollback

import com.intellij.history.ByteContent
import com.intellij.history.Label
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    @Test
    fun `begin turn failure does not create checkpoint`() {
        val gateway = FakeLocalHistoryGateway(failPut = true)
        val manager = TurnRollbackManager(gateway)

        val turnId = manager.beginTurn("session-e", "Should fail")
        assertNull(turnId)
        assertFalse(manager.canRollback("session-e", isProcessing = false))
        assertEquals(RollbackStatus.NO_CHECKPOINT, manager.rollbackLastTurn("session-e", isProcessing = false).status)
    }

    @Test
    fun `multiple completed turns rollback in LIFO order`() {
        val gateway = FakeLocalHistoryGateway()
        val manager = TurnRollbackManager(gateway)

        val turn1 = manager.beginTurn("session-f", "First")
        manager.completeTurn("session-f", turn1, interrupted = false)
        val turn2 = manager.beginTurn("session-f", "Second")
        manager.completeTurn("session-f", turn2, interrupted = false)

        assertEquals(RollbackStatus.SUCCESS, manager.rollbackLastTurn("session-f", isProcessing = false).status)
        assertEquals(RollbackStatus.SUCCESS, manager.rollbackLastTurn("session-f", isProcessing = false).status)
        assertEquals(RollbackStatus.NO_CHECKPOINT, manager.rollbackLastTurn("session-f", isProcessing = false).status)
        assertEquals(
            listOf(gateway.createdLabels[1], gateway.createdLabels[0]),
            gateway.revertedLabels,
        )
    }

    @Test
    fun `rollback checkpoints are isolated per session`() {
        val gateway = FakeLocalHistoryGateway()
        val manager = TurnRollbackManager(gateway)

        val turnA = manager.beginTurn("session-g-a", "Session A")
        manager.completeTurn("session-g-a", turnA, interrupted = false)
        val turnB = manager.beginTurn("session-g-b", "Session B")
        manager.completeTurn("session-g-b", turnB, interrupted = false)

        assertEquals(RollbackStatus.SUCCESS, manager.rollbackLastTurn("session-g-a", isProcessing = false).status)
        assertTrue(manager.canRollback("session-g-b", isProcessing = false))
        assertEquals(RollbackStatus.SUCCESS, manager.rollbackLastTurn("session-g-b", isProcessing = false).status)
    }

    @Test
    fun `max entries keeps newest checkpoints only`() {
        val gateway = FakeLocalHistoryGateway()
        val manager = TurnRollbackManager(gateway, maxEntriesPerSession = 2)

        val turn1 = manager.beginTurn("session-h", "One")
        manager.completeTurn("session-h", turn1, interrupted = false)
        val turn2 = manager.beginTurn("session-h", "Two")
        manager.completeTurn("session-h", turn2, interrupted = false)
        val turn3 = manager.beginTurn("session-h", "Three")
        manager.completeTurn("session-h", turn3, interrupted = false)

        assertEquals(RollbackStatus.SUCCESS, manager.rollbackLastTurn("session-h", isProcessing = false).status)
        assertEquals(RollbackStatus.SUCCESS, manager.rollbackLastTurn("session-h", isProcessing = false).status)
        assertEquals(RollbackStatus.NO_CHECKPOINT, manager.rollbackLastTurn("session-h", isProcessing = false).status)
        assertEquals(
            listOf(gateway.createdLabels[2], gateway.createdLabels[1]),
            gateway.revertedLabels,
        )
    }

    @Test
    fun `mark active turn interrupted blocks rollback`() {
        val gateway = FakeLocalHistoryGateway()
        val manager = TurnRollbackManager(gateway)

        val turnId = manager.beginTurn("session-i", "Interrupted turn")
        assertNotNull(turnId)
        manager.markActiveTurnInterrupted("session-i")

        assertEquals(RollbackStatus.INTERRUPTED, manager.rollbackLastTurn("session-i", isProcessing = false).status)
        assertFalse(manager.canRollback("session-i", isProcessing = false))
    }

    private class FakeLocalHistoryGateway(
        private val failPut: Boolean = false,
        private val failRevert: Boolean = false,
    ) : LocalHistoryGateway {
        val createdLabels = mutableListOf<String>()
        val revertedLabels = mutableListOf<String>()

        override fun putSystemLabel(name: String): Label {
            if (failPut) throw IllegalStateException("put failed")
            createdLabels.add(name)
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
