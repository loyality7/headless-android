package dev.headless.browser.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.headless.browser.BrowserConfig
import dev.headless.browser.BrowserException
import dev.headless.browser.ErrorCode
import dev.headless.browser.SessionMetrics
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
public class PageSession(
    context: Context,
    public val viewport: Viewport?,
    public val config: BrowserConfig,
    private val parentJob: Job? = null,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val host = OffscreenHost(context)
    private val stateRef = AtomicReference(SessionState.Acquired)
    private val isClosed = AtomicBoolean(false)
    private val isRendererDead = AtomicBoolean(false)
    private val capabilityProbe = dev.headless.browser.protocol.ProtocolCapabilityProbe(context, config)

    private val startTimeMs = System.currentTimeMillis()
    private val navigationCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val jsEvalCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val jsExecutionTimeMs = java.util.concurrent.atomic.AtomicLong(0)
    private val blockedBytesCount = java.util.concurrent.atomic.AtomicLong(0)

    public fun recordNavigation(): Unit { navigationCount.incrementAndGet() }
    public fun recordJsEvaluation(durationMs: Long): Unit {
        jsEvalCount.incrementAndGet()
        jsExecutionTimeMs.addAndGet(durationMs)
    }
    public fun recordBlockedBytes(bytes: Long): Unit { blockedBytesCount.addAndGet(bytes) }

    /**
     * When this session's page last requested something over the network.
     *
     * Written from the interception callback on a background thread, read by the
     * settle engine. Per session rather than global, so one page's activity
     * cannot make another look busy.
     */
    internal val requestActivity: dev.headless.browser.platform.RequestActivityTracker =
        dev.headless.browser.platform.RequestActivityTracker()

    /**
     * Returns snapshot of locally recorded session metrics.
     */
    public fun metrics(): SessionMetrics {
        val duration = System.currentTimeMillis() - startTimeMs
        return SessionMetrics(
            sessionDurationMs = duration,
            totalNavigations = navigationCount.get(),
            totalJsEvaluations = jsEvalCount.get(),
            totalJsExecutionTimeMs = jsExecutionTimeMs.get(),
            blockedBytes = blockedBytesCount.get(),
            memoryPressureEvents = memoryLimitRefusalCount.get(),
            rendererCrashes = rendererCrashCount.get() + rendererOomCount.get(),
        )
    }

    /**
     * Coroutine scope owned by this session.
     * Cancelling parentJob or calling [close] cancels this scope and triggers teardown.
     */
    public var sessionJob: Job = SupervisorJob(parentJob).apply {
        invokeOnCompletion { cause ->
            if (cause != null && !isClosed.get()) {
                scheduleTeardown()
            }
        }
    }
    public var scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate + sessionJob + CoroutineName("PageSession"))

    private var _hostedWebView: HostedWebView? = null

    public val hostedWebView: HostedWebView
        get() {
            checkNotClosed()
            return _hostedWebView ?: throw browserError(ErrorCode.DETACHED, "session is not initialized")
        }

    public val state: SessionState
        get() = stateRef.get()

    /**
     * Probes and returns actual capabilities for this session.
     */
    public suspend fun capabilities(): dev.headless.browser.Capabilities {
        checkNotClosed()
        return capabilityProbe.probeCapabilities(viewport)
    }

    /**
     * Initializes the session on the main thread and creates the underlying WebView.
     *
     * State transition: [SessionState.Acquired] -> [SessionState.Initialized].
     */
    public suspend fun initialize(): HostedWebView {
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
            hosted.webView.webViewClient = object : android.webkit.WebViewClient() {
                override fun onRenderProcessGone(
                    view: android.webkit.WebView?,
                    detail: android.webkit.RenderProcessGoneDetail?,
                ): Boolean {
                    val didCrash = detail?.didCrash() ?: true
                    return handleRendererDeath(didCrash)
                }
            }
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE)) {
                try {
                    androidx.webkit.WebViewCompat.setWebViewRenderProcessClient(
                        hosted.webView,
                        object : androidx.webkit.WebViewRenderProcessClient() {
                            override fun onRenderProcessUnresponsive(
                                view: android.webkit.WebView,
                                renderer: androidx.webkit.WebViewRenderProcess?,
                            ) {
                                renderer?.terminate()
                            }

                            override fun onRenderProcessResponsive(
                                view: android.webkit.WebView,
                                renderer: androidx.webkit.WebViewRenderProcess?,
                            ) {}
                        }
                    )
                } catch (_: Throwable) {}
            }
            _hostedWebView = hosted
            activeSessionCount.incrementAndGet()
            hosted
        }
    }

    /**
     * Handles renderer termination (crash or OOM kill) from WebView callbacks.
     * Discards the dead view, closes session state, and prevents host app crash.
     *
     * @return true to tell Android the app handled the renderer termination.
     */
    public fun handleRendererDeath(didCrash: Boolean): Boolean {
        if (didCrash) {
            rendererCrashCount.incrementAndGet()
        } else {
            rendererOomCount.incrementAndGet()
        }

        isRendererDead.set(true)
        val hosted = _hostedWebView
        _hostedWebView = null
        if (hosted != null) {
            hosted.destroyed = true
            activeSessionCount.decrementAndGet()
        }

        stateRef.set(SessionState.Closed)
        if (isClosed.compareAndSet(false, true)) {
            sessionJob.cancel()
        }
        return true
    }

    /**
     * Returns true if the WebView render process for this session died or was killed by OS.
     */
    public fun isRendererDead(): Boolean = isRendererDead.get()

    /**
     * Recovers a session after a renderer crash or termination.
     * Recreates the underlying WebView and resets session health state.
     */
    public suspend fun recover(): HostedWebView {
        if (!isRendererDead.get()) {
            val existing = _hostedWebView
            if (existing != null && !existing.destroyed) {
                return existing
            }
        }
        return runCatchingOnMain {
            val hosted = host.create(viewport)
            hosted.webView.webViewClient = object : android.webkit.WebViewClient() {
                override fun onRenderProcessGone(
                    view: android.webkit.WebView?,
                    detail: android.webkit.RenderProcessGoneDetail?,
                ): Boolean {
                    val didCrash = detail?.didCrash() ?: true
                    return handleRendererDeath(didCrash)
                }
            }
            _hostedWebView = hosted
            sessionJob = SupervisorJob(parentJob).apply {
                invokeOnCompletion { cause ->
                    if (cause != null && !isClosed.get()) {
                        scheduleTeardown()
                    }
                }
            }
            scope = CoroutineScope(Dispatchers.Main.immediate + sessionJob + CoroutineName("PageSession"))
            isRendererDead.set(false)
            isClosed.set(false)
            stateRef.set(SessionState.Initialized)
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
    public suspend fun <T> runInState(targetState: SessionState, block: suspend CoroutineScope.() -> T): T {
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
     * Throws [ErrorCode.TARGET_CRASHED] or [ErrorCode.DETACHED] if the session has been closed or destroyed.
     */
    public fun checkNotClosed(): Unit {
        if (isRendererDead.get()) {
            throw browserError(ErrorCode.TARGET_CRASHED, "renderer process died")
        }
        if (isClosed.get() || stateRef.get() == SessionState.Closed) {
            throw browserError(ErrorCode.DETACHED, "session is closed")
        }
        if (dev.headless.browser.platform.MemoryPressureMonitor.isCriticalMemory()) {
            memoryLimitRefusalCount.incrementAndGet()
            throw browserError(ErrorCode.MEMORY_LIMIT, "Refused operation due to critical memory pressure")
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
    public fun scheduleTeardown(): Unit {
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
    public suspend fun close(): Unit {
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
            val causeMsg = e.message ?: e.cause?.message ?: e.javaClass.simpleName
            throw browserError(ErrorCode.DETACHED, "failed to execute session action on main thread: $causeMsg", e)
        }
    }

    public companion object {
        private val activeSessionCount = java.util.concurrent.atomic.AtomicInteger(0)
        private val rendererCrashCount = java.util.concurrent.atomic.AtomicInteger(0)
        private val rendererOomCount = java.util.concurrent.atomic.AtomicInteger(0)
        private val memoryLimitRefusalCount = java.util.concurrent.atomic.AtomicInteger(0)

        /**
         * Returns the current number of active initialized sessions.
         */
        public val activeSessions: Int
            get() = activeSessionCount.get()

        private val ssrfBlockedCount = java.util.concurrent.atomic.AtomicInteger(0)

        /**
         * Total number of renderer process crashes survived since process start.
         */
        public val totalRendererCrashes: Int
            get() = rendererCrashCount.get()

        /**
         * Total number of renderer process OOM kills survived since process start.
         */
        public val totalRendererOoms: Int
            get() = rendererOomCount.get()

        /**
         * Total number of operations refused due to critical memory pressure.
         */
        public val totalMemoryLimitRefusals: Int
            get() = memoryLimitRefusalCount.get()

        /**
         * Total number of navigations or redirects blocked by SSRF rules.
         */
        public val totalSsrfBlocked: Int
            get() = ssrfBlockedCount.get()

        /**
         * Records an SSRF blockage event.
         */
        public fun recordSsrfBlocked(): Unit {
            ssrfBlockedCount.incrementAndGet()
        }
    }
}
