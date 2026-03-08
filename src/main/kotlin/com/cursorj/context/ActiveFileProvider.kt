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
    data class SelectionContext(
        val label: String,
        val blocks: List<ContentBlock>,
    )

    companion object {
        internal fun buildSelectionContext(
            selectedText: String,
            filePath: String?,
            fileName: String?,
            startLine: Int,
            endLine: Int,
        ): SelectionContext {
            val lineRange = if (startLine == endLine) "L$startLine" else "L$startLine-L$endLine"
            val label = if (fileName != null) "$fileName ($lineRange)" else "Selection ($lineRange)"
            val location = if (filePath != null) "$filePath:$lineRange" else lineRange

            val blocks = mutableListOf<ContentBlock>()
            if (filePath != null && fileName != null) {
                blocks.add(ResourceLinkContent(uri = filePath, name = fileName))
            }
            blocks.add(TextContent(text = "Selected code from $location:\n```\n$selectedText\n```"))
            return SelectionContext(label = label, blocks = blocks)
        }
    }

    fun getSelectedText(): Pair<String, String?>? {
        val editor = currentEditor() ?: return null
        val selectionModel = editor.selectionModel
        val selectedText = selectionModel.selectedText ?: return null
        if (selectedText.isBlank()) return null

        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        return Pair(selectedText, file?.path)
    }

    fun getSelectionContext(): SelectionContext? {
        val editor = currentEditor() ?: return null
        val selectionModel = editor.selectionModel
        val selectedText = selectionModel.selectedText ?: return null
        if (selectedText.isBlank()) return null

        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        val document = editor.document
        val startOffset = selectionModel.selectionStart
        val endExclusive = selectionModel.selectionEnd
        val endOffset = if (endExclusive > startOffset) endExclusive - 1 else startOffset
        val startLine = document.getLineNumber(startOffset) + 1
        val endLine = document.getLineNumber(endOffset) + 1
        return buildSelectionContext(
            selectedText = selectedText,
            filePath = file?.path,
            fileName = file?.name,
            startLine = startLine,
            endLine = endLine,
        )
    }

    fun buildContextBlocks(): List<ContentBlock> {
        return getSelectionContext()?.blocks ?: emptyList()
    }

    private fun currentEditor(): Editor? {
        val editorManager = FileEditorManager.getInstance(project)
        return editorManager.selectedTextEditor
    }
}
