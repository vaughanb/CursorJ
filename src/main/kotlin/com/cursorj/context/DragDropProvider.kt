package com.cursorj.context

import com.cursorj.acp.messages.ContentBlock
import com.cursorj.acp.messages.ResourceLinkContent
import com.cursorj.acp.messages.TextContent
import com.intellij.openapi.vfs.VirtualFile
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.net.URI
import javax.swing.JComponent
import javax.swing.TransferHandler
import javax.swing.TransferHandler.COPY

class DragDropProvider(
    private val onFilesDropped: (List<ContentBlock>) -> Unit,
) {
    fun install(component: JComponent) {
        component.transferHandler = FileDropTransferHandler()
    }

    private inner class FileDropTransferHandler : TransferHandler() {
        override fun canImport(support: TransferSupport): Boolean {
            val canImport = support.dataFlavors.any(::isSupportedFlavor)
            if (canImport && support.isDrop && (support.sourceDropActions and COPY) != 0) {
                support.dropAction = COPY
            }
            return canImport
        }

        override fun importData(support: TransferSupport): Boolean {
            if (!canImport(support)) return false

            return try {
                val blocks = extractDroppedBlocks(support.transferable)
                if (blocks.isEmpty()) return false
                onFilesDropped(blocks)
                true
            } catch (e: Exception) {
                false
            }
        }

        private fun extractDroppedBlocks(transferable: Transferable): List<ContentBlock> {
            val resourcesByPath = linkedMapOf<String, ResourceLinkContent>()

            fun addFile(file: File) {
                val absolute = file.absoluteFile
                val key = absolute.path.lowercase()
                if (key in resourcesByPath) return
                resourcesByPath[key] = ResourceLinkContent(
                    uri = absolute.path,
                    name = absolute.name.ifBlank { absolute.path.substringAfterLast(File.separatorChar) },
                )
            }

            fun addVirtualFile(vf: VirtualFile) {
                val key = vf.path.lowercase()
                if (key in resourcesByPath) return
                resourcesByPath[key] = ResourceLinkContent(
                    uri = vf.path,
                    name = vf.name.ifBlank { vf.path.substringAfterLast('/') },
                )
            }

            fun addPathLikeText(text: String) {
                val candidate = text.trim().removeSurrounding("\"")
                if (candidate.isBlank()) return

                val resolvedPath = when {
                    candidate.startsWith("file:", ignoreCase = true) -> {
                        runCatching { File(URI(candidate)).path }.getOrNull()
                    }
                    else -> candidate
                } ?: return

                val file = File(resolvedPath)
                if (file.exists()) addFile(file)
            }

            if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                @Suppress("UNCHECKED_CAST")
                val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
                files?.forEach(::addFile)
            }

            for (flavor in transferable.transferDataFlavors) {
                val data = runCatching { transferable.getTransferData(flavor) }.getOrNull() ?: continue
                when (data) {
                    is VirtualFile -> addVirtualFile(data)
                    is File -> addFile(data)
                    is String -> {
                        data.lineSequence()
                            .map { it.trim() }
                            .filter { it.isNotBlank() && !it.startsWith("#") }
                            .forEach(::addPathLikeText)
                    }
                    is Array<*> -> {
                        data.forEach { item ->
                            when (item) {
                                is VirtualFile -> addVirtualFile(item)
                                is File -> addFile(item)
                                is String -> addPathLikeText(item)
                            }
                        }
                    }
                    is List<*> -> {
                        data.forEach { item ->
                            when (item) {
                                is VirtualFile -> addVirtualFile(item)
                                is File -> addFile(item)
                                is String -> addPathLikeText(item)
                            }
                        }
                    }
                }
            }

            if (resourcesByPath.isNotEmpty()) {
                return resourcesByPath.values.toList()
            }

            if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                val text = transferable.getTransferData(DataFlavor.stringFlavor) as? String
                if (!text.isNullOrBlank()) {
                    return listOf(TextContent(text = text))
                }
            }

            return emptyList()
        }

        private fun isSupportedFlavor(flavor: DataFlavor): Boolean {
            return flavor == DataFlavor.javaFileListFlavor ||
                flavor == DataFlavor.stringFlavor ||
                flavor.mimeType.startsWith("text/uri-list") ||
                flavor.representationClass == VirtualFile::class.java ||
                (flavor.representationClass.isArray &&
                    flavor.representationClass.componentType == VirtualFile::class.java)
        }
    }
}
