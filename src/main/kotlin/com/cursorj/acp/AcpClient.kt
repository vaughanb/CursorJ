package com.cursorj.acp

import com.cursorj.acp.messages.*
import com.cursorj.settings.CursorJSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

typealias NotificationHandler = (method: String, params: JsonElement) -> Unit
typealias ServerRequestHandler = (method: String, params: JsonElement) -> JsonElement?
typealias DisconnectListener = () -> Unit

class AcpClient(private val parentDisposable: Disposable) : Disposable {
    private val log = Logger.getInstance(AcpClient::class.java)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val nextId = AtomicInteger(1)
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()
    private val notificationHandlers = CopyOnWriteArrayList<NotificationHandler>()
    private val serverRequestHandlers = CopyOnWriteArrayList<ServerRequestHandler>()
    private val disconnectListeners = CopyOnWriteArrayList<DisconnectListener>()

    private val serverRequestExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "CursorJ-ServerReq-${nextId.get()}").apply { isDaemon = true }
    }

    private val writeLock = ReentrantLock()

    private var scope: CoroutineScope? = null
    private var readJob: Job? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    private val acpRawLogEnabled: Boolean
        get() = runCatching { CursorJSettings.instance.enableAcpRawLogging }.getOrDefault(false)

    var isConnected = false
        private set

    var agentCapabilities: AgentCapabilities? = null
        private set

    init {
        Disposer.register(parentDisposable, this)
    }

    fun addNotificationHandler(handler: NotificationHandler) {
        notificationHandlers.add(handler)
    }

    fun addServerRequestHandler(handler: ServerRequestHandler) {
        serverRequestHandlers.add(handler)
    }

    fun addDisconnectListener(listener: DisconnectListener) {
        disconnectListeners.add(listener)
    }

    fun connect(reader: BufferedReader, writer: BufferedWriter) {
        this.reader = reader
        this.writer = writer
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        readJob = scope!!.launch { readLoop() }
        isConnected = true
    }

    suspend fun initialize(): InitializeResult {
        val params = InitializeParams()
        val result = sendRequest("initialize", json.encodeToJsonElement(params))
        val initResult = json.decodeFromJsonElement<InitializeResult>(result)
        agentCapabilities = initResult.agentCapabilities
        return initResult
    }

    suspend fun authenticate(methodId: String = "cursor_login") {
        val params = AuthenticateParams(methodId)
        sendRequest("authenticate", json.encodeToJsonElement(params))
    }

    suspend fun sessionNew(cwd: String): SessionNewResult {
        val params = SessionNewParams(cwd = cwd)
        val result = sendRequest("session/new", json.encodeToJsonElement(params))
        log.info("session/new response: $result")
        return json.decodeFromJsonElement(result)
    }

    suspend fun sessionLoad(sessionId: String, cwd: String): SessionLoadResult {
        val params = SessionLoadParams(sessionId = sessionId, cwd = cwd, mcpServers = emptyList())
        val result = sendRequest("session/load", json.encodeToJsonElement(params))
        return json.decodeFromJsonElement(result)
    }

    suspend fun sessionPrompt(sessionId: String, prompt: List<ContentBlock>): SessionPromptResult {
        val params = SessionPromptParams(sessionId = sessionId, prompt = prompt)
        val result = sendRequest("session/prompt", json.encodeToJsonElement(params))
        return json.decodeFromJsonElement(result)
    }

    suspend fun sessionCancel(sessionId: String) {
        val params = SessionCancelParams(sessionId = sessionId)
        sendNotification("session/cancel", json.encodeToJsonElement(params))
    }

    suspend fun sessionSetMode(sessionId: String, modeId: String) {
        val params = SessionSetModeParams(sessionId = sessionId, modeId = modeId)
        sendRequest("session/set_mode", json.encodeToJsonElement(params))
    }

    suspend fun sessionSetModel(sessionId: String, modelId: String) {
        val params = SessionSetModelParams(sessionId = sessionId, modelId = modelId)
        sendRequest("session/set_model", json.encodeToJsonElement(params))
    }

    suspend fun sessionSetConfigOption(sessionId: String, configId: String, value: String): SetConfigOptionResult {
        val params = SetConfigOptionParams(sessionId = sessionId, configId = configId, value = value)
        val result = sendRequest("session/set_config_option", json.encodeToJsonElement(params))
        return json.decodeFromJsonElement(result)
    }

    private suspend fun sendRequest(method: String, params: JsonElement): JsonElement {
        val id = nextId.getAndIncrement()
        val request = JsonRpcRequest(id = id, method = method, params = params)
        val deferred = CompletableDeferred<JsonElement>()
        pendingRequests[id] = deferred
        deferred.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                pendingRequests.remove(id, deferred)
            }
        }

        try {
            val line = json.encodeToString(request)
            logRawAcp("ACP raw -> request(id=$id, method=$method): ", line)
            withContext(Dispatchers.IO) {
                writeLock.lock()
                try {
                    writer!!.write(line)
                    writer!!.newLine()
                    writer!!.flush()
                } finally {
                    writeLock.unlock()
                }
            }
            log.debug("ACP request: $method (id=$id)")
        } catch (e: Exception) {
            pendingRequests.remove(id)
            throw e
        }

        return deferred.await()
    }

    private suspend fun sendNotification(method: String, params: JsonElement) {
        val notification = JsonRpcNotification(method = method, params = params)
        val line = json.encodeToString(notification)
        logRawAcp("ACP raw -> notification(method=$method): ", line)
        withContext(Dispatchers.IO) {
            if (writeLock.tryLock(NOTIFICATION_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                try {
                    writer!!.write(line)
                    writer!!.newLine()
                    writer!!.flush()
                } finally {
                    writeLock.unlock()
                }
            } else {
                log.warn("Could not acquire write lock for notification $method within ${NOTIFICATION_WRITE_TIMEOUT_MS}ms")
            }
        }
        log.debug("ACP notification: $method")
    }

    fun respondToServerRequest(id: Int, result: JsonElement?) {
        val response = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result ?: JsonNull)
        }
        val line = json.encodeToString(JsonObject.serializer(), response)
        logRawAcp("ACP raw -> server_response(id=$id): ", line)
        writeLock.lock()
        try {
            writer!!.write(line)
            writer!!.newLine()
            writer!!.flush()
        } finally {
            writeLock.unlock()
        }
        log.debug("ACP response to server request id=$id")
    }

    fun respondToServerRequestWithError(id: Int, code: Int, message: String) {
        val response = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }
        val line = json.encodeToString(JsonObject.serializer(), response)
        logRawAcp("ACP raw -> server_response_error(id=$id, code=$code): ", line)
        writeLock.lock()
        try {
            writer!!.write(line)
            writer!!.newLine()
            writer!!.flush()
        } finally {
            writeLock.unlock()
        }
        log.debug("ACP error response to server request id=$id: $message")
    }

    private suspend fun readLoop() {
        try {
            while (isActive()) {
                val line = withContext(Dispatchers.IO) { reader?.readLine() } ?: break
                if (line.isBlank()) continue
                handleMessage(line)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("ACP read loop error", e)
        } finally {
            isConnected = false
            cancelAllPending("Agent connection lost")
            for (listener in disconnectListeners) {
                try { listener() } catch (e: Exception) { log.warn("Disconnect listener error", e) }
            }
        }
    }

    private fun isActive(): Boolean = scope?.isActive == true

    private fun handleMessage(line: String) {
        try {
            logRawAcp("ACP raw <- ", line)
            val element = json.parseToJsonElement(line)
            val obj = element.jsonObject

            when {
                "id" in obj && "method" in obj -> handleServerRequest(obj)
                "id" in obj && ("result" in obj || "error" in obj) -> handleResponse(obj)
                "method" in obj -> handleNotification(obj)
                else -> log.warn("Unknown ACP message format: $line")
            }
        } catch (e: Exception) {
            log.warn("Failed to parse ACP message: $line", e)
        }
    }

    private fun handleResponse(obj: JsonObject) {
        val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return
        val deferred = pendingRequests.remove(id) ?: run {
            log.warn("No pending request for response id=$id")
            return
        }

        val error = obj["error"]
        if (error != null && error !is JsonNull) {
            val rpcError = json.decodeFromJsonElement<JsonRpcError>(error)
            deferred.completeExceptionally(AcpException(rpcError.code, rpcError.message))
        } else {
            deferred.complete(obj["result"] ?: JsonNull)
        }
    }

    private fun handleNotification(obj: JsonObject) {
        val method = obj["method"]?.jsonPrimitive?.contentOrNull ?: return
        val params = obj["params"] ?: JsonObject(emptyMap())
        for (handler in notificationHandlers) {
            try {
                handler(method, params)
            } catch (e: Exception) {
                log.warn("Notification handler error for $method", e)
            }
        }
    }

    private fun handleServerRequest(obj: JsonObject) {
        val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return
        val method = obj["method"]?.jsonPrimitive?.contentOrNull ?: return
        val params = obj["params"] ?: JsonObject(emptyMap())

        log.info("ACP server request: method=$method id=$id params_keys=${(params as? JsonObject)?.keys}")

        serverRequestExecutor.submit {
            for (handler in serverRequestHandlers) {
                try {
                    val result = handler(method, params)
                    if (result != null) {
                        log.info("ACP server request handled: method=$method id=$id result_keys=${(result as? JsonObject)?.keys}")
                        respondToServerRequest(id, result)
                        return@submit
                    }
                } catch (e: Exception) {
                    log.warn("Server request handler error for $method (id=$id): ${e.javaClass.simpleName}: ${e.message}", e)
                    respondToServerRequestWithError(id, -32603, e.message ?: "Internal error")
                    return@submit
                }
            }

            log.warn("Unhandled ACP server request: $method (id=$id)")
            respondToServerRequestWithError(id, -32601, "Method not found: $method")
        }
    }

    fun clearHandlers() {
        notificationHandlers.clear()
        serverRequestHandlers.clear()
        disconnectListeners.clear()
    }

    fun disconnect() {
        readJob?.cancel()
        scope?.cancel()
        cancelAllPending("Disconnected")
        isConnected = false
    }

    private fun cancelAllPending(reason: String) {
        val pending = pendingRequests.values.toList()
        pendingRequests.clear()
        for (deferred in pending) {
            deferred.completeExceptionally(AcpException(-1, reason))
        }
    }

    private fun logRawAcp(prefix: String, payload: String) {
        if (!acpRawLogEnabled) return
        val sanitized = payload
            .replace("\r", "\\r")
            .replace("\n", "\\n")
        val rendered = if (sanitized.length <= RAW_ACP_LOG_LIMIT_CHARS) {
            sanitized
        } else {
            val omitted = sanitized.length - RAW_ACP_LOG_LIMIT_CHARS
            sanitized.take(RAW_ACP_LOG_LIMIT_CHARS) + "... [truncated $omitted chars]"
        }
        log.info("$prefix$rendered")
    }

    override fun dispose() {
        disconnect()
        serverRequestExecutor.shutdownNow()
    }

    companion object {
        private const val RAW_ACP_LOG_LIMIT_CHARS = 8000
        private const val NOTIFICATION_WRITE_TIMEOUT_MS = 3000L
    }
}

class AcpException(val code: Int, override val message: String) : Exception(message)
