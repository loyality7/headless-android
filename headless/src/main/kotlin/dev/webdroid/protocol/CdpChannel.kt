package dev.webdroid.protocol

import dev.webdroid.BrowserException
import dev.webdroid.ErrorCode
import dev.webdroid.browserError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** An unsolicited CDP notification, not a response to a command this side sent. */
public data class CdpEvent(
    /** The CDP method name, e.g. `Network.requestWillBeSent`. */
    public val method: String,
    /** The event's payload, in whatever shape that method defines. */
    public val params: JSONObject,
)

/**
 * Handles CDP RPC command correlation, event dispatching, backpressure buffering,
 * and deterministic teardown over [WebSocketClient].
 */
internal class CdpChannel(
    private val client: WebSocketClient,
) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val nextCommandId = AtomicLong(1)
    private val pendingCommands = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()

    private val _events = MutableSharedFlow<CdpEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val events: SharedFlow<CdpEvent> = _events.asSharedFlow()
    val subscriptionCount: kotlinx.coroutines.flow.StateFlow<Int> = _events.subscriptionCount

    val droppedEventsCount = AtomicLong(0)

    @Volatile
    var isClosed: Boolean = false
        private set

    init {
        scope.launch {
            client.textMessages.collect { rawText ->
                handleIncomingMessage(rawText)
            }
        }
    }

    /**
     * Sends a CDP RPC command and awaits its correlated response.
     *
     * @param method CDP method name (e.g. `Page.enable`, `Runtime.evaluate`).
     * @param params Optional CDP parameters payload.
     * @return [JSONObject] containing the CDP `result` payload.
     * @throws BrowserException if channel is closed, command fails, or response contains a protocol error.
     */
    suspend fun sendCommand(method: String, params: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        if (isClosed) {
            throw browserError(ErrorCode.CANCELLED, "CdpChannel is closed")
        }

        val id = nextCommandId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pendingCommands[id] = deferred

        val requestJson = JSONObject().apply {
            put("id", id)
            put("method", method)
            put("params", params)
        }

        try {
            client.sendText(requestJson.toString())
        } catch (e: Throwable) {
            pendingCommands.remove(id)
            throw browserError(ErrorCode.PROTOCOL_ERROR, "Failed to send CDP command $method", e)
        }

        val response = try {
            deferred.await()
        } catch (e: Throwable) {
            pendingCommands.remove(id)
            throw e
        }

        if (response.has("error")) {
            val errorObj = response.optJSONObject("error")
            val errorMsg = errorObj?.optString("message", "CDP command failed") ?: "CDP command failed"
            val errorCode = errorObj?.optInt("code", -1) ?: -1
            throw browserError(ErrorCode.PROTOCOL_ERROR, "CDP $method error [$errorCode]: $errorMsg")
        }

        response.optJSONObject("result") ?: JSONObject()
    }

    private fun handleIncomingMessage(rawText: String) {
        val json = try {
            JSONObject(rawText)
        } catch (_: Throwable) {
            return
        }

        if (json.has("id")) {
            val id = json.optLong("id")
            val deferred = pendingCommands.remove(id)
            deferred?.complete(json)
        } else if (json.has("method")) {
            val method = json.optString("method")
            val params = json.optJSONObject("params") ?: JSONObject()
            val emitted = _events.tryEmit(CdpEvent(method, params))
            if (!emitted) {
                droppedEventsCount.incrementAndGet()
            }
        }
    }

    /**
     * Closes the CDP channel, cancels all pending commands with `CANCELLED`, and releases resources.
     */
    suspend fun close(): Unit = withContext(Dispatchers.IO) {
        if (isClosed) return@withContext
        isClosed = true

        val cancelException = browserError(ErrorCode.CANCELLED, "CdpChannel teardown in progress")
        val pending = pendingCommands.values.toList()
        pendingCommands.clear()

        for (deferred in pending) {
            deferred.completeExceptionally(cancelException)
        }

        scope.cancel()
        client.close()
    }
}
