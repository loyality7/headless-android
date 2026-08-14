package dev.headless.probe

import android.app.Activity
import android.util.Log
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry

const val TAG = "probe"

/** Everything the probe measures gets logged in one grep-able shape. */
fun record(key: String, value: Any?) = Log.i(TAG, "MEASUREMENT $key = $value")

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
    ActivityScenario.launch(HostActivity::class.java).use { scenario ->
        var activity: HostActivity? = null
        scenario.onActivity { activity = it }
        return block(requireNotNull(activity) { "host activity never started" })
    }
}

/** Loads [url] and waits for onPageFinished, or fails after [timeoutMs]. */
fun HostActivity.load(webView: WebView, url: String, timeoutMs: Long = 30_000) {
    val finished = java.util.concurrent.CountDownLatch(1)
    onMain {
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView, loadedUrl: String) = finished.countDown()
        }
        webView.loadUrl(url)
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
