package dev.webdroid.core

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.webdroid.BrowserConfig
import dev.webdroid.BrowserException
import dev.webdroid.ErrorCode
import dev.webdroid.Viewport
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PageSessionInstrumentationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun initializesAndDestroysCleanly() = runBlocking {
        val session = PageSession(context, Viewport(360, 640), BrowserConfig())
        val hosted = session.initialize()
        assertNotNull(hosted.webView)
        assertFalse(hosted.destroyed)
        assertEquals(SessionState.Initialized, session.state)

        session.close()
        assertEquals(SessionState.Closed, session.state)
        assertTrue(hosted.destroyed)
    }

    @Test
    fun callingMethodsAfterCloseThrowsDetached() = runBlocking {
        val session = PageSession(context, null, BrowserConfig())
        session.initialize()
        session.close()

        val ex = assertThrows(BrowserException::class.java) {
            session.checkNotClosed()
        }
        assertEquals(ErrorCode.DETACHED, ex.code)

        val ex2 = assertThrows(BrowserException::class.java) {
            runBlocking { session.hostedWebView }
        }
        assertEquals(ErrorCode.DETACHED, ex2.code)
    }

    @Test
    fun exceptionMidSessionReleasesView() = runBlocking {
        val session = PageSession(context, Viewport(200, 200), BrowserConfig())
        val hosted = session.initialize()

        assertThrows(BrowserException::class.java) {
            runBlocking {
                session.runInState(SessionState.Operating) {
                    throw IllegalStateException("simulated error during session operation")
                }
            }
        }

        // Mid-session failure should schedule teardown or close the session safely
        session.close()
        assertTrue(hosted.destroyed)
        assertEquals(SessionState.Closed, session.state)
    }

    @Test
    fun destroyIsNeverInvokedDirectlyInsideClientCallback() {
        val latch = CountDownLatch(1)
        var destroyedSynchronouslyInCallback = false
        var hostedRef: dev.webdroid.platform.HostedWebView? = null

        runBlocking {
            val session = PageSession(context, null, BrowserConfig())
            val hosted = session.initialize()
            hostedRef = hosted

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val originalClient = hosted.webView.webViewClient
                hosted.webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        session.scheduleTeardown()
                        // Check immediately inside callback stack frame:
                        if (hosted.destroyed) {
                            destroyedSynchronouslyInCallback = true
                        }
                        latch.countDown()
                    }
                }
                hosted.webView.loadDataWithBaseURL("https://example.com", "<html><body><h1>test</h1></body></html>", "text/html", "UTF-8", null)
            }
        }

        assertTrue("onPageFinished callback did not fire", latch.await(15, TimeUnit.SECONDS))
        assertFalse("Destroy was executed synchronously inside client callback", destroyedSynchronouslyInCallback)

        // Wait for main handler loop to process teardown
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue("HostedWebView was not destroyed after handler loop tick", hostedRef?.destroyed == true)
    }

    @Test
    fun noSessionOutlivesTaskThatCreatedIt() = runBlocking {
        val parentJob = Job()
        val session = PageSession(context, Viewport(100, 100), BrowserConfig(), parentJob = parentJob)
        val hosted = session.initialize()

        parentJob.cancel()
        // Allow completion handlers to execute
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        assertTrue(session.sessionJob.isCancelled)
    }
}
