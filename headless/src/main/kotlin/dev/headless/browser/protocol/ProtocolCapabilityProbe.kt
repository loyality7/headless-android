package dev.headless.browser.protocol

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.headless.browser.BrowserConfig
import dev.headless.browser.Capabilities
import dev.headless.browser.Viewport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Probes the device's actual WebView build for capabilities at runtime.
 *
 * Capabilities are never inferred from version strings. Each feature is dynamically
 * probed or checked via [WebViewFeature] APIs. Probe outcomes are cached in-memory
 * per WebView package version to ensure session startup overhead is bounded.
 */
internal class ProtocolCapabilityProbe(
    private val context: Context? = null,
    private val config: BrowserConfig,
) {

    /**
     * Obtains probed [Capabilities] for the given [viewport].
     * Uses cached results for feature probes if WebView version hasn't changed.
     */
    suspend fun probeCapabilities(viewport: Viewport?): Capabilities = withContext(Dispatchers.IO) {
        val webViewPackage = context?.let { ctx ->
            runCatching { WebViewCompat.getCurrentWebViewPackage(ctx) }.getOrNull()
        }
        val versionKey = webViewPackage?.versionName ?: "unknown_${Build.VERSION.SDK_INT}"

        val cachedProbe = versionCache.getOrPut(versionKey) {
            runProbe(context, config)
        }

        Capabilities(
            protocolBackend = cachedProbe.protocolBackend,
            documentStartScript = cachedProbe.documentStartScript,
            webMessageChannel = cachedProbe.webMessageChannel,
            serviceWorkerInterception = cachedProbe.serviceWorkerInterception,
            rendererResponsiveness = cachedProbe.rendererResponsiveness,
            screenshots = viewport != null,
        )
    }

    private fun runProbe(context: Context?, config: BrowserConfig): FeatureProbeResult {
        val documentStartScript = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }.getOrDefault(false)
        val webMessageChannel = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) }.getOrDefault(false)
        val serviceWorkerInterception = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) }.getOrDefault(false)
        val rendererResponsiveness = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE) }.getOrDefault(false)

        val protocolBackend = if (config.enableProtocolBackend) {
            probeProtocolSocketReachability()
        } else {
            false
        }

        return FeatureProbeResult(
            protocolBackend = protocolBackend,
            documentStartScript = documentStartScript,
            webMessageChannel = webMessageChannel,
            serviceWorkerInterception = serviceWorkerInterception,
            rendererResponsiveness = rendererResponsiveness,
        )
    }

    private fun probeProtocolSocketReachability(): Boolean {
        return try {
            val socketName = "webview_devtools_remote"
            val socket = LocalSocket()
            socket.soTimeout = 1000
            socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
            socket.close()
            true
        } catch (_: Throwable) {
            false
        }
    }

    private data class FeatureProbeResult(
        val protocolBackend: Boolean,
        val documentStartScript: Boolean,
        val webMessageChannel: Boolean,
        val serviceWorkerInterception: Boolean,
        val rendererResponsiveness: Boolean,
    )

    companion object {
        private val versionCache = ConcurrentHashMap<String, FeatureProbeResult>()

        /** Clears in-memory capability cache (used for unit testing). */
        fun clearCache() {
            versionCache.clear()
        }
    }
}
