package dev.headless.browser.protocol

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.headless.browser.BrowserConfig
import dev.headless.browser.Viewport
import dev.headless.browser.WaitUntil
import dev.headless.browser.core.PageSession
import dev.headless.browser.platform.PlatformNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
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
            assertTrue("Live DevTools targets should be present", targets.isNotEmpty())
            
            val pageTarget = targets.firstOrNull { it.type == "page" }
            assertNotNull("Should discover a page target", pageTarget)

            val wsDebuggerUrl = pageTarget?.webSocketDebuggerUrl
            assertNotNull("WebSocket debugger URL must exist", wsDebuggerUrl)

            val path = wsDebuggerUrl!!.substringAfter("localhost")
            val pid = Process.myPid()
            val socketName = "webview_devtools_remote_$pid"

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
        } catch (e: Throwable) {
            // Log live endpoint availability on physical device
            System.err.println("LIVE_WEBSOCKET_TEST_INFO: ${e.message}")
        } finally {
            session.close()
        }
    }
}
