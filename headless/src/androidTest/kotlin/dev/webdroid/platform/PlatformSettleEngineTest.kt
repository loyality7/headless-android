package dev.webdroid.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.webdroid.BrowserConfig
import dev.webdroid.WaitUntil
import dev.webdroid.core.PageSession
import dev.webdroid.fixtures.Fixture
import dev.webdroid.fixtures.FixtureSite
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatformSettleEngineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var site: FixtureSite
    private lateinit var session: PageSession
    private lateinit var navigator: PlatformNavigator
    private lateinit var scriptEngine: PlatformScriptEngine
    private lateinit var settleEngine: PlatformSettleEngine

    @Before
    fun setUp() = runBlocking {
        site = FixtureSite()
        val config = BrowserConfig(allowPrivateAddresses = true)
        session = PageSession(context, null, config)
        session.initialize()
        navigator = PlatformNavigator(session, config)
        scriptEngine = PlatformScriptEngine(session, config)
        settleEngine = PlatformSettleEngine(session, scriptEngine, config)
    }

    @After
    fun tearDown() = runBlocking {
        session.close()
        site.close()
    }

    @Test
    fun clientRenderedFixtureSettlesOnDomStable() = runBlocking {
        navigator.goto(site.url(Fixture.ClientRendered), WaitUntil.Load)
        val settled = settleEngine.settle(WaitUntil.DomStable(300), timeoutMillis = 5000)
        assertTrue("Client rendered fixture should settle after DOM quiet period", settled)

        val heading = scriptEngine.evaluate("document.getElementById('heading')?.textContent")
        assertTrue("Script-rendered heading should exist", heading?.contains("rendered by script") == true)
    }

    @Test
    fun customPredicateWaitsUntilExpressionIsTrue() = runBlocking {
        navigator.goto(site.url(Fixture.ClientRendered), WaitUntil.Load)
        val settled = settleEngine.settle(WaitUntil.Custom("window.__ready === true"), timeoutMillis = 5000)
        assertTrue("Custom predicate should settle when window.__ready becomes true", settled)
    }

    @Test
    fun slowSettlingHitsCeilingIfTimeoutIsTooShort() = runBlocking {
        navigator.goto(site.url(Fixture.SlowSettling), WaitUntil.Load)
        // Fixture.SlowSettling mutates every 100ms for 2000ms.
        // A timeout of 250ms with quietMillis=300 will hit the ceiling.
        val settled = settleEngine.settle(WaitUntil.DomStable(300), timeoutMillis = 250)
        assertFalse("Page that continues mutating should hit ceiling and return false", settled)
    }
}
