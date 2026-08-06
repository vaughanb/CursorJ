package com.cursorj.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Disposable-free Debug log sink for manual ACP integration tests.
 *
 * Mirrors [com.cursorj.debug.DebugLogServer] behavior without referencing IntelliJ types, so the
 * plain `integrationTest` worker (AppClassLoader) can load the suite.
 */
internal class IntegrationDebugLogSink(private val projectBasePath: String) : AutoCloseable {
    data class Endpoint(
        val sessionId: String,
        val logFilePath: String,
        val url: String,
        val httpAvailable: Boolean,
    )

    private data class SessionState(
        val token: String,
        val logFile: File,
        val buffer: StringBuilder = StringBuilder(),
        val lock: Any = Any(),
    )

    private val sessions = ConcurrentHashMap<String, SessionState>()
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var server: HttpServer? = null
    private var boundPort: Int = -1
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "cursorj-debug-it-log").apply { isDaemon = true }
    }

    fun beginTurn(sessionId: String): Endpoint {
        ensureStarted()
        val safeId = sanitizeSessionId(sessionId)
        val logFile = File(File(projectBasePath, ".cursorj/debug"), "session-$safeId.log")
        logFile.parentFile?.mkdirs()
        logFile.writeText("", StandardCharsets.UTF_8)
        val token = newToken()
        sessions[safeId] = SessionState(token = token, logFile = logFile)
        val httpAvailable = boundPort > 0
        val url = if (httpAvailable) "http://127.0.0.1:$boundPort/log/$safeId/$token" else ""
        return Endpoint(
            sessionId = safeId,
            logFilePath = logFile.absolutePath.replace('\\', '/'),
            url = url,
            httpAvailable = httpAvailable,
        )
    }

    private fun ensureStarted() {
        if (closed.get()) return
        if (!started.compareAndSet(false, true)) return
        try {
            val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            http.executor = executor
            http.createContext("/") { exchange -> handleExchange(exchange) }
            http.start()
            server = http
            boundPort = http.address.port
        } catch (_: Exception) {
            boundPort = -1
            server = null
        }
    }

    private fun handleExchange(exchange: HttpExchange) {
        try {
            val parts = exchange.requestURI.path.trimEnd('/').trimStart('/').split('/')
            if (parts.size != 3 || (parts[0] != "log" && parts[0] != "logs")) {
                send(exchange, 404, "not found")
                return
            }
            val state = sessions[parts[1]]
            if (state == null || state.token != parts[2]) {
                send(exchange, 403, "forbidden")
                return
            }
            when {
                parts[0] == "log" && exchange.requestMethod.equals("POST", ignoreCase = true) -> {
                    val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                    val line = if (body.endsWith('\n')) body else "$body\n"
                    synchronized(state.lock) {
                        state.buffer.append(line)
                        state.logFile.appendText(line, StandardCharsets.UTF_8)
                    }
                    send(exchange, 204, "")
                }
                parts[0] == "logs" && exchange.requestMethod.equals("GET", ignoreCase = true) -> {
                    val content = synchronized(state.lock) {
                        state.buffer.toString().ifBlank {
                            runCatching { state.logFile.readText(StandardCharsets.UTF_8) }.getOrDefault("")
                        }
                    }
                    send(exchange, 200, content)
                }
                else -> send(exchange, 405, "method not allowed")
            }
        } catch (_: Exception) {
            runCatching { send(exchange, 500, "error") }
        } finally {
            runCatching { exchange.close() }
        }
    }

    private fun send(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        if (status == 204) {
            exchange.sendResponseHeaders(204, -1)
            return
        }
        exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server?.stop(0) }
        server = null
        boundPort = -1
        sessions.clear()
        executor.shutdownNow()
    }

    companion object {
        private val TOKEN_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        private val secureRandom = SecureRandom()

        fun sanitizeSessionId(sessionId: String): String {
            val trimmed = sessionId.trim().ifBlank { "unknown" }
            return trimmed.map { c ->
                if (c.isLetterOrDigit() || c == '-' || c == '_') c else '-'
            }.joinToString("").take(80)
        }

        private fun newToken(): String {
            val chars = CharArray(24) {
                TOKEN_ALPHABET[secureRandom.nextInt(TOKEN_ALPHABET.length)]
            }
            return String(chars)
        }
    }
}
