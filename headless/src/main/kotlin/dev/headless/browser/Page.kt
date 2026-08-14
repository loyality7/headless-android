package dev.headless.browser

/**
 * One page, driven programmatically.
 *
 * Every call suspends, takes a timeout, and cancels cleanly: cancelling the
 * calling coroutine destroys the session on every path, including mid-navigation.
 * Once [close] has run, every call raises [ErrorCode.DETACHED].
 *
 * A timeout defaulting to zero means "use the value from [BrowserConfig.timeouts]".
 *
 * The shape is Playwright's on purpose. Anyone who knows that API knows this one.
 */
public interface Page {

    // ---- navigation ------------------------------------------------------

    /**
     * Navigates and waits for [waitUntil].
     *
     * On expiry the call returns with [NavigationResult.settled] false rather
     * than throwing: partial content is usually still useful.
     *
     * @throws BrowserException [ErrorCode.NAVIGATION_FAILED] if the document
     *   could not be loaded, [ErrorCode.SSRF_BLOCKED] if the URL resolved to an
     *   address the host app did not allow, at any point in a redirect chain.
     */
    public suspend fun goto(
        url: String,
        waitUntil: WaitUntil = WaitUntil.Load,
        timeoutMillis: Long = 0,
    ): NavigationResult

    /** Waits for an element to exist. Returns as soon as it appears, not on a poll tick. */
    public suspend fun waitForSelector(selector: String, timeoutMillis: Long = 0): Element

    /** Waits until a JavaScript expression is truthy. */
    public suspend fun waitForFunction(expression: String, timeoutMillis: Long = 0)

    // ---- reading ---------------------------------------------------------

    /** The rendered text of the document. Capped; oversized output is truncated and reported. */
    public suspend fun text(): String

    /** The document's serialised HTML. Subject to the same cap as [text]. */
    public suspend fun content(): String

    public suspend fun title(): String

    /** The document actually loaded, which after a redirect is not the requested URL. */
    public suspend fun url(): String

    /** @throws BrowserException [ErrorCode.SELECTOR_NOT_FOUND] if nothing matches. */
    public suspend fun querySelector(selector: String): Element

    /** Empty when nothing matches. Unlike [querySelector], this is not an error. */
    public suspend fun querySelectorAll(selector: String): List<Element>

    // ---- input -----------------------------------------------------------

    /** Waits for the element, scrolls it into view, then clicks it. */
    public suspend fun click(selector: String, timeoutMillis: Long = 0)

    /** Types into the element, firing the input and change events a framework needs. */
    public suspend fun type(selector: String, text: String, timeoutMillis: Long = 0)

    /** Presses a key against the focused element, for example `Enter`. */
    public suspend fun press(key: String, timeoutMillis: Long = 0)

    public suspend fun hover(selector: String, timeoutMillis: Long = 0)

    public suspend fun scrollIntoView(selector: String, timeoutMillis: Long = 0)

    public suspend fun selectOption(selector: String, value: String, timeoutMillis: Long = 0)

    // ---- script ----------------------------------------------------------

    /**
     * Evaluates an expression in page context and returns its value.
     *
     * Bounded by the script timeout and an output size cap: page content is
     * hostile, and a page that cannot be stopped from running can still be
     * stopped from spending unbounded resources.
     */
    public suspend fun evaluate(expression: String, timeoutMillis: Long = 0): String?

    /**
     * Installs a script that runs before any page script, on this and every
     * subsequent document.
     *
     * @throws BrowserException [ErrorCode.UNSUPPORTED] where the device's
     *   WebView cannot install one. There is no silent fallback.
     */
    public suspend fun addInitScript(script: String)

    /**
     * Exposes a native function to page context, over an origin-scoped channel.
     *
     * No native bridge exists by default. Exposure is explicit, typed and
     * origin-checked, and nothing on the filesystem, in app storage or in
     * secrets is reachable through it.
     */
    public suspend fun exposeFunction(
        name: String,
        allowedOrigins: Set<String>,
        handler: suspend (String) -> String?,
    )

    // ---- network ---------------------------------------------------------

    /**
     * Routes requests matching a glob pattern, such as one selecting every png,
     * jpg and woff2 under any path.
     *
     * The handler runs off the main thread for every matching resource and must
     * be cheap: a slow handler stalls page loads. Aborting images, fonts and
     * media is the single largest saving this library offers, and it needs no
     * protocol at all.
     */
    public suspend fun route(pattern: String, handler: suspend (Route) -> Unit)

    public fun onRequest(listener: (Request) -> Unit)

    public fun onResponse(listener: (Response) -> Unit)

    public suspend fun setUserAgent(userAgent: String)

    public suspend fun setExtraHeaders(headers: Map<String, String>)

    // ---- storage ---------------------------------------------------------

    /** Cookie storage is process-global: these are not scoped to this session. */
    public suspend fun cookies(url: String): List<Cookie>

    public suspend fun setCookie(cookie: Cookie)

    /**
     * Clears cookies for a domain, or all of them when [domain] is null.
     *
     * This is the isolation the platform offers. Genuinely separate jars would
     * need separate processes, so the library clears rather than pretending to
     * isolate.
     */
    public suspend fun clearCookies(domain: String? = null)

    /** Clears local storage, session storage and IndexedDB. */
    public suspend fun clearStorage()

    // ---- capture ---------------------------------------------------------

    /**
     * Captures the viewport by drawing the view.
     *
     * @throws BrowserException [ErrorCode.UNSUPPORTED] on a one-pixel session.
     *   Pass a viewport to [HeadlessBrowser.newPage] when a screenshot is wanted.
     */
    public suspend fun screenshot(format: ImageFormat = ImageFormat.Png, quality: Int = 90): ByteArray

    // ---- observation -----------------------------------------------------

    public suspend fun frames(): List<Frame>

    /** Dialogs block the page until answered. Without a listener they are dismissed. */
    public fun onDialog(listener: suspend (Dialog) -> Unit)

    public fun onConsole(listener: (ConsoleMessage) -> Unit)

    /** What this session can do. Branch on it instead of discovering a gap through a failure. */
    public suspend fun capabilities(): Capabilities

    // ---- lifecycle -------------------------------------------------------

    /**
     * Destroys the session. Every path ends here, including cancellation and
     * exceptions. Safe to call twice.
     */
    public suspend fun close()
}

public enum class ImageFormat { Png, Jpeg }
