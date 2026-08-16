package dev.headless.browser.platform

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.headless.browser.BrowserConfig
import dev.headless.browser.BrowserException
import dev.headless.browser.ErrorCode
import dev.headless.browser.NavigationResult
import dev.headless.browser.WaitUntil
import dev.headless.browser.browserError
import dev.headless.browser.core.PageSession
import dev.headless.browser.core.SessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Handles page navigation via [WebView] for the platform backend.
 *
 * Surfacing load events, managing redirect chains, handling timeouts gracefully,
 * and mapping network/platform errors onto [ErrorCode.NAVIGATION_FAILED].
 */
public class PlatformNavigator internal constructor(
    private val session: PageSession,
    private val config: BrowserConfig,
    private val router: PlatformRouter? = null,
) {
    public constructor(
        session: PageSession,
        config: BrowserConfig,
    ) : this(session, config, null)

    init {
        router?.requestActivityTracker = session.requestActivity
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Navigates the session's view to [url] and waits until [waitUntil].
     *
     * @return [NavigationResult] containing final URL, status, and [NavigationResult.settled] boolean.
     * @throws BrowserException [ErrorCode.NAVIGATION_FAILED] if navigation fails fatally.
     * @throws BrowserException [ErrorCode.SSRF_BLOCKED] if URL is private and not allowed.
     */
    public suspend fun goto(
        url: String,
        waitUntil: WaitUntil = WaitUntil.Load,
        timeoutMillis: Long = 0,
    ): NavigationResult = session.runInState(SessionState.Navigating) {
        val effectiveTimeout = if (timeoutMillis > 0) timeoutMillis else config.timeouts.navigationMillis
        checkUrlAllowed(url)

        val client = NavigationClient(url, waitUntil)
        val hosted = session.hostedWebView

        // A fresh window per navigation: the previous page's requests must not
        // make this one look busy, nor its silence make this one look idle.
        session.requestActivity.reset()

        withContext(Dispatchers.Main) {
            hosted.webView.webViewClient = client
            hosted.webView.loadUrl(url)
        }

        // Two stages. The document has to arrive before anything can be said
        // about whether it has settled, so every mode waits for the load signal
        // first and then, where the mode asks for more, hands over to the settle
        // engine. Modes beyond Load and DomReady used to fall through to the
        // load event and report settled = true, which returned a page that had
        // not finished building and said it had.
        val startNano = System.nanoTime()
        var settled: Boolean
        try {
            settled = withTimeout(effectiveTimeout) {
                client.awaitSignal()
                true
            }
            session.recordNavigation()
        } catch (e: TimeoutCancellationException) {
            // Timeout returns what exists, flagged as not settled (settled = false),
            // rather than throwing away partial content!
            return@runInState NavigationResult(
                url = client.currentUrl.ifEmpty { url },
                status = client.lastHttpStatus,
                settled = false,
            )
        }

        if (settled && waitUntil.needsSettleEngine()) {
            val elapsedMillis = (System.nanoTime() - startNano) / 1_000_000L
            val remaining = effectiveTimeout - elapsedMillis
            settled = if (remaining <= 0) {
                false
            } else {
                val scriptEngine = PlatformScriptEngine(session, config)
                PlatformSettleEngine(session, scriptEngine, config).settle(waitUntil, remaining)
            }
        }

        // Check if there was a fatal navigation error
        client.fatalError?.let { cause ->
            if (cause is BrowserException) throw cause
            throw browserError(
                ErrorCode.NAVIGATION_FAILED,
                "Navigation failed for $url: ${cause.message}",
                cause,
            )
        }

        NavigationResult(
            url = client.currentUrl.ifEmpty { url },
            status = client.lastHttpStatus,
            settled = settled,
        )
    }

    /**
     * Validates the destination before loading it, resolving the host off the
     * main thread.
     *
     * Resolution must not happen on the dispatcher this runs under: a name
     * lookup on the main thread throws `NetworkOnMainThreadException`, and the
     * previous implementation caught that and continued, so no hostname was
     * ever actually validated on a device.
     */
    private suspend fun checkUrlAllowed(url: String) {
        try {
            dev.headless.browser.security.SsrfGuard.validateUriResolving(url, config.allowPrivateAddresses)
        } catch (e: BrowserException) {
            if (e.code == ErrorCode.SSRF_BLOCKED) {
                session.recordSsrfBlocked()
            }
            throw e
        }
    }

    /**
     * Whether this mode needs more than the document's own load signal.
     *
     * `Load` and `DomReady` are satisfied by the WebView client callbacks.
     * Everything else describes a condition the page reaches afterwards, and
     * only the settle engine can observe it.
     */
    private fun WaitUntil.needsSettleEngine(): Boolean = when (this) {
        is WaitUntil.Load, is WaitUntil.DomReady -> false
        is WaitUntil.DomStable, is WaitUntil.NetworkIdle, is WaitUntil.Custom -> true
    }

    private inner class NavigationClient(
        private val initialUrl: String,
        private val waitUntil: WaitUntil,
    ) : WebViewClient() {
        val domReadyDeferred = CompletableDeferred<Unit>()
        val loadDeferred = CompletableDeferred<Unit>()
        val redirectCount = AtomicInteger(0)

        @Volatile
        var currentUrl: String = initialUrl

        @Volatile
        var lastHttpStatus: Int? = null

        @Volatile
        var fatalError: Throwable? = null

        private val isLoadFinished = AtomicBoolean(false)

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            url?.let { currentUrl = it }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            url?.let { currentUrl = it }
            if (isLoadFinished.compareAndSet(false, true)) {
                domReadyDeferred.complete(Unit)
                loadDeferred.complete(Unit)
            }
        }

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse? {
            // The only network signal the platform backend gets, and it arrives
            // on a background thread for every resource the page asks for. The
            // settle engine reads it rather than sleeping.
            session.requestActivity.recordRequest()

            val intercepted = router?.interceptRequest(request)
            if (intercepted != null) return intercepted
            return super.shouldInterceptRequest(view, request)
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val targetUrl = request?.url?.toString() ?: ""
            if (targetUrl.isNotEmpty()) {
                try {
                    // Synchronous callback on the main thread, so this is the
                    // non-resolving check: literal addresses are judged outright,
                    // and a hostname against what navigation already resolved.
                    dev.headless.browser.security.SsrfGuard.validateUri(targetUrl, config.allowPrivateAddresses)
                } catch (e: BrowserException) {
                    if (e.code == ErrorCode.SSRF_BLOCKED) {
                        session.recordSsrfBlocked()
                        fatalError = e
                        domReadyDeferred.complete(Unit)
                        loadDeferred.complete(Unit)
                        return true
                    }
                }
            }

            val count = redirectCount.incrementAndGet()
            if (count > MAX_REDIRECTS) {
                fatalError = dev.headless.browser.browserError(
                    ErrorCode.NAVIGATION_FAILED,
                    "Too many redirects: exceeded maximum cap of $MAX_REDIRECTS"
                )
                domReadyDeferred.complete(Unit)
                loadDeferred.complete(Unit)
                return true
            }
            request?.url?.toString()?.let { currentUrl = it }
            return false
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame == true) {
                val description = error?.description?.toString() ?: "Unknown network error"
                val errorCode = error?.errorCode ?: -1
                fatalError = RuntimeException("Network error ($errorCode): $description")
                domReadyDeferred.complete(Unit)
                loadDeferred.complete(Unit)
            }
        }

        override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
            val didCrash = detail?.didCrash() ?: true
            val crashMsg = if (didCrash) "Renderer process crashed" else "Renderer process killed by system (OOM)"
            fatalError = browserError(ErrorCode.TARGET_CRASHED, crashMsg)
            domReadyDeferred.completeExceptionally(fatalError!!)
            loadDeferred.completeExceptionally(fatalError!!)
            return session.handleRendererDeath(didCrash)
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?,
        ) {
            super.onReceivedHttpError(view, request, errorResponse)
            if (request?.isForMainFrame == true) {
                lastHttpStatus = errorResponse?.statusCode
            }
        }

        /**
         * Waits for the document itself.
         *
         * Every mode needs this much: a page cannot be stable, idle or matching
         * a predicate before it has loaded. What a mode needs *beyond* this is
         * the settle engine's job, and [goto] hands over there rather than
         * treating the load event as the answer.
         */
        suspend fun awaitSignal() {
            when (waitUntil) {
                is WaitUntil.DomReady -> domReadyDeferred.await()
                is WaitUntil.Load,
                is WaitUntil.DomStable,
                is WaitUntil.NetworkIdle,
                is WaitUntil.Custom,
                -> loadDeferred.await()
            }
        }
    }

    internal companion object {
        const val MAX_REDIRECTS = 20
    }
}
