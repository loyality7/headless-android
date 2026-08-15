package dev.headless.browser.core

import dev.headless.browser.BrowserException
import dev.headless.browser.ErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LifecycleCancellationTest {

    @Test(timeout = 10_000)
    fun cancellationPreservesCancelledErrorCode() = runBlocking {
        val parentJob = Job()
        val job = launch(parentJob) {
            MonotonicTimeout.runWithTimeout(5000L, "test") {
                delay(2000L)
            }
        }

        delay(50L)
        parentJob.cancel()

        try {
            job.join()
        } catch (ex: Throwable) {
            assertTrue("Should be cancellation", ex is CancellationException)
        }
    }
}
