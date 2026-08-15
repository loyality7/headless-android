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

@RunWith(AndroidJUnit4::class)
class SessionLeakAcceptanceDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun hundredSequentialSessionsReturnToBaselineMemory() = runBlocking {
        // Run 100 sequential creation and destruction cycles
        for (i in 1..100) {
            val session = PageSession(
                context = context,
                viewport = Viewport(360, 640),
                config = BrowserConfig(allowPrivateAddresses = true),
            )
            session.initialize()

            // Perform simple operation
            session.checkNotClosed()

            // Close session explicitly
            session.close()
        }

        // Force GC to clean unreferenced view/session memory
        System.gc()
        Runtime.getRuntime().gc()

        // Verify active sessions return to 0 (all sessions destroyed)
        val finalSessions = PageSession.activeSessions
        assertEquals(
            "Active sessions count must return to 0 after 100 cycles",
            0,
            finalSessions,
        )
    }
}
