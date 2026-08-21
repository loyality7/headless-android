package dev.webdroid.protocol

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.webdroid.BrowserConfig
import dev.webdroid.BrowserException
import dev.webdroid.ErrorCode
import dev.webdroid.Viewport
import dev.webdroid.WaitUntil
import dev.webdroid.core.PageSession
import dev.webdroid.platform.PlatformNavigator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebSocketClientDeviceTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun enableDebugging() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                WebView.setWebContentsDebuggingEnabled(true)
            }
        }
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun liveWebSocketConnectionAndCDPExchangeOverLocalSocket() = runBlocking {
        val config = BrowserConfig(enableProtocolBackend = true)
        val session = PageSession(context, Viewport.Phone, config)
        session.initialize()

        val navigator = PlatformNavigator(session, config)
        navigator.goto("https://example.com", WaitUntil.Load)

        val discovery = ProtocolTargetDiscovery(session, config)

        try {
            val targets = discovery.discoverTargets()
            val pageTarget = targets.firstOrNull { it.type == "page" }
            assertNotNull("Should discover a page target", pageTarget)

            val wsDebuggerUrl = pageTarget?.webSocketDebuggerUrl
            assertNotNull("WebSocket debugger URL must exist", wsDebuggerUrl)

            val path = wsDebuggerUrl!!.substringAfter("localhost")
            val socketName = pageTarget.socketName.ifEmpty { "webview_devtools_remote" }

            val socket = LocalSocket()
            try {
                socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
                socket.soTimeout = 5000

                val client = WebSocketClient(socket.inputStream, socket.outputStream)
                client.connect(path = path, host = "localhost")

                assertTrue("WebSocket client should be connected to Chromium DevTools", client.isConnected)

                // Send live CDP command: Page.enable
                val cdpRequest = """{"id": 1, "method": "Page.enable"}"""
                client.sendText(cdpRequest)

                // Read live response from Chromium DevTools
                val responseString = withTimeout(5000) {
                    client.textMessages.first()
                }

                assertNotNull("Response from DevTools should not be null", responseString)
                val jsonResponse = JSONObject(responseString)
                assertEquals("Response ID should match request ID", 1, jsonResponse.optInt("id"))

                client.close()
            } finally {
                runCatching { socket.close() }
            }
        } catch (ex: BrowserException) {
            // SELinux / OS level socket restrictions throw UNSUPPORTED cleanly
            assertEquals(ErrorCode.UNSUPPORTED, ex.code)
            assertTrue("Message should detail socket reachability failure", ex.message?.contains("unreachable") == true)
        } finally {
            session.close()
        }
    }
}
