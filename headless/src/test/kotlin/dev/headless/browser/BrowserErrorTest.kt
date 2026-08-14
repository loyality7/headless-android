package dev.headless.browser

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class BrowserErrorTest {

    @Test
    fun `every code is reachable through the mapping or is raised directly`() {
        // The closed set is the contract. If a code is added, this test forces a
        // decision about how callers ever see it.
        val mapped = setOf(
            ErrorCode.TIMEOUT,
            ErrorCode.CANCELLED,
            ErrorCode.NAVIGATION_FAILED,
            ErrorCode.MEMORY_LIMIT,
            ErrorCode.PROTOCOL_ERROR,
        )
        val raisedDirectly = setOf(
            ErrorCode.TARGET_CRASHED,
            ErrorCode.SELECTOR_NOT_FOUND,
            ErrorCode.DETACHED,
            ErrorCode.BLOCKED,
            ErrorCode.SSRF_BLOCKED,
            ErrorCode.UNSUPPORTED,
        )
        assertEquals(ErrorCode.entries.toSet(), mapped + raisedDirectly)
    }

    @Test
    fun `a coroutine timeout maps to TIMEOUT, not CANCELLED`() {
        // TimeoutCancellationException is a CancellationException, so order in the
        // mapping matters. This is the test that catches it being reordered.
        val thrown = runBlocking {
            runCatching { withTimeout(1) { delay(1_000) } }.exceptionOrNull()
        }
        assertTrue(thrown is TimeoutCancellationException)

        val mapped = thrown!!.toBrowserException("navigation")
        assertEquals(ErrorCode.TIMEOUT, mapped.code)
    }

    @Test
    fun `plain cancellation maps to CANCELLED`() {
        val mapped = CancellationException("job cancelled").toBrowserException("navigation")
        assertEquals(ErrorCode.CANCELLED, mapped.code)
    }

    @Test
    fun `transport failures map to NAVIGATION_FAILED`() {
        assertEquals(
            ErrorCode.NAVIGATION_FAILED,
            UnknownHostException("nowhere.invalid").toBrowserException("navigation").code,
        )
        assertEquals(
            ErrorCode.NAVIGATION_FAILED,
            IOException("connection reset").toBrowserException("navigation").code,
        )
    }

    @Test
    fun `a socket timeout maps to TIMEOUT`() {
        assertEquals(
            ErrorCode.TIMEOUT,
            SocketTimeoutException("read timed out").toBrowserException("navigation").code,
        )
    }

    @Test
    fun `out of memory maps to MEMORY_LIMIT`() {
        assertEquals(
            ErrorCode.MEMORY_LIMIT,
            OutOfMemoryError("java heap space").toBrowserException("session").code,
        )
    }

    @Test
    fun `an unrecognised failure falls back to the stated default`() {
        assertEquals(
            ErrorCode.PROTOCOL_ERROR,
            IllegalStateException("something odd").toBrowserException("command").code,
        )
        assertEquals(
            ErrorCode.NAVIGATION_FAILED,
            IllegalStateException("something odd")
                .toBrowserException("navigation", default = ErrorCode.NAVIGATION_FAILED).code,
        )
    }

    @Test
    fun `mapping an existing BrowserException returns it unchanged`() {
        val original = browserError(ErrorCode.DETACHED, "the session is closed")
        assertSame(original, original.toBrowserException("anything"))
    }

    @Test
    fun `messages never leak internals`() {
        val leaky = browserError(
            ErrorCode.PROTOCOL_ERROR,
            "failed at dev.headless.browser.protocol.Session.send on @webview_devtools_remote_1234\n" +
                "\tat java.lang.Thread.run(Thread.java:1012)",
        )
        val message = leaky.message.orEmpty()

        assertFalse("leaked a package name: $message", message.contains("dev.headless.browser"))
        assertFalse("leaked a socket name: $message", message.contains("webview_devtools_remote"))
        assertFalse("leaked a stack frame: $message", message.contains("\tat "))
        assertFalse("kept a newline: $message", message.contains("\n"))
    }

    @Test
    fun `messages are capped`() {
        val long = browserError(ErrorCode.TIMEOUT, "x".repeat(5_000))
        assertTrue(long.message!!.length <= 200)
    }

    @Test
    fun `the cause is preserved for debugging but stays out of the message`() {
        val cause = IOException("connection reset by peer at 10.0.0.1")
        val mapped = cause.toBrowserException("navigation")

        assertSame(cause, mapped.cause)
        assertFalse(mapped.message!!.contains("10.0.0.1"))
    }
}
