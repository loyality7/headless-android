package dev.headless.browser.platform

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import dev.headless.browser.ErrorCode
import dev.headless.browser.Viewport
import dev.headless.browser.browserError
import java.lang.ref.WeakReference

/**
 * How a session's WebView is hosted, and what that costs.
 *
 * Not a preference: the probe found that a vendor may refuse to let an app that
 * is not already in the foreground create a window at all, so the library takes
 * the best arrangement it can obtain and reports which one it got.
 */
public enum class HostMode {
    /**
     * Inside the host app's own activity, behind its content and invisible.
     *
     * The best arrangement available, and the one to prefer: the view is in a
     * real window with a real size, so the renderer keeps it updated and
     * drawing it produces the page — yet it needs no permission and creates no
     * window of its own, which is what a vendor would refuse.
     *
     * Requires the caller to have created the browser with an activity.
     */
    AttachedToHost,

    /**
     * Attached to a window of our own, positioned off the display.
     *
     * Same behaviour as [AttachedToHost] without needing an activity, at the
     * cost of permission to draw overlays — which a user must grant by hand and
     * some vendors make awkward.
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
     * Held weakly. An activity outlived by this object is a leak of the whole
     * view hierarchy, and a browser is meant to outlive any one screen.
     */
    private val hostActivity: WeakReference<Activity>? =
        (context as? Activity)?.let { WeakReference(it) }

    /**
     * The best hosting this device and app will allow, in preference order.
     *
     * Checked per call rather than cached: an activity finishes, and overlay
     * permission can be granted or revoked, while the process lives on.
     */
    fun availableMode(): HostMode = when {
        liveHostActivity() != null -> HostMode.AttachedToHost
        canDrawOverlays() -> HostMode.AttachedOverlay
        else -> HostMode.Detached
    }

    /** The host activity, if it still exists and is still usable. */
    private fun liveHostActivity(): Activity? =
        hostActivity?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

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

        try {
            WebView.enableSlowWholeDocumentDraw()
        } catch (_: Throwable) {
            // Best effort call
        }

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
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.webViewClient = object : android.webkit.WebViewClient() {
                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: android.webkit.RenderProcessGoneDetail?,
                ): Boolean = true
            }
        }

        val mode = availableMode()
        when (mode) {
            HostMode.AttachedToHost -> attachToHostActivity(webView, width, height)
            HostMode.AttachedOverlay -> attachOffscreen(webView, width, height)
            HostMode.Detached -> layOutDetached(webView, width, height)
        }

        return HostedWebView(webView, mode, width, height)
    }

    /**
     * Adds the view to the host activity's content, behind everything it draws.
     *
     * Index zero and `INVISIBLE`, so it occupies no visual space and cannot be
     * seen or touched, while still living in a real window at a real size. No
     * permission is involved and no window is created, which is what makes this
     * work on devices that refuse an overlay.
     */
    private fun attachToHostActivity(webView: WebView, width: Int, height: Int) {
        val activity = liveHostActivity()
            ?: throw browserError(ErrorCode.DETACHED, "the host activity went away before the session started")

        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val parent = content?.getChildAt(0) as? ViewGroup
            ?: throw browserError(ErrorCode.UNSUPPORTED, "the host activity has no content view to attach to")

        webView.layoutParams = FrameLayout.LayoutParams(width, height)
        webView.visibility = View.INVISIBLE
        parent.addView(webView, 0)
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
            HostMode.AttachedToHost -> (webView.parent as? ViewGroup)?.removeView(webView)
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
        get() = mode != HostMode.Detached && width > 1 && height > 1
}
