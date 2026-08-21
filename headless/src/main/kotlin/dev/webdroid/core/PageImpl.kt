package dev.webdroid.core

import android.webkit.WebResourceResponse
import dev.webdroid.BrowserConfig
import dev.webdroid.Capabilities
import dev.webdroid.ConsoleMessage
import dev.webdroid.Cookie
import dev.webdroid.Dialog
import dev.webdroid.Element
import dev.webdroid.ErrorCode
import dev.webdroid.Frame
import dev.webdroid.ImageFormat
import dev.webdroid.NavigationResult
import dev.webdroid.Page
import dev.webdroid.Request
import dev.webdroid.Response
import dev.webdroid.ResourceType
import dev.webdroid.Route
import dev.webdroid.WaitUntil
import dev.webdroid.browserError
import dev.webdroid.platform.PlatformChannelEngine
import dev.webdroid.platform.PlatformInputEngine
import dev.webdroid.platform.PlatformNavigator
import dev.webdroid.platform.PlatformReader
import dev.webdroid.platform.PlatformRouter
import dev.webdroid.platform.PlatformScreenshotEngine
import dev.webdroid.platform.PlatformScriptEngine
import dev.webdroid.platform.PlatformServiceWorkerEngine
import dev.webdroid.platform.PlatformStorageEngine
import dev.webdroid.platform.ScreenshotFormat
import dev.webdroid.platform.ScreenshotOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Implementation of [Page] that delegates operations across engines via [BackendRouter].
 */
