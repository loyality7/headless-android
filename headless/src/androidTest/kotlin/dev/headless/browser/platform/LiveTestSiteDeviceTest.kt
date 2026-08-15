package dev.headless.browser.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.headless.browser.BrowserConfig
import dev.headless.browser.Viewport
import dev.headless.browser.WaitUntil
import dev.headless.browser.core.PageSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveTestSiteDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var session: PageSession
    private lateinit var navigator: PlatformNavigator
    private lateinit var scriptEngine: PlatformScriptEngine
    private lateinit var reader: PlatformReader
    private lateinit var inputEngine: PlatformInputEngine
    private lateinit var screenshotEngine: PlatformScreenshotEngine

    @Before
    fun setUp() = runBlocking {
        val config = BrowserConfig(enableProtocolBackend = false)
        session = PageSession(context, Viewport.Phone, config)
        session.initialize()
        navigator = PlatformNavigator(session, config)
        scriptEngine = PlatformScriptEngine(session, config)
        reader = PlatformReader(session, scriptEngine, config)
        inputEngine = PlatformInputEngine(session, scriptEngine, reader, config)
        screenshotEngine = PlatformScreenshotEngine(session, config)
    }

    @After
    fun tearDown() = runBlocking {
        session.close()
    }

    @Test
    fun testLiveHerokuappLoginAutomation() = runBlocking {
        // Step 1: Open live HTML test site login page
        navigator.goto("https://the-internet.herokuapp.com/login", WaitUntil.Load)

        val initialTitle = reader.title()
        assertTrue("Page title should mention The Internet", initialTitle.contains("The Internet"))

        // Step 2: Fill credentials via PlatformInputEngine
        inputEngine.type("#username", "tomsmith")
        inputEngine.type("#password", "SuperSecretPassword!")

        // Step 3: Click login button
        inputEngine.click("button[type='submit']")

        // Wait for live server network POST response and settlement
        kotlinx.coroutines.delay(3000)

        // Step 4: Verify logged in message via PlatformReader
        val flashMessage = reader.querySelector("#flash")?.text ?: ""
        assertTrue(
            "Flash message should confirm login success but was: $flashMessage",
            flashMessage.contains("You logged into a secure area!")
        )

        // Step 5: Capture screenshot of the logged-in secure area
        val screenshotBytes = screenshotEngine.screenshot()
        assertNotNull("Screenshot byte array should not be null", screenshotBytes)
        assertTrue("Screenshot should contain encoded bytes", screenshotBytes.isNotEmpty())

        // Step 6: Verify telemetry metrics
        val metrics = session.metrics()
        assertTrue("Metrics should record navigation", metrics.totalNavigations >= 1)
        assertTrue("Metrics should record JS evaluations", metrics.totalJsEvaluations > 0)
    }
}
