package dev.webdroid.probe

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * Host window for the probe's WebViews. Attached and VISIBLE, but sized and
 * positioned so nothing is seen — the arrangement D3 requires. A detached view
 * is measured here for comparison, never used as the working environment.
 */
class HostActivity : Activity() {

    lateinit var container: FrameLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = FrameLayout(this)
        setContentView(container)
    }

    /** Attached, visible, offscreen. [width]/[height] in pixels: 1×1 for text, a viewport for pixels. */
    fun addWebView(width: Int, height: Int): WebView {
        val webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        container.addView(
            webView,
            FrameLayout.LayoutParams(width, height).apply {
                leftMargin = 0
                topMargin = 0
            }
        )
        return webView
    }

    /** Destroy order matters: detach, stop, blank, clear, destroy. Never from a client callback. */
    fun destroyWebView(webView: WebView) {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.clearHistory()
        webView.removeAllViews()
        webView.destroy()
    }
}
