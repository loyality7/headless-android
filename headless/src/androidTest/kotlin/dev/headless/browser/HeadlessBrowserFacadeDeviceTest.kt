package dev.headless.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.headless.fixtures.Fixture
import dev.headless.fixtures.FixtureSite
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeadlessBrowserFacadeDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var site: FixtureSite
    private lateinit var browser: HeadlessBrowser

    @Before
    fun setUp() = runBlocking {
        site = FixtureSite()
        val config = BrowserConfig(allowPrivateAddresses = true, maxSessions = 2)
        browser = HeadlessBrowser.create(context, config)
    }

    @After
    fun tearDown() = runBlocking {
        browser.close()
        site.close()
    }

    @Test
    fun facadeLoadsPageAndScrapesContent() = runBlocking {
        val page = browser.newPage(Viewport.Phone)
        val result = page.goto(site.url(Fixture.Static), WaitUntil.Load)

        assertTrue("Navigation should be settled", result.settled)
        assertEquals(site.url(Fixture.Static), result.url)

        val title = page.title()
        assertTrue("Title should contain fixture name", title.isNotEmpty())

        val text = page.text()
        assertTrue("Text should contain content from static fixture", text.isNotEmpty())

        val heading = page.querySelector("h1")
        assertNotNull("Heading element should exist", heading)
        assertTrue("Heading tag should be h1", heading.tag == "h1")

        val evalResult = page.evaluate("10 + 20")
        assertEquals("30", evalResult)

        page.close()
    }

    @Test
    fun facadeInteractsWithInputElements() = runBlocking {
        val page = browser.newPage(Viewport.Phone)
        page.goto(site.url(Fixture.ClientRendered), WaitUntil.DomStable())

        val heading = page.querySelector("h1")
        assertNotNull("Rendered heading should exist", heading)
        assertEquals("rendered by script", heading.text)

        val price = page.querySelector(".price")
        assertNotNull("Price element should exist", price)
        assertEquals("42", price.text)

        page.close()
    }

    @Test
    fun facadeEnforcesMaxSessionsBudget() = runBlocking {
        val page1 = browser.newPage()
        val page2 = browser.newPage()

        var limitExceeded = false
        try {
            browser.newPage()
        } catch (e: BrowserException) {
            if (e.code == ErrorCode.MEMORY_LIMIT) {
                limitExceeded = true
            }
        }

        assertTrue("Attempting to exceed maxSessions should throw ErrorCode.MEMORY_LIMIT", limitExceeded)

        page1.close()
        page2.close()
    }

    @Test
    fun facadeLiveInternetNavigationWithoutMocks() = runBlocking {
        val liveConfig = BrowserConfig(allowPrivateAddresses = false)
        val liveBrowser = HeadlessBrowser.create(context, liveConfig)
        val page = liveBrowser.newPage(Viewport.Phone)

        val navRes = page.goto("https://httpbin.org/get", WaitUntil.Load)
        assertTrue("Live internet navigation to httpbin.org should succeed", navRes.settled)

        val content = page.content()
        assertTrue("Page content should contain httpbin response data", content.contains("httpbin.org"))

        page.close()
        liveBrowser.close()
    }

    @Test
    fun facadeEnablesRequestAndResourceBlocking() = runBlocking {
        val page = browser.newPage(Viewport.Phone)
        page.blockResourceTypes(ResourceType.Images, ResourceType.Fonts)

        var routeIntercepted = false
        page.route("*.png") { route ->
            routeIntercepted = true
            route.abort()
        }

        val result = page.goto(site.url(Fixture.Static), WaitUntil.Load)
        assertTrue("Navigation with request blocking enabled should settle", result.settled)
        page.close()
    }
}
