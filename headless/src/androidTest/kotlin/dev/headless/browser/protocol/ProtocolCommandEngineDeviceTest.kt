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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtocolCommandEngineDeviceTest {

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
    fun liveProtocolCommandEngineBoundDomainsAndUnboundRejectionOnDevice() = runBlocking {
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
                val engine = ProtocolCommandEngine(channel)

                // 1. Bound Domain: Page.enable
                engine.pageEnable()

                // 2. Bound Domain: Runtime.evaluate
                val evalRes = engine.runtimeEvaluate("document.title")
                assertEquals("string", evalRes.type)
                assertTrue("Title should contain Example Domain", (evalRes.value as? String)?.contains("Example Domain") == true)

                // 3. Bound Domain: DOM.getDocument
                val domNode = engine.domGetDocument()
                assertTrue("Root nodeId should be valid", domNode.nodeId != 0)

                // 4. Bound Domain: Network.enable & Console.enable
                engine.networkEnable()
                engine.consoleEnable()

                // 5. Unbound Domain Rejection: Target.getTargets throws UNSUPPORTED
                val ex = assertThrows(BrowserException::class.java) {
                    runBlocking { engine.executeCommand("Target.getTargets") }
                }
                assertEquals(ErrorCode.UNSUPPORTED, ex.code)

                channel.close()
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
