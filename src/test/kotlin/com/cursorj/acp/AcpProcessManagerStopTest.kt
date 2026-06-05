package com.cursorj.acp

import com.cursorj.settings.CursorJSettings
import com.intellij.openapi.util.Disposer
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcpProcessManagerStopTest {
    @Test
    fun `stop terminates process and unblocks reader without deadlock`() {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        assumeTrue(!isWindows) { "This test requires Unix-like environment for script execution" }

        val disposable = Disposer.newDisposable("AcpProcessManagerStopTestDisposable")
        val tempScript = Files.createTempFile("dummy-agent", ".sh").toFile()
        tempScript.writeText("#!/bin/sh\ncat\n")

        // Make it executable
        try {
            val perms = Files.getPosixFilePermissions(tempScript.toPath()).toMutableSet()
            perms.add(PosixFilePermission.OWNER_EXECUTE)
            Files.setPosixFilePermissions(tempScript.toPath(), perms)
        } catch (e: Exception) {
            tempScript.setExecutable(true)
        }

        val settings = CursorJSettings.instance
        val previousPath = settings.agentPath
        settings.agentPath = tempScript.absolutePath

        try {
            val manager = AcpProcessManager(disposable)
            assertTrue(manager.start(), "Dummy script should start successfully")
            assertTrue(manager.isRunning, "Process should be running")

            val reader = manager.reader!!
            var readBlocked = true
            var threadException: Throwable? = null

            val readerThread = Thread {
                try {
                    reader.readLine()
                    readBlocked = false
                } catch (e: Throwable) {
                    threadException = e
                }
            }
            readerThread.isDaemon = true
            readerThread.start()

            // Wait a bit to ensure the reader thread is blocked on readLine
            Thread.sleep(500)

            // Now stop the manager. Under the old implementation, this would hang indefinitely
            // because stop() would call reader.close() which tries to acquire the BufferedReader's lock
            // while the reader thread is holding it inside readLine().
            val stopStart = System.currentTimeMillis()
            manager.stop()
            val stopDuration = System.currentTimeMillis() - stopStart

            // Assert that stop completed in a reasonable time (e.g. less than 2000ms, usually instantaneous)
            assertTrue(stopDuration < 2000, "AcpProcessManager.stop() took too long ($stopDuration ms), deadlock likely occurred")
            assertFalse(manager.isRunning, "Process should no longer be running")

            // Wait for the reader thread to finish/exit
            readerThread.join(1000)
            assertFalse(readerThread.isAlive, "Reader thread should have exited")
        } finally {
            settings.agentPath = previousPath
            tempScript.delete()
            Disposer.dispose(disposable)
        }
    }
}
