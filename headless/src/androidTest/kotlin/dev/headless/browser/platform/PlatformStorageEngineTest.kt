package dev.headless.browser.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.headless.browser.BrowserConfig
import dev.headless.browser.Viewport
import dev.headless.browser.WaitUntil
import dev.headless.browser.core.PageSession
import dev.headless.fixtures.Fixture
import dev.headless.fixtures.FixtureSite
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatformStorageEngineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var site: FixtureSite
    private lateinit var session: PageSession
    private lateinit var navigator: PlatformNavigator
    private lateinit var scriptEngine: PlatformScriptEngine
    private lateinit var storageEngine: PlatformStorageEngine

    @Before
    fun setUp() = runBlocking {
        site = FixtureSite()
        session = PageSession(context, Viewport.Phone, BrowserConfig())
        session.initialize()
        navigator = PlatformNavigator(session, BrowserConfig())
        scriptEngine = PlatformScriptEngine(session, BrowserConfig())
        storageEngine = PlatformStorageEngine(session, scriptEngine, BrowserConfig())
    }

    @After
    fun tearDown() = runBlocking {
        session.close()
        site.close()
    }

    @Test
    fun setAndGetCookiesReturnsCookie() = runBlocking {
        val pageUrl = site.url(Fixture.Static)
        navigator.goto(pageUrl, WaitUntil.Load)

        storageEngine.setCookie(pageUrl, "auth_token=secret123; Path=/")
        val cookies = storageEngine.getCookies(pageUrl)

        val tokenCookie = cookies.find { it.name == "auth_token" }
        assertTrue("Cookie auth_token should exist", tokenCookie != null)
        assertEquals("secret123", tokenCookie?.value)
    }

    @Test
    fun cookieSetInOneSessionIsAbsentInNextAfterClearing() = runBlocking {
        val pageUrl = site.url(Fixture.Static)
        navigator.goto(pageUrl, WaitUntil.Load)

        storageEngine.setCookie(pageUrl, "temp_session=active; Path=/")
        storageEngine.clearCookies()

        val cookiesAfter = storageEngine.getCookies(pageUrl)
        val tempCookie = cookiesAfter.find { it.name == "temp_session" }
        assertTrue("Cookie should be absent after clearCookies", tempCookie == null)
    }

    @Test
    fun clearStorageClearsLocalStorageAndSessionStorage() = runBlocking {
        navigator.goto(site.url(Fixture.Static), WaitUntil.Load)

        scriptEngine.evaluate("localStorage.setItem('user_pref', 'dark_mode'); sessionStorage.setItem('tab_id', '42');")

        val localBefore = scriptEngine.evaluate("localStorage.getItem('user_pref')")
        assertEquals("\"dark_mode\"", localBefore)

        storageEngine.clearStorage()

        val localAfter = scriptEngine.evaluate("localStorage.getItem('user_pref')")
        val sessionAfter = scriptEngine.evaluate("sessionStorage.getItem('tab_id')")

        assertEquals("null", localAfter)
        assertEquals("null", sessionAfter)
    }

    @Test
    fun liveInternetCookieAndStorageTest() = runBlocking {
        val liveUrl = "https://example.com"
        navigator.goto(liveUrl, WaitUntil.Load)

        storageEngine.setCookie(liveUrl, "live_test_cookie=hello_live; Path=/")
        val liveCookies = storageEngine.getCookies(liveUrl)

        val testCookie = liveCookies.find { it.name == "live_test_cookie" }
        assertTrue("Live test cookie should be present", testCookie != null)
        assertEquals("hello_live", testCookie?.value)

        storageEngine.clearCookies()
        storageEngine.clearStorage()

        val liveCookiesAfter = storageEngine.getCookies(liveUrl)
        val testCookieAfter = liveCookiesAfter.find { it.name == "live_test_cookie" }
        assertTrue("Live test cookie should be cleared", testCookieAfter == null)
    }
}
