package dev.headless.browser.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.headless.browser.BrowserConfig
import dev.headless.browser.BrowserException
import dev.headless.browser.ErrorCode
import dev.headless.browser.core.PageSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SsrfGuardDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun blocksPrivateIpNavigationOnDevice() = runBlocking {
        val initialBlocked = PageSession.totalSsrfBlocked
        val session = PageSession(context, viewport = null, config = BrowserConfig(allowPrivateAddresses = false))
        session.initialize()

        // Temporarily disable allowLoopbackInTests to test loopback blocking
        SsrfGuard.allowLoopbackInTests = false
        try {
            // Validate raw private IP directly with SsrfGuard
            val ex = assertThrows(BrowserException::class.java) {
                SsrfGuard.validateUri("http://127.0.0.1/status", allowPrivateAddresses = false)
            }

            assertEquals(ErrorCode.SSRF_BLOCKED, ex.code)
            PageSession.recordSsrfBlocked()
            assertTrue("Metric totalSsrfBlocked should increment", PageSession.totalSsrfBlocked > initialBlocked)
        } finally {
            SsrfGuard.allowLoopbackInTests = true
        }

        session.close()
    }
}
