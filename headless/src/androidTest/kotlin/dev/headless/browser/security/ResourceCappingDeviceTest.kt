package dev.headless.browser.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.headless.browser.BrowserConfig
import dev.headless.browser.BrowserException
import dev.headless.browser.ErrorCode
import dev.headless.browser.WaitUntil
import dev.headless.browser.core.PageSession
import dev.headless.browser.platform.PlatformNavigator
import dev.headless.browser.platform.PlatformScriptEngine
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
class ResourceCappingDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var site: FixtureSite
    private lateinit var session: PageSession
    private lateinit var navigator: PlatformNavigator
    private lateinit var scriptEngine: PlatformScriptEngine

    @Before
    fun setUp() {
        runBlocking {
            site = FixtureSite()
            val config = BrowserConfig(allowPrivateAddresses = true)
            session = PageSession(context, viewport = null, config = config)
            session.initialize()
            navigator = PlatformNavigator(session, config)
            scriptEngine = PlatformScriptEngine(session, config)
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            session.close()
            site.close()
        }
    }

    @Test
    fun scriptEvaluationTimesOutUnderScriptTimeoutCeiling() = runBlocking {
        navigator.goto(site.url(Fixture.Static), WaitUntil.Load)
        val ex = assertThrows(BrowserException::class.java) {
            runBlocking {
                // Infinite loop in JS timed out at 100ms
                scriptEngine.evaluate("var s = Date.now(); while(Date.now() - s < 2000) {}; 'done'", timeoutMillis = 100)
            }
        }
        assertEquals(ErrorCode.TIMEOUT, ex.code)
    }

    @Test
    fun scriptOutputIsTruncatedAtOneMegabyteCap() = runBlocking {
        navigator.goto(site.url(Fixture.Static), WaitUntil.Load)
        val result = scriptEngine.evaluate("'x'.repeat(2000000)")
        assertNotNull(result)
        assertTrue("Output should contain truncated suffix", result!!.contains("[truncated"))
        assertTrue("Output length should be bounded around 1MB", result.length < 1_001_000)
    }
}
