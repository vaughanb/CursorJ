package com.cursorj.debug

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DebugLogServerTest {
    private val tempDir = Files.createTempDirectory("cursorj-debug-test").toFile()
    private val server = DebugLogServer(tempDir.absolutePath)

    @AfterTest
    fun tearDown() {
        server.dispose()
        tempDir.deleteRecursively()
    }

    @Test
    fun `beginTurn creates empty log file and returns endpoint`() {
        val endpoint = server.beginTurn("session-abc")
        assertTrue(endpoint.logFilePath.contains(".cursorj/debug/session-session-abc.log") ||
            endpoint.logFilePath.replace('\\', '/').endsWith(".cursorj/debug/session-session-abc.log"))
        val file = File(endpoint.logFilePath)
        assertTrue(file.isFile)
        assertEquals("", file.readText(StandardCharsets.UTF_8))
        assertTrue(endpoint.httpAvailable, "HTTP server should bind on localhost in tests")
        assertTrue(endpoint.url.startsWith("http://127.0.0.1:"))
        assertTrue(endpoint.url.contains("/log/session-abc/"))
    }

    @Test
    fun `POST appends to log file and GET returns collected logs`() {
        val endpoint = server.beginTurn("s1")
        assertTrue(endpoint.httpAvailable)

        post(endpoint.url, """{"hypothesis":1,"msg":"entered foo"}""")
        post(endpoint.url, "plain-line")

        val fileText = File(endpoint.logFilePath).readText(StandardCharsets.UTF_8)
        assertTrue(fileText.contains("entered foo"))
        assertTrue(fileText.contains("plain-line"))

        val getUrl = endpoint.url.replace("/log/", "/logs/")
        val body = get(getUrl)
        assertTrue(body.contains("entered foo"))
        assertTrue(body.contains("plain-line"))
        assertEquals(fileText, server.collectedLogs("s1"))
    }

    @Test
    fun `wrong token is rejected`() {
        val endpoint = server.beginTurn("s2")
        assertTrue(endpoint.httpAvailable)
        val badUrl = endpoint.url.replaceAfterLast('/', "wrong-token")
        val code = postStatus(badUrl, "secret")
        assertEquals(403, code)
        assertEquals("", File(endpoint.logFilePath).readText(StandardCharsets.UTF_8))
    }

    @Test
    fun `beginTurn rotates log and issues a new token`() {
        val first = server.beginTurn("s3")
        post(first.url, "old-line")
        assertTrue(File(first.logFilePath).readText().contains("old-line"))

        val second = server.beginTurn("s3")
        assertEquals("", File(second.logFilePath).readText(StandardCharsets.UTF_8))
        assertNotEquals(first.url, second.url)

        val staleCode = postStatus(first.url, "should-fail")
        assertEquals(403, staleCode)
    }

    @Test
    fun `sanitizeSessionId strips unsafe characters`() {
        assertEquals("a-b_c", DebugLogServer.sanitizeSessionId("a/b_c"))
        assertEquals("unknown", DebugLogServer.sanitizeSessionId("   "))
    }

    @Test
    fun `file-only path still works when logFilePathFor is queried`() {
        val endpoint = server.beginTurn("s4")
        File(endpoint.logFilePath).appendText("from-file\n", StandardCharsets.UTF_8)
        val path = server.logFilePathFor("s4")
        assertFalse(path.isNullOrBlank())
        assertTrue(File(path!!).readText().contains("from-file"))
    }

    private fun post(url: String, body: String) {
        assertEquals(204, postStatus(url, body))
    }

    private fun postStatus(url: String, body: String): Int {
        val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 3_000
            readTimeout = 3_000
            setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        }
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        return conn.responseCode.also { conn.disconnect() }
    }

    private fun get(url: String): String {
        val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 3_000
            readTimeout = 3_000
        }
        val code = conn.responseCode
        val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        conn.disconnect()
        assertEquals(200, code)
        return text
    }
}
