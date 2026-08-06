package com.cursorj.debug

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
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
 * Per-session runtime-evidence channel for CursorJ Debug mode.
 *
 * Provisions a log file under `<project>/.cursorj/debug/` and (when possible) a localhost-only
 * HTTP endpoint so instrumented code can append structured lines. The agent primarily reads the
 * log file via `fs/read_text_file`; HTTP is for runtimes that cannot easily write local files.
 */
class DebugLogServer(
    private val projectBasePath: String?,
) : Disposable {
    data class DebugEndpoint(
        val sessionId: String,
        val logFilePath: String,
        /** Full POST URL for appending a log line, or empty when the HTTP server failed to bind. */
        val url: String,
        val httpAvailable: Boolean,
    )

    private data class SessionState(
        val token: String,
        val logFile: File,
        val buffer: StringBuilder = StringBuilder(),
        val lock: Any = Any(),
    )

    private val log = Logger.getInstance(DebugLogServer::class.java)
    private val sessions = ConcurrentHashMap<String, SessionState>()
    private val started = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)

    @Volatile
    private var server: HttpServer? = null

    @Volatile
    private var boundPort: Int = -1

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "cursorj-debug-log").apply { isDaemon = true }
    }

    /**
     * Truncates (or creates) the session log file and returns the endpoint the Debug prompt should
     * advertise. Safe to call repeatedly; each call starts a fresh capture for that turn.
     */
    fun beginTurn(sessionId: String): DebugEndpoint {
        ensureStarted()
        val safeId = sanitizeSessionId(sessionId)
        val logFile = resolveLogFile(safeId)
        logFile.parentFile?.mkdirs()
        logFile.writeText("", StandardCharsets.UTF_8)

        val token = newToken()
        val state = SessionState(token = token, logFile = logFile)
        sessions[safeId] = state

        val port = boundPort
        val httpAvailable = port > 0
        val url = if (httpAvailable) {
            "http://127.0.0.1:$port/log/$safeId/$token"
        } else {
            ""
        }
        return DebugEndpoint(
            sessionId = safeId,
            logFilePath = logFile.absolutePath.replace('\\', '/'),
            url = url,
            httpAvailable = httpAvailable,
        )
    }

    /** Absolute path of the current log file for [sessionId], or null if no turn has begun. */
    fun logFilePathFor(sessionId: String): String? {
        val safeId = sanitizeSessionId(sessionId)
        return sessions[safeId]?.logFile?.absolutePath?.replace('\\', '/')
            ?: resolveLogFile(safeId).takeIf { it.isFile }?.absolutePath?.replace('\\', '/')
    }

    /** In-memory + file contents collected for [sessionId] (empty if unknown). */
    fun collectedLogs(sessionId: String): String {
        val state = sessions[sanitizeSessionId(sessionId)] ?: return ""
        synchronized(state.lock) {
            return state.buffer.toString().ifBlank {
                runCatching { state.logFile.readText(StandardCharsets.UTF_8) }.getOrDefault("")
            }
        }
    }

    private fun ensureStarted() {
        if (disposed.get()) return
        if (!started.compareAndSet(false, true)) return
        try {
            val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            http.executor = executor
            http.createContext("/") { exchange -> handleExchange(exchange) }
            http.start()
            server = http
            boundPort = http.address.port
            log.info("DebugLogServer listening on 127.0.0.1:$boundPort")
        } catch (e: Exception) {
            boundPort = -1
            server = null
            log.warn("DebugLogServer failed to bind; falling back to file-only capture", e)
        }
    }

    private fun handleExchange(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path.trimEnd('/')
            val parts = path.trimStart('/').split('/')
            // Expected: log|logs / {sessionId} / {token}
            if (parts.size != 3 || (parts[0] != "log" && parts[0] != "logs")) {
                send(exchange, 404, "not found")
                return
            }
            val sessionId = parts[1]
            val token = parts[2]
            val state = sessions[sessionId]
            if (state == null || state.token != token) {
                send(exchange, 403, "forbidden")
                return
            }
            when {
                parts[0] == "log" && exchange.requestMethod.equals("POST", ignoreCase = true) -> {
                    val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                    appendLog(state, body)
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
        } catch (e: Exception) {
            log.warn("DebugLogServer request failed", e)
            runCatching { send(exchange, 500, "error") }
        } finally {
            runCatching { exchange.close() }
        }
    }

    private fun appendLog(state: SessionState, body: String) {
        val line = if (body.endsWith('\n')) body else "$body\n"
        synchronized(state.lock) {
            if (state.buffer.length + line.length > MAX_BUFFER_CHARS) {
                val overflow = state.buffer.length + line.length - MAX_BUFFER_CHARS
                if (overflow >= state.buffer.length) {
                    state.buffer.clear()
                } else {
                    state.buffer.delete(0, overflow)
                }
            }
            state.buffer.append(line)
            if (state.logFile.length() + line.length > MAX_FILE_BYTES) {
                // Rotate: keep the newest half of the file plus this line.
                val existing = runCatching { state.logFile.readText(StandardCharsets.UTF_8) }.getOrDefault("")
                val keepFrom = (existing.length / 2).coerceAtLeast(0)
                state.logFile.writeText(existing.substring(keepFrom) + line, StandardCharsets.UTF_8)
            } else {
                state.logFile.appendText(line, StandardCharsets.UTF_8)
            }
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

    private fun resolveLogFile(safeSessionId: String): File {
        val base = projectBasePath?.trim()?.takeIf { it.isNotBlank() }
            ?: System.getProperty("java.io.tmpdir")
        return File(File(base, ".cursorj/debug"), "session-$safeSessionId.log")
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        runCatching { server?.stop(0) }
        server = null
        boundPort = -1
        sessions.clear()
        executor.shutdownNow()
    }

    companion object {
        private const val MAX_BUFFER_CHARS = 512_000
        private const val MAX_FILE_BYTES = 2L * 1024L * 1024L
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
