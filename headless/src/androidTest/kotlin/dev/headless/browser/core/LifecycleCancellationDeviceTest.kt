package dev.headless.browser.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.headless.browser.BrowserConfig
import dev.headless.browser.ErrorCode
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LifecycleCancellationDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun cancellingCallerDestroysSessionAndDecrementsActiveCountToZero() = runBlocking {
        val parentJob = Job()
        val initialCount = PageSession.activeSessions

        val session = PageSession(context, viewport = null, config = BrowserConfig(enableProtocolBackend = false), parentJob = parentJob)
        session.initialize()

        assertEquals(initialCount + 1, PageSession.activeSessions)

        // Cancel caller parent job mid-operation
        parentJob.cancel()

        // Wait brief moment for main-thread teardown dispatch
        delay(200L)

        assertEquals("Active session count should drop to initial baseline after caller cancellation", initialCount, PageSession.activeSessions)
        assertTrue("Session should be marked closed after cancellation", session.state == SessionState.Closed)
    }

    @Test
    fun massCancellationCleansUpAllSessionsToZero() = runBlocking {
        val parentJob = Job()
        val initialCount = PageSession.activeSessions
        val sessions = mutableListOf<PageSession>()

        for (i in 1..5) {
            val session = PageSession(context, viewport = null, config = BrowserConfig(enableProtocolBackend = false), parentJob = parentJob)
            session.initialize()
            sessions.add(session)
        }

        assertEquals(initialCount + 5, PageSession.activeSessions)

        // Mass cancellation of parent job
        parentJob.cancel()
        delay(300L)

        assertEquals("Mass cancellation should return active session count to baseline", initialCount, PageSession.activeSessions)
        assertTrue("All sessions should be closed", sessions.all { it.state == SessionState.Closed })
    }
}
