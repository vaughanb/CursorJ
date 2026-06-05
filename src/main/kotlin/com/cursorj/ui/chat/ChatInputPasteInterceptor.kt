package com.cursorj.ui.chat

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.editor.actionSystem.EditorWriteActionHandler
import java.awt.Component
import java.awt.Toolkit
import java.awt.datatransfer.Transferable
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JComponent

/**
 * Intercepts IntelliJ editor paste for chat input text areas so image/file paths become
 * attachment chips instead of raw text. Must run at the editor-action layer; swallowing paste
 * in [javax.swing.text.DocumentFilter] leaves IntelliJ's paste handler with a stale caret offset.
 */
object ChatInputPasteInterceptor {
    private const val CHAT_INPUT_KEY = "cursorj.chatInput"

    private val pasteCallbacks = ConcurrentHashMap<JComponent, (Transferable, Int) -> Boolean>()
    private var previousPasteHandler: EditorActionHandler? = null
    private var installCount = 0

    fun register(textArea: JComponent, onPaste: (Transferable, Int) -> Boolean) {
        textArea.putClientProperty(CHAT_INPUT_KEY, true)
        pasteCallbacks[textArea] = onPaste
        if (installCount++ == 0) {
            val manager = EditorActionManager.getInstance()
            previousPasteHandler = manager.getActionHandler(IdeActions.ACTION_EDITOR_PASTE)
            manager.setActionHandler(IdeActions.ACTION_EDITOR_PASTE, DelegatingPasteHandler())
        }
    }

    fun unregister(textArea: JComponent) {
        pasteCallbacks.remove(textArea)
        textArea.putClientProperty(CHAT_INPUT_KEY, null)
        if (--installCount == 0) {
            val previous = previousPasteHandler
            if (previous != null) {
                EditorActionManager.getInstance().setActionHandler(IdeActions.ACTION_EDITOR_PASTE, previous)
            }
            previousPasteHandler = null
        }
    }

    private fun findCallback(editor: Editor): ((Transferable, Int) -> Boolean)? {
        var component: Component? = editor.contentComponent
        while (component != null) {
            if (component is JComponent && component.getClientProperty(CHAT_INPUT_KEY) == true) {
                return pasteCallbacks[component]
            }
            component = component.parent
        }
        return null
    }

    private class DelegatingPasteHandler : EditorWriteActionHandler() {
        override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
            val callback = findCallback(editor)
            if (callback != null) {
                val transferable = runCatching {
                    Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
                }.getOrNull()
                if (transferable != null && callback(transferable, editor.caretModel.offset)) {
                    return
                }
            }
            previousPasteHandler?.execute(editor, caret, dataContext)
        }

        override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext): Boolean {
            return previousPasteHandler?.isEnabled(editor, dataContext) ?: super.isEnabledForCaret(
                editor,
                caret,
                dataContext,
            )
        }
    }
}
