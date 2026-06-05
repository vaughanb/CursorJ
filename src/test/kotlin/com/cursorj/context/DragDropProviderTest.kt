package com.cursorj.context

import com.cursorj.acp.messages.LocalImageContent
import com.cursorj.acp.messages.ResourceLinkContent
import com.cursorj.acp.messages.TextContent
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DragDropProviderTest {

    @Test
    fun `extractBlocksFromTransferable returns ResourceLinkContent for file list`() {
        val tempFile = Files.createTempFile("cursorj-dragdrop", ".kt").toFile()
        try {
            tempFile.writeText("content")
            val transferable = transferableWithFileList(listOf(tempFile))
            val provider = DragDropProvider { _, _ -> }

            val blocks = provider.extractBlocksFromTransferable(transferable)

            assertEquals(1, blocks.size)
            val block = blocks.single() as ResourceLinkContent
            assertEquals(tempFile.absolutePath, block.uri)
            assertEquals(tempFile.name, block.name)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `extractBlocksFromTransferable returns TextContent for plain string when no files`() {
        val transferable = object : Transferable {
            override fun getTransferData(flavor: DataFlavor): Any =
                if (flavor == DataFlavor.stringFlavor) "some text" else throw Exception("unsupported")

            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.stringFlavor)

            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.stringFlavor
        }
        val provider = DragDropProvider { _, _ -> }

        val blocks = provider.extractBlocksFromTransferable(transferable)

        assertEquals(1, blocks.size)
        val block = blocks.single() as TextContent
        assertEquals("some text", block.text)
    }

    @Test
    fun `extractBlocksFromTransferable returns empty for unsupported transferable`() {
        val transferable = object : Transferable {
            override fun getTransferData(flavor: DataFlavor): Any = throw Exception("unsupported")
            override fun getTransferDataFlavors(): Array<DataFlavor> = emptyArray()
            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = false
        }
        val provider = DragDropProvider { _, _ -> }

        val blocks = provider.extractBlocksFromTransferable(transferable)

        assertTrue(blocks.isEmpty())
    }

    @Test
    fun `extractBlocksFromTransferable resolves file path from string when file exists`() {
        val tempFile = Files.createTempFile("cursorj-drag-path", ".txt").toFile()
        try {
            tempFile.writeText("x")
            val transferable = object : Transferable {
                override fun getTransferData(flavor: DataFlavor): Any =
                    if (flavor == DataFlavor.stringFlavor) tempFile.absolutePath else throw Exception("unsupported")

                override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.stringFlavor)

                override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.stringFlavor
            }
            val provider = DragDropProvider { _, _ -> }

            val blocks = provider.extractBlocksFromTransferable(transferable)

            assertEquals(1, blocks.size)
            val block = blocks.single() as ResourceLinkContent
            assertEquals(tempFile.absolutePath, block.uri)
            assertEquals(tempFile.name, block.name)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `extractBlocksFromTransferable resolves image path from string when file exists`() {
        val tempFile = Files.createTempFile("cursorj-drag-img-path", ".png").toFile()
        try {
            tempFile.writeBytes(byteArrayOf(10, 20, 30))
            val transferable = object : Transferable {
                override fun getTransferData(flavor: DataFlavor): Any =
                    if (flavor == DataFlavor.stringFlavor) tempFile.absolutePath else throw Exception("unsupported")

                override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.stringFlavor)

                override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.stringFlavor
            }
            val provider = DragDropProvider { _, _ -> }

            val blocks = provider.extractBlocksFromTransferable(transferable)

            assertEquals(1, blocks.size)
            val block = blocks.single() as LocalImageContent
            assertEquals(tempFile.name, block.name)
            assertEquals("image/png", block.mimeType)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `extractAttachableBlocksFromText resolves gnome copied files format`() {
        val tempFile = Files.createTempFile("cursorj-gnome-img", ".png").toFile()
        try {
            tempFile.writeBytes(byteArrayOf(10, 20, 30))
            val transferable = object : Transferable {
                override fun getTransferData(flavor: DataFlavor): Any =
                    if (flavor == DataFlavor.stringFlavor) {
                        "copy\nfile://${tempFile.absolutePath}"
                    } else {
                        throw Exception("unsupported")
                    }

                override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.stringFlavor)

                override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.stringFlavor
            }
            val provider = DragDropProvider { _, _ -> }

            val blocks = provider.extractAttachableBlocksFromTransferable(transferable)

            assertEquals(1, blocks.size)
            val block = blocks.single() as LocalImageContent
            assertEquals(tempFile.name, block.name)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `extractAttachableBlocksFromText resolves image path with spaces`() {
        val tempDir = Files.createTempDirectory("cursorj-img-space").toFile()
        val tempFile = File(tempDir, "Screenshot From 2026.png")
        try {
            tempFile.writeBytes(byteArrayOf(10, 20, 30))
            val provider = DragDropProvider { _, _ -> }

            val blocks = provider.extractAttachableBlocksFromText(tempFile.absolutePath)

            assertEquals(1, blocks.size)
            val block = blocks.single() as LocalImageContent
            assertEquals(tempFile.name, block.name)
        } finally {
            tempFile.delete()
            tempDir.delete()
        }
    }

    @Test
    fun `extractAttachableBlocksFromText ignores plain text`() {
        val provider = DragDropProvider { _, _ -> }
        val blocks = provider.extractAttachableBlocksFromText("hello world")
        assertTrue(blocks.isEmpty())
    }

    @Test
    fun `extractBlocksFromTransferable deduplicates files by path`() {
        val tempFile = Files.createTempFile("cursorj-drag-dup", ".kt").toFile()
        try {
            val transferable = transferableWithFileList(listOf(tempFile, tempFile))
            val provider = DragDropProvider { _, _ -> }

            val blocks = provider.extractBlocksFromTransferable(transferable)

            assertEquals(1, blocks.size)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `install sets transfer handler on component`() {
        val provider = DragDropProvider { _, _ -> }
        val component = javax.swing.JPanel()

        provider.install(component)

        assertTrue(component.transferHandler != null)
    }

    @Test
    fun `extractBlocksFromTransferable extracts clipboard image data flavor`() {
        val bufferedImage = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)
        val transferable = object : Transferable {
            override fun getTransferData(flavor: DataFlavor): Any =
                if (flavor == DataFlavor.imageFlavor) bufferedImage else throw Exception("unsupported")

            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)

            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor
        }
        val provider = DragDropProvider { _, _ -> }

        val blocks = provider.extractBlocksFromTransferable(transferable)

        assertEquals(1, blocks.size)
        val block = blocks.single() as LocalImageContent
        assertEquals("clipboard_image.png", block.name)
        assertEquals("image/png", block.mimeType)
        assertTrue(block.data.isNotEmpty())
    }

    @Test
    fun `extractBlocksFromTransferable extracts image file from list`() {
        val tempFile = Files.createTempFile("cursorj-drag-img", ".png").toFile()
        try {
            tempFile.writeBytes(byteArrayOf(10, 20, 30))
            val transferable = transferableWithFileList(listOf(tempFile))
            val provider = DragDropProvider { _, _ -> }

            val blocks = provider.extractBlocksFromTransferable(transferable)

            assertEquals(1, blocks.size)
            val block = blocks.single() as LocalImageContent
            assertEquals(tempFile.name, block.name)
            assertEquals("image/png", block.mimeType)
            assertEquals(java.util.Base64.getEncoder().encodeToString(byteArrayOf(10, 20, 30)), block.data)
        } finally {
            tempFile.delete()
        }
    }

    private fun transferableWithFileList(files: List<File>): Transferable {
        return object : Transferable {
            override fun getTransferData(flavor: DataFlavor): Any =
                if (flavor == DataFlavor.javaFileListFlavor) ArrayList(files) else throw Exception("unsupported")

            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)

            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.javaFileListFlavor
        }
    }
}
