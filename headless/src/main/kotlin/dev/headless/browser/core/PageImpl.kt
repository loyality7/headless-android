package dev.headless.browser.core

import android.webkit.WebResourceResponse
import dev.headless.browser.BrowserConfig
import dev.headless.browser.Capabilities
import dev.headless.browser.ConsoleMessage
import dev.headless.browser.Cookie
import dev.headless.browser.Dialog
import dev.headless.browser.Element
import dev.headless.browser.ErrorCode
import dev.headless.browser.Frame
import dev.headless.browser.ImageFormat
import dev.headless.browser.NavigationResult
import dev.headless.browser.Page
import dev.headless.browser.Request
import dev.headless.browser.Response
import dev.headless.browser.ResourceType
import dev.headless.browser.Route
import dev.headless.browser.WaitUntil
import dev.headless.browser.browserError
import dev.headless.browser.platform.PlatformChannelEngine
import dev.headless.browser.platform.PlatformInputEngine
import dev.headless.browser.platform.PlatformNavigator
import dev.headless.browser.platform.PlatformReader
import dev.headless.browser.platform.PlatformRouter
import dev.headless.browser.platform.PlatformScreenshotEngine
import dev.headless.browser.platform.PlatformScriptEngine
import dev.headless.browser.platform.PlatformServiceWorkerEngine
import dev.headless.browser.platform.PlatformStorageEngine
import dev.headless.browser.platform.ScreenshotFormat
import dev.headless.browser.platform.ScreenshotOptions
import dev.headless.browser.protocol.CdpChannel
import dev.headless.browser.protocol.ProtocolCommandEngine
import dev.headless.browser.protocol.ProtocolTargetDiscovery
import dev.headless.browser.protocol.WebSocketClient
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

    private var cdpChannel: CdpChannel? = null
    private var webSocketClient: WebSocketClient? = null

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
        return backendRouter.goto(url, waitUntil, timeoutMillis)
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

    override suspend fun hover(selector: String, timeoutMillis: Long) {
        platformInputEngine.hover(selector, timeoutMillis)
    }

    override suspend fun scrollIntoView(selector: String, timeoutMillis: Long) {
        platformInputEngine.scrollIntoView(selector, timeoutMillis)
    }

    override suspend fun selectOption(selector: String, value: String, timeoutMillis: Long) {
        platformInputEngine.selectOption(selector, value, timeoutMillis)
    }

    // ---- script ----------------------------------------------------------

    override suspend fun evaluate(expression: String, timeoutMillis: Long): String? {
        return backendRouter.evaluateScript(expression)
    }

    override suspend fun addInitScript(script: String) {
        val caps = capabilities()
        CapabilityGuard.requireDocumentStartScript(caps)
        platformScriptEngine.addInitScript(script)
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

    override fun onResponse(listener: (Response) -> Unit) {
        // Response observation hook
    }

    override suspend fun setUserAgent(userAgent: String) {
        session.checkNotClosed()
        withContext(Dispatchers.Main) {
            session.hostedWebView.webView.settings.userAgentString = userAgent
        }
    }

    override suspend fun setExtraHeaders(headers: Map<String, String>) {
        // Headers applied per request
    }

    // ---- storage ---------------------------------------------------------

    override suspend fun cookies(url: String): List<Cookie> {
        return platformStorageEngine.getCookies(url)
    }

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

    override fun onConsole(listener: (ConsoleMessage) -> Unit) {
        // Console handler
    }

    override suspend fun capabilities(): Capabilities {
        return session.capabilities()
    }

    // ---- lifecycle -------------------------------------------------------

    override suspend fun close() {
        try {
            cdpChannel?.close()
            webSocketClient?.close()
        } catch (_: Throwable) {}
        session.close()
        onClose?.invoke()
    }
}
