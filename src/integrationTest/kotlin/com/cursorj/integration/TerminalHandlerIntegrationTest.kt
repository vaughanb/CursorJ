package com.cursorj.integration

import com.cursorj.acp.AcpClient
import com.cursorj.acp.messages.ReadTextFileParams
import com.cursorj.acp.messages.TerminalCreateParams
import com.cursorj.acp.messages.TerminalIdParams
import com.cursorj.acp.messages.WriteTextFileParams
import com.cursorj.handlers.FileSystemHandler
import com.cursorj.handlers.TerminalHandler
import com.intellij.openapi.util.Disposer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.io.StringWriter
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalHandlerIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `terminal lifecycle works through acp server request dispatch`() {
        AgentCliIntegrationSupport.assumeManualOnlyEnabled()
        AgentCliIntegrationSupport.assumeIdeApplicationAvailable()

        val workspace = Files.createTempDirectory("cursorj-terminal-handler-it").toFile()
        val project = AgentCliIntegrationSupport.projectWithBasePath(workspace.absolutePath)
        val disposable = AgentCliIntegrationSupport.newDisposable("TerminalHandlerIntegrationTest")
        try {
            val client = AcpClient(disposable)
            val writerCapture = StringWriter()
            AgentCliIntegrationSupport.setClientWriter(client, BufferedWriter(writerCapture))

            val terminalHandler = TerminalHandler(project)
            terminalHandler.register(client)

            val javaExec = java.io.File(System.getProperty("java.home"), "bin/java").absolutePath
            val createParams = TerminalCreateParams(
                command = javaExec,
                args = listOf("-version"),
                cwd = workspace.absolutePath,
            )
            AgentCliIntegrationSupport.invokeHandleServerRequest(
                client,
                buildJsonObject {
                    put("id", 1)
                    put("method", "terminal/create")
                    put("params", json.parseToJsonElement(json.encodeToString(createParams)))
                },
            )
            val createResponse = AgentCliIntegrationSupport.awaitJsonRpcResponse(writerCapture)
            val terminalId = createResponse["result"]!!
                .jsonObject["terminal_id"]!!
                .jsonPrimitive
                .content

            writerCapture.buffer.setLength(0)
            val waitParams = buildJsonObject {
                put("terminalId", terminalId)
                put("timeoutMs", 10_000)
            }
            AgentCliIntegrationSupport.invokeHandleServerRequest(
                client,
                buildJsonObject {
                    put("id", 2)
                    put("method", "terminal/wait")
                    put("params", waitParams)
                },
            )
            val waitResponse = AgentCliIntegrationSupport.awaitJsonRpcResponse(writerCapture)
            val exitCode = waitResponse["result"]!!.jsonObject["exitCode"]!!.jsonPrimitive.int
            assertEquals(0, exitCode, "Expected java -version to exit successfully")

            writerCapture.buffer.setLength(0)
            AgentCliIntegrationSupport.invokeHandleServerRequest(
                client,
                buildJsonObject {
                    put("id", 3)
                    put("method", "terminal/get_output")
                    put("params", json.parseToJsonElement(json.encodeToString(TerminalIdParams(terminalId = terminalId))))
                },
            )
            val outputResponse = AgentCliIntegrationSupport.awaitJsonRpcResponse(writerCapture)
            val output = outputResponse["result"]!!.jsonObject["output"]!!.jsonPrimitive.content.lowercase()
            assertTrue(output.contains("version"), "Expected terminal output to include JVM version text")

            writerCapture.buffer.setLength(0)
            AgentCliIntegrationSupport.invokeHandleServerRequest(
                client,
                buildJsonObject {
                    put("id", 4)
                    put("method", "terminal/release")
                    put("params", json.parseToJsonElement(json.encodeToString(TerminalIdParams(terminalId = terminalId))))
                },
            )
            AgentCliIntegrationSupport.awaitJsonRpcResponse(writerCapture)
        } finally {
            Disposer.dispose(disposable)
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `file system read and write works through acp server request dispatch`() {
        AgentCliIntegrationSupport.assumeManualOnlyEnabled()
        AgentCliIntegrationSupport.assumeIdeApplicationAvailable()

        val workspace = Files.createTempDirectory("cursorj-filesystem-handler-it").toFile()
        val project = AgentCliIntegrationSupport.projectWithBasePath(workspace.absolutePath)
        val disposable = AgentCliIntegrationSupport.newDisposable("FileSystemHandlerIntegrationTest")
        try {
            val client = AcpClient(disposable)
            val writerCapture = StringWriter()
            AgentCliIntegrationSupport.setClientWriter(client, BufferedWriter(writerCapture))

            val handler = FileSystemHandler(project)
            handler.register(client)

            val relativePath = "notes/integration.txt"
            val expected = "integration file contents"
            AgentCliIntegrationSupport.invokeHandleServerRequest(
                client,
                buildJsonObject {
                    put("id", 11)
                    put("method", "fs/write_text_file")
                    put(
                        "params",
                        json.parseToJsonElement(
                            json.encodeToString(
                                WriteTextFileParams(
                                    path = relativePath,
                                    content = expected,
                                ),
                            ),
                        ),
                    )
                },
            )
            AgentCliIntegrationSupport.awaitJsonRpcResponse(writerCapture)

            writerCapture.buffer.setLength(0)
            AgentCliIntegrationSupport.invokeHandleServerRequest(
                client,
                buildJsonObject {
                    put("id", 12)
                    put("method", "fs/read_text_file")
                    put(
                        "params",
                        json.parseToJsonElement(
                            json.encodeToString(
                                ReadTextFileParams(path = relativePath),
                            ),
                        ),
                    )
                },
            )
            val readResponse = AgentCliIntegrationSupport.awaitJsonRpcResponse(writerCapture)
            val content = readResponse["result"]!!.jsonObject["content"]!!.jsonPrimitive.content
            assertEquals(expected, content)
        } finally {
            Disposer.dispose(disposable)
            workspace.deleteRecursively()
        }
    }
}
