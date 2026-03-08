package com.cursorj.acp

import com.intellij.openapi.util.Disposer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcpProcessManagerParsingTest {
    @Test
    fun `strip ansi removes terminal color sequences`() = withManager { manager ->
        val input = "\u001B[32mgpt-5\u001B[0m - \u001B[1mGPT-5\u001B[0m"
        val cleaned = invokePrivateString(manager, "stripAnsi", input)

        assertEquals("gpt-5 - GPT-5", cleaned)
    }

    @Test
    fun `parse model info list parses ids display names and current marker`() = withManager { manager ->
        val output = """
            ${"\u001B[32m"}gpt-5 - GPT-5 (current)${"\u001B[0m"}
            claude-3.7-sonnet - Claude 3.7 Sonnet
            invalid line without delimiter
        """.trimIndent()

        val models = invokePrivateModelInfoList(manager, "parseModelInfoList", output)

        assertEquals(2, models.size)
        assertEquals("gpt-5", models[0].id)
        assertEquals("GPT-5", models[0].displayName)
        assertTrue(models[0].isCurrent)
        assertEquals("claude-3.7-sonnet", models[1].id)
        assertEquals("Claude 3.7 Sonnet", models[1].displayName)
        assertFalse(models[1].isCurrent)
    }

    @Test
    fun `parse model list returns only ids`() = withManager { manager ->
        val output = """
            gpt-5 - GPT-5
            claude-3.7-sonnet - Claude 3.7 Sonnet
        """.trimIndent()

        val ids = invokePrivateStringList(manager, "parseModelList", output)

        assertEquals(listOf("gpt-5", "claude-3.7-sonnet"), ids)
    }

    private fun withManager(block: (AcpProcessManager) -> Unit) {
        val disposable = Disposer.newDisposable("AcpProcessManagerParsingTestDisposable")
        try {
            block(AcpProcessManager(disposable))
        } finally {
            Disposer.dispose(disposable)
        }
    }

    private fun invokePrivateString(target: Any, name: String, arg: String): String {
        val method = target.javaClass.getDeclaredMethod(name, String::class.java).apply { isAccessible = true }
        return method.invoke(target, arg) as String
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokePrivateStringList(target: Any, name: String, arg: String): List<String> {
        val method = target.javaClass.getDeclaredMethod(name, String::class.java).apply { isAccessible = true }
        return method.invoke(target, arg) as List<String>
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokePrivateModelInfoList(
        target: Any,
        name: String,
        arg: String,
    ): List<AcpProcessManager.ModelInfo> {
        val method = target.javaClass.getDeclaredMethod(name, String::class.java).apply { isAccessible = true }
        return method.invoke(target, arg) as List<AcpProcessManager.ModelInfo>
    }
}
