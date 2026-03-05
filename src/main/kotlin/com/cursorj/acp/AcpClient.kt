package com.cursorj.acp

import com.cursorj.acp.messages.*
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

typealias NotificationHandler = (method: String, params: JsonElement) -> Unit
typealias ServerRequestHandler = (method: String, params: JsonElement) -> JsonElement?

class AcpClient(private val parentDisposable: Disposable) : Disposable {
    private val log = Logger.getInstance(AcpClient::class.java)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val nextId = AtomicInteger(1)
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()
    private val notificationHandlers = mutableListOf<NotificationHandler>()
    private val serverRequestHandlers = mutableListOf<ServerRequestHandler>()

    private var scope: CoroutineScope? = null
    private var readJob: Job? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

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
        return json.decodeFromJsonElement(result)
    }

    suspend fun sessionLoad(sessionId: String): SessionLoadResult {
        val params = SessionLoadParams(sessionId = sessionId)
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

    suspend fun sessionSetMode(sessionId: String, mode: String) {
        val params = SessionSetModeParams(sessionId = sessionId, mode = mode)
        sendRequest("session/setMode", json.encodeToJsonElement(params))
    }

    private suspend fun sendRequest(method: String, params: JsonElement): JsonElement {
        val id = nextId.getAndIncrement()
        val request = JsonRpcRequest(id = id, method = method, params = params)
        val deferred = CompletableDeferred<JsonElement>()
        pendingRequests[id] = deferred

        val line = json.encodeToString(request)
        withContext(Dispatchers.IO) {
            synchronized(writer!!) {
                writer!!.write(line)
                writer!!.newLine()
                writer!!.flush()
            }
        }
        log.debug("ACP request: $method (id=$id)")

        return deferred.await()
    }

    private suspend fun sendNotification(method: String, params: JsonElement) {
        val notification = JsonRpcNotification(method = method, params = params)
        val line = json.encodeToString(notification)
        withContext(Dispatchers.IO) {
            synchronized(writer!!) {
                writer!!.write(line)
                writer!!.newLine()
                writer!!.flush()
            }
        }
        log.debug("ACP notification: $method")
    }

    fun respondToServerRequest(id: Int, result: JsonElement?) {
        val response = JsonRpcServerResponse(id = id, result = result)
        val line = json.encodeToString(response)
        synchronized(writer!!) {
            writer!!.write(line)
            writer!!.newLine()
            writer!!.flush()
        }
        log.debug("ACP response to server request id=$id")
    }

    fun respondToServerRequestWithError(id: Int, code: Int, message: String) {
        val response = JsonRpcServerResponse(
            id = id,
            error = JsonRpcError(code = code, message = message),
        )
        val line = json.encodeToString(response)
        synchronized(writer!!) {
            writer!!.write(line)
            writer!!.newLine()
            writer!!.flush()
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
        }
    }

    private fun isActive(): Boolean = scope?.isActive == true

    private fun handleMessage(line: String) {
        try {
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

        for (handler in serverRequestHandlers) {
            try {
                val result = handler(method, params)
                if (result != null) {
                    respondToServerRequest(id, result)
                    return
                }
            } catch (e: Exception) {
                log.warn("Server request handler error for $method", e)
                respondToServerRequestWithError(id, -32603, e.message ?: "Internal error")
                return
            }
        }

        respondToServerRequestWithError(id, -32601, "Method not found: $method")
    }

    fun disconnect() {
        readJob?.cancel()
        scope?.cancel()
        pendingRequests.values.forEach {
            it.completeExceptionally(AcpException(-1, "Disconnected"))
        }
        pendingRequests.clear()
        isConnected = false
    }

    override fun dispose() {
        disconnect()
    }
}

class AcpException(val code: Int, override val message: String) : Exception(message)
