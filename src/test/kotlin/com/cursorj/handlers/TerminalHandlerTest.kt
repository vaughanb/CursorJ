package com.cursorj.handlers

import com.cursorj.acp.messages.TerminalCreateParams
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalHandlerTest {

    @Test
    fun `buildProcessCommand uses command and args when args provided`() {
        val handler = TerminalHandler(projectWithBasePath(null))
        val request = TerminalCreateParams(command = "npm", args = listOf("run", "test"))

        val cmd = invokePrivate<List<String>>(handler, "buildProcessCommand", request)

        assertEquals(listOf("npm", "run", "test"), cmd)
    }

    @Test
    fun `buildProcessCommand uses shell when args empty`() {
        val handler = TerminalHandler(projectWithBasePath(null))
        val request = TerminalCreateParams(command = "echo hello", args = emptyList())

        val cmd = invokePrivate<List<String>>(handler, "buildProcessCommand", request)

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        if (isWindows) {
            assertEquals("cmd.exe", cmd[0])
            assertEquals("/d", cmd[1])
            assertEquals("/s", cmd[2])
            assertEquals("/c", cmd[3])
            assertTrue(cmd[4].contains("echo hello"))
        } else {
            assertTrue(cmd[0].startsWith("/"))
            assertTrue(cmd[1] == "-lc" || cmd[1] == "-c")
            assertTrue(cmd[2].contains("echo hello"))
        }
    }

    @Test
    fun `buildProcessCommand filters blank args`() {
        val handler = TerminalHandler(projectWithBasePath(null))
        val request = TerminalCreateParams(command = "cmd", args = listOf("a", "", "  ", "b"))

        val cmd = invokePrivate<List<String>>(handler, "buildProcessCommand", request)

        assertEquals(listOf("cmd", "a", "b"), cmd)
    }

    @Test
    fun `buildCommandPreview returns command when no args`() {
        val handler = TerminalHandler(projectWithBasePath(null))
        val request = TerminalCreateParams(command = "echo hello", args = emptyList())

        val preview = invokePrivate<String>(handler, "buildCommandPreview", request)

        assertEquals("echo hello", preview)
    }

    @Test
    fun `buildCommandPreview joins command and args and truncates`() {
        val handler = TerminalHandler(projectWithBasePath(null))
        val longArgs = List(50) { "x" }.joinToString(" ")
        val request = TerminalCreateParams(command = "run", args = longArgs.split(" "))

        val preview = invokePrivate<String>(handler, "buildCommandPreview", request)

        assertTrue(preview.length <= 300)
        assertTrue(preview.startsWith("run"))
    }

    @Test
    fun `extractTimeoutMs returns null when absent`() {
        val handler = TerminalHandler(projectWithBasePath(null))
        val params = buildJsonObject { put("terminalId", "term-1") }

        val result = invokePrivate<Long?>(handler, "extractTimeoutMs", params)

        assertEquals(null, result)
    }

    @Test
    fun `extractTimeoutMs returns timeoutMs`() {
        val handler = TerminalHandler(projectWithBasePath(null))
        val params = buildJsonObject {
            put("terminalId", "term-1")
            put("timeoutMs", 5000)
        }

        val result = invokePrivate<Long?>(handler, "extractTimeoutMs", params)

        assertEquals(5000L, result)
    }

    @Test
    fun `extractTimeoutMs returns timeout_ms snake_case`() {
        val handler = TerminalHandler(projectWithBasePath(null))
        val params = buildJsonObject {
            put("terminalId", "term-1")
            put("timeout_ms", 3000)
        }

        val result = invokePrivate<Long?>(handler, "extractTimeoutMs", params)

        assertEquals(3000L, result)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> invokePrivate(target: Any, methodName: String, vararg args: Any?): T {
        val method = target.javaClass.declaredMethods.first {
            it.name == methodName && it.parameterCount == args.size
        }.apply { isAccessible = true }
        return method.invoke(target, *args) as T
    }

    private fun projectWithBasePath(basePath: String?): Project {
        return Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getBasePath" -> basePath
                "isDisposed" -> false
                "isDefault" -> false
                "getName" -> "test-project"
                "getLocationHash" -> "test-hash"
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    else -> null
                }
            }
        } as Project
    }
}
