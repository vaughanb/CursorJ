package com.cursorj.indexing.freshness

import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBusConnection
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IndexFreshnessManagerTest {
    @Test
    fun `notify file written normalizes separators and forwards callback`() {
        val changed = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val invalidations = mutableListOf<String>()
        val manager = IndexFreshnessManager(
            project = projectStub(),
            onFileChanged = { changed += it },
            onFileRemoved = { removed += it },
            onBulkInvalidation = { invalidations += it },
        )

        manager.notifyFileWritten("C:\\repo\\src\\Main.kt")

        assertEquals(listOf("C:/repo/src/Main.kt"), changed)
        assertEquals(emptyList(), removed)
        assertEquals(emptyList(), invalidations)
    }

    @Test
    fun `notify rollback emits rollback invalidation reason`() {
        val invalidations = mutableListOf<String>()
        val manager = IndexFreshnessManager(
            project = projectStub(),
            onFileChanged = {},
            onFileRemoved = {},
            onBulkInvalidation = { invalidations += it },
        )

        manager.notifyRollback()

        assertEquals(listOf("rollback"), invalidations)
    }

    @Test
    fun `dispose is safe when not attached`() {
        val manager = IndexFreshnessManager(
            project = projectStub(),
            onFileChanged = {},
            onFileRemoved = {},
            onBulkInvalidation = {},
        )

        manager.dispose()
    }

    @Test
    fun `attach maps vfs and psi events to callbacks`() {
        val changed = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val invalidations = mutableListOf<String>()
        var disconnected = false
        var vfsRegistrationCount = 0
        var psiRegistrationCount = 0

        val manager = IndexFreshnessManager(
            project = projectStub(),
            onFileChanged = { changed += it },
            onFileRemoved = { removed += it },
            onBulkInvalidation = { invalidations += it },
            connectMessageBus = {
                fakeConnection(
                    onDisconnect = { disconnected = true },
                )
            },
            registerVfsListener = { _, sink ->
                vfsRegistrationCount++
                sink("C:\\repo\\src\\Main.kt", IndexFreshnessManager.VfsChangeKind.CHANGED)
                sink("C:\\repo\\src\\DeleteMe.kt", IndexFreshnessManager.VfsChangeKind.REMOVED)
                sink("C:\\repo\\src\\Moved.kt", IndexFreshnessManager.VfsChangeKind.MOVED)
                sink("C:\\repo\\src\\Rename.kt", IndexFreshnessManager.VfsChangeKind.RENAMED)
            },
            registerPsiListener = { _, onChildrenChanged, onPropertyChanged ->
                psiRegistrationCount++
                onChildrenChanged()
                onPropertyChanged()
            },
        )

        manager.attach()
        manager.dispose()

        assertEquals(listOf("C:/repo/src/Main.kt"), changed)
        assertEquals(listOf("C:/repo/src/DeleteMe.kt"), removed)
        assertTrue("file-move" in invalidations)
        assertTrue("file-rename" in invalidations)
        assertTrue("psi-children" in invalidations)
        assertTrue("psi-property" in invalidations)
        assertEquals(1, vfsRegistrationCount)
        assertEquals(1, psiRegistrationCount)
        assertTrue(disconnected)
    }

    @Test
    fun `attach is idempotent`() {
        var connects = 0
        var vfsRegistrations = 0
        var psiRegistrations = 0
        val manager = IndexFreshnessManager(
            project = projectStub(),
            onFileChanged = {},
            onFileRemoved = {},
            onBulkInvalidation = {},
            connectMessageBus = {
                connects++
                fakeConnection()
            },
            registerVfsListener = { _, _ -> vfsRegistrations++ },
            registerPsiListener = { _, _, _ -> psiRegistrations++ },
        )

        manager.attach()
        manager.attach()
        manager.dispose()

        assertEquals(1, connects)
        assertEquals(1, vfsRegistrations)
        assertEquals(1, psiRegistrations)
    }

    private fun projectStub(): Project {
        return Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "isDisposed" -> false
                "isDefault" -> false
                "getName" -> "test-project"
                "getLocationHash" -> "test-hash"
                else -> defaultValue(method.returnType)
            }
        } as Project
    }

    private fun defaultValue(returnType: Class<*>): Any? {
        return when (returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Double.TYPE -> 0.0
            java.lang.Float.TYPE -> 0f
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }
    }

    private fun fakeConnection(onDisconnect: () -> Unit = {}): MessageBusConnection {
        return Proxy.newProxyInstance(
            MessageBusConnection::class.java.classLoader,
            arrayOf(MessageBusConnection::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "disconnect" -> {
                    onDisconnect()
                    null
                }
                else -> defaultValue(method.returnType)
            }
        } as MessageBusConnection
    }
}
