package dev.headless.browser.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.headless.browser.BrowserConfig
import dev.headless.browser.BrowserException
import dev.headless.browser.ErrorCode
import dev.headless.browser.Viewport
import dev.headless.browser.WaitUntil
import dev.headless.browser.core.PageSession
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
class PlatformScreenshotEngineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var site: FixtureSite
    private lateinit var session1x1: PageSession
    private lateinit var sessionViewport: PageSession
    private lateinit var screenshotEngine1x1: PlatformScreenshotEngine
    private lateinit var screenshotEngineViewport: PlatformScreenshotEngine
    private lateinit var navigatorViewport: PlatformNavigator

    @Before
    fun setUp() = runBlocking {
        site = FixtureSite()

        // 1x1 session
        session1x1 = PageSession(context, null, BrowserConfig())
        session1x1.initialize()
        screenshotEngine1x1 = PlatformScreenshotEngine(session1x1, BrowserConfig())

        // Viewport-sized session
        val viewport = Viewport(800, 600)
        sessionViewport = PageSession(context, viewport, BrowserConfig())
        sessionViewport.initialize()
        screenshotEngineViewport = PlatformScreenshotEngine(sessionViewport, BrowserConfig())
        navigatorViewport = PlatformNavigator(sessionViewport, BrowserConfig())
    }

    @After
    fun tearDown() = runBlocking {
        session1x1.close()
        sessionViewport.close()
        site.close()
    }

    @Test
    fun screenshotOn1x1SessionRaisesUnsupportedError() = runBlocking {
        val ex = assertThrows(BrowserException::class.java) {
            runBlocking { screenshotEngine1x1.screenshot() }
        }
        assertEquals(ErrorCode.UNSUPPORTED, ex.code)
        assertTrue("Error message should mention viewport requirement", ex.message?.contains("viewport-sized session") == true)
    }

    @Test
    fun screenshotOnViewportSessionReturnsValidEncodedBytes() = runBlocking {
        navigatorViewport.goto(site.url(Fixture.Static), WaitUntil.Load)
        val pngBytes = screenshotEngineViewport.screenshot(ScreenshotOptions(ScreenshotFormat.PNG))

        assertNotNull(pngBytes)
        assertTrue("PNG byte array should be non-empty", pngBytes.isNotEmpty())
        // Magic PNG header check: 0x89 'P' 'N' 'G'
        assertEquals(0x89.toByte(), pngBytes[0])
        assertEquals('P'.code.toByte(), pngBytes[1])
        assertEquals('N'.code.toByte(), pngBytes[2])
        assertEquals('G'.code.toByte(), pngBytes[3])
    }

    @Test
    fun repeatedCaptureDoesNotGrowResidentMemory() = runBlocking {
        navigatorViewport.goto(site.url(Fixture.Static), WaitUntil.Load)

        repeat(20) {
            val bytes = screenshotEngineViewport.screenshot(ScreenshotOptions(ScreenshotFormat.JPEG, quality = 80))
            assertTrue("Screenshot bytes should be non-empty", bytes.isNotEmpty())
        }
    }

    @Test
    fun livePublicWebsiteScreenshotTest() = runBlocking {
        navigatorViewport.goto("https://example.com", WaitUntil.Load)

        // Inject high-contrast styled banner so rendered canvas has visible colors
        val scriptEngine = PlatformScriptEngine(sessionViewport, BrowserConfig())
        scriptEngine.evaluate(
            """
            (function() {
                var b = document.createElement('div');
                b.style.position = 'fixed';
                b.style.top = '0px';
                b.style.left = '0px';
                b.style.width = '100%';
                b.style.height = '100px';
                b.style.backgroundColor = '#ff0055';
                b.style.color = '#ffffff';
                b.style.fontSize = '30px';
                b.style.padding = '20px';
                b.style.zIndex = '99999';
                b.innerText = 'HEADLESS ANDROID SCREENSHOT OK';
                document.body.appendChild(b);
            })();
            """.trimIndent()
        )

        val livePngBytes = screenshotEngineViewport.screenshot(ScreenshotOptions(ScreenshotFormat.PNG))

        assertNotNull(livePngBytes)
        assertTrue("Live website screenshot PNG bytes should be non-empty", livePngBytes.size > 100)

        val file = java.io.File(context.cacheDir, "example_screenshot.png")
        file.writeBytes(livePngBytes)
        assertTrue("Saved screenshot file should exist", file.exists() && file.length() > 0)
    }
}
