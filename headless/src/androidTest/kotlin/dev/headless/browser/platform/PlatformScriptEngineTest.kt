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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatformScriptEngineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var site: FixtureSite
    private lateinit var session: PageSession
    private lateinit var navigator: PlatformNavigator
    private lateinit var scriptEngine: PlatformScriptEngine

    @Before
    fun setUp() = runBlocking {
        site = FixtureSite()
        val config = BrowserConfig(allowPrivateAddresses = true)
        session = PageSession(context, null, config)
        session.initialize()
        navigator = PlatformNavigator(session, config)
        scriptEngine = PlatformScriptEngine(session, config)
    }

    @After
    fun tearDown() = runBlocking {
        session.close()
        site.close()
    }

    @Test
    fun evaluatesExpressionAndReturnsResult() = runBlocking {
        navigator.goto(site.url(Fixture.Static), WaitUntil.Load)
        val result = scriptEngine.evaluate("1 + 1")
        assertEquals("2", result)
    }

    @Test
    fun truncatesOversizedScriptOutput() = runBlocking {
        navigator.goto(site.url(Fixture.Static), WaitUntil.Load)
        val result = scriptEngine.evaluate("'a'.repeat(2000000)")
        assertNotNull(result)
        assertTrue("Expected truncated output, length was ${result?.length}", result!!.contains("[truncated"))
        assertTrue("Output should not exceed cap + suffix", result.length < 1_001_000)
    }

    @Test
    fun scriptTimeoutThrowsTimeoutException() = runBlocking {
        navigator.goto(site.url(Fixture.Static), WaitUntil.Load)
        val ex = assertThrows(BrowserException::class.java) {
            runBlocking {
                // Force 1ms timeout ceiling on a 500ms delay script
                scriptEngine.evaluate("var start = Date.now(); while(Date.now() - start < 500) {}; 'done'", timeoutMillis = 1)
            }
        }
        assertEquals(ErrorCode.TIMEOUT, ex.code)
    }

    @Test
    fun addInitScriptExecutesBeforePageScripts() = runBlocking {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            scriptEngine.addInitScript("window.__init_test = 'injected_before_page';")
            navigator.goto(site.url(Fixture.ClientRendered), WaitUntil.Load)
            val result = scriptEngine.evaluate("window.__init_test")
            assertEquals("\"injected_before_page\"", result)
        } else {
            val ex = assertThrows(BrowserException::class.java) {
                runBlocking { scriptEngine.addInitScript("window.x = 1;") }
            }
            assertEquals(ErrorCode.UNSUPPORTED, ex.code)
        }
    }
}
