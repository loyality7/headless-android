package dev.headless.browser

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.headless.browser.core.PageSession
import dev.headless.browser.platform.PlatformNavigator
import dev.headless.browser.platform.PlatformReader
import dev.headless.browser.platform.PlatformScriptEngine
import dev.headless.browser.protocol.CdpChannel
import dev.headless.browser.protocol.ProtocolCommandEngine
import dev.headless.browser.protocol.ProtocolTargetDiscovery
import dev.headless.browser.protocol.WebSocketClient
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveRealSitesDeviceTest {

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
    fun testLiveHackerNewsNavigationAndExtractionOnDevice() = runBlocking {
        val config = BrowserConfig(enableProtocolBackend = true)
        val session = PageSession(context, Viewport.Phone, config)
        session.initialize()

        val navigator = PlatformNavigator(session, config)
        val scriptEngine = PlatformScriptEngine(session, config)
        val reader = PlatformReader(session, scriptEngine, config)

        try {
            navigator.goto("https://news.ycombinator.com", WaitUntil.Load)
            val title = reader.title()
            assertTrue("Title should contain Hacker News", title.contains("Hacker News"))

            val topStory = reader.querySelector(".titleline > a")
            assertNotNull("Top story link should be found on Hacker News", topStory)
            assertTrue("Top story link text should not be blank", topStory!!.text.isNotBlank())
        } finally {
            session.close()
        }
    }

    @Test
    fun testLiveWikipediaNavigationAndDomQueryOnDevice() = runBlocking {
        val config = BrowserConfig(enableProtocolBackend = true)
        val session = PageSession(context, Viewport.Phone, config)
        session.initialize()

        val navigator = PlatformNavigator(session, config)
        val scriptEngine = PlatformScriptEngine(session, config)
        val reader = PlatformReader(session, scriptEngine, config)

        try {
            navigator.goto("https://en.wikipedia.org/wiki/Main_Page", WaitUntil.Load)
            val title = reader.title()
            assertTrue("Wikipedia title should contain Wikipedia", title.contains("Wikipedia"))

            val heading = reader.querySelector("#mp-welcome")
            assertNotNull("Main page welcome section should exist", heading)
        } finally {
            session.close()
        }
    }

    @Test
    fun testLiveCdpProtocolEngineOnExampleCom() = runBlocking {
        val config = BrowserConfig(enableProtocolBackend = true)
        val session = PageSession(context, Viewport.Phone, config)
        session.initialize()

        val navigator = PlatformNavigator(session, config)
        navigator.goto("https://example.com", WaitUntil.Load)

        val discovery = ProtocolTargetDiscovery(session, config)

        try {
            val targets = discovery.discoverTargets()
            val pageTarget = targets.firstOrNull { it.type == "page" }
            assertNotNull("Page target must be discovered", pageTarget)

            val wsDebuggerUrl = pageTarget?.webSocketDebuggerUrl
            assertNotNull("WebSocket debugger URL must exist", wsDebuggerUrl)

            val path = wsDebuggerUrl!!.substringAfter("localhost")
            val socketName = pageTarget.socketName.ifEmpty { "webview_devtools_remote" }

            val socket = android.net.LocalSocket()
            try {
                socket.connect(android.net.LocalSocketAddress(socketName, android.net.LocalSocketAddress.Namespace.ABSTRACT))
                socket.soTimeout = 5000

                val client = WebSocketClient(socket.inputStream, socket.outputStream)
                client.connect(path = path, host = "localhost")

                val channel = CdpChannel(client)
                val engine = ProtocolCommandEngine(channel)

                // Live CDP domain execution
                engine.pageEnable()
                val evalResult = engine.runtimeEvaluate("document.title")
                assertEquals("string", evalResult.type)
                assertEquals("Example Domain", evalResult.value)

                val domNode = engine.domGetDocument()
                assertTrue("DOM root nodeId should be non-zero", domNode.nodeId != 0)

                channel.close()
            } finally {
                runCatching { socket.close() }
            }
        } catch (ex: BrowserException) {
            // OS SELinux policy socket restriction fail-fast path
            assertEquals(ErrorCode.UNSUPPORTED, ex.code)
        } finally {
            session.close()
        }
    }
}
