package dev.headless.browser.core

import dev.headless.browser.SessionMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

public class SessionMetricsUnitTest {

    @Test
    public fun testSessionMetricsDataStructure() {
        val metrics = SessionMetrics(
            sessionDurationMs = 1500L,
            totalNavigations = 3,
            totalJsEvaluations = 10,
            totalJsExecutionTimeMs = 450L,
            blockedBytes = 204800L,
            memoryPressureEvents = 1,
            rendererCrashes = 0,
        )

        assertEquals(1500L, metrics.sessionDurationMs)
        assertEquals(3, metrics.totalNavigations)
        assertEquals(10, metrics.totalJsEvaluations)
        assertEquals(450L, metrics.totalJsExecutionTimeMs)
        assertEquals(204800L, metrics.blockedBytes)
        assertEquals(1, metrics.memoryPressureEvents)
        assertEquals(0, metrics.rendererCrashes)
        assertNotNull(metrics.toString())
    }
}
