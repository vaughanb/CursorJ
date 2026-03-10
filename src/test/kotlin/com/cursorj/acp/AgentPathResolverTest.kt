package com.cursorj.acp

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentPathResolverTest {
    @Test
    fun `resolveConfiguredPath returns existing configured binary`() {
        val dir = Files.createTempDirectory("cursorj-agent-config").toFile()
        try {
            val osName = if (isWindows()) "Windows 11" else "Linux"
            val fileName = if (osName.contains("Windows")) "agent.cmd" else "agent"
            val agent = dir.resolve(fileName)
            agent.writeText("@echo off")
            if (!osName.contains("Windows")) {
                agent.setExecutable(true)
            }

            val resolved = AgentPathResolver.resolveConfiguredPath(agent.absolutePath, osName = osName)
            assertNotNull(resolved)
            assertEquals(agent.absolutePath, resolved)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `resolveFromEnvironment finds agent on PATH`() {
        val dir = Files.createTempDirectory("cursorj-agent-path").toFile()
        try {
            val agent = dir.resolve("agent.cmd")
            agent.writeText("@echo off")
            val resolved = AgentPathResolver.resolveFromEnvironment(
                osName = "Windows 11",
                pathValue = dir.absolutePath,
                userHome = dir.absolutePath,
            )
            assertNotNull(resolved)
            assertTrue(resolved.endsWith("agent.cmd"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `resolveConfiguredPath returns null for blank or missing path`() {
        assertNull(AgentPathResolver.resolveConfiguredPath("   ", osName = "Windows 11"))
        assertNull(AgentPathResolver.resolveConfiguredPath("Z:/missing/agent.cmd", osName = "Windows 11"))
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name").lowercase().contains("win")
    }
}
