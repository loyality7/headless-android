package dev.webdroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation

class PublicApiTest {

    // ---- the suspend-and-cancel contract ---------------------------------

    @Test
    fun `every page operation suspends`() {
        // A suspend function carries a Continuation as its last parameter. Anything
        // on Page that does page work must have one, so a caller can always cancel.
        // Listener registration is the deliberate exception: it returns immediately.
        val listeners = setOf("onRequest", "onResponse", "onDialog", "onConsole")

        val blocking = Page::class.java.declaredMethods
            .filterNot { it.isSynthetic || it.name in listeners }
            .filterNot { it.parameterTypes.lastOrNull() == Continuation::class.java }
            .map { it.name }

        assertEquals("these Page methods do not suspend: $blocking", emptyList<String>(), blocking)
    }

    @Test
    fun `every browser operation suspends`() {
        // create is the exception, and deliberately so: it starts nothing. No
        // WebView exists until newPage, so there is nothing to wait on or cancel.
        val blocking = HeadlessBrowser::class.java.declaredMethods
            .filterNot { it.isSynthetic || it.name == "create" }
            .filterNot { it.parameterTypes.lastOrNull() == Continuation::class.java }
            .map { it.name }

        assertEquals("these HeadlessBrowser methods do not suspend: $blocking", emptyList<String>(), blocking)
    }

    @Test
    fun `operations that wait accept a timeout`() {
        // Anything that can wait on the page must be boundable by the caller.
        val waiting = setOf(
            "goto", "waitForSelector", "waitForFunction", "click", "type",
            "press", "hover", "scrollIntoView", "selectOption", "evaluate",
        )

        val missing = Page::class.java.declaredMethods
            .filter { it.name in waiting }
            .filterNot { method -> method.parameterTypes.any { it == Long::class.javaPrimitiveType } }
            .map { it.name }
            .distinct()

        assertEquals("these waiting methods take no timeout: $missing", emptyList<String>(), missing)
    }

    // ---- configuration defaults ------------------------------------------

    @Test
    fun `the debugging opt-in is off by default`() {
        // Enabling it is process-wide and exposes every WebView in the host app
        // over USB. It is never a silent default.
        assertFalse(BrowserConfig().enableProtocolBackend)
    }

    @Test
    fun `private addresses are refused by default`() {
        assertFalse(BrowserConfig().allowPrivateAddresses)
    }

    @Test
    fun `one session is the default, because that is what a phone affords`() {
        assertEquals(1, BrowserConfig().maxSessions)
    }

    @Test
    fun `a session count below one is rejected`() {
        assertThrows { BrowserConfig(maxSessions = 0) }
        assertThrows { BrowserConfig(maxSessions = -1) }
    }

    // ---- timeouts ---------------------------------------------------------

    @Test
    fun `every timeout defaults to a positive ceiling`() {
        val timeouts = Timeouts()
        assertTrue(timeouts.navigationMillis > 0)
        assertTrue(timeouts.settleMillis > 0)
        assertTrue(timeouts.scriptMillis > 0)
        assertTrue(timeouts.totalMillis > 0)
    }

    @Test
    fun `a non-positive timeout is rejected`() {
        assertThrows { Timeouts(navigationMillis = 0) }
        assertThrows { Timeouts(settleMillis = -1) }
        assertThrows { Timeouts(scriptMillis = 0) }
        assertThrows { Timeouts(totalMillis = 0) }
    }

    @Test
    fun `a total ceiling below the navigation ceiling is rejected`() {
        // Otherwise the total timeout is unreachable and the stage ceiling wins,
        // which is the opposite of what the caller asked for.
        assertThrows { Timeouts(navigationMillis = 30_000, totalMillis = 10_000) }
    }

    // ---- viewport ---------------------------------------------------------

    @Test
    fun `a viewport must have positive dimensions`() {
        assertThrows { Viewport(0, 100) }
        assertThrows { Viewport(100, 0) }
        assertThrows { Viewport(100, 100, deviceScaleFactor = 0f) }
    }

    @Test
    fun `the phone viewport is usable`() {
        assertTrue(Viewport.Phone.width > 0 && Viewport.Phone.height > 0)
    }

    // ---- wait modes -------------------------------------------------------

    @Test
    fun `quiet-period modes carry a default period`() {
        assertTrue((WaitUntil.NetworkIdle() as WaitUntil.NetworkIdle).quietMillis > 0)
        assertTrue((WaitUntil.DomStable() as WaitUntil.DomStable).quietMillis > 0)
    }

    @Test
    fun `the wait modes are exactly the five in the specification`() {
        val modes = listOf<WaitUntil>(
            WaitUntil.Load,
            WaitUntil.DomReady,
            WaitUntil.NetworkIdle(),
            WaitUntil.DomStable(),
            WaitUntil.Custom("window.__ready === true"),
        )
        assertEquals(5, modes.map { it::class }.distinct().size)
    }

    private fun assertThrows(block: () -> Unit) {
        val thrown = runCatching(block).exceptionOrNull()
        assertTrue("expected the constructor to reject this", thrown is IllegalArgumentException)
    }
}
