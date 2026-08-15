package dev.headless.browser.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.headless.browser.BrowserConfig
import dev.headless.browser.BrowserException
import dev.headless.browser.ErrorCode
import dev.headless.browser.Viewport
import dev.headless.browser.browserError
import dev.headless.browser.platform.HostedWebView
import dev.headless.browser.platform.OffscreenHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Lifecycle states of a page session.
 *
 * acquire → initialize → navigate → settle → operate → close → release
 */
public enum class SessionState {
    Acquired,
    Initialized,
    Navigating,
    Settling,
    Operating,
    Closed,
}

/**
 * Manages the lifecycle, thread safety, state transitions, and ordered teardown
 * of a single page session.
 *
 * Rules:
 * - Every path ends in [close], including cancellation and exceptions.
 * - Calling any method on a closed session throws [ErrorCode.DETACHED].
 * - Destroying the view is NEVER performed inside a WebView client callback.
 * - Main thread operations are marshalled cleanly to the Looper.Main.
 */
internal class PageSession(
    context: Context,
    val viewport: Viewport?,
    val config: BrowserConfig,
    parentJob: Job? = null,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val host = OffscreenHost(context)
    private val stateRef = AtomicReference(SessionState.Acquired)
    private val isClosed = AtomicBoolean(false)
    private val capabilityProbe = dev.headless.browser.protocol.ProtocolCapabilityProbe(context, config)

    /**
     * Coroutine scope owned by this session.
     * Cancelling parentJob or calling [close] cancels this scope and triggers teardown.
     */
    val sessionJob: Job = SupervisorJob(parentJob).apply {
        invokeOnCompletion { cause ->
            if (cause != null && !isClosed.get()) {
                scheduleTeardown()
            }
        }
    }
    val scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate + sessionJob + CoroutineName("PageSession"))

    private var _hostedWebView: HostedWebView? = null

    val hostedWebView: HostedWebView
        get() {
            checkNotClosed()
            return _hostedWebView ?: throw browserError(ErrorCode.DETACHED, "session is not initialized")
        }

    val state: SessionState
        get() = stateRef.get()

    /**
     * Probes and returns actual capabilities for this session.
     */
    suspend fun capabilities(): dev.headless.browser.Capabilities {
        checkNotClosed()
        return capabilityProbe.probeCapabilities(viewport)
    }

    /**
     * Initializes the session on the main thread and creates the underlying WebView.
     *
     * State transition: [SessionState.Acquired] -> [SessionState.Initialized].
     */
    suspend fun initialize(): HostedWebView {
        checkNotClosed()
        return runCatchingOnMain {
            checkNotClosed()
            checkState(SessionState.Acquired, SessionState.Initialized)
            if (config.enableProtocolBackend) {
                try {
                    android.webkit.WebView.setWebContentsDebuggingEnabled(true)
                } catch (_: Throwable) {}
            }
            val hosted = host.create(viewport)
            _hostedWebView = hosted
            activeSessionCount.incrementAndGet()
            hosted
        }
    }

    /**
     * Guarantees that an operation runs within the active session state.
     *
     * @param targetState optional transition state during execution
     * @param block suspendable operation to execute
     */
    suspend fun <T> runInState(targetState: SessionState, block: suspend CoroutineScope.() -> T): T {
        checkNotClosed()
        val previousState = stateRef.get()
        if (previousState == SessionState.Closed) {
            throw browserError(ErrorCode.DETACHED, "session is closed")
        }
        stateRef.set(targetState)
        return try {
            withContext(scope.coroutineContext) {
                checkNotClosed()
                block()
            }
        } catch (e: CancellationException) {
            scheduleTeardown()
            throw browserError(ErrorCode.CANCELLED, "operation was cancelled", e)
        } catch (e: BrowserException) {
            throw e
        } catch (e: Throwable) {
            throw browserError(ErrorCode.DETACHED, "session operation failed: ${e.message}", e)
        } finally {
            if (stateRef.get() == targetState) {
                stateRef.set(previousState)
            }
        }
    }

    /**
     * Throws [ErrorCode.DETACHED] if the session has been closed or destroyed.
     */
    fun checkNotClosed() {
        if (isClosed.get() || stateRef.get() == SessionState.Closed) {
            throw browserError(ErrorCode.DETACHED, "session is closed")
        }
    }

    private fun checkState(expected: SessionState, next: SessionState) {
        checkNotClosed()
        if (!stateRef.compareAndSet(expected, next)) {
            val current = stateRef.get()
            if (current == SessionState.Closed) {
                throw browserError(ErrorCode.DETACHED, "session is closed")
            }
            throw browserError(
                ErrorCode.DETACHED,
                "invalid session state transition: expected $expected but was $current",
            )
        }
    }

    /**
     * Schedules ordered teardown off the current stack trace if triggered from a callback.
     * Always posts to [mainHandler] so teardown never runs inside a WebView callback stack frame.
     */
    fun scheduleTeardown() {
        if (isClosed.compareAndSet(false, true)) {
            stateRef.set(SessionState.Closed)
            sessionJob.cancel()
            mainHandler.post { performTeardown() }
        }
    }

    /**
     * Synchronously/Suspendably closes the session and tears down resources.
     * Safe to call multiple times (idempotent).
     * Teardown itself is non-cancellable.
     */
    suspend fun close() {
        if (isClosed.compareAndSet(false, true)) {
            stateRef.set(SessionState.Closed)
            sessionJob.cancel()
            withContext(kotlinx.coroutines.NonCancellable) {
                runOnMain { performTeardown() }
            }
        }
    }

    private fun performTeardown() {
        val hosted = _hostedWebView
        _hostedWebView = null
        if (hosted != null) {
            activeSessionCount.decrementAndGet()
            host.destroy(hosted)
        }
    }

    private suspend fun <T> runOnMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }
        return withContext(Dispatchers.Main) {
            block()
        }
    }

    private suspend fun <T> runCatchingOnMain(block: () -> T): T {
        return try {
            runOnMain(block)
        } catch (e: BrowserException) {
            scheduleTeardown()
            throw e
        } catch (e: Throwable) {
            scheduleTeardown()
            throw browserError(ErrorCode.DETACHED, "failed to execute session action on main thread", e)
        }
    }

    companion object {
        private val activeSessionCount = java.util.concurrent.atomic.AtomicInteger(0)

        /**
         * Returns the current number of active initialized sessions.
         */
        val activeSessions: Int
            get() = activeSessionCount.get()
    }
}
