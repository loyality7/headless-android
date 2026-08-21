package dev.webdroid.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.webdroid.BrowserConfig
import dev.webdroid.ErrorCode
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

    /** Counters owned by this test, so another class's sessions cannot be read here. */
    private val registry = SessionRegistry()

    @Test
    fun cancellingCallerDestroysSessionAndDecrementsActiveCountToZero() = runBlocking {
        val parentJob = Job()
        val initialCount = registry.activeSessions

        val session = PageSession(context, viewport = null, config = BrowserConfig(enableProtocolBackend = false), parentJob = parentJob, registry = registry)
        session.initialize()

        assertEquals(initialCount + 1, registry.activeSessions)

        // Cancel caller parent job mid-operation
        parentJob.cancel()

        // Wait brief moment for main-thread teardown dispatch
        delay(200L)

        assertEquals("Active session count should drop to initial baseline after caller cancellation", initialCount, registry.activeSessions)
        assertTrue("Session should be marked closed after cancellation", session.state == SessionState.Closed)
    }

    @Test
    fun massCancellationCleansUpAllSessionsToZero() = runBlocking {
        val parentJob = Job()
        val initialCount = registry.activeSessions
        val sessions = mutableListOf<PageSession>()

        for (i in 1..5) {
            val session = PageSession(context, viewport = null, config = BrowserConfig(enableProtocolBackend = false), parentJob = parentJob, registry = registry)
            session.initialize()
            sessions.add(session)
        }

        assertEquals(initialCount + 5, registry.activeSessions)

        // Mass cancellation of parent job
        parentJob.cancel()
        delay(300L)

        assertEquals("Mass cancellation should return active session count to baseline", initialCount, registry.activeSessions)
        assertTrue("All sessions should be closed", sessions.all { it.state == SessionState.Closed })
    }
}
