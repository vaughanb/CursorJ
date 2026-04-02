package com.cursorj.acp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem

/**
 * Keeps an open editor tab in sync when the agent mutates a Cursor plan file on disk (e.g. via
 * edit diffs) without going through our [com.cursorj.handlers.FileSystemHandler].
 */
object AgentPlanEditorSync {
    private val log = Logger.getInstance(AgentPlanEditorSync::class.java)

    fun reloadOpenPlanEditorFromDisk(project: Project, absolutePath: String) {
        val normalized = absolutePath.replace('\\', '/')
        if (!AgentPlanFileSupport.isCursorAgentPlanMarkdownPath(normalized)) return

        val app = ApplicationManager.getApplication()
        if (app.isDisposed) return

        app.invokeLater {
            if (project.isDisposed) return@invokeLater
            try {
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(normalized)
                    ?: return@invokeLater
                vf.refresh(false, false)
                val fdm = FileDocumentManager.getInstance()
                val doc = fdm.getCachedDocument(vf) ?: return@invokeLater
                fdm.reloadFromDisk(doc)
            } catch (e: Exception) {
                log.warn("Could not reload open plan editor from disk: $normalized", e)
            }
        }
    }
}
