package dev.webdroid

import android.content.Context

/**
 * A browser running inside this app: no server, no cable, no visible UI.
 *
 * Create one per host app and keep it for as long as automation is wanted.
 * Creating it does not start a WebView; [newPage] does.
 *
 * ```kotlin
 * val browser = HeadlessBrowser.create(context, BrowserConfig())
 * val page = browser.newPage()                     // 1x1, text and DOM only
 * page.goto("https://example.com", WaitUntil.DomStable())
 * val title = page.title()
 * page.close()
 * browser.close()
 * ```
 *
 * Long-running automation belongs in a foreground service the host app owns.
 * A WebView working in a backgrounded app can be throttled or killed, and this
 * library does not take that decision on the app's behalf.
 */
public interface HeadlessBrowser {

    /**
     * Opens a session.
     *
     * A null [viewport] gives a one-pixel session: enough for text, the DOM and
     * script, and far cheaper. Pass a real viewport for screenshots and for
     * pages whose behaviour depends on layout. The size is fixed for the life of
     * the session.
     *
     * Suspends until the session is ready. Cancelling the caller destroys it.
     *
     * @throws BrowserException [ErrorCode.MEMORY_LIMIT] when the session budget
     *   is exhausted, rather than risking an out-of-memory kill.
     */
    public suspend fun newPage(viewport: Viewport? = null): Page

    /** What this device can actually do, from probing rather than from a version number. */
    public suspend fun capabilities(): Capabilities

    /** Closes every open session and releases everything. Safe to call twice. */
    public suspend fun close()

    /** Where a [HeadlessBrowser] instance comes from; there is no public constructor. */
    public companion object {
        /**
         * Creates a browser. Cheap: nothing is started until [newPage].
         *
         * [context] is retained as its application context, so passing an
         * activity does not leak it.
         */
        @JvmStatic
        public fun create(
            context: Context,
            config: BrowserConfig = BrowserConfig(),
        ): HeadlessBrowser = dev.webdroid.core.HeadlessBrowserImpl(context, config)
    }
}
