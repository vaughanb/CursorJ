package com.cursorj.permissions

import com.cursorj.acp.messages.RequestPermissionParams
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PermissionPolicyTest {
    @Test
    fun `sticky approval allows subsequent request without re-prompt`() {
        val request = RequestPermissionParams(
            toolName = "Shell",
            description = "Run shell command",
            arguments = buildJsonObject {
                put("command", "npm test")
            },
        )

        val keys = PermissionPolicy.approvedKeysForRequest(request)
        val autoAllow = PermissionPolicy.shouldAutoAllowRequest(
            mode = PermissionMode.ASK_EVERY_TIME,
            approvedKeys = keys,
            request = request,
        )

        assertNotNull(autoAllow)
        assertTrue(PermissionPolicy.isAllowOption(autoAllow))
    }

    @Test
    fun `run everything mode auto-allows all permission requests`() {
        val request = RequestPermissionParams(toolName = "AnyTool")
        val autoAllow = PermissionPolicy.shouldAutoAllowRequest(
            mode = PermissionMode.RUN_EVERYTHING,
            approvedKeys = emptySet(),
            request = request,
        )

        assertNotNull(autoAllow)
        assertTrue(PermissionPolicy.isAllowOption(autoAllow))
    }

    @Test
    fun `ask mode blocks sensitive write without approval`() {
        val allowed = PermissionPolicy.shouldAllowMethodExecution(
            mode = PermissionMode.ASK_EVERY_TIME,
            approvedKeys = emptySet(),
            method = "fs/write_text_file",
            params = buildJsonObject {
                put("path", "src/Main.kt")
            },
        )

        assertFalse(allowed)
    }

    @Test
    fun `ask mode allows sensitive write after tool approval`() {
        val allowed = PermissionPolicy.shouldAllowMethodExecution(
            mode = PermissionMode.ASK_EVERY_TIME,
            approvedKeys = setOf("fs/write_text_file"),
            method = "fs/write_text_file",
            params = buildJsonObject {
                put("path", "src/Main.kt")
            },
        )

        assertTrue(allowed)
    }

    @Test
    fun `ask mode allows read-only indexing methods without approval`() {
        val allowed = PermissionPolicy.shouldAllowMethodExecution(
            mode = PermissionMode.ASK_EVERY_TIME,
            approvedKeys = emptySet(),
            method = "fs/find_text_in_files",
            params = buildJsonObject {
                put("query", "symbol")
            },
        )
        assertTrue(allowed)
    }

    @Test
    fun `ask mode allows editor symbol lookup methods without approval`() {
        val methodList = listOf("editor/get_open_files", "editor/find_symbol", "editor/list_file_symbols", "editor/find_references")
        for (method in methodList) {
            val allowed = PermissionPolicy.shouldAllowMethodExecution(
                mode = PermissionMode.ASK_EVERY_TIME,
                approvedKeys = emptySet(),
                method = method,
                params = buildJsonObject {},
            )
            assertTrue(allowed, "Expected read-only method to be allowed: $method")
        }
    }

    @Test
    fun `approved shell command base allows terminal create`() {
        val allowed = PermissionPolicy.shouldAllowMethodExecution(
            mode = PermissionMode.ASK_EVERY_TIME,
            approvedKeys = setOf("shell:npm"),
            method = "terminal/create",
            params = buildJsonObject {
                put("command", "npm run test")
            },
        )

        assertTrue(allowed)
    }

    @Test
    fun `workspace path detection blocks external files`() {
        val workspace = Files.createTempDirectory("cursorj-policy-workspace").toFile()
        val insideFile = workspace.resolve("src/Main.kt")
        insideFile.parentFile.mkdirs()
        insideFile.writeText("class Main")

        val outsideRoot = Files.createTempDirectory("cursorj-policy-outside").toFile()
        val outsideFile = outsideRoot.resolve("Secret.kt")
        outsideFile.writeText("top secret")

        val inside = PermissionPolicy.isPathInsideWorkspace(
            path = insideFile.absolutePath,
            workspacePath = workspace.absolutePath,
        )
        val outside = PermissionPolicy.isPathInsideWorkspace(
            path = outsideFile.absolutePath,
            workspacePath = workspace.absolutePath,
        )

        assertTrue(inside)
        assertFalse(outside)
    }
}
