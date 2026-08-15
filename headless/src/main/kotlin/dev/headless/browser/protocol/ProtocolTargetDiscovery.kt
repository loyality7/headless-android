package dev.headless.browser.protocol

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import dev.headless.browser.BrowserConfig
import dev.headless.browser.BrowserException
import dev.headless.browser.ErrorCode
import dev.headless.browser.browserError
import dev.headless.browser.core.PageSession
import dev.headless.browser.core.SessionState
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
            val pid = Process.myPid()
            val socketCandidates = mutableListOf(
                "webview_devtools_remote_$pid",
                "webview_devtools_remote",
                "chrome_devtools_remote",
            )

            // Fallback: socket enumeration from /proc/net/unix
            runCatching {
                val procFile = java.io.File("/proc/net/unix")
                if (procFile.exists() && procFile.canRead()) {
                    procFile.useLines { lines ->
                        lines.forEach { line ->
                            val atIdx = line.indexOf('@')
                            if (atIdx != -1) {
                                val sName = line.substring(atIdx + 1).trim().takeWhile { !it.isWhitespace() }
                                if ((sName.contains("devtools") || sName.contains("webview")) && !socketCandidates.contains(sName)) {
                                    socketCandidates.add(sName)
                                }
                            }
                        }
                    }
                }
            }

            var lastException: Throwable? = null

            for (socketName in socketCandidates) {
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

            val responseBytes = input.readBytes()
            val responseString = String(responseBytes, StandardCharsets.UTF_8)

            parseHttpResponseJson(responseString)
        } finally {
            runCatching { socket.close() }
        }
    }

    internal companion object {
        fun parseHttpResponseJson(response: String): List<ProtocolTarget> {
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
                        )
                    )
                }
            }

            return targets
        }
    }
}
