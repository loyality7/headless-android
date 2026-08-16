package dev.headless.browser.platform

import android.webkit.ValueCallback
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.headless.browser.BrowserConfig
import dev.headless.browser.BrowserException
import dev.headless.browser.ErrorCode
import dev.headless.browser.browserError
import dev.headless.browser.core.PageSession
import dev.headless.browser.core.SessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Handles JavaScript evaluation and document-start init script injection on the platform backend.
 */
public class PlatformScriptEngine(
    private val session: PageSession,
    private val config: BrowserConfig,
) {

    /**
     * Evaluates a JavaScript expression in the page context.
     *
     * @param expression Script to evaluate
     * @param timeoutMillis Ceiling in ms (defaults to [BrowserConfig.timeouts.scriptMillis])
     * @return String representation of the return value, truncated if oversized
     * @throws BrowserException [ErrorCode.TIMEOUT] if execution exceeds timeout
     */
    public suspend fun evaluate(
        expression: String,
        timeoutMillis: Long = 0,
    ): String? = session.runInState(SessionState.Operating) {
        val effectiveTimeout = if (timeoutMillis > 0) timeoutMillis else config.timeouts.scriptMillis
        val hosted = session.hostedWebView
        val deferred = CompletableDeferred<String?>()

        withContext(Dispatchers.Main) {
            hosted.webView.evaluateJavascript(expression, ValueCallback { value ->
                deferred.complete(value)
            })
        }

        val startTime = System.currentTimeMillis()
        val rawResult = dev.headless.browser.core.MonotonicTimeout.runWithTimeout(effectiveTimeout, "script") {
            deferred.await()
        }
        val elapsed = System.currentTimeMillis() - startTime
        session.recordJsEvaluation(elapsed)

        truncateIfNeeded(rawResult)
    }

    /**
     * Installs a script that runs before any page script runs on current and future documents.
     *
     * @throws BrowserException [ErrorCode.UNSUPPORTED] if the device's WebView package lacks
     *   the [WebViewFeature.DOCUMENT_START_SCRIPT] feature.
     */
    @android.annotation.SuppressLint("RequiresFeature")
    public suspend fun addInitScript(
        script: String,
        allowedOrigins: Set<String> = setOf("*"),
    ): Unit = session.runInState(SessionState.Operating) {
        dev.headless.browser.core.CapabilityGuard.requireDocumentStartScript(session.capabilities())

        val hosted = session.hostedWebView
        withContext(Dispatchers.Main) {
            WebViewCompat.addDocumentStartJavaScript(hosted.webView, script, allowedOrigins)
        }
    }

    public companion object {
        /** 1 million characters output cap (~1 MB UTF-16) to prevent OOM. */
        public const val MAX_OUTPUT_CHARS: Int = 1_000_000

        internal fun truncateIfNeeded(raw: String?): String? {
            if (raw == null) return null
            if (raw.length <= MAX_OUTPUT_CHARS) return raw
            return raw.substring(0, MAX_OUTPUT_CHARS) + "\n... [truncated ${raw.length - MAX_OUTPUT_CHARS} characters]"
        }
    }
}