public class PageImpl internal constructor(
    private val session: PageSession,
    private val config: BrowserConfig,
    private val onClose: (() -> Unit)? = null,
) : Page {

    private val platformRouter = PlatformRouter()
    private val platformNavigator = PlatformNavigator(session, config, platformRouter)
    private val platformScriptEngine = PlatformScriptEngine(session, config)
    private val platformReader = PlatformReader(session, platformScriptEngine, config)
    private val platformInputEngine = PlatformInputEngine(session, platformScriptEngine, platformReader, config)
    private val platformScreenshotEngine = PlatformScreenshotEngine(session, config)
    private val platformStorageEngine = PlatformStorageEngine(session, platformScriptEngine, config)
    private val platformChannelEngine = PlatformChannelEngine(session, config)
    // Deliberately not wired up automatically: ServiceWorkerControllerCompat is
    // process-global, so calling setupServiceWorkerInterception() from every
    // PageImpl's constructor points that global client at whichever page's
    // router was built most recently — a later, unrelated page's requests then
    // route through an earlier page's (possibly already-closed) router. Exactly
    // the shared-global-state failure shape SessionRegistry exists to avoid
    // elsewhere in this class; confirmed live in the instrumented suite, where
    // an unrelated later test failed only when run after a PageImpl-based one.
    // No public Page method currently opts a caller into this, so it stays
    // constructed but unused until that's designed deliberately.
    private val platformServiceWorkerEngine = PlatformServiceWorkerEngine(platformRouter)

    private val backendRouter = BackendRouter(
        session = session,
        config = config,
        platformNavigator = platformNavigator,
        platformReader = platformReader,
        platformScriptEngine = platformScriptEngine,
        platformInputEngine = platformInputEngine,
        platformScreenshotEngine = platformScreenshotEngine,
        platformRouter = platformRouter,
    )

    private var protocolConnection: dev.webdroid.protocol.ProtocolConnector.Connection? = null

    /** Direct access to block resource types (images, fonts, media). */
    public fun blockTypes(vararg types: ResourceType) {
        platformRouter.blockTypes(*types)
    }

    // ---- navigation ------------------------------------------------------

    override suspend fun goto(
        url: String,
        waitUntil: WaitUntil,
        timeoutMillis: Long,
    ): NavigationResult {
        val result = backendRouter.goto(url, waitUntil, timeoutMillis)
        connectProtocolBackendIfNeeded(result.url)
        return result
    }

    /**
     * Opens the protocol backend's connection for this session, once, the first
     * time a navigation lands on a device where it is enabled and reachable.
     *
     * Silent on failure: [ProtocolConnector.connect] already returns null
     * rather than throwing, so a device that cannot connect stays on the
     * platform backend without the caller noticing anything went wrong.
     */
    private suspend fun connectProtocolBackendIfNeeded(currentUrl: String) {
        if (!config.enableProtocolBackend || protocolConnection != null) return
        val caps = session.capabilities()
        if (!caps.protocolBackend) return

        val connection = dev.webdroid.protocol.ProtocolConnector.connect(session, config, currentUrl)
        if (connection != null) {
            protocolConnection = connection
            backendRouter.setProtocolEngine(connection.engine)
        }
    }

    override suspend fun waitForSelector(selector: String, timeoutMillis: Long): Element {
        return platformReader.waitForSelector(selector, timeoutMillis)
    }

    override suspend fun waitForFunction(expression: String, timeoutMillis: Long) {
        val effectiveTimeout = if (timeoutMillis > 0) timeoutMillis else config.timeouts.settleMillis
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < effectiveTimeout) {
            val result = evaluate(expression)
            if (result != null && result != "false" && result != "null" && result != "0" && result != "\"\"") {
                return
            }
            kotlinx.coroutines.delay(100)
        }
        throw browserError(ErrorCode.TIMEOUT, "waitForFunction timed out after ${effectiveTimeout}ms for expression: $expression")
    }

    // ---- reading ---------------------------------------------------------

    override suspend fun text(): String = backendRouter.text()

    override suspend fun content(): String = backendRouter.content()

    /** Delegates to [backendRouter]. */
    override suspend fun title(): String = backendRouter.title()

    override suspend fun url(): String {
        session.checkNotClosed()
        return withContext(Dispatchers.Main) {
            session.hostedWebView.webView.url ?: ""
        }
    }

    override suspend fun querySelector(selector: String): Element {
        return backendRouter.querySelector(selector)
            ?: throw browserError(ErrorCode.SELECTOR_NOT_FOUND, "No element matching '$selector'")
    }

    override suspend fun querySelectorAll(selector: String): List<Element> {
        return platformReader.querySelectorAll(selector)
    }

    // ---- input -----------------------------------------------------------

    override suspend fun click(selector: String, timeoutMillis: Long) {
        backendRouter.click(selector)
    }

    override suspend fun type(selector: String, text: String, timeoutMillis: Long) {
        platformInputEngine.type(selector, text, timeoutMillis)
    }

    override suspend fun press(key: String, timeoutMillis: Long) {
        platformInputEngine.press("body", key, timeoutMillis)
    }

    /** Delegates to [platformInputEngine]. */
    override suspend fun hover(selector: String, timeoutMillis: Long) {
        platformInputEngine.hover(selector, timeoutMillis)
    }

    /** Delegates to [platformInputEngine]. */
    override suspend fun scrollIntoView(selector: String, timeoutMillis: Long) {
        platformInputEngine.scrollIntoView(selector, timeoutMillis)
    }

    /** Delegates to [platformInputEngine]. */
    override suspend fun selectOption(selector: String, value: String, timeoutMillis: Long) {
        platformInputEngine.selectOption(selector, value, timeoutMillis)
    }

    override suspend fun fillTime(selector: String, time: String, timeoutMillis: Long) {
        platformInputEngine.fillTime(selector, time, timeoutMillis)
    }

    // ---- script ----------------------------------------------------------

    override suspend fun evaluate(expression: String, timeoutMillis: Long): String? {
        return backendRouter.evaluateScript(expression)
    }

    override suspend fun addInitScript(script: String, allowedOrigins: Set<String>) {
        val caps = capabilities()
        CapabilityGuard.requireDocumentStartScript(caps)
        platformScriptEngine.addInitScript(script, allowedOrigins)
    }

    override suspend fun exposeFunction(
        name: String,
        allowedOrigins: Set<String>,
        handler: suspend (String) -> String?,
    ) {
        val caps = capabilities()
        CapabilityGuard.requireWebMessageChannel(caps)
        platformChannelEngine.exposeFunction(name, allowedOrigins, handler)
    }

    // ---- network ---------------------------------------------------------

    override suspend fun route(pattern: String, handler: suspend (Route) -> Unit) {
        platformRouter.route(pattern) { route ->
            kotlinx.coroutines.runBlocking {
                handler(route)
            }
        }
    }

    override suspend fun blockResourceTypes(vararg types: ResourceType) {
        platformRouter.blockTypes(*types)
    }

    /** Delegates to [platformRouter], synthesizing a [Request] per intercepted URL. */
    override fun onRequest(listener: (Request) -> Unit) {
        platformRouter.onRequest { urlStr ->
            listener(
                Request(
                    url = urlStr,
                    method = "GET",
                    headers = emptyMap(),
                    resourceType = ResourceType.Other,
                )
            )
        }
    }

    /** Not yet implemented: registered but never invoked. */
    override fun onResponse(listener: (Response) -> Unit) {
        // Response observation hook
    }

    /** Sets the WebView's `User-Agent` string directly. */
    override suspend fun setUserAgent(userAgent: String) {
        session.checkNotClosed()
        withContext(Dispatchers.Main) {
            session.hostedWebView.webView.settings.userAgentString = userAgent
        }
    }

    /** Not yet implemented: accepted but never applied to requests. */
    override suspend fun setExtraHeaders(headers: Map<String, String>) {
        // Headers applied per request
    }

    // ---- storage ---------------------------------------------------------

    override suspend fun cookies(url: String): List<Cookie> {
        return platformStorageEngine.getCookies(url)
    }

    /** Delegates to [platformStorageEngine]. */
    override suspend fun setCookie(cookie: Cookie) {
        platformStorageEngine.setCookie(cookie)
    }

    override suspend fun clearCookies(domain: String?) {
        platformStorageEngine.clearCookies()
    }

    override suspend fun clearStorage() {
        platformStorageEngine.clearStorage()
    }

    // ---- capture ---------------------------------------------------------

    override suspend fun screenshot(format: ImageFormat, quality: Int): ByteArray {
        val caps = capabilities()
        CapabilityGuard.requireScreenshots(caps)
        val screenshotFormat = when (format) {
            ImageFormat.Png -> ScreenshotFormat.PNG
            ImageFormat.Jpeg -> ScreenshotFormat.JPEG
        }
        return platformScreenshotEngine.screenshot(ScreenshotOptions(format = screenshotFormat, quality = quality))
    }

    // ---- observation -----------------------------------------------------

    /** Reports only the main frame; the WebView does not expose child frames. */
    override suspend fun frames(): List<Frame> {
        return listOf(object : Frame {
            override val url: String get() = session.hostedWebView.webView.url ?: ""
            override val isMain: Boolean get() = true
            override suspend fun text(): String = this@PageImpl.text()
            override suspend fun content(): String = this@PageImpl.content()
            override suspend fun evaluate(expression: String): String? = this@PageImpl.evaluate(expression)
        })
    }

    override fun onDialog(listener: suspend (Dialog) -> Unit) {
        // Dialog handler
    }

    /** Not yet implemented: registered but never invoked. */
    override fun onConsole(listener: (ConsoleMessage) -> Unit) {
        // Console handler
    }

    override suspend fun capabilities(): Capabilities {
        return session.capabilities()
    }

    // ---- lifecycle -------------------------------------------------------

    override suspend fun close() {
        try {
            protocolConnection?.close()
        } catch (_: Throwable) {}
        session.close()
        onClose?.invoke()
    }
}
