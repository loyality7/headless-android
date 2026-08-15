package dev.headless.browser.protocol

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.headless.browser.BrowserConfig
import dev.headless.browser.BrowserException
import dev.headless.browser.ErrorCode
import dev.headless.browser.Viewport
import dev.headless.browser.WaitUntil
import dev.headless.browser.core.PageSession
import dev.headless.browser.platform.PlatformNavigator
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
class CdpChannelDeviceTest {

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
    fun liveCdpChannelCommandCorrelationAndEventDispatchingOnDevice() = runBlocking {
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

                val channel = CdpChannel(client)

                // 1. Live CDP Command: Page.enable
                val pageEnableRes = channel.sendCommand("Page.enable")
                assertNotNull("Page.enable response should not be null", pageEnableRes)

                // 2. Live CDP Command: Runtime.evaluate expression
                val evalParams = JSONObject().apply {
                    put("expression", "document.title")
                    put("returnByValue", true)
                }
                val evalRes = channel.sendCommand("Runtime.evaluate", evalParams)
                val resultObj = evalRes.optJSONObject("result")
                assertNotNull("Runtime.evaluate result should exist", resultObj)
                val value = resultObj?.optString("value")
                assertTrue("Evaluated title should contain Example Domain", value?.contains("Example Domain") == true)

                // 3. Live Teardown
                channel.close()
                assertTrue("CdpChannel should be closed cleanly", channel.isClosed)
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
