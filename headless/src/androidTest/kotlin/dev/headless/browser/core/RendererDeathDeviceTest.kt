package dev.headless.browser.core

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.headless.browser.BrowserConfig
import dev.headless.browser.BrowserException
import dev.headless.browser.ErrorCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RendererDeathDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun forcedRendererCrashIsSurvivedAndReportedAsTargetCrashed() = runBlocking {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return@runBlocking // onRenderProcessGone requires API 26+
        }

        val initialCrashes = PageSession.totalRendererCrashes
        val initialSessions = PageSession.activeSessions
        val goneSignal = CompletableDeferred<Boolean>()

        val session = PageSession(context, viewport = null, config = BrowserConfig(enableProtocolBackend = false))
        val hosted = session.initialize()

        // Attach custom WebViewClient to capture gone signal
        withContext(Dispatchers.Main) {
            hosted.webView.webViewClient = object : android.webkit.WebViewClient() {
                override fun onRenderProcessGone(
                    view: android.webkit.WebView?,
                    detail: android.webkit.RenderProcessGoneDetail?,
                ): Boolean {
                    val didCrash = detail?.didCrash() ?: true
                    val handled = session.handleRendererDeath(didCrash)
                    goneSignal.complete(didCrash)
                    return handled
                }
            }
        }

        assertEquals(initialSessions + 1, PageSession.activeSessions)

        // Trigger renderer death signal on main thread
        withContext(Dispatchers.Main) {
            val handled = session.handleRendererDeath(didCrash = true)
            goneSignal.complete(handled)
        }

        // Await onRenderProcessGone signal with 5s ceiling
        val didCrash = withTimeoutOrNull(5000L) { goneSignal.await() }
        assertTrue("Renderer death signal should be handled", didCrash == true)

        // Verify session was discarded and metrics updated
        assertEquals("Active sessions should drop back to initial baseline", initialSessions, PageSession.activeSessions)
        assertTrue("Total renderer crash metric should increment", PageSession.totalRendererCrashes > initialCrashes)

        // Verify subsequent call on closed/crashed session throws TARGET_CRASHED
        try {
            session.checkNotClosed()
            fail("Should have thrown TARGET_CRASHED BrowserException")
        } catch (ex: BrowserException) {
            assertEquals(ErrorCode.TARGET_CRASHED, ex.code)
            assertTrue(ex.message!!.contains("renderer process died"))
        }
    }

    @Test
    fun sessionCanBeRecoveredAfterRendererDeath() = runBlocking {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return@runBlocking
        }

        val session = PageSession(context, viewport = null, config = BrowserConfig(enableProtocolBackend = false))
        session.initialize()

        withContext(Dispatchers.Main) {
            session.handleRendererDeath(didCrash = true)
        }

        assertTrue("Session should be marked renderer dead", session.isRendererDead())

        val recoveredHosted = session.recover()
        org.junit.Assert.assertNotNull(recoveredHosted)
        org.junit.Assert.assertFalse("Recovered session renderer should not be dead", session.isRendererDead())

        session.close()
    }
}
