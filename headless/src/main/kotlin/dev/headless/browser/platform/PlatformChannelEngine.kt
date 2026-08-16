package dev.headless.browser.platform

import android.net.Uri
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.headless.browser.BrowserConfig
import dev.headless.browser.BrowserException
import dev.headless.browser.ErrorCode
import dev.headless.browser.browserError
import dev.headless.browser.core.PageSession
import dev.headless.browser.core.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages origin-scoped native function exposure over WebMessageListener.
 *
 * Exclusively uses [WebViewCompat.addWebMessageListener] and NEVER uses the reflection-based
 * [android.webkit.WebView.addJavascriptInterface] bridge.
 */
internal class PlatformChannelEngine(
    private val session: PageSession,
    private val config: BrowserConfig,
) {

    private val exposedFunctions = mutableSetOf<String>()

    /**
     * Exposes a native Kotlin handler to JavaScript page context under [name].
     *
     * @param name JS window property name (e.g. "nativeBridge")
     * @param allowedOrigins Set of URI origin patterns (e.g. setOf("https://example.com", "*"))
     * @param handler Native callback accepting JSON/String payload and returning result
     * @throws BrowserException [ErrorCode.UNSUPPORTED] if device WebView package lacks [WebViewFeature.WEB_MESSAGE_LISTENER]
     */
    @android.annotation.SuppressLint("RequiresFeature")
    suspend fun exposeFunction(
        name: String,
        allowedOrigins: Set<String> = setOf("*"),
        handler: suspend (String) -> String?,
    ) = session.runInState(SessionState.Operating) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            throw browserError(
                ErrorCode.UNSUPPORTED,
                "WEB_MESSAGE_LISTENER feature is unsupported on this device's WebView package",
            )
        }

        val hosted = session.hostedWebView
        withContext(Dispatchers.Main) {
            val listener = WebViewCompat.WebMessageListener { _, message, sourceOrigin, _, replyProxy ->
                handleIncomingMessage(name, message, sourceOrigin, allowedOrigins, replyProxy, handler)
            }
            WebViewCompat.addWebMessageListener(hosted.webView, name, allowedOrigins, listener)
            exposedFunctions.add(name)
        }
    }

    /**
     * Removes all registered WebMessageListeners from the hosted WebView.
     */
    @android.annotation.SuppressLint("RequiresFeature")
    suspend fun clearExposedFunctions() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        val hosted = session.hostedWebView
        withContext(Dispatchers.Main) {
            for (name in exposedFunctions) {
                runCatching { WebViewCompat.removeWebMessageListener(hosted.webView, name) }
            }
            exposedFunctions.clear()
        }
    }

    @android.annotation.SuppressLint("RequiresFeature")
    private fun handleIncomingMessage(
        functionName: String,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        allowedOrigins: Set<String>,
        replyProxy: JavaScriptReplyProxy,
        handler: suspend (String) -> String?,
    ) {
        val payload = message.data ?: ""

        // Payload size cap check
        if (payload.length > MAX_PAYLOAD_CHARS) {
            replyProxy.postMessage(
                "{\"error\":\"PAYLOAD_TOO_LARGE\",\"message\":\"Payload exceeds maximum limit of $MAX_PAYLOAD_CHARS characters\"}"
            )
            return
        }

        // Origin check
        val originStr = sourceOrigin.toString()
        if (!isOriginAllowed(originStr, allowedOrigins)) {
            replyProxy.postMessage(
                "{\"error\":\"ORIGIN_DISALLOWED\",\"message\":\"Origin $originStr is not in allowed origins list\"}"
            )
            return
        }

        // Process callback safely
        kotlinx.coroutines.runBlocking {
            try {
                val result = handler(payload) ?: ""
                replyProxy.postMessage(result)
            } catch (e: Exception) {
                replyProxy.postMessage(
                    "{\"error\":\"HANDLER_EXCEPTION\",\"message\":\"${e.message ?: "Native function execution failed"}\"}"
                )
            }
        }
    }

    private fun isOriginAllowed(sourceOrigin: String, allowedOrigins: Set<String>): Boolean {
        if (allowedOrigins.contains("*")) return true
        return allowedOrigins.any { allowed ->
            allowed == sourceOrigin || sourceOrigin.startsWith(allowed.removeSuffix("/"))
        }
    }

    companion object {
        /** 500,000 character (~500 KB) payload cap to prevent native process flooding/OOM. */
        const val MAX_PAYLOAD_CHARS = 500_000
    }
}
