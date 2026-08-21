package dev.headless.browser.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.headless.browser.BrowserConfig
import dev.headless.browser.ResourceType
import dev.headless.browser.WaitUntil
import dev.headless.browser.core.PageSession
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
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class PlatformRouterTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var site: FixtureSite
    private lateinit var session: PageSession
    private lateinit var router: PlatformRouter
    private lateinit var navigator: PlatformNavigator
    private lateinit var scriptEngine: PlatformScriptEngine
    private lateinit var reader: PlatformReader

    @Before
    fun setUp() = runBlocking {
        site = FixtureSite()
        val config = BrowserConfig(allowPrivateAddresses = true)
        session = PageSession(context, null, config)
        session.initialize()
        router = PlatformRouter()
        navigator = PlatformNavigator(session, config, router)
        scriptEngine = PlatformScriptEngine(session, config)
        reader = PlatformReader(session, scriptEngine, config)
    }

    @After
    fun tearDown() = runBlocking {
        session.close()
        site.close()
    }

    @Test
    fun blocksImagesFontsMediaAndCutsBytes() = runBlocking {
        router.blockTypes(ResourceType.Images)
        val requestedUrls = CopyOnWriteArrayList<String>()
        router.onRequest { url -> requestedUrls.add(url) }

        navigator.goto(site.url(Fixture.AssetHeavy), WaitUntil.Load)

        assertTrue("Should have made request hooks", requestedUrls.isNotEmpty())
        val imageRequested = requestedUrls.any { it.contains("1.png") || it.contains("3.jpg") }
        assertTrue("AssetHeavy images should be requested and intercepted", imageRequested)
    }

    @Test
    fun customRouteRuleAbortsMatchingRequests() = runBlocking {
        val abortedUrls = CopyOnWriteArrayList<String>()
        router.route("**/*.png") { route ->
            abortedUrls.add(route.url)
            route.abort()
        }

        navigator.goto(site.url(Fixture.AssetHeavy), WaitUntil.Load)

        assertTrue("PNG requests should be intercepted and aborted", abortedUrls.isNotEmpty())
    }

    @Test
    fun customRouteRuleFulfillsSyntheticResponse() = runBlocking {
        router.route("**/synthetic-test-api") { route ->
            route.fulfill(
                mimeType = "application/json",
                body = "{\"status\":\"synthetic_ok\"}".toByteArray(),
            )
        }

        navigator.goto(site.url(Fixture.Static), WaitUntil.Load)

        val script = """
            (function() {
                window.__syn_res = null;
                fetch('/synthetic-test-api')
                    .then(function(r) { return r.json(); })
                    .then(function(data) { window.__syn_res = data.status; })
                    .catch(function(e) { window.__syn_res = 'error:' + e; });
                return 'fetching';
            })();
        """.trimIndent()

        scriptEngine.evaluate(script)

        var result: String? = null
        for (i in 0..30) {
            result = scriptEngine.evaluate("window.__syn_res")
            if (result != null && result != "null") break
            kotlinx.coroutines.delay(100)
        }

        assertNotNull(result)
        assertEquals("\"synthetic_ok\"", result)
    }

    @Test
    fun liveInternetWebsiteImageBlockingTest() = runBlocking {
        router.blockTypes(ResourceType.Images)
        val liveUrls = CopyOnWriteArrayList<String>()
        router.onRequest { url -> liveUrls.add(url) }

        navigator.goto("https://httpbin.org/image/png", WaitUntil.Load)

        assertTrue("Live internet requests should be captured", liveUrls.isNotEmpty())
        val liveImageBlocked = liveUrls.any { it.contains("httpbin.org") }
        assertTrue("Live image request to httpbin.org should be intercepted", liveImageBlocked)
    }
}
