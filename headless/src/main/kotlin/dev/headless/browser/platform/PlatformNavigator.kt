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
internal class PlatformNavigator(
    private val session: PageSession,
    private val config: BrowserConfig,
    private val router: PlatformRouter? = null,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Navigates the session's view to [url] and waits until [waitUntil].
     *
     * @return [NavigationResult] containing final URL, status, and [NavigationResult.settled] boolean.
     * @throws BrowserException [ErrorCode.NAVIGATION_FAILED] if navigation fails fatally.
     * @throws BrowserException [ErrorCode.SSRF_BLOCKED] if URL is private and not allowed.
     */
    suspend fun goto(
        url: String,
        waitUntil: WaitUntil = WaitUntil.Load,
        timeoutMillis: Long = 0,
    ): NavigationResult = session.runInState(SessionState.Navigating) {
        val effectiveTimeout = if (timeoutMillis > 0) timeoutMillis else config.timeouts.navigationMillis
        checkUrlAllowed(url)

        val client = NavigationClient(url, waitUntil)
        val hosted = session.hostedWebView

        withContext(Dispatchers.Main) {
            hosted.webView.webViewClient = client
            hosted.webView.loadUrl(url)
        }

        val settled: Boolean
        try {
            settled = withTimeout(effectiveTimeout) {
                client.awaitSignal()
                true
            }
        } catch (e: TimeoutCancellationException) {
            // Timeout returns what exists, flagged as not settled (settled = false),
            // rather than throwing away partial content!
            return@runInState NavigationResult(
                url = client.currentUrl.ifEmpty { url },
                status = client.lastHttpStatus,
                settled = false,
            )
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

    private fun checkUrlAllowed(url: String) {
        try {
            dev.headless.browser.security.SsrfGuard.validateUri(url, config.allowPrivateAddresses)
        } catch (e: BrowserException) {
            if (e.code == ErrorCode.SSRF_BLOCKED) {
                PageSession.recordSsrfBlocked()
            }
            throw e
        }
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
            val intercepted = router?.interceptRequest(request)
            if (intercepted != null) return intercepted
            return super.shouldInterceptRequest(view, request)
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val targetUrl = request?.url?.toString() ?: ""
            if (targetUrl.isNotEmpty()) {
                try {
                    dev.headless.browser.security.SsrfGuard.validateUri(targetUrl, config.allowPrivateAddresses)
                } catch (e: BrowserException) {
                    if (e.code == ErrorCode.SSRF_BLOCKED) {
                        PageSession.recordSsrfBlocked()
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

        suspend fun awaitSignal() {
            when (waitUntil) {
                is WaitUntil.Load -> loadDeferred.await()
                is WaitUntil.DomReady -> domReadyDeferred.await()
                else -> loadDeferred.await() // Default fallback for basic navigation
            }
        }
    }

    internal companion object {
        const val MAX_REDIRECTS = 20
    }
}
