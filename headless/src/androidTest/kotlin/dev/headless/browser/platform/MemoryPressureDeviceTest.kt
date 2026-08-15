package dev.headless.browser.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.headless.browser.BrowserConfig
import dev.headless.browser.BrowserException
import dev.headless.browser.ErrorCode
import dev.headless.browser.core.PageSession
import dev.headless.browser.core.SessionPool
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

    private lateinit var host: OffscreenHost
    private lateinit var pool: SessionPool
    private lateinit var session: PageSession

    @Before
    fun setUp() = runBlocking {
        MemoryPressureMonitor.setSimulatedCritical(false)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MemoryPressureMonitor.register(context)
        host = OffscreenHost(context)
        pool = SessionPool(host)
        session = pool.acquire(BrowserConfig())
    }

    @After
    fun tearDown() = runBlocking {
        MemoryPressureMonitor.setSimulatedCritical(false)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MemoryPressureMonitor.unregister(context)
        runCatching { session.close() }
        pool.close()
        host.close()
    }

    @Test
    fun refusesOperationWhenMemoryPressureIsCritical() = runBlocking {
        // Initialize session normally
        session.initialize()

        // Simulate critical memory pressure
        MemoryPressureMonitor.setSimulatedCritical(true)

        val initialRefusals = PageSession.totalMemoryLimitRefusals

        // Attempting to navigate under critical memory pressure must throw MEMORY_LIMIT
        val ex = assertThrows(BrowserException::class.java) {
            runBlocking {
                session.navigate("https://example.com")
            }
        }

        assertEquals(ErrorCode.MEMORY_LIMIT, ex.code)
        assertTrue("Metric totalMemoryLimitRefusals should increment", PageSession.totalMemoryLimitRefusals > initialRefusals)

        // Reset memory pressure and verify session can proceed
        MemoryPressureMonitor.setSimulatedCritical(false)
    }
}
