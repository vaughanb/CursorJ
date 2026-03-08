package com.cursorj.rollback

import com.intellij.history.Label
import com.intellij.history.LocalHistory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.util.concurrent.atomic.AtomicLong

enum class RollbackStatus {
    SUCCESS,
    NO_CHECKPOINT,
    IN_PROGRESS,
    INTERRUPTED,
    PROCESSING,
    FAILED,
}

data class RollbackResult(
    val status: RollbackStatus,
    val errorMessage: String? = null,
) {
    val success: Boolean get() = status == RollbackStatus.SUCCESS
}

interface LocalHistoryGateway {
    fun putSystemLabel(name: String): Label
    fun revert(label: Label)
}

class IntelliJLocalHistoryGateway(private val project: Project) : LocalHistoryGateway {
    override fun putSystemLabel(name: String): Label {
        return LocalHistory.getInstance().putSystemLabel(project, name)
    }

    override fun revert(label: Label) {
        val basePath = project.basePath ?: throw IllegalStateException("Project base path is unavailable")
        val normalizedBasePath = basePath.replace('\\', '/')
        val projectRoot = LocalFileSystem.getInstance().refreshAndFindFileByPath(normalizedBasePath)
            ?: throw IllegalStateException("Project root not found: $normalizedBasePath")

        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            label.revert(project, projectRoot)
        } else {
            app.invokeAndWait { label.revert(project, projectRoot) }
        }

        projectRoot.refresh(false, true)
    }
}

class TurnRollbackManager(
    private val gateway: LocalHistoryGateway,
    private val maxEntriesPerSession: Int = 20,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private enum class TurnState {
        IN_PROGRESS,
        COMPLETED,
        INTERRUPTED,
    }

    private data class TurnCheckpoint(
        val turnId: Long,
        val label: Label,
        val promptSummary: String,
        val createdAt: Long,
        var state: TurnState,
    )

    private val log = Logger.getInstance(TurnRollbackManager::class.java)
    private val nextTurnId = AtomicLong(1L)
    private val checkpointsBySession = mutableMapOf<String, MutableList<TurnCheckpoint>>()

    companion object {
        fun forProject(project: Project): TurnRollbackManager {
            return TurnRollbackManager(IntelliJLocalHistoryGateway(project))
        }
    }

    @Synchronized
    fun beginTurn(sessionId: String, promptSummary: String): Long? {
        return try {
            val turnId = nextTurnId.getAndIncrement()
            val label = gateway.putSystemLabel(buildLabelName(promptSummary))
            val checkpoints = checkpointsBySession.getOrPut(sessionId) { mutableListOf() }
            checkpoints.add(
                TurnCheckpoint(
                    turnId = turnId,
                    label = label,
                    promptSummary = promptSummary,
                    createdAt = clock(),
                    state = TurnState.IN_PROGRESS,
                ),
            )
            if (checkpoints.size > maxEntriesPerSession) {
                checkpoints.removeAt(0)
            }
            turnId
        } catch (e: Exception) {
            log.warn("Failed to create Local History label for rollback", e)
            null
        }
    }

    @Synchronized
    fun completeTurn(sessionId: String, turnId: Long?, interrupted: Boolean = false) {
        if (turnId == null) return
        val checkpoints = checkpointsBySession[sessionId] ?: return
        val checkpoint = checkpoints.lastOrNull { it.turnId == turnId } ?: return
        checkpoint.state = if (interrupted) TurnState.INTERRUPTED else TurnState.COMPLETED
    }

    @Synchronized
    fun markActiveTurnInterrupted(sessionId: String) {
        val checkpoints = checkpointsBySession[sessionId] ?: return
        val checkpoint = checkpoints.lastOrNull { it.state == TurnState.IN_PROGRESS } ?: return
        checkpoint.state = TurnState.INTERRUPTED
    }

    @Synchronized
    fun canRollback(sessionId: String, isProcessing: Boolean): Boolean {
        if (isProcessing) return false
        val latest = checkpointsBySession[sessionId]?.lastOrNull() ?: return false
        return latest.state == TurnState.COMPLETED
    }

    @Synchronized
    fun rollbackLastTurn(sessionId: String, isProcessing: Boolean): RollbackResult {
        if (isProcessing) return RollbackResult(RollbackStatus.PROCESSING)

        val checkpoints = checkpointsBySession[sessionId] ?: return RollbackResult(RollbackStatus.NO_CHECKPOINT)
        val latest = checkpoints.lastOrNull() ?: return RollbackResult(RollbackStatus.NO_CHECKPOINT)

        return when (latest.state) {
            TurnState.IN_PROGRESS -> RollbackResult(RollbackStatus.IN_PROGRESS)
            TurnState.INTERRUPTED -> RollbackResult(RollbackStatus.INTERRUPTED)
            TurnState.COMPLETED -> {
                try {
                    gateway.revert(latest.label)
                    checkpoints.removeAt(checkpoints.lastIndex)
                    if (checkpoints.isEmpty()) checkpointsBySession.remove(sessionId)
                    RollbackResult(RollbackStatus.SUCCESS)
                } catch (e: Exception) {
                    log.warn("Failed to rollback Local History label", e)
                    RollbackResult(RollbackStatus.FAILED, e.message ?: "Unknown error")
                }
            }
        }
    }

    private fun buildLabelName(promptSummary: String): String {
        val compact = promptSummary.replace(Regex("\\s+"), " ").trim()
        val summary = compact.take(40).ifBlank { "agent turn" }
        return "CursorJ before turn: $summary"
    }
}
