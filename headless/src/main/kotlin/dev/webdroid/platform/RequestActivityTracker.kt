package dev.webdroid.platform

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Records when the page last asked for something over the network.
 *
 * `shouldInterceptRequest` fires on a background thread for every resource the
 * page requests — documents, stylesheets, scripts, images, fonts, media, and the
 * fetch and XHR calls a client-rendered page makes after load. That callback is
 * the only network signal the platform backend gets, and it is enough to answer
 * the question the caller is really asking: has this page stopped fetching?
 *
 * What the platform does **not** give is a completion callback per resource, so
 * this deliberately measures quiet rather than pretending to count outstanding
 * requests. "Nothing has been requested for N milliseconds" is a claim the data
 * supports; "zero requests are in flight" is not.
 *
 * Uses [System.nanoTime] so a device clock change cannot make a page look idle.
 */
internal class RequestActivityTracker {

    private val lastRequestNano = AtomicLong(System.nanoTime())
    private val requestCount = AtomicInteger(0)

    /** Total resources requested since this tracker was created. */
    val totalRequests: Int get() = requestCount.get()

    /** Called for every intercepted request, from whichever thread WebView uses. */
    fun recordRequest() {
        lastRequestNano.set(System.nanoTime())
        requestCount.incrementAndGet()
    }

    /** Milliseconds since the page last requested anything. */
    fun quietForMillis(): Long = (System.nanoTime() - lastRequestNano.get()) / 1_000_000L

    /** Whether nothing has been requested for [quietMillis]. */
    fun isQuietFor(quietMillis: Long): Boolean = quietForMillis() >= quietMillis

    /** Resets the window, so a navigation is not judged by the previous page's activity. */
    fun reset() {
        lastRequestNano.set(System.nanoTime())
        requestCount.set(0)
    }
}
