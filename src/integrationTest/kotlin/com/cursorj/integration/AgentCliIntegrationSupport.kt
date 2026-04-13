package com.cursorj.integration

import com.cursorj.acp.AcpClient
import com.cursorj.acp.AgentPathResolver
import com.cursorj.settings.CursorJSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.BufferedWriter
import java.io.File
import java.io.StringWriter
import java.lang.reflect.Method
import java.lang.reflect.Proxy

object AgentCliIntegrationSupport {
    private val json = Json { ignoreUnknownKeys = true }

    fun assumeManualOnlyEnabled() {
        assumeTrue(System.getenv("CURSORJ_INTEGRATION") == "1") {
            "Manual integration tests are disabled. Set CURSORJ_INTEGRATION=1 to run."
        }
        assumeTrue(!isCi()) {
            "Manual integration tests must never run on CI."
        }
    }

    fun assumeIdeApplicationAvailable() {
        val appAvailable = runCatching { ApplicationManager.getApplication() != null }.getOrDefault(false)
        assumeTrue(appAvailable) {
            "IntelliJ application is not available in this test runtime."
        }
    }

    fun resolveAgentPathOrSkip(): String {
        val configured = System.getenv("CURSOR_AGENT_PATH")?.trim().orEmpty()
        if (configured.isNotBlank()) {
            val file = File(configured)
            val runnable = file.isFile && (isWindows() || file.canExecute())
            assumeTrue(runnable) {
                "CURSOR_AGENT_PATH is set but not runnable: $configured"
            }
            return file.absolutePath
        }

        val resolved = AgentPathResolver.resolve(null)
        assumeTrue(!resolved.isNullOrBlank()) {
            "Could not resolve agent binary from PATH/common locations."
        }
        return resolved!!
    }

    fun <T> withConfiguredAgentPath(agentPath: String, block: () -> T): T {
        val settings = CursorJSettings.instance
        val previous = settings.agentPath
        settings.agentPath = agentPath
        return try {
            block()
        } finally {
            settings.agentPath = previous
        }
    }

    suspend fun <T> withConfiguredAgentPathSuspending(agentPath: String, block: suspend () -> T): T {
        val settings = CursorJSettings.instance
        val previous = settings.agentPath
        settings.agentPath = agentPath
        return try {
            block()
        } finally {
            settings.agentPath = previous
        }
    }

    fun newDisposable(name: String): Disposable {
        return Disposer.newDisposable(name)
    }

    fun projectWithBasePath(basePath: String?): Project {
        return Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getBasePath" -> basePath
                "isDisposed" -> false
                "isDefault" -> false
                "getName" -> "integration-project"
                "getLocationHash" -> "integration-hash"
                else -> defaultValue(method.returnType)
            }
        } as Project
    }

    fun setClientWriter(client: AcpClient, writer: BufferedWriter) {
        val field = AcpClient::class.java.getDeclaredField("writer").apply { isAccessible = true }
        field.set(client, writer)
    }

    fun invokeHandleServerRequest(client: AcpClient, payload: JsonObject) {
        val method: Method = AcpClient::class.java.declaredMethods.first {
            it.name == "handleServerRequest" && it.parameterCount == 1
        }.apply { isAccessible = true }
        method.invoke(client, payload)
    }

    fun awaitJsonRpcResponse(writerCapture: StringWriter, timeoutMs: Long = 5_000L): JsonObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val text = writerCapture.toString()
            val line = text.lineSequence().firstOrNull { it.isNotBlank() }
            if (line != null) {
                return json.parseToJsonElement(line).jsonObject
            }
            Thread.sleep(25)
        }
        error("Timed out waiting for JSON-RPC response")
    }

    private fun isCi(): Boolean {
        val raw = System.getenv("CI")?.trim() ?: return false
        return raw.equals("true", ignoreCase = true) || raw == "1"
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name").lowercase().contains("win")
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
}
