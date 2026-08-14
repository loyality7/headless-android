package dev.headless.browser.platform

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import dev.headless.browser.ErrorCode
import dev.headless.browser.Viewport
import dev.headless.browser.browserError

/**
 * How a session's WebView is hosted, and what that costs.
 *
 * Not a preference: the probe found that a vendor may refuse to let an app that
 * is not already in the foreground create a window at all, so the library takes
 * the best arrangement it can obtain and reports which one it got.
 */
public enum class HostMode {
    /**
     * Attached to a window, positioned off the display.
     *
     * Timers and animation frames run unthrottled and the view renders, so
     * drawing it produces the page. Needs permission to draw overlays.
     */
    AttachedOverlay,

    /**
     * No window at all.
     *
     * Works on every device and needs no permission. The control endpoint,
     * navigation, evaluation and DOM access are unaffected — measured on
     * Android 14 — but timers are throttled, animation frames may not run, and
     * drawing the view is unreliable.
     */
    Detached,
}

/**
 * Creates and destroys the WebView a session runs in.
 *
 * Every method touching the view must be called on the main thread. The caller
 * marshals; this class does not, so that it never hides a thread hop inside
 * something that looks cheap.
 */
internal class OffscreenHost(context: Context) {

    private val context = context.applicationContext
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /**
     * The best hosting this device and app will allow.
     *
     * Checked per call rather than cached: overlay permission can be granted or
     * revoked while the process lives.
     */
    fun availableMode(): HostMode =
        if (canDrawOverlays()) HostMode.AttachedOverlay else HostMode.Detached

    /** Whether an overlay window is permitted. Granted by the user, and revocable. */
    private fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    /**
     * Creates a WebView sized for [viewport], or one pixel when it is null.
     *
     * @throws BrowserException [ErrorCode.UNSUPPORTED] if the WebView package is
     *   missing or being updated, which leaves an app with no engine at all.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun create(viewport: Viewport?): HostedWebView {
        val width = viewport?.width ?: 1
        val height = viewport?.height ?: 1

        val webView = try {
            WebView(context)
        } catch (e: Exception) {
            // The WebView package can be absent or mid-update, and the failure
            // arrives as an inflation error rather than as anything specific.
            throw browserError(ErrorCode.UNSUPPORTED, "this device has no usable WebView", e)
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        // Nothing here should ever reach the filesystem: page content is hostile.
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false

        val mode = availableMode()
        when (mode) {
            HostMode.AttachedOverlay -> attachOffscreen(webView, width, height)
            HostMode.Detached -> layOutDetached(webView, width, height)
        }

        return HostedWebView(webView, mode, width, height)
    }

    /**
     * Adds the view to a window placed outside the display.
     *
     * Off the display rather than sized to nothing: a zero-area or invisible
     * view is treated by the renderer as not worth updating, which is the
     * throttling this arrangement exists to avoid.
     */
    private fun attachOffscreen(webView: WebView, width: Int, height: Int) {
        val params = WindowManager.LayoutParams(
            width,
            height,
            OVERLAY_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = OFFSCREEN_X
            y = OFFSCREEN_Y
        }

        webView.visibility = View.VISIBLE
        windowManager.addView(webView, params)
    }

    /** Measured and laid out so the renderer has geometry, but attached to nothing. */
    private fun layOutDetached(webView: WebView, width: Int, height: Int) {
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, width, height)
    }

    /**
     * Destroys a hosted view.
     *
     * The order is not a style choice. Destroying while attached, or from inside
     * a client callback, is the classic crash and leak: detach, stop, blank,
     * clear, destroy.
     */
    fun destroy(hosted: HostedWebView) {
        if (hosted.destroyed) return
        hosted.destroyed = true

        val webView = hosted.webView
        webView.webViewClient = android.webkit.WebViewClient()
        webView.webChromeClient = null

        when (hosted.mode) {
            HostMode.AttachedOverlay -> runCatching { windowManager.removeViewImmediate(webView) }
            HostMode.Detached -> (webView.parent as? ViewGroup)?.removeView(webView)
        }

        runCatching {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
        }
        webView.destroy()
    }

    private companion object {
        /** The only overlay type an ordinary app may use from API 26 onward. */
        const val OVERLAY_TYPE = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        // Far enough off any real display that no part of it is ever composited
        // into what the user sees.
        const val OFFSCREEN_X = -10_000
        const val OFFSCREEN_Y = -10_000
    }
}

/** A WebView and the arrangement it was given. */
internal class HostedWebView(
    val webView: WebView,
    val mode: HostMode,
    val width: Int,
    val height: Int,
) {
    /** Set once, so a second destroy is a no-op rather than a crash. */
    var destroyed: Boolean = false

    /** Drawing only produces the page from an attached view of real size. */
    val canCapture: Boolean
        get() = mode == HostMode.AttachedOverlay && width > 1 && height > 1
}
