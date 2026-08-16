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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `goto` must honour the wait mode it was given.
 *
 * Before this was wired, `NetworkIdle`, `DomStable` and `Custom` all fell
 * through to the load event and reported `settled = true`. On a client-rendered
 * page that returns a document with no content in it and calls the navigation a
 * success — a confident wrong answer, which is worse than a failure.
 *
 * The load-versus-stable pair below is the whole point: same page, two modes,
 * and the difference has to be visible.
 */
@RunWith(AndroidJUnit4::class)
class NavigationSettleDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val config = BrowserConfig(allowPrivateAddresses = true)

    private lateinit var site: FixtureSite
    private lateinit var session: PageSession
    private lateinit var navigator: PlatformNavigator
    private lateinit var reader: PlatformReader

    @Before
    fun setUp() = runBlocking {
        site = FixtureSite(host = "127.0.0.1")
        session = PageSession(context, Viewport.Phone, config)
        session.initialize()
        navigator = PlatformNavigator(session, config)
        val script = PlatformScriptEngine(session, config)
        reader = PlatformReader(session, script, config)
    }

    @After
    fun tearDown() = runBlocking {
        session.close()
        site.close()
    }

    @Test
    fun domStableWaitsForContentThatLoadDoesNotSeeYet() = runBlocking {
        // The fixture populates #app 300ms after load, so a load-event wait
        // observes an empty container and a DomStable wait must not.
        val onLoad = navigator.goto(site.url(Fixture.ClientRendered), WaitUntil.Load)
        assertTrue(onLoad.settled)
        val textAtLoad = reader.text()

        val onStable = navigator.goto(site.url(Fixture.ClientRendered), WaitUntil.DomStable(300))
        assertTrue("DomStable should have settled within the ceiling", onStable.settled)
        val textWhenStable = reader.text()

        assertFalse(
            "the load event should fire before the script has rendered anything",
            textAtLoad.contains("rendered by script"),
        )
        assertTrue(
            "DomStable must wait for the content the load event does not see, got: $textWhenStable",
            textWhenStable.contains("rendered by script"),
        )
    }

    @Test
    fun customPredicateIsWaitedFor() = runBlocking {
        // The slow-settling fixture mutates for about two seconds and only then
        // sets __ready. A mode that fell through to load would return long before.
        val result = navigator.goto(
            site.url(Fixture.SlowSettling),
            WaitUntil.Custom("window.__ready === true"),
            timeoutMillis = 15_000,
        )

        assertTrue("the predicate should have been satisfied", result.settled)
        assertTrue(
            "the page marks itself settled only after it stops mutating",
            reader.text().contains("row 20"),
        )
    }

    @Test
    fun aCeilingReachedIsReportedAsUnsettledRatherThanAsSuccess() = runBlocking {
        // A predicate that never becomes true must produce settled = false, and
        // must still return whatever the page had.
        val result = navigator.goto(
            site.url(Fixture.SlowSettling),
            WaitUntil.Custom("window.__never_set_by_anything === true"),
            timeoutMillis = 3_000,
        )

        assertFalse("an unmet predicate must not be reported as settled", result.settled)
        assertTrue("partial content is still returned", result.url.isNotEmpty())
    }

    @Test
    fun networkIdleSettlesOnAPageThatStopsRequesting() = runBlocking {
        val result = navigator.goto(site.url(Fixture.Static), WaitUntil.NetworkIdle(300))
        assertTrue(result.settled)
        assertTrue(reader.text().contains("static fixture"))
    }

    @Test
    fun networkIdleObservesRealRequestsRatherThanSleeping() = runBlocking {
        // The asset-heavy fixture pulls a stylesheet, four images, a font, a
        // video and a script. If NetworkIdle were still a fixed sleep it would
        // return after exactly the quiet period having observed nothing; the
        // request count is what proves it is reading a real signal.
        val requestsBefore = session.requestActivity.totalRequests
        val result = navigator.goto(site.url(Fixture.AssetHeavy), WaitUntil.NetworkIdle(400))

        assertTrue("the page should have settled", result.settled)
        assertTrue(
            "the interception callback must have fed the tracker, saw " +
                "${session.requestActivity.totalRequests} requests",
            session.requestActivity.totalRequests > requestsBefore,
        )
        assertTrue(
            "settling must not be declared while the page is still requesting",
            session.requestActivity.isQuietFor(400),
        )
    }

    @Test
    fun eachNavigationGetsAFreshQuietWindow() = runBlocking {
        navigator.goto(site.url(Fixture.AssetHeavy), WaitUntil.Load)
        val afterFirst = session.requestActivity.totalRequests
        assertTrue("the first page should have requested resources", afterFirst > 0)

        navigator.goto(site.url(Fixture.Static), WaitUntil.Load)

        assertTrue(
            "the counter resets per navigation so one page's activity cannot " +
                "describe another, saw ${session.requestActivity.totalRequests}",
            session.requestActivity.totalRequests < afterFirst,
        )
    }

    @Test
    fun loadAndDomReadyStillBehaveAsBefore() = runBlocking {
        assertTrue(navigator.goto(site.url(Fixture.Static), WaitUntil.Load).settled)
        assertTrue(navigator.goto(site.url(Fixture.Static), WaitUntil.DomReady).settled)
    }
}
