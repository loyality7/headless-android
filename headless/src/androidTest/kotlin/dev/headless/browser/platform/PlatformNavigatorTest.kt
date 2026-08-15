package dev.headless.browser.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatformNavigatorTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var site: FixtureSite
    private lateinit var session: PageSession
    private lateinit var navigator: PlatformNavigator

    @Before
    fun setUp() = runBlocking {
        site = FixtureSite()
        val config = BrowserConfig(allowPrivateAddresses = true)
        session = PageSession(context, null, config)
        session.initialize()
        navigator = PlatformNavigator(session, config)
    }

    @After
    fun tearDown() = runBlocking {
        session.close()
        site.close()
    }

    @Test
    fun navigatesToStaticPageAndSettlesOnLoad() = runBlocking {
        val result = navigator.goto(site.url(Fixture.Static), WaitUntil.Load)
        assertTrue(result.settled)
        assertTrue(result.url.contains(Fixture.Static.path))
    }

    @Test
    fun navigatesToStaticPageAndSettlesOnDomReady() = runBlocking {
        val result = navigator.goto(site.url(Fixture.Static), WaitUntil.DomReady)
        assertTrue(result.settled)
        assertTrue(result.url.contains(Fixture.Static.path))
    }

    @Test
    fun returnsUnsettledOnTimeoutWithoutThrowing() = runBlocking {
        // Very small timeout (1ms) to force timeout return
        val result = navigator.goto(site.url(Fixture.SlowSettling), WaitUntil.Load, timeoutMillis = 1)
        assertFalse("Expected settled to be false on timeout", result.settled)
        assertNotNull(result.url)
    }

    @Test
    fun navigationFailureRaisesNavigationFailed() = runBlocking {
        val ex = assertThrows(BrowserException::class.java) {
            runBlocking {
                navigator.goto("http://invalid.domain.that.does.not.exist.local", WaitUntil.Load)
            }
        }
        assertEquals(ErrorCode.NAVIGATION_FAILED, ex.code)
    }

    @Test
    fun followsRedirectsAndReportsFinalUrl() = runBlocking {
        val redirectUrl = "${site.url(Fixture.RedirectChain)}?n=2"
        val result = navigator.goto(redirectUrl, WaitUntil.Load)
        assertTrue(result.settled)
        assertTrue(result.url.contains(Fixture.RedirectChain.path))
    }
}
