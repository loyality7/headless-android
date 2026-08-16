package dev.headless.browser.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.headless.browser.BrowserConfig
import dev.headless.browser.BrowserException
import dev.headless.browser.ErrorCode
import dev.headless.browser.core.PageSession
import dev.headless.browser.core.SessionRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryPressureDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Counters owned by this test, so another class's sessions cannot be read here. */
    private val registry = SessionRegistry()
    private lateinit var session: PageSession

    @Before
    fun setUp() {
        runBlocking {
            MemoryPressureMonitor.setSimulatedCritical(false)
            MemoryPressureMonitor.register(context)
            session = PageSession(context, viewport = null, config = BrowserConfig(), registry = registry)
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            MemoryPressureMonitor.setSimulatedCritical(false)
            MemoryPressureMonitor.unregister(context)
            runCatching { session.close() }
        }
    }

    @Test
    fun refusesOperationWhenMemoryPressureIsCritical() = runBlocking {
        // Initialize session normally
        session.initialize()

        // Simulate critical memory pressure
        MemoryPressureMonitor.setSimulatedCritical(true)

        val initialRefusals = registry.totalMemoryLimitRefusals

        // Attempting checkNotClosed under critical memory pressure must throw MEMORY_LIMIT
        val ex = assertThrows(BrowserException::class.java) {
            session.checkNotClosed()
        }

        assertEquals(ErrorCode.MEMORY_LIMIT, ex.code)
        assertTrue("Metric totalMemoryLimitRefusals should increment", registry.totalMemoryLimitRefusals > initialRefusals)

        // Reset memory pressure
        MemoryPressureMonitor.setSimulatedCritical(false)
    }
}
