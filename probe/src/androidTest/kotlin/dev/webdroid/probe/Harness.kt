package dev.webdroid.probe

import android.app.Activity
import android.util.Log
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry

const val TAG = "probe"

/**
 * Everything the probe measures, in one grep-able shape.
 *
 * Reported through the instrumentation status channel as well as logcat: MIUI
 * suppresses application log output by default, and a measurement nobody can
 * read is not a measurement. The status channel prints directly into the
 * `am instrument` output on every device.
 */
fun record(key: String, value: Any?) {
    val line = "MEASUREMENT $key = $value"
    Log.i(TAG, line)
    InstrumentationRegistry.getInstrumentation().sendStatus(
        0,
        android.os.Bundle().apply { putString("stream", "\n$line") },
    )
}

/**
 * Process-wide and opt-in, per C9. Enabling it makes every WebView in this
 * process inspectable over USB for as long as it is on.
 */
fun enableDebugging() = onMain { WebView.setWebContentsDebuggingEnabled(true) }

fun <T> onMain(block: () -> T): T {
    var result: T? = null
    var failure: Throwable? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        try {
            result = block()
        } catch (t: Throwable) {
            failure = t
        }
    }
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
}

/** Runs [block] with a live host activity. Every path closes the scenario. */
fun <T> withHost(block: (HostActivity) -> T): T {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val intent = android.content.Intent(context, HostActivity::class.java).apply {
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    }
    runCatching { context.startActivity(intent) }
    ActivityScenario.launch<HostActivity>(intent).use { scenario ->
        var activity: HostActivity? = null
        scenario.onActivity { activity = it }
        return block(requireNotNull(activity) { "host activity never started" })
    }
}

/**
 * Runs [block] with a WebView and no activity at all.
 *
 * The control endpoint opens when a WebView exists in the process with debugging
 * enabled; it does not care whether anything is on screen. Only the tests that
 * measure throttling and drawing need a window, so the rest avoid one — which
 * also avoids MIUI, where an app that is not already in the foreground is
 * refused permission to show an activity.
 *
 * Not the arrangement the library will ship. It is the arrangement that answers
 * the protocol questions without a vendor policy in the way.
 */
fun <T> withDetachedWebView(block: (android.webkit.WebView) -> T): T {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val webView = onMain {
        android.webkit.WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Laid out so the renderer has a surface to work against, even
            // though nothing is attached to a window.
            measure(1, 1)
            layout(0, 0, 1, 1)
        }
    }
    try {
        return block(webView)
    } finally {
        onMain {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
    }
}

/** Loads [url] into a WebView that has no activity, waiting for the load to finish. */
fun loadDetached(webView: android.webkit.WebView, url: String, timeoutMs: Long = 10_000) {
    val finished = java.util.concurrent.CountDownLatch(1)
    onMain {
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView, loadedUrl: String) {
                finished.countDown()
            }
        }
        if (url.startsWith("data:text/html,")) {
            val rawHtml = java.net.URLDecoder.decode(url.removePrefix("data:text/html,"), "UTF-8")
            webView.loadDataWithBaseURL("http://localhost/", rawHtml, "text/html", "UTF-8", null)
        } else {
            webView.loadUrl(url)
        }
    }
    check(finished.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
        "page did not finish loading within ${timeoutMs}ms: $url"
    }
}

/** Loads [url] and waits for onPageFinished, or fails after [timeoutMs]. */
fun HostActivity.load(webView: WebView, url: String, timeoutMs: Long = 10_000) {
    val finished = java.util.concurrent.CountDownLatch(1)
    onMain {
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView, loadedUrl: String) {
                finished.countDown()
            }
        }
        if (url.startsWith("data:text/html,")) {
            val rawHtml = java.net.URLDecoder.decode(url.removePrefix("data:text/html,"), "UTF-8")
            webView.loadDataWithBaseURL("http://localhost/", rawHtml, "text/html", "UTF-8", null)
        } else {
            webView.loadUrl(url)
        }
    }
    check(finished.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
        "page did not finish loading within ${timeoutMs}ms: $url"
    }
}

/** This process's PSS in KB. Renderer memory is out of reach — see N4. */
fun pssKb(): Int {
    val info = android.os.Debug.MemoryInfo()
    android.os.Debug.getMemoryInfo(info)
    return info.totalPss
}
