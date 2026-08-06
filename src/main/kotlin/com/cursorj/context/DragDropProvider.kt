package com.cursorj.context

import com.cursorj.acp.messages.ContentBlock
import com.cursorj.acp.messages.LocalImageContent
import com.cursorj.acp.messages.ResourceLinkContent
import com.cursorj.acp.messages.TextContent
import com.intellij.openapi.vfs.VirtualFile
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.TransferHandler
import javax.swing.TransferHandler.COPY

class DragDropProvider(
    private val onFilesDropped: (List<ContentBlock>, Int) -> Unit,
) {
    fun install(component: JComponent) {
        component.transferHandler = FileDropTransferHandler()
    }

    internal fun extractBlocksFromTransferable(transferable: Transferable): List<ContentBlock> =
        extractDroppedBlocks(transferable)

    internal fun extractAttachableBlocksFromTransferable(transferable: Transferable): List<ContentBlock> =
        extractAttachableBlocks(extractDroppedBlocks(transferable))

    internal fun extractAttachableBlocksFromText(text: String): List<ContentBlock> =
        extractAttachableBlocks(extractBlocksFromPlainText(text))

    private fun extractAttachableBlocks(blocks: List<ContentBlock>): List<ContentBlock> =
        blocks.filter { it is LocalImageContent || it is ResourceLinkContent }

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
                val blocks = extractAttachableBlocksFromTransferable(support.transferable)
                if (blocks.isEmpty()) return false
                val dropIndex = if (support.isDrop && support.component is javax.swing.text.JTextComponent) {
                    val loc = support.dropLocation as? javax.swing.text.JTextComponent.DropLocation
                    loc?.index ?: -1
                } else {
                    -1
                }
                onFilesDropped(blocks, dropIndex)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun extractDroppedBlocks(transferable: Transferable): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        val resourcesByPath = linkedMapOf<String, ResourceLinkContent>()
        val extractor = PathBlockExtractor(blocks, resourcesByPath)

        if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            val img = runCatching { transferable.getTransferData(DataFlavor.imageFlavor) as? java.awt.Image }.getOrNull()
            if (img != null) {
                val width = img.getWidth(null)
                val height = img.getHeight(null)
                if (width > 0 && height > 0) {
                    val bimage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                    val g2d = bimage.createGraphics()
                    g2d.drawImage(img, 0, 0, null)
                    g2d.dispose()
                    val baos = ByteArrayOutputStream()
                    if (ImageIO.write(bimage, "png", baos)) {
                        val base64 = Base64.getEncoder().encodeToString(baos.toByteArray())
                        blocks.add(LocalImageContent(name = "clipboard_image.png", mimeType = "image/png", data = base64))
                    }
                }
            }
        }

        if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            @Suppress("UNCHECKED_CAST")
            val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
            files?.forEach(extractor::addFile)
        }

        for (flavor in transferable.transferDataFlavors) {
            val data = runCatching { transferable.getTransferData(flavor) }.getOrNull() ?: continue
            when (data) {
                is VirtualFile -> extractor.addVirtualFile(data)
                is File -> extractor.addFile(data)
                is String -> extractor.addPathLikeLines(data)
                is Array<*> -> {
                    data.forEach { item ->
                        when (item) {
                            is VirtualFile -> extractor.addVirtualFile(item)
                            is File -> extractor.addFile(item)
                            is String -> extractor.addPathLikeText(item)
                        }
                    }
                }
                is List<*> -> {
                    data.forEach { item ->
                        when (item) {
                            is VirtualFile -> extractor.addVirtualFile(item)
                            is File -> extractor.addFile(item)
                            is String -> extractor.addPathLikeText(item)
                        }
                    }
                }
            }
        }

        if (resourcesByPath.isNotEmpty()) {
            blocks.addAll(resourcesByPath.values)
        }

        if (blocks.isNotEmpty()) {
            return blocks
        }

        if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            val text = transferable.getTransferData(DataFlavor.stringFlavor) as? String
            if (!text.isNullOrBlank()) {
                val fromText = extractBlocksFromPlainText(text)
                if (fromText.isNotEmpty()) return fromText
                return listOf(TextContent(text = text))
            }
        }

        return emptyList()
    }

    private fun extractBlocksFromPlainText(text: String): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        val resourcesByPath = linkedMapOf<String, ResourceLinkContent>()
        val extractor = PathBlockExtractor(blocks, resourcesByPath)
        extractor.addPathLikeLines(text)
        if (resourcesByPath.isNotEmpty()) {
            blocks.addAll(resourcesByPath.values)
        }
        return blocks
    }

    private inner class PathBlockExtractor(
        private val blocks: MutableList<ContentBlock>,
        private val resourcesByPath: MutableMap<String, ResourceLinkContent>,
    ) {
        private val processedPaths = mutableSetOf<String>()

        fun addFile(file: File) {
            val absolute = file.absoluteFile
            val key = absolute.path.lowercase()
            if (key in processedPaths) return
            processedPaths.add(key)

            if (absolute.isImageFile()) {
                val bytes = runCatching { absolute.readBytes() }.getOrNull()
                if (bytes != null) {
                    val base64 = Base64.getEncoder().encodeToString(bytes)
                    val mimeType = mimeTypeForExtension(absolute.extension.lowercase())
                    blocks.add(LocalImageContent(name = absolute.name, mimeType = mimeType, data = base64))
                }
            } else {
                if (key in resourcesByPath) return
                resourcesByPath[key] = ResourceLinkContent(
                    uri = absolute.path,
                    name = absolute.name.ifBlank { absolute.path.substringAfterLast(File.separatorChar) },
                )
            }
        }

        fun addVirtualFile(vf: VirtualFile) {
            val key = vf.path.lowercase()
            if (key in processedPaths) return
            processedPaths.add(key)

            if (vf.isImageFile()) {
                val bytes = runCatching { vf.contentsToByteArray() }.getOrNull()
                if (bytes != null) {
                    val base64 = Base64.getEncoder().encodeToString(bytes)
                    val mimeType = mimeTypeForExtension(vf.extension?.lowercase() ?: "")
                    blocks.add(LocalImageContent(name = vf.name, mimeType = mimeType, data = base64))
                }
            } else {
                if (key in resourcesByPath) return
                resourcesByPath[key] = ResourceLinkContent(
                    uri = vf.path,
                    name = vf.name.ifBlank { vf.path.substringAfterLast('/') },
                )
            }
        }

        fun addPathLikeLines(text: String) {
            text.lineSequence()
                .map { it.trim().trim('\r', '\n', '\u0000') }
                .filter { it.isNotBlank() && !it.startsWith("#") && !isClipboardMetadataLine(it) }
                .forEach(::addPathLikeText)
        }

        fun addPathLikeText(text: String) {
            val resolvedPath = resolvePathCandidate(text) ?: return
            val file = File(resolvedPath)
            if (file.exists()) addFile(file)
        }

        private fun isClipboardMetadataLine(line: String): Boolean {
            return line.equals("copy", ignoreCase = true) ||
                line.equals("cut", ignoreCase = true)
        }

        private fun resolvePathCandidate(raw: String): String? {
            var candidate = raw.trim().trim('\r', '\n', '\u0000').removeSurrounding("\"")
            if (candidate.isBlank()) return null

            if (candidate.startsWith("file:", ignoreCase = true)) {
                candidate = runCatching { File(URI(candidate)).path }.getOrNull() ?: return null
            }

            return candidate
        }
    }

    private fun isSupportedFlavor(flavor: DataFlavor): Boolean {
        return flavor == DataFlavor.imageFlavor ||
            flavor == DataFlavor.javaFileListFlavor ||
            flavor == DataFlavor.stringFlavor ||
            flavor.mimeType.startsWith("text/uri-list") ||
            flavor.mimeType.contains("gnome-copied-files") ||
            flavor.representationClass == VirtualFile::class.java ||
            (flavor.representationClass.isArray &&
                flavor.representationClass.componentType == VirtualFile::class.java)
    }

    private fun File.isImageFile(): Boolean = hasImageExtension(extension.lowercase())

    private fun VirtualFile.isImageFile(): Boolean = hasImageExtension(extension?.lowercase() ?: "")

    private fun hasImageExtension(ext: String): Boolean {
        return ext == "png" || ext == "jpg" || ext == "jpeg" || ext == "gif" || ext == "bmp" || ext == "webp"
    }

    private fun mimeTypeForExtension(ext: String): String {
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            else -> "image/png"
        }
    }
}
