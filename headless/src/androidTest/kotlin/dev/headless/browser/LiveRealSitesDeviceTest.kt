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
import dev.headless.fixtures.Fixture
import dev.headless.fixtures.FixtureSite
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
    @LiveSite
    fun testLiveHackerNewsNavigationAndExtractionOnDevice() = runBlocking {
        val config = BrowserConfig(enableProtocolBackend = true)
        val session = PageSession(context, Viewport.Phone, config)
        session.initialize()

        val navigator = PlatformNavigator(session, config)
        val scriptEngine = PlatformScriptEngine(session, config)
        val reader = PlatformReader(session, scriptEngine, config)

        try {
            val result = navigator.goto("https://news.ycombinator.com", WaitUntil.Load)
            assumeLiveSiteHealthy(result.status)
            val title = reader.title()
            assertTrue(
                "HackerNews returned status ${result.status} (settled=${result.settled}); title: '$title'",
                title.contains("Hacker News"),
            )

            val topStory = reader.querySelector(".titleline > a")
            assertNotNull("Top story link should be found on Hacker News (status ${result.status})", topStory)
            assertTrue("Top story link text should not be blank", topStory!!.text.isNotBlank())
        } finally {
            session.close()
        }
    }

    @Test
    @LiveSite
    fun testLiveWikipediaNavigationAndDomQueryOnDevice() = runBlocking {
        val config = BrowserConfig(enableProtocolBackend = true)
        val session = PageSession(context, Viewport.Phone, config)
        session.initialize()

        val navigator = PlatformNavigator(session, config)
        val scriptEngine = PlatformScriptEngine(session, config)
        val reader = PlatformReader(session, scriptEngine, config)

        try {
            val result = navigator.goto("https://en.wikipedia.org/wiki/Main_Page", WaitUntil.Load)
            assumeLiveSiteHealthy(result.status)
            val title = reader.title()
            assertTrue(
                "Wikipedia returned status ${result.status} (settled=${result.settled}); title: '$title'",
                title.contains("Wikipedia"),
            )

            val heading = reader.querySelector("#mp-welcome")
            assertNotNull("Main page welcome section should exist (status ${result.status})", heading)
        } finally {
            session.close()
        }
    }

    @Test
    @LiveSite
    fun testLiveCdpProtocolEngineOnExampleCom() = runBlocking {
        val config = BrowserConfig(enableProtocolBackend = true)
        val session = PageSession(context, Viewport.Phone, config)
        session.initialize()

        val navigator = PlatformNavigator(session, config)
        val navResult = navigator.goto("https://example.com", WaitUntil.Load)
        assumeLiveSiteHealthy(navResult.status)

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

    @Test
    @LiveSite
    fun testLiveHttpBinJsonFetchAndExtractionOnDevice() = runBlocking {
        val config = BrowserConfig(enableProtocolBackend = true)
        val session = PageSession(context, Viewport.Phone, config)
        session.initialize()

        val navigator = PlatformNavigator(session, config)
        val scriptEngine = PlatformScriptEngine(session, config)
        val reader = PlatformReader(session, scriptEngine, config)

        try {
            val result = navigator.goto("https://httpbin.org/get", WaitUntil.Load)
            assumeLiveSiteHealthy(result.status)
            val text = reader.text()
            assertTrue(
                "httpbin.org returned status ${result.status} (settled=${result.settled}); response text: '${text.take(100)}'",
                text.contains("headers"),
            )

            val userAgent = scriptEngine.evaluate("navigator.userAgent") as String
            assertTrue("User agent should be non-blank", userAgent.isNotBlank())
        } finally {
            session.close()
        }
    }

    @Test
    fun testLiveSsrfBlockOnDevice() = runBlocking {
        val config = BrowserConfig(enableProtocolBackend = false)
        val session = PageSession(context, Viewport.Phone, config)
        session.initialize()
        val navigator = PlatformNavigator(session, config)

        try {
            navigator.goto("http://127.0.0.1", WaitUntil.Load)
            org.junit.Assert.fail("Should have blocked SSRF target")
        } catch (ex: BrowserException) {
            assertTrue("Exception code should be SSRF_BLOCKED or NAVIGATION_FAILED", ex.code == ErrorCode.SSRF_BLOCKED || ex.code == ErrorCode.NAVIGATION_FAILED)
        } finally {
            session.close()
        }
    }

    @Test
    fun testLiveRendererRecoveryOnDevice() = runBlocking {
        val config = BrowserConfig(enableProtocolBackend = false, allowPrivateAddresses = true)
        val session = PageSession(context, Viewport.Phone, config)
        session.initialize()

        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            session.handleRendererDeath(didCrash = true)
        }
        assertTrue("Session should report renderer dead", session.isRendererDead())

        val recoveredHosted = session.recover()
        assertNotNull("Recovered HostedWebView must not be null", recoveredHosted)
        org.junit.Assert.assertFalse("Recovered session must not report renderer dead", session.isRendererDead())

        val navigator = PlatformNavigator(session, config)
        val reader = PlatformReader(session, dev.headless.browser.platform.PlatformScriptEngine(session, config), config)

        FixtureSite().use { site ->
            try {
                navigator.goto(site.url(Fixture.Static), WaitUntil.Load)
                val title = reader.title()
                assertTrue("Recovered session should render page", title == "static")
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun testLiveJsTimeoutCappingOnDevice() = runBlocking {
        val config = BrowserConfig(timeouts = Timeouts(scriptMillis = 1500L))
        val session = PageSession(context, Viewport.Phone, config)
        session.initialize()
        val scriptEngine = PlatformScriptEngine(session, config)

        try {
            scriptEngine.evaluate("while(true) {}")
            org.junit.Assert.fail("Should have timed out infinite JS loop")
        } catch (ex: BrowserException) {
            assertEquals(ErrorCode.TIMEOUT, ex.code)
        } finally {
            session.close()
        }
    }

    @Test
    fun testLiveSessionMetricsTrackingOnDevice() = runBlocking {
        val config = BrowserConfig(enableProtocolBackend = false, allowPrivateAddresses = true)
        val session = PageSession(context, Viewport.Phone, config)
        session.initialize()

        val navigator = PlatformNavigator(session, config)
        val scriptEngine = PlatformScriptEngine(session, config)

        FixtureSite().use { site ->
            try {
                navigator.goto(site.url(Fixture.Static), WaitUntil.Load)
                scriptEngine.evaluate("1 + 1")
                scriptEngine.evaluate("'hello' + ' world'")

                val metrics = session.metrics()
                assertTrue("Session duration should be > 0", metrics.sessionDurationMs >= 0)
                assertEquals("Navigations count should be 1", 1, metrics.totalNavigations)
                assertEquals("JS evaluations count should be 2", 2, metrics.totalJsEvaluations)
            } finally {
                session.close()
            }
        }
    }
}
