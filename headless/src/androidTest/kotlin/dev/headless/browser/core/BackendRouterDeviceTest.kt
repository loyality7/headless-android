package dev.headless.browser.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.headless.browser.BrowserConfig
import dev.headless.browser.WaitUntil
import dev.headless.browser.platform.PlatformInputEngine
import dev.headless.browser.platform.PlatformNavigator
import dev.headless.browser.platform.PlatformReader
import dev.headless.browser.platform.PlatformRouter
import dev.headless.browser.platform.PlatformScreenshotEngine
import dev.headless.browser.platform.PlatformScriptEngine
import dev.headless.fixtures.Fixture
import dev.headless.fixtures.FixtureSite
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackendRouterDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var site: FixtureSite
    private lateinit var session: PageSession
    private lateinit var navigator: PlatformNavigator
    private lateinit var reader: PlatformReader
    private lateinit var scriptEngine: PlatformScriptEngine
    private lateinit var inputEngine: PlatformInputEngine
    private lateinit var screenshotEngine: PlatformScreenshotEngine
    private lateinit var platformRouter: PlatformRouter
    private lateinit var backendRouter: BackendRouter

    @Before
    fun setUp() = runBlocking {
        site = FixtureSite()
        session = PageSession(context, dev.headless.browser.Viewport.Phone, BrowserConfig(enableProtocolBackend = false, allowPrivateAddresses = true))
        session.initialize()

        platformRouter = PlatformRouter()
        navigator = PlatformNavigator(session, session.config, platformRouter)
        scriptEngine = PlatformScriptEngine(session, session.config)
        reader = PlatformReader(session, scriptEngine, session.config)
        inputEngine = PlatformInputEngine(session, scriptEngine, reader, session.config)
        screenshotEngine = PlatformScreenshotEngine(session, session.config)

        backendRouter = BackendRouter(
            session = session,
            config = session.config,
            platformNavigator = navigator,
            platformReader = reader,
            platformScriptEngine = scriptEngine,
            platformInputEngine = inputEngine,
            platformScreenshotEngine = screenshotEngine,
            platformRouter = platformRouter,
            protocolEngine = null,
        )
    }

    @After
    fun tearDown() = runBlocking {
        session.close()
        site.close()
    }

    @Test
    fun routerServesAllOperationsCleanlyWithPlatformBackend() = runBlocking {
        backendRouter.goto(site.url(Fixture.Static), WaitUntil.Load, timeoutMillis = 15000)

        assertEquals("static", backendRouter.title())
        assertNotNull(backendRouter.content())
        assertNotNull(backendRouter.text())

        val heading = backendRouter.querySelector("#heading")
        assertNotNull(heading)

        val router = backendRouter.routeRequestBlocking()
        assertNotNull("Request blocking router must always be non-null PlatformRouter", router)
    }
}
