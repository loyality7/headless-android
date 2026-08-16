package dev.headless.browser.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.headless.browser.BrowserConfig
import dev.headless.browser.Viewport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The v1 acceptance gate: a hundred sessions, opened and closed, leaving nothing
 * behind.
 *
 * Counts against a registry this test owns. It previously read a process-wide
 * counter, so any session another test class left open was attributed here — the
 * test passed alone and failed in the suite, and the failure could not
 * distinguish a real leak from another test's residue.
 */
@RunWith(AndroidJUnit4::class)
class SessionLeakAcceptanceDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun hundredSequentialSessionsReturnToBaselineMemory() = runBlocking {
        val registry = SessionRegistry()

        // Warmup session to initialize WebView native runtime overhead
        val warmup = PageSession(context, Viewport(360, 640), BrowserConfig(allowPrivateAddresses = true), registry = registry)
        warmup.initialize()
        warmup.close()
        kotlinx.coroutines.delay(200)
        System.gc()

        val baselineMemoryInfo = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(baselineMemoryInfo)
        val baselinePssKb = baselineMemoryInfo.totalPss

        for (i in 1..100) {
            val session = PageSession(
                context = context,
                viewport = Viewport(360, 640),
                config = BrowserConfig(allowPrivateAddresses = true),
                registry = registry,
            )
            session.initialize()
            session.checkNotClosed()
            session.close()
        }

        // Teardown is posted to the main looper, so the last few closes may still
        // be queued when the loop finishes.
        kotlinx.coroutines.delay(500)
        System.gc()
        Runtime.getRuntime().gc()
        kotlinx.coroutines.delay(200)

        assertEquals(
            "every one of the hundred sessions must have been released",
            0,
            registry.activeSessions,
        )

        val finalMemoryInfo = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(finalMemoryInfo)
        val finalPssKb = finalMemoryInfo.totalPss
        val pssGrowthKb = finalPssKb - baselinePssKb
        val perCycleGrowthKb = pssGrowthKb.toDouble() / 100.0

        android.util.Log.i(
            "SessionLeakAcceptanceDeviceTest",
            "100-cycle session run memory metrics: baselinePss=${baselinePssKb}KB, finalPss=${finalPssKb}KB, totalGrowth=${pssGrowthKb}KB, perCycleGrowth=${perCycleGrowthKb}KB",
        )

        // Assert memory growth stays under 20MB across 100 sequential session cycles
        val maxAllowedGrowthKb = 20 * 1024
        org.junit.Assert.assertTrue(
            "Resident memory growth across 100 sessions ($pssGrowthKb KB) must remain under budget ceiling ($maxAllowedGrowthKb KB)",
            pssGrowthKb < maxAllowedGrowthKb,
        )
    }

    @Test
    fun aLeakedSessionIsReflectedInRegistryActiveSessions() = runBlocking {
        val registry = SessionRegistry()
        val session = PageSession(
            context = context,
            viewport = null,
            config = BrowserConfig(),
            registry = registry,
        )
        session.initialize()
        assertEquals("active sessions count must equal 1 when session is opened", 1, registry.activeSessions)

        // Clean up
        session.close()
        assertEquals("active sessions count must return to 0 when session is closed", 0, registry.activeSessions)
    }

    @Test
    fun aRegistryCountsOnlyItsOwnSessions() = runBlocking {
        // The property that makes the gate above trustworthy.
        val mine = SessionRegistry()
        val someoneElses = SessionRegistry()

        val session = PageSession(
            context = context,
            viewport = null,
            config = BrowserConfig(),
            registry = mine,
        )
        session.initialize()

        try {
            assertEquals(1, mine.activeSessions)
            assertEquals("another owner's registry must not see this session", 0, someoneElses.activeSessions)
        } finally {
            session.close()
        }
    }
}
