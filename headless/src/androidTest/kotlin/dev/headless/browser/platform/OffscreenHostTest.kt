package dev.headless.browser.platform

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.headless.browser.BrowserException
import dev.headless.browser.ErrorCode
import dev.headless.browser.Viewport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The environment every other platform feature runs in.
 *
 * These run on a device because the thing under test is the device's own
 * behaviour: whether a window can be obtained at all, and whether a WebView
 * without one still executes script.
 */
@RunWith(AndroidJUnit4::class)
class OffscreenHostTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var host: OffscreenHost
    private val created = mutableListOf<HostedWebView>()

    @Before
    fun setUp() {
        host = OffscreenHost(context)
    }

    @After
    fun tearDown() {
        onMain { created.forEach { host.destroy(it) } }
        created.clear()
    }

    private fun create(viewport: Viewport? = null): HostedWebView =
        onMain { host.create(viewport) }.also { created += it }

    @Test
    fun reportsTheModeItActuallyObtained() {
        val hosted = create()

        // Either is a correct answer. What must never happen is claiming an
        // attached window on a device that refused one.
        assertTrue(hosted.mode == HostMode.AttachedOverlay || hosted.mode == HostMode.Detached)
        assertEquals(host.availableMode(), hosted.mode)
    }

    @Test
    fun aNullViewportIsOnePixel() {
        val hosted = create(viewport = null)
        assertEquals(1, hosted.width)
        assertEquals(1, hosted.height)
    }

    @Test
    fun aViewportSizedSessionKeepsItsSize() {
        val hosted = create(Viewport(360, 640))
        assertEquals(360, hosted.width)
        assertEquals(640, hosted.height)
    }

    @Test
    fun scriptRunsWhateverTheMode() {
        // The floor this whole design rests on: a WebView with no window still
        // executes script. Measured on Android 14, where a vendor refused the
        // window outright.
        val hosted = create()
        load(hosted.webView, "data:text/html,<h1 id=t>hosted</h1>")

        assertEquals("\"hosted\"", evaluate(hosted.webView, "document.getElementById('t').textContent"))
    }

    @Test
    fun captureIsOnlyClaimedWhenItCanWork() {
        // Drawing produces the page only from an attached view of real size.
        // A one-pixel session must never claim otherwise.
        val onePixel = create(viewport = null)
        assertFalse(onePixel.canCapture)

        val sized = create(Viewport(360, 640))
        assertEquals(sized.mode == HostMode.AttachedOverlay, sized.canCapture)
    }

    @Test
    fun destroyIsIdempotent() {
        val hosted = create()
        onMain { host.destroy(hosted) }
        onMain { host.destroy(hosted) }
        assertTrue(hosted.destroyed)
    }

    @Test
    fun theViewIsNeverReachableFromTheFilesystem() {
        // Page content is hostile, and the settings that matter are the ones a
        // future edit might flip without noticing.
        val hosted = create()
        onMain {
            assertFalse(hosted.webView.settings.allowFileAccess)
            assertFalse(hosted.webView.settings.allowContentAccess)
        }
    }

    @Test
    fun sessionsAreIndependent() {
        val first = create()
        val second = create()
        load(first.webView, "data:text/html,<span id=v>first</span>")
        load(second.webView, "data:text/html,<span id=v>second</span>")

        assertEquals("\"first\"", evaluate(first.webView, "document.getElementById('v').textContent"))
        assertEquals("\"second\"", evaluate(second.webView, "document.getElementById('v').textContent"))
    }

    @Test
    fun aDestroyedSessionDoesNotTakeTheNextOneWithIt() {
        val first = create()
        load(first.webView, "data:text/html,<h1>one</h1>")
        onMain { host.destroy(first) }

        val second = create()
        load(second.webView, "data:text/html,<h1 id=t>two</h1>")
        assertEquals("\"two\"", evaluate(second.webView, "document.getElementById('t').textContent"))
    }

    // ---- helpers ---------------------------------------------------------

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                result = block()
            } catch (t: Throwable) {
                failure = t
            }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun load(webView: WebView, url: String, timeoutMs: Long = 20_000) {
        val finished = java.util.concurrent.CountDownLatch(1)
        onMain {
            webView.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView, loadedUrl: String) = finished.countDown()
            }
            webView.loadUrl(url)
        }
        assertTrue("page did not load within ${timeoutMs}ms", finished.await(timeoutMs, TimeUnit.MILLISECONDS))
    }

    private fun evaluate(webView: WebView, expression: String, timeoutMs: Long = 10_000): String? {
        val answers = ArrayBlockingQueue<String>(1)
        onMain { webView.evaluateJavascript(expression) { answers.offer(it ?: "null") } }
        return answers.poll(timeoutMs, TimeUnit.MILLISECONDS).also {
            assertNotNull("evaluate did not answer within ${timeoutMs}ms", it)
        }
    }
}
