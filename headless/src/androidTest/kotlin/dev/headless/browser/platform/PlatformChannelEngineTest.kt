package dev.headless.browser.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import dev.headless.browser.BrowserConfig
import dev.headless.browser.BrowserException
import dev.headless.browser.ErrorCode
import dev.headless.browser.WaitUntil
import dev.headless.browser.core.PageSession
import dev.headless.fixtures.Fixture
import dev.headless.fixtures.FixtureSite
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatformChannelEngineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var site: FixtureSite
    private lateinit var session: PageSession
    private lateinit var navigator: PlatformNavigator
    private lateinit var scriptEngine: PlatformScriptEngine
    private lateinit var channelEngine: PlatformChannelEngine

    @Before
    fun setUp() = runBlocking {
        site = FixtureSite()
        session = PageSession(context, null, BrowserConfig())
        session.initialize()
        navigator = PlatformNavigator(session, BrowserConfig())
        scriptEngine = PlatformScriptEngine(session, BrowserConfig())
        channelEngine = PlatformChannelEngine(session, BrowserConfig())
    }

    @After
    fun tearDown() = runBlocking {
        session.close()
        site.close()
    }

    @Test
    fun noExposeFunctionLeavesNoInjectedNativeObject() = runBlocking {
        navigator.goto(site.url(Fixture.Static), WaitUntil.Load)
        val result = scriptEngine.evaluate("typeof window.myUnexposedBridge")
        assertEquals("\"undefined\"", result)
    }

    @Test
    fun exposeFunctionTransfersMessagesOverWebMessageListener() = runBlocking {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            val ex = assertThrows(BrowserException::class.java) {
                runBlocking { channelEngine.exposeFunction("echoBridge") { it } }
            }
            assertEquals(ErrorCode.UNSUPPORTED, ex.code)
            return@runBlocking
        }

        channelEngine.exposeFunction("echoBridge", setOf("*")) { input ->
            "native_echo: $input"
        }

        navigator.goto(site.url(Fixture.Static), WaitUntil.Load)

        val script = """
            (function() {
                if (!window.echoBridge) return 'no_bridge';
                window.__received = null;
                window.echoBridge.onmessage = function(event) {
                    window.__received = event.data;
                };
                window.echoBridge.postMessage('ping_payload');
                return 'posted';
            })();
        """.trimIndent()

        scriptEngine.evaluate(script)

        // Poll for response on window.__received
        var result: String? = null
        for (i in 0..20) {
            result = scriptEngine.evaluate("window.__received")
            if (result != null && result != "null") break
            kotlinx.coroutines.delay(100)
        }

        assertEquals("\"native_echo: ping_payload\"", result)
    }

    @Test
    fun disallowedOriginRejectsCall() = runBlocking {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return@runBlocking

        channelEngine.exposeFunction("secureBridge", setOf("https://only-trusted.com")) { "allowed" }

        navigator.goto(site.url(Fixture.Static), WaitUntil.Load)

        val script = """
            (function() {
                if (!window.secureBridge) return 'no_bridge';
                window.__sec_received = null;
                window.secureBridge.onmessage = function(event) {
                    window.__sec_received = event.data;
                };
                window.secureBridge.postMessage('test');
                return 'posted';
            })();
        """.trimIndent()

        scriptEngine.evaluate(script)

        var result: String? = null
        for (i in 0..10) {
            result = scriptEngine.evaluate("window.__sec_received")
            if (result != null && result != "null") break
            kotlinx.coroutines.delay(50)
        }

        assertTrue(
            "Disallowed origin should be blocked and yield no message or rejection, got: $result",
            result == null || result == "null" || result.contains("ORIGIN_DISALLOWED"),
        )
    }

    @Test
    fun oversizedPayloadIsRejected() = runBlocking {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return@runBlocking

        channelEngine.exposeFunction("capBridge", setOf("*")) { "ok" }

        navigator.goto(site.url(Fixture.Static), WaitUntil.Load)

        val script = """
            (function() {
                if (!window.capBridge) return 'no_bridge';
                window.__cap_received = null;
                window.capBridge.onmessage = function(event) {
                    window.__cap_received = event.data;
                };
                window.capBridge.postMessage('x'.repeat(600000));
                return 'posted';
            })();
        """.trimIndent()

        scriptEngine.evaluate(script)

        var result: String? = null
        for (i in 0..20) {
            result = scriptEngine.evaluate("window.__cap_received")
            if (result != null && result != "null") break
            kotlinx.coroutines.delay(100)
        }

        assertTrue("Oversized payload should be rejected, got: $result", result?.contains("PAYLOAD_TOO_LARGE") == true)
    }
}
