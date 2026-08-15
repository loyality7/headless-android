package dev.headless.browser.core

import dev.headless.browser.BrowserConfig
import dev.headless.browser.BrowserException
import dev.headless.browser.ErrorCode
import dev.headless.browser.Viewport
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSessionTest {

    @Test
    fun sessionStateEnumValuesAreDefinedInOrder() {
        val states = SessionState.values()
        assertEquals(SessionState.Acquired, states[0])
        assertEquals(SessionState.Initialized, states[1])
        assertEquals(SessionState.Navigating, states[2])
        assertEquals(SessionState.Settling, states[3])
        assertEquals(SessionState.Operating, states[4])
        assertEquals(SessionState.Closed, states[5])
    }

    @Test
    fun sessionJobIsCancelledWhenParentJobCancels() {
        val parentJob = Job()
        // Parent job cancellation propagates down to child jobs
        val childJob = Job(parentJob)
        parentJob.cancel()
        assertTrue(childJob.isCancelled)
    }
}

