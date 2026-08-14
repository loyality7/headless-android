package dev.headless.probe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Probe sections: does the session cycle leak, does an attached-offscreen view
 * behave differently from a detached one, and does drawing the view produce the
 * rendered page?
 *
 * These are measurements. The assertions are deliberately loose — the numbers in
 * the log are the deliverable, not a pass mark.
 */
@RunWith(AndroidJUnit4::class)
class LifecycleAndMemoryTest {

    private val cycles = 100

    @Test
    fun hundredCyclesReturnToBaseline() = withHost { host ->
        // One warm cycle first: the WebView provider loads once and never unloads.
        cycle(host)
        Runtime.getRuntime().gc()
        val baseline = pssKb()
        record("memory.baselineKb", baseline)

        repeat(cycles) { i ->
            cycle(host)
            if (i % 10 == 0) record("memory.cycle$i.pssKb", pssKb())
        }

        Runtime.getRuntime().gc()
        val after = pssKb()
        record("memory.afterKb", after)
        record("memory.growthKb", after - baseline)
        record("memory.growthPerCycleKb", (after - baseline) / cycles)

        // Not the acceptance gate — that lives in the library. This catches a runaway leak.
        assertTrue(
            "grew ${after - baseline}KB over $cycles cycles",
            after - baseline < 50_000
        )
    }

    /** D3's premise, measured rather than assumed. */
    @Test
    fun attachedVersusDetached() = withHost { host ->
        val attached = onMain { host.addWebView(360, 640) }
        host.load(attached, TIMER_PAGE)
        record("attached.timerTicks", tickCount(attached))
        record("attached.rafFrames", rafCount(attached))
        record("attached.drawNonBlank", drawIsNonBlank(attached, 360, 640))
        onMain { host.destroyWebView(attached) }

        val detached = onMain {
            WebView(host).apply {
                settings.javaScriptEnabled = true
                measure(360, 640)
                layout(0, 0, 360, 640)
            }
        }
        host.load(detached, TIMER_PAGE)
        record("detached.timerTicks", tickCount(detached))
        record("detached.rafFrames", rafCount(detached))
        record("detached.drawNonBlank", drawIsNonBlank(detached, 360, 640))
        onMain { host.destroyWebView(detached) }
    }

    /** Screenshots come from the view, never from the protocol — N10. */
    @Test
    fun viewportSizedViewDrawsThePage() = withHost { host ->
        val webView = onMain { host.addWebView(360, 640) }
        host.load(webView, "data:text/html,<body style='background:%23ff0000'>")
        assertTrue("bitmap was blank", drawIsNonBlank(webView, 360, 640))

        val onePixel = onMain { host.addWebView(1, 1) }
        host.load(onePixel, "data:text/html,<body style='background:%23ff0000'>")
        record("capture.onePixelNonBlank", drawIsNonBlank(onePixel, 1, 1))

        onMain { host.destroyWebView(webView) }
        onMain { host.destroyWebView(onePixel) }
    }

    private fun cycle(host: HostActivity) {
        val webView = onMain { host.addWebView(1, 1) }
        host.load(webView, "data:text/html,<h1>cycle</h1>")
        onMain { host.destroyWebView(webView) }
    }

    private fun tickCount(webView: WebView): Int = countAfterOneSecond(webView, "__ticks")

    private fun rafCount(webView: WebView): Int = countAfterOneSecond(webView, "__frames")

    private fun countAfterOneSecond(webView: WebView, variable: String): Int {
        Thread.sleep(1_000)
        val latch = java.util.concurrent.ArrayBlockingQueue<String>(1)
        onMain { webView.evaluateJavascript("window.$variable") { latch.offer(it ?: "0") } }
        return latch.poll(5, java.util.concurrent.TimeUnit.SECONDS)?.trim('"')?.toIntOrNull() ?: -1
    }

    private fun drawIsNonBlank(webView: WebView, width: Int, height: Int): Boolean = onMain {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        webView.draw(Canvas(bitmap))
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        pixels.any { it != pixels[0] } || pixels[0] != 0
    }

    private companion object {
        /** Counts timer ticks and animation frames, so throttling shows up as a number. */
        val TIMER_PAGE = "data:text/html," + java.net.URLEncoder.encode(
            """
            <script>
              window.__ticks = 0; window.__frames = 0;
              setInterval(() => window.__ticks++, 10);
              (function frame() { window.__frames++; requestAnimationFrame(frame); })();
            </script>
            """.trimIndent(),
            "UTF-8"
        )
    }
}
