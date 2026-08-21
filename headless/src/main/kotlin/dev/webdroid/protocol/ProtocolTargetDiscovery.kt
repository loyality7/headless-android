package dev.webdroid.protocol

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import dev.webdroid.BrowserConfig
import dev.webdroid.BrowserException
import dev.webdroid.ErrorCode
import dev.webdroid.browserError
import dev.webdroid.core.PageSession
import dev.webdroid.core.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

public data class ProtocolTarget(
    public val id: String,
    public val type: String,
    public val title: String,
    public val url: String,
    public val webSocketDebuggerUrl: String?,
    public val socketName: String = "",
)

/**
 * Connects to Chromium's DevTools Unix domain socket (LocalSocket) in Android's
 * abstract namespace and discovers available page debugging targets using raw HTTP framing.
 */
internal class ProtocolTargetDiscovery(
    private val session: PageSession,
    private val config: BrowserConfig,
) {

    /**
     * Attempts to discover DevTools page targets by probing socket candidate names.
     *
     * Order of socket candidate names:
     * 1. Primary: `webview_devtools_remote_<pid>`
     * 2. Fallback 1: `webview_devtools_remote`
     * 3. Fallback 2: `chrome_devtools_remote`
     *
     * @return [List<ProtocolTarget>] matching available page targets.
     * @throws BrowserException [ErrorCode.UNSUPPORTED] if protocol debugging is disabled or socket is unreachable.
     */
    suspend fun discoverTargets(): List<ProtocolTarget> = session.runInState(SessionState.Operating) {
        if (!config.enableProtocolBackend) {
            throw browserError(
                ErrorCode.UNSUPPORTED,
                "Protocol backend is disabled in BrowserConfig; enableProtocolBackend must be true",
            )
        }

        withContext(Dispatchers.IO) {
            var lastException: Throwable? = null

            for (socketName in socketCandidates()) {
                try {
                    val targets = queryTargetsOverSocket(socketName)
                    if (targets.isNotEmpty()) {
                        return@withContext targets
                    }
                } catch (e: Throwable) {
                    lastException = e
                }
            }

            throw browserError(
                ErrorCode.UNSUPPORTED,
                "DevTools control endpoint is unreachable over LocalSocket abstract namespace; " +
                    "ensure WebView.setWebContentsDebuggingEnabled(true) is enabled",
                lastException,
            )
        }
    }

    private fun queryTargetsOverSocket(socketName: String): List<ProtocolTarget> {
        val socket = LocalSocket()
        return try {
            socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
            socket.soTimeout = 5000

            val output: OutputStream = socket.outputStream
            val input: InputStream = socket.inputStream

            val httpRequest = "GET /json/list HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
            output.write(httpRequest.toByteArray(StandardCharsets.UTF_8))
            output.flush()

            parseHttpResponseJson(readHttpResponse(input), socketName)
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Reads one HTTP response, framed by what the server announced.
     *
     * Never reads to end of stream. The DevTools server keeps the connection
     * alive regardless of the `Connection: close` header we send, so a
     * read-until-EOF blocks until the socket timeout fires, the exception is
     * swallowed by the candidate loop, and a reachable endpoint is reported as
     * unreachable. That is exactly how discovery failed while the capability
     * probe said the endpoint was answering.
     */
    private fun readHttpResponse(input: InputStream): String {
        val headers = StringBuilder()
        var state = 0
        while (state < 4) {
            val b = input.read()
            if (b < 0) break
            headers.append(b.toChar())
            state = when {
                b == '\r'.code && state % 2 == 0 -> state + 1
                b == '\n'.code && state % 2 == 1 -> state + 1
                else -> 0
            }
        }

        val headerText = headers.toString()
        val lines = headerText.split("\r\n").filter { it.isNotEmpty() }

        val contentLength = lines
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull()

        val body = when {
            contentLength != null -> {
                val buffer = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(buffer, read, contentLength - read)
                    if (n <= 0) break
                    read += n
                }
                String(buffer, 0, read, StandardCharsets.UTF_8)
            }

            lines.any { it.equals("Transfer-Encoding: chunked", ignoreCase = true) } -> readChunked(input)

            // No framing offered, so the server does intend to close.
            else -> String(input.readBytes(), StandardCharsets.UTF_8)
        }

        return headerText + body
    }

    private fun readChunked(input: InputStream): String {
        val body = StringBuilder()
        while (true) {
            val size = readLine(input).trim().substringBefore(';').toIntOrNull(16) ?: break
            if (size == 0) break
            val chunk = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = input.read(chunk, read, size - read)
                if (n <= 0) break
                read += n
            }
            body.append(String(chunk, 0, read, StandardCharsets.UTF_8))
            readLine(input) // trailing CRLF
        }
        return body.toString()
    }

    private fun readLine(input: InputStream): String {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) line.append(b.toChar())
        }
        return line.toString()
    }

    internal companion object {

        /**
         * Every name the control endpoint may be published under, in the order
         * worth trying.
         *
         * The pid-suffixed name is first because it is the one that connects.
         * Measured on Android 14, WebView 152: the bare name does not answer,
         * and `/proc/net/unix` returns nothing at all to an unprivileged app, so
         * enumeration is a development aid rather than a fallback.
         *
         * This list lives here and is used by both discovery and the capability
         * probe. They previously disagreed — the probe tried only the bare name
         * and therefore reported the protocol backend as absent on hardware
         * where discovery worked.
         */
        fun socketCandidates(): List<String> {
            val pid = Process.myPid()
            val candidates = mutableListOf(
                "webview_devtools_remote_$pid",
                "webview_devtools_remote",
                "chrome_devtools_remote",
            )

            runCatching {
                val procFile = java.io.File("/proc/net/unix")
                if (procFile.exists() && procFile.canRead()) {
                    procFile.useLines { lines ->
                        lines.forEach { line ->
                            val atIdx = line.indexOf('@')
                            if (atIdx != -1) {
                                val name = line.substring(atIdx + 1).trim().takeWhile { !it.isWhitespace() }
                                if ((name.contains("devtools") || name.contains("webview")) && name !in candidates) {
                                    candidates.add(name)
                                }
                            }
                        }
                    }
                }
            }

            return candidates
        }

        /**
         * Whether any candidate name accepts a connection.
         *
         * Connect only: the capability probe needs to know the endpoint exists,
         * not what it is hosting, and a full HTTP exchange on every session
         * start is a cost with no answer attached.
         */
        fun isEndpointReachable(readTimeoutMillis: Int = 1_000): Boolean {
            for (name in socketCandidates()) {
                val socket = LocalSocket()
                try {
                    // Connect first. Setting a timeout on an unbound LocalSocket
                    // throws, which would swallow every candidate and report the
                    // endpoint as absent on a device where it answers.
                    socket.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
                    socket.soTimeout = readTimeoutMillis
                    return true
                } catch (_: Throwable) {
                    // Try the next name.
                } finally {
                    runCatching { socket.close() }
                }
            }
            return false
        }

        fun parseHttpResponseJson(response: String, socketName: String = ""): List<ProtocolTarget> {
            val headerBodySplit = response.split(Regex("\r?\n\r?\n"), limit = 2)
            if (headerBodySplit.isEmpty()) return emptyList()

            val headers = headerBodySplit[0]
            val statusLine = headers.lines().firstOrNull() ?: ""

            if (!statusLine.contains("200")) {
                return emptyList()
            }

            val body = if (headerBodySplit.size > 1) headerBodySplit[1].trim() else ""
            if (body.isEmpty()) return emptyList()

            val jsonArray = try {
                JSONArray(body)
            } catch (e: Exception) {
                return emptyList()
            }

            val targets = mutableListOf<ProtocolTarget>()
            for (i in 0 until jsonArray.length()) {
                val item: JSONObject = jsonArray.optJSONObject(i) ?: continue
                val id = item.optString("id", "")
                val type = item.optString("type", "page")
                val title = item.optString("title", "")
                val url = item.optString("url", "")
                val wsUrl = if (item.has("webSocketDebuggerUrl")) item.optString("webSocketDebuggerUrl") else null

                if (id.isNotEmpty()) {
                    targets.add(
                        ProtocolTarget(
                            id = id,
                            type = type,
                            title = title,
                            url = url,
                            webSocketDebuggerUrl = wsUrl,
                            socketName = socketName,
                        )
                    )
                }
            }

            return targets
        }
    }
}
