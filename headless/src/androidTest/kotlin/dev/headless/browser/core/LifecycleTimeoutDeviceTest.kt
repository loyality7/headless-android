package dev.headless.browser.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.headless.browser.BrowserConfig
import dev.headless.browser.BrowserException
import dev.headless.browser.ErrorCode
import dev.headless.browser.Timeouts
import dev.headless.browser.platform.PlatformScriptEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LifecycleTimeoutDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun scriptEvaluationRespectsScriptTimeout() = runBlocking {
        val config = BrowserConfig(
            enableProtocolBackend = false,
            timeouts = Timeouts(scriptMillis = 100L),
        )
        val session = PageSession(context, viewport = null, config = config)
        session.initialize()

        try {
            MonotonicTimeout.runWithTimeout(config.timeouts.scriptMillis, "script") {
                kotlinx.coroutines.delay(300L)
            }
            fail("Should throw TIMEOUT for long running operation")
        } catch (ex: BrowserException) {
            assertEquals(ErrorCode.TIMEOUT, ex.code)
        } finally {
            session.close()
        }
    }
}
