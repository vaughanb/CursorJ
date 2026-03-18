package com.cursorj.indexing.freshness

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.util.messages.MessageBusConnection

class IndexFreshnessManager(
    private val project: Project,
    private val onFileChanged: (String) -> Unit,
    private val onFileRemoved: (String) -> Unit,
    private val onBulkInvalidation: (String) -> Unit,
    private val listenToPsiChanges: Boolean = false,
    private val connectMessageBus: (Disposable) -> MessageBusConnection = { disposable ->
        project.messageBus.connect(disposable)
    },
    private val registerVfsListener: (MessageBusConnection, (String, VfsChangeKind) -> Unit) -> Unit = { bus, sink ->
        bus.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                for (event in events) {
                    val path = event.path
                    when (event) {
                        is VFileDeleteEvent -> sink(path, VfsChangeKind.REMOVED)
                        is VFileMoveEvent -> sink(path, VfsChangeKind.MOVED)
                        is VFilePropertyChangeEvent -> sink(path, VfsChangeKind.RENAMED)
                        else -> sink(path, VfsChangeKind.CHANGED)
                    }
                }
            }
        })
    },
    private val registerPsiListener: (Disposable, () -> Unit, () -> Unit) -> Unit = { parent, onChildrenChanged, onPropertyChanged ->
        PsiManager.getInstance(project).addPsiTreeChangeListener(
            object : PsiTreeChangeAdapter() {
                override fun childrenChanged(event: PsiTreeChangeEvent) {
                    onChildrenChanged()
                }

                override fun propertyChanged(event: PsiTreeChangeEvent) {
                    onPropertyChanged()
                }
            },
            parent,
        )
    },
) : Disposable {
    enum class VfsChangeKind {
        CHANGED,
        REMOVED,
        MOVED,
        RENAMED,
    }

    private var connection: MessageBusConnection? = null

    fun attach() {
        if (connection != null) return
        connection = connectMessageBus(this)
        val bus = connection ?: return
        registerVfsListener(bus) { path, changeKind ->
            val normalizedPath = path.replace('\\', '/')
            when (changeKind) {
                VfsChangeKind.CHANGED -> onFileChanged(normalizedPath)
                VfsChangeKind.REMOVED -> onFileRemoved(normalizedPath)
                VfsChangeKind.MOVED -> onBulkInvalidation("file-move")
                VfsChangeKind.RENAMED -> onBulkInvalidation("file-rename")
            }
        }
        if (listenToPsiChanges) {
            registerPsiListener(
                this,
                { onBulkInvalidation("psi-children") },
                { onBulkInvalidation("psi-property") },
            )
        }
    }

    fun notifyFileWritten(path: String) {
        onFileChanged(path.replace('\\', '/'))
    }

    fun notifyRollback() {
        onBulkInvalidation("rollback")
    }

    override fun dispose() {
        connection?.disconnect()
        connection = null
    }
}
