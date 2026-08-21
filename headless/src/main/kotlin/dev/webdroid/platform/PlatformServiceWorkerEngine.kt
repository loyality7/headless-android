package dev.webdroid.platform

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature

/**
 * Manages service worker request interception using [ServiceWorkerControllerCompat].
 *
 * NOTE: On Android, [ServiceWorkerControllerCompat] is **process-global**. Registering
 * service worker route interception applies across all WebViews in the process.
 */
internal class PlatformServiceWorkerEngine(
    private val router: PlatformRouter,
) {

    private var isRegistered = false

    /**
     * Binds [router] to the process-global Service Worker controller.
     *
     * @return `true` if service worker interception was successfully attached, `false` if unsupported on this device package.
     */
    @SuppressLint("RequiresFeature")
    fun setupServiceWorkerInterception(): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) {
            return false
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)) {
            return false
        }

        val controller = ServiceWorkerControllerCompat.getInstance()
        controller.setServiceWorkerClient(object : ServiceWorkerClientCompat() {
            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                return router.interceptRequest(request)
            }
        })

        isRegistered = true
        return true
    }
}
