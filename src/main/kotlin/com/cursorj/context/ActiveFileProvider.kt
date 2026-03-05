package com.cursorj.context

import com.cursorj.acp.messages.ContentBlock
import com.cursorj.acp.messages.ResourceLinkContent
import com.cursorj.acp.messages.TextContent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class ActiveFileProvider(private val project: Project) {
    private var _activeFile: VirtualFile? = null
    val activeFile: VirtualFile? get() = _activeFile

    private val listeners = mutableListOf<(VirtualFile?) -> Unit>()

    init {
        _activeFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()

        project.messageBus.connect().subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    _activeFile = event.newFile
                    listeners.forEach { it(_activeFile) }
                }
            },
        )
    }

    fun addListener(listener: (VirtualFile?) -> Unit) {
        listeners.add(listener)
    }

    fun buildContextBlocks(): List<ContentBlock> {
        val file = _activeFile ?: return emptyList()
        return listOf(
            ResourceLinkContent(
                uri = file.path,
                name = file.name,
            ),
        )
    }
}

class SelectionProvider(private val project: Project) {
    fun getSelectedText(): Pair<String, String?>? {
        val editor = currentEditor() ?: return null
        val selectionModel = editor.selectionModel
        val selectedText = selectionModel.selectedText ?: return null
        if (selectedText.isBlank()) return null

        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        return Pair(selectedText, file?.path)
    }

    fun buildContextBlocks(): List<ContentBlock> {
        val (text, filePath) = getSelectedText() ?: return emptyList()
        val blocks = mutableListOf<ContentBlock>()
        if (filePath != null) {
            blocks.add(ResourceLinkContent(uri = filePath, name = filePath.substringAfterLast('/')))
        }
        blocks.add(TextContent(text = "Selected code:\n```\n$text\n```"))
        return blocks
    }

    private fun currentEditor(): Editor? {
        val editorManager = FileEditorManager.getInstance(project)
        return editorManager.selectedTextEditor
    }
}
