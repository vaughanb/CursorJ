package com.cursorj.acp

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedWriter
import java.io.StringWriter
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AcpClientProtocolTest {
    @Test
    fun `handleResponse completes pending request on success`() = runBlocking {
        fixture().use { (client, _, _) ->
            val pending = pendingRequests(client)
            val deferred = CompletableDeferred<JsonElement>()
            pending[101] = deferred

            invokePrivate(
                client,
                "handleResponse",
                buildJsonObject {
                    put("id", 101)
                    put("result", buildJsonObject { put("ok", true) })
                },
            )

            val result = withTimeout(2_000) { deferred.await() }
            assertEquals("true", result.jsonObject["ok"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `handleResponse maps rpc error to AcpException`() = runBlocking {
        fixture().use { (client, _, _) ->
            val pending = pendingRequests(client)
            val deferred = CompletableDeferred<JsonElement>()
            pending[202] = deferred

            invokePrivate(
                client,
                "handleResponse",
                buildJsonObject {
                    put("id", 202)
                    putJsonObject("error") {
                        put("code", -32000)
                        put("message", "boom")
                    }
                },
            )

            val error = assertFailsWith<AcpException> {
                withTimeout(2_000) { deferred.await() }
            }
            assertEquals(-32000, error.code)
            assertEquals("boom", error.message)
        }
    }

    @Test
    fun `handleNotification dispatches registered listeners`() = runBlocking {
        fixture().use { (client, _, _) ->
            val notificationSeen = CompletableDeferred<Pair<String, String>>()
            client.addNotificationHandler { method, params ->
                val updateType = params
                    .jsonObject["sessionUpdate"]
                    ?.jsonPrimitive
                    ?.content
                    ?: return@addNotificationHandler
                if (!notificationSeen.isCompleted) {
                    notificationSeen.complete(method to updateType)
                }
            }

            invokePrivate(
                client,
                "handleNotification",
                buildJsonObject {
                    put("method", "session/update")
                    put("params", buildJsonObject { put("sessionUpdate", "tool_result") })
                },
            )

            val notification = withTimeout(2_000) { notificationSeen.await() }
            assertEquals("session/update", notification.first)
            assertEquals("tool_result", notification.second)
        }
    }

    @Test
    fun `handleServerRequest writes response and method not found errors`() = runBlocking {
        fixture().use { (client, writerCapture, _) ->
            client.addServerRequestHandler { method, _ ->
                if (method == "editor/ping") {
                    buildJsonObject { put("ok", true) }
                } else {
                    null
                }
            }

            invokePrivate(
                client,
                "handleServerRequest",
                buildJsonObject {
                    put("id", 41)
                    put("method", "editor/ping")
                    put("params", buildJsonObject {})
                },
            )
            val successResponse = awaitResponseFromWriter(writerCapture)
            assertEquals(41, successResponse["id"]?.jsonPrimitive?.intOrNull)
            assertEquals("true", successResponse["result"]?.jsonObject?.get("ok")?.jsonPrimitive?.content)

            writerCapture.buffer.setLength(0)
            invokePrivate(
                client,
                "handleServerRequest",
                buildJsonObject {
                    put("id", 99)
                    put("method", "no/such_method")
                    put("params", buildJsonObject {})
                },
            )
            val errorResponse = awaitResponseFromWriter(writerCapture)
            assertEquals(99, errorResponse["id"]?.jsonPrimitive?.intOrNull)
            assertEquals(-32601, errorResponse["error"]?.jsonObject?.get("code")?.jsonPrimitive?.intOrNull)
        }
    }

    private suspend fun awaitResponseFromWriter(writerCapture: StringWriter): kotlinx.serialization.json.JsonObject {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            val text = writerCapture.toString()
            if (text.isNotBlank()) {
                val line = text.lineSequence().firstOrNull { it.isNotBlank() }
                if (line != null) {
                    return Json.parseToJsonElement(line).jsonObject
                }
            }
            delay(25)
        }
        error("Timed out waiting for JSON-RPC response")
    }

    private data class ClientFixture(
        val client: AcpClient,
        val writerCapture: StringWriter,
        val disposable: Disposable,
    ) : AutoCloseable {
        override fun close() {
            runCatching { Disposer.dispose(disposable) }
        }
    }

    private fun fixture(): ClientFixture {
        val disposable = Disposer.newDisposable("AcpClientProtocolTest")
        val client = AcpClient(disposable)
        val writerCapture = StringWriter()
        setWriter(client, BufferedWriter(writerCapture))
        return ClientFixture(client = client, writerCapture = writerCapture, disposable = disposable)
    }

    @Suppress("UNCHECKED_CAST")
    private fun pendingRequests(client: AcpClient): ConcurrentHashMap<Int, CompletableDeferred<JsonElement>> {
        val field = AcpClient::class.java.getDeclaredField("pendingRequests").apply { isAccessible = true }
        return field.get(client) as ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>
    }

    private fun setWriter(client: AcpClient, writer: BufferedWriter) {
        val field = AcpClient::class.java.getDeclaredField("writer").apply { isAccessible = true }
        field.set(client, writer)
    }

    private fun invokePrivate(client: AcpClient, methodName: String, arg: Any) {
        val method: Method = AcpClient::class.java.declaredMethods.first {
            it.name == methodName && it.parameterCount == 1
        }.apply { isAccessible = true }
        method.invoke(client, arg)
    }
}
