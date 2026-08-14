package dev.headless.browser

/**
 * How a browser instance behaves. Every default is the conservative choice: one
 * session, no protocol backend, no private addresses.
 */
public class BrowserConfig(
    /**
     * Enables the protocol backend, which requires switching on web contents
     * debugging.
     *
     * That switch is process-wide and makes every WebView in the host app
     * inspectable over USB for as long as it is on. Android documents this as a
     * production security liability, so it is an explicit opt-in and never a
     * silent default. With it off, the library runs on the platform backend
     * alone at reduced capability.
     */
    public val enableProtocolBackend: Boolean = false,

    /** Ceilings for each stage. No call runs without one. */
    public val timeouts: Timeouts = Timeouts(),

    /**
     * Concurrent sessions. One is what a phone affords: a loaded page costs
     * 100-250 MB and the system kills the app, not the page.
     */
    public val maxSessions: Int = 1,

    /**
     * Permits navigation to private, loopback, link-local and cloud metadata
     * addresses. Off by default, and re-checked after every redirect.
     */
    public val allowPrivateAddresses: Boolean = false,

    /** Replaces the WebView's user agent for every session. */
    public val userAgent: String? = null,
) {
    init {
        require(maxSessions >= 1) { "maxSessions must be at least 1, was $maxSessions" }
    }
}

/**
 * Ceilings, in milliseconds. Each stage is bounded separately, and [total]
 * bounds the whole task even when no single stage exceeds its own limit.
 */
public class Timeouts(
    public val navigationMillis: Long = 30_000,
    public val settleMillis: Long = 10_000,
    public val scriptMillis: Long = 10_000,
    public val totalMillis: Long = 120_000,
) {
    init {
        require(navigationMillis > 0) { "navigationMillis must be positive, was $navigationMillis" }
        require(settleMillis > 0) { "settleMillis must be positive, was $settleMillis" }
        require(scriptMillis > 0) { "scriptMillis must be positive, was $scriptMillis" }
        require(totalMillis > 0) { "totalMillis must be positive, was $totalMillis" }
        require(totalMillis >= navigationMillis) {
            "totalMillis ($totalMillis) cannot be smaller than navigationMillis ($navigationMillis)"
        }
    }
}

/**
 * The size a session renders at.
 *
 * A session created with a null viewport is one pixel: enough for text, the DOM
 * and script, and far cheaper. Screenshots and layout-dependent pages need a
 * real viewport, chosen when the session is created and fixed for its lifetime.
 */
public class Viewport(
    public val width: Int,
    public val height: Int,
    public val deviceScaleFactor: Float = 1f,
) {
    init {
        require(width > 0) { "width must be positive, was $width" }
        require(height > 0) { "height must be positive, was $height" }
        require(deviceScaleFactor > 0f) { "deviceScaleFactor must be positive, was $deviceScaleFactor" }
    }

    public companion object {
        /** A common phone viewport, for callers that do not care to pick one. */
        public val Phone: Viewport = Viewport(412, 915, 2.625f)
    }
}

/**
 * When a navigation or wait is considered finished.
 *
 * Each carries a hard ceiling. On expiry the call returns what exists, flagged
 * as unsettled, rather than discarding partial content that is usually still
 * useful.
 */
public sealed interface WaitUntil {
    /** The load event fired. */
    public data object Load : WaitUntil

    /** The DOM content loaded event fired. Earlier than [Load], and often enough. */
    public data object DomReady : WaitUntil

    /** No in-flight requests for [quietMillis]. */
    public data class NetworkIdle(public val quietMillis: Long = 500) : WaitUntil

    /**
     * No DOM mutations for [quietMillis].
     *
     * Independent of network activity, so it is the signal that works on both
     * backends and the one to reach for on a client-rendered page.
     */
    public data class DomStable(public val quietMillis: Long = 500) : WaitUntil

    /** A caller predicate: a JavaScript expression polled until it is truthy. */
    public data class Custom(public val expression: String) : WaitUntil
}
