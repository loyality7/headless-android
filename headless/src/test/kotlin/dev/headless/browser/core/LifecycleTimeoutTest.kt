package dev.headless.browser.core

import dev.headless.browser.BrowserException
import dev.headless.browser.ErrorCode
import dev.headless.browser.Timeouts
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LifecycleTimeoutTest {

    @Test
    fun stageTimeoutThrowsBrowserExceptionTimeout() = runBlocking {
        try {
            MonotonicTimeout.runWithTimeout(50L, "script") {
                delay(200L)
            }
            fail("Should have thrown TIMEOUT BrowserException")
        } catch (ex: BrowserException) {
            assertEquals(ErrorCode.TIMEOUT, ex.code)
            assertTrue(ex.message!!.contains("Stage 'script' timed out"))
        }
    }

    @Test
    fun remainingMillisDecreasesMonotonically() {
        val startNano = System.nanoTime()
        val totalMillis = 1000L

        val initialRemaining = MonotonicTimeout.remainingMillis(startNano, totalMillis)
        assertTrue(initialRemaining in 1..1000)

        Thread.sleep(50)
        val laterRemaining = MonotonicTimeout.remainingMillis(startNano, totalMillis)
        assertTrue(laterRemaining < initialRemaining)
    }

    @Test
    fun timeoutsConfigDefaultsAreValid() {
        val timeouts = Timeouts()
        assertEquals(30_000L, timeouts.navigationMillis)
        assertEquals(10_000L, timeouts.settleMillis)
        assertEquals(10_000L, timeouts.scriptMillis)
        assertEquals(120_000L, timeouts.totalMillis)
    }
}
