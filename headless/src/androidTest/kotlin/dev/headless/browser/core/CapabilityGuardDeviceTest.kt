package dev.headless.browser.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.headless.browser.BrowserConfig
import dev.headless.browser.BrowserException
import dev.headless.browser.ErrorCode
import dev.headless.browser.platform.PlatformScreenshotEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapabilityGuardDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun nullViewportSessionRaisesUnsupportedOnScreenshotImmediately() = runBlocking {
        val session = PageSession(context, viewport = null, config = BrowserConfig(enableProtocolBackend = false))
        session.initialize()

        val screenshotEngine = PlatformScreenshotEngine(session, session.config)

        try {
            screenshotEngine.screenshot()
            fail("Null viewport session must raise UNSUPPORTED on screenshot")
        } catch (ex: BrowserException) {
            assertEquals(ErrorCode.UNSUPPORTED, ex.code)
        } finally {
            session.close()
        }
    }
}
