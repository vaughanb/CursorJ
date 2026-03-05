package com.cursorj.context

import com.cursorj.acp.messages.ContentBlock
import com.cursorj.acp.messages.ResourceLinkContent
import com.cursorj.acp.messages.TextContent
import java.awt.datatransfer.DataFlavor
import java.io.File
import javax.swing.JComponent
import javax.swing.TransferHandler

class DragDropProvider(
    private val onFilesDropped: (List<ContentBlock>) -> Unit,
) {
    fun install(component: JComponent) {
        component.transferHandler = FileDropTransferHandler()
    }

    private inner class FileDropTransferHandler : TransferHandler() {
        override fun canImport(support: TransferSupport): Boolean {
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
                support.isDataFlavorSupported(DataFlavor.stringFlavor)
        }

        override fun importData(support: TransferSupport): Boolean {
            if (!canImport(support)) return false

            return try {
                when {
                    support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) -> {
                        @Suppress("UNCHECKED_CAST")
                        val files = support.transferable
                            .getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                        val blocks = files.map { file ->
                            ResourceLinkContent(
                                uri = file.absolutePath,
                                name = file.name,
                            )
                        }
                        onFilesDropped(blocks)
                        true
                    }
                    support.isDataFlavorSupported(DataFlavor.stringFlavor) -> {
                        val text = support.transferable
                            .getTransferData(DataFlavor.stringFlavor) as String
                        onFilesDropped(listOf(TextContent(text = text)))
                        true
                    }
                    else -> false
                }
            } catch (e: Exception) {
                false
            }
        }
    }
}
