package com.cursorj.context

import com.cursorj.acp.messages.ResourceLinkContent
import com.cursorj.acp.messages.TextContent
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
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
            val provider = DragDropProvider { }

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
        val provider = DragDropProvider { }

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
        val provider = DragDropProvider { }

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
            val provider = DragDropProvider { }

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
    fun `extractBlocksFromTransferable deduplicates files by path`() {
        val tempFile = Files.createTempFile("cursorj-drag-dup", ".kt").toFile()
        try {
            val transferable = transferableWithFileList(listOf(tempFile, tempFile))
            val provider = DragDropProvider { }

            val blocks = provider.extractBlocksFromTransferable(transferable)

            assertEquals(1, blocks.size)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `install sets transfer handler on component`() {
        val provider = DragDropProvider { }
        val component = javax.swing.JPanel()

        provider.install(component)

        assertTrue(component.transferHandler != null)
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
