package dev.headless.browser.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.headless.browser.BrowserConfig
import dev.headless.browser.BrowserException
import dev.headless.browser.ErrorCode
import dev.headless.browser.WaitUntil
import dev.headless.browser.core.PageSession
import dev.headless.browser.core.SessionRegistry
import dev.headless.browser.platform.PlatformNavigator
import dev.headless.fixtures.Fixture
import dev.headless.fixtures.FixtureSite
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The guard, exercised through the path a caller actually takes.
 *
 * The previous version of this test called [SsrfGuard.validateUri] directly and
 * then incremented the metric by hand before asserting it had incremented. This
 * one navigates, and lets the production code do both.
 */
@RunWith(AndroidJUnit4::class)
class SsrfGuardDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Counters owned by this test, so another class's sessions cannot be read here. */
    private val registry = SessionRegistry()

    @Test
    fun navigatingToAPrivateAddressIsRefusedAndCounted() = runBlocking {
        val before = registry.totalSsrfBlocked
        val session = PageSession(context, viewport = null, config = BrowserConfig(), registry = registry)
        session.initialize()

        try {
            val navigator = PlatformNavigator(session, session.config)

            val thrown = assertThrows(BrowserException::class.java) {
                runBlocking { navigator.goto("http://127.0.0.1:9/status", WaitUntil.Load) }
            }

            assertEquals(ErrorCode.SSRF_BLOCKED, thrown.code)
            assertTrue(
                "the guard must count what it refused, without the test doing it",
                registry.totalSsrfBlocked > before,
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun theFixtureSiteIsReachableOnlyWithExplicitOptIn() = runBlocking {
        FixtureSite(host = "127.0.0.1").use { site ->
            val refused = PageSession(context, viewport = null, config = BrowserConfig(), registry = registry)
            refused.initialize()
            try {
                val navigator = PlatformNavigator(refused, refused.config)
                val thrown = assertThrows(BrowserException::class.java) {
                    runBlocking { navigator.goto(site.url(Fixture.Static), WaitUntil.Load) }
                }
                assertEquals(ErrorCode.SSRF_BLOCKED, thrown.code)
            } finally {
                refused.close()
            }

            val permitted = PageSession(
                context,
                viewport = null,
                config = BrowserConfig(allowPrivateAddresses = true),
            )
            permitted.initialize()
            try {
                val navigator = PlatformNavigator(permitted, permitted.config)
                val result = navigator.goto(site.url(Fixture.Static), WaitUntil.Load)
                assertTrue("the opt-in must actually permit the navigation", result.settled)
            } finally {
                permitted.close()
            }
        }
    }
}
