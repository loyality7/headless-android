package dev.headless.browser.protocol

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
import dev.headless.fixtures.Fixture
import dev.headless.fixtures.FixtureSite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtocolTargetDiscoveryTest {

    companion object {
        @org.junit.BeforeClass
        @JvmStatic
        fun initClass() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                WebView.setWebContentsDebuggingEnabled(true)
            }
        }
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var site: FixtureSite

    @Before
    fun setUp() = runBlocking {
        site = FixtureSite()
    }

    @After
    fun tearDown() = runBlocking {
        site.close()
    }

    @Test
    fun parseHttpResponseJsonParsesValidDevToolsTargetArray() {
        val sampleResponse = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=UTF-8\r\nContent-Length: 180\r\n\r\n" +
            "[\n" +
            "  {\n" +
            "    \"description\": \"\",\n" +
            "    \"devtoolsFrontendUrl\": \"/devtools/inspector.html?ws=localhost/devtools/page/123\",\n" +
            "    \"id\": \"123\",\n" +
            "    \"title\": \"Example Page\",\n" +
            "    \"type\": \"page\",\n" +
            "    \"url\": \"https://example.com\",\n" +
            "    \"webSocketDebuggerUrl\": \"ws://localhost/devtools/page/123\"\n" +
            "  }\n" +
            "]"

        val targets = ProtocolTargetDiscovery.parseHttpResponseJson(sampleResponse)

        assertEquals(1, targets.size)
        assertEquals("123", targets[0].id)
        assertEquals("page", targets[0].type)
        assertEquals("Example Page", targets[0].title)
        assertEquals("https://example.com", targets[0].url)
        assertEquals("ws://localhost/devtools/page/123", targets[0].webSocketDebuggerUrl)
    }

    @Test
    fun disabledProtocolBackendRaisesUnsupportedError() = runBlocking {
        val config = BrowserConfig(enableProtocolBackend = false)
        val session = PageSession(context, Viewport.Phone, config)
        session.initialize()

        val discovery = ProtocolTargetDiscovery(session, config)
        val ex = assertThrows(BrowserException::class.java) {
            runBlocking { discovery.discoverTargets() }
        }

        assertEquals(ErrorCode.UNSUPPORTED, ex.code)
        assertTrue("Error should mention protocol backend disabled", ex.message?.contains("Protocol backend is disabled") == true)
        session.close()
    }

    @Test
    fun unreachableEndpointFailsFastWithClearDiagnosisAndPlatformBackendContinuesToServe() = runBlocking {
        val config = BrowserConfig(enableProtocolBackend = true)
        val session = PageSession(context, Viewport.Phone, config)
        session.initialize()

        val navigator = PlatformNavigator(session, config)
        navigator.goto(site.url(Fixture.Static), WaitUntil.Load)

        val discovery = ProtocolTargetDiscovery(session, config)
        
        try {
            val targets = discovery.discoverTargets()
            // If DevTools socket is enabled on the device, targets should contain page targets
            assertTrue("Targets should be non-empty when endpoint is open", targets.isNotEmpty())
        } catch (ex: BrowserException) {
            // Fails fast with clear diagnosis if unreachable
            assertEquals(ErrorCode.UNSUPPORTED, ex.code)
            assertTrue("Message should give clear diagnosis", ex.message?.contains("DevTools control endpoint is unreachable") == true)
        }

        // Platform backend continues to serve normally regardless
        val title = withContext(Dispatchers.Main) { session.hostedWebView.webView.title }
        assertNotNull("Platform backend continues to serve", title)

        session.close()
    }
}
