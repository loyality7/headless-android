package dev.headless.browser.protocol

import android.content.Context
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

    /**
     * Whether the control endpoint answers on this device.
     *
     * Delegates to the same candidate list discovery uses. This probe used to
     * try only the bare `webview_devtools_remote` name, which does not connect
     * on Android 14 — so it reported the protocol backend as absent on hardware
     * where the endpoint was reachable and the CDP tests passed against it.
     * Every routing decision consults this result, so the whole protocol backend
     * was unreachable at runtime because of the missing pid suffix.
     */
    private fun probeProtocolSocketReachability(): Boolean =
        ProtocolTargetDiscovery.isEndpointReachable()

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
