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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatformReaderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var site: FixtureSite
    private lateinit var session: PageSession
    private lateinit var navigator: PlatformNavigator
    private lateinit var scriptEngine: PlatformScriptEngine
    private lateinit var reader: PlatformReader

    @Before
    fun setUp() = runBlocking {
        site = FixtureSite()
        session = PageSession(context, null, BrowserConfig())
        session.initialize()
        navigator = PlatformNavigator(session, BrowserConfig())
        scriptEngine = PlatformScriptEngine(session, BrowserConfig())
        reader = PlatformReader(session, scriptEngine, BrowserConfig())
    }

    @After
    fun tearDown() = runBlocking {
        session.close()
        site.close()
    }

    @Test
    fun readsPageTitleTextAndContent() = runBlocking {
        navigator.goto(site.url(Fixture.Static), WaitUntil.Load)
        assertEquals("static", reader.title())
        assertTrue("Text should contain heading", reader.text().contains("static fixture"))
        assertTrue("Content should contain HTML root", reader.content().contains("</html>"))
    }

    @Test
    fun querySelectorReturnsElementAndAttributes() = runBlocking {
        navigator.goto(site.url(Fixture.Static), WaitUntil.Load)
        val heading = reader.querySelector("#heading")
        assertNotNull(heading)
        assertEquals("h1", heading?.tag)
        assertEquals("static fixture", heading?.text)
        assertEquals("heading", heading?.attributes?.get("id"))

        val link = reader.querySelector("a[href='/one']")
        assertNotNull(link)
        assertEquals("a", link?.tag)
        assertEquals("one", link?.text)
        assertEquals("/one", link?.attributes?.get("href"))

        val missing = reader.querySelector("#non-existent")
        assertNull(missing)
    }

    @Test
    fun querySelectorAllReturnsMatchingElements() = runBlocking {
        navigator.goto(site.url(Fixture.Static), WaitUntil.Load)
        val links = reader.querySelectorAll("a")
        assertEquals(2, links.size)
        assertEquals("one", links[0].text)
        assertEquals("two", links[1].text)
    }

    @Test
    fun waitForSelectorReturnsElementAsSoonAsItAppears() = runBlocking {
        navigator.goto(site.url(Fixture.ClientRendered), WaitUntil.Load)
        val heading = reader.waitForSelector("#heading", timeoutMillis = 5000)
        assertEquals("h1", heading.tag)
        assertEquals("rendered by script", heading.text)
    }

    @Test
    fun missingSelectorRaisesSelectorNotFound() = runBlocking {
        navigator.goto(site.url(Fixture.Static), WaitUntil.Load)
        var caught: BrowserException? = null
        try {
            reader.waitForSelector("#missing-element-id", timeoutMillis = 100)
        } catch (ex: BrowserException) {
            caught = ex
        }
        assertNotNull(caught)
        assertEquals(ErrorCode.SELECTOR_NOT_FOUND, caught?.code)
    }

    @Test
    fun readsHugeDomWithinCapWithoutOom() = runBlocking {
        navigator.goto(site.url(Fixture.HugeDom), WaitUntil.Load)
        val text = reader.text()
        val content = reader.content()
        assertNotNull(text)
        assertNotNull(content)
        assertTrue("Text should contain row content", text.contains("row 0"))
    }

    @Test
    fun readsLivePublicWebsiteExampleCom() = runBlocking {
        navigator.goto("https://example.com", WaitUntil.Load)
        val title = reader.title()
        val heading = reader.querySelector("h1")
        val paragraph = reader.querySelector("p")

        assertEquals("Example Domain", title)
        assertEquals("h1", heading?.tag)
        assertEquals("Example Domain", heading?.text)
        assertNotNull(paragraph)
        assertTrue("Paragraph text should be non-empty", paragraph!!.text.isNotBlank())
    }
}
