package dev.headless.browser.protocol

import android.net.LocalSocket
import android.net.LocalSocketAddress
import dev.headless.browser.BrowserConfig
import dev.headless.browser.core.PageSession

/**
 * Opens the protocol backend's connection for a session: discover this
 * process's DevTools targets, match the one this session navigated to, upgrade
 * to a WebSocket, and stand up the CDP channel and command engine over it.
 *
 * Best-effort and silent on failure. The protocol backend is never a
 * dependency — [dev.headless.browser.core.BackendRouter] only consults an
 * engine that connected, and falls back to the platform backend otherwise.
 */
internal object ProtocolConnector {

    /** The open resources for one session's protocol connection. */
    internal class Connection(
        val engine: ProtocolCommandEngine,
        private val channel: CdpChannel,
        private val socket: LocalSocket,
    ) {
        suspend fun close() {
            runCatching { channel.close() }
            runCatching { socket.close() }
        }
    }

    /**
     * Connects to the target matching [currentUrl], or the first page target
     * when nothing matches exactly. Returns null on any failure — discovery
     * unreachable, handshake refused, socket error — rather than throwing:
     * a caller that cannot get the protocol backend still has the platform one.
     */
    suspend fun connect(session: PageSession, config: BrowserConfig, currentUrl: String): Connection? {
        if (!config.enableProtocolBackend) return null

        val targets = try {
            ProtocolTargetDiscovery(session, config).discoverTargets()
        } catch (_: Throwable) {
            return null
        }

        val target = targets.firstOrNull { it.type == "page" && it.url == currentUrl }
            ?: targets.firstOrNull { it.type == "page" }
            ?: return null

        val wsUrl = target.webSocketDebuggerUrl ?: return null
        val path = wsUrl.substringAfter("localhost")
        val socketName = target.socketName.ifEmpty { "webview_devtools_remote" }

        val socket = LocalSocket()
        return try {
            socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS

            val client = WebSocketClient(socket.inputStream, socket.outputStream)
            client.connect(path = path, host = "localhost")

            val channel = CdpChannel(client)
            val engine = ProtocolCommandEngine(channel)
            engine.pageEnable()
            engine.runtimeEnable()

            Connection(engine, channel, socket)
        } catch (_: Throwable) {
            runCatching { socket.close() }
            null
        }
    }

    private const val SOCKET_TIMEOUT_MILLIS = 5_000
}
