package dev.webdroid.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signal `NetworkIdle` is built on.
 *
 * Worth testing off-device because the logic is the whole claim: a page is quiet
 * when nothing has been requested for a window, and any request restarts it.
 */
class RequestActivityTrackerTest {

    @Test
    fun `a fresh tracker is quiet`() {
        val tracker = RequestActivityTracker()
        Thread.sleep(30)
        assertTrue(tracker.isQuietFor(10))
    }

    @Test
    fun `a request restarts the quiet window`() {
        val tracker = RequestActivityTracker()
        Thread.sleep(40)
        assertTrue("should be quiet before the request", tracker.isQuietFor(30))

        tracker.recordRequest()

        assertFalse("a request must restart the window", tracker.isQuietFor(30))
    }

    @Test
    fun `the window reopens once requests stop`() {
        val tracker = RequestActivityTracker()
        tracker.recordRequest()
        assertFalse(tracker.isQuietFor(50))

        Thread.sleep(70)

        assertTrue("quiet must return once nothing more is requested", tracker.isQuietFor(50))
    }

    @Test
    fun `requests are counted`() {
        val tracker = RequestActivityTracker()
        assertEquals(0, tracker.totalRequests)

        repeat(5) { tracker.recordRequest() }

        assertEquals(5, tracker.totalRequests)
    }

    @Test
    fun `reset clears the count and reopens the window`() {
        val tracker = RequestActivityTracker()
        repeat(3) { tracker.recordRequest() }

        tracker.reset()

        assertEquals(0, tracker.totalRequests)
        assertFalse("reset restarts the window rather than declaring quiet", tracker.isQuietFor(50))
    }

    @Test
    fun `recording from several threads loses nothing`() {
        // The callback fires on whichever thread WebView chooses, and several
        // resources can be requested at once.
        val tracker = RequestActivityTracker()
        val threads = (1..8).map {
            Thread { repeat(100) { tracker.recordRequest() } }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(800, tracker.totalRequests)
    }

    @Test
    fun `quiet duration grows with elapsed time`() {
        val tracker = RequestActivityTracker()
        tracker.recordRequest()
        val first = tracker.quietForMillis()
        Thread.sleep(40)
        val second = tracker.quietForMillis()

        assertTrue("quiet duration should increase, was $first then $second", second > first)
    }
}
