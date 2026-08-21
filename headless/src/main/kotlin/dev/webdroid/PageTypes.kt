package dev.webdroid

/**
 * What a device can actually do.
 *
 * Every field is the result of a probe, never an inference from a version
 * number: availability is a property of the WebView build on the device, and
 * that updates independently of the operating system. A capability reported here
 * as present never raises [ErrorCode.UNSUPPORTED] in use; one reported absent
 * raises it immediately rather than timing out.
 */
public class Capabilities internal constructor(
    /** The protocol backend connected. False means the platform backend serves everything. */
    public val protocolBackend: Boolean,
    /** Scripts can be installed before any page script runs. */
    public val documentStartScript: Boolean,
    /** The typed, origin-scoped channel from page context to native code is available. */
    public val webMessageChannel: Boolean,
    /** Requests issued by a service worker can be intercepted. */
    public val serviceWorkerInterception: Boolean,
    /** The renderer reports responsiveness, so a hung page is detectable before it crashes. */
    public val rendererResponsiveness: Boolean,
    /** This session can produce a screenshot. Requires a viewport-sized session. */
    public val screenshots: Boolean,
) {
    /** A readable summary of every probed flag, for logs. */
    override fun toString(): String = buildString {
        append("Capabilities(")
        append("protocolBackend=").append(protocolBackend)
        append(", documentStartScript=").append(documentStartScript)
        append(", webMessageChannel=").append(webMessageChannel)
        append(", serviceWorkerInterception=").append(serviceWorkerInterception)
        append(", rendererResponsiveness=").append(rendererResponsiveness)
        append(", screenshots=").append(screenshots)
        append(")")
    }
}

/**
 * The outcome of a navigation.
 *
 * [settled] is the honest part: false means the ceiling was reached with work
 * still in flight, and whatever the page had at that moment is still readable.
 */
public class NavigationResult internal constructor(
    /** The document actually loaded, which after a redirect is not the requested URL. */
    public val url: String,
    /** The HTTP status of the main document, or null if it never arrived. */
    public val status: Int?,
    /** False means the ceiling was reached before [dev.webdroid.WaitUntil] was satisfied. */
    public val settled: Boolean,
)

/** A matched element, read at the moment of the query. */
public class Element internal constructor(
    /** Lower-cased tag name, e.g. `"div"`. */
    public val tag: String,
    /** Rendered text content, as `innerText` reads it. */
    public val text: String,
    /** Serialised HTML, as `outerHTML` reads it. */
    public val html: String,
    /** Every attribute present on the element at read time. */
    public val attributes: Map<String, String>,
)

/** A cookie, as the platform stores it. Cookie storage is process-global. */
public class Cookie(
    /** The cookie's name. */
    public val name: String,
    /** The cookie's value. */
    public val value: String,
    /** The domain this cookie applies to. */
    public val domain: String,
    /** The path scope this cookie applies to. */
    public val path: String = "/",
    /** Epoch milliseconds, or null for a session cookie. */
    public val expiresAtMillis: Long? = null,
    /** Sent only over HTTPS when true. */
    public val secure: Boolean = false,
    /** Not reachable from page script when true. */
    public val httpOnly: Boolean = false,
)

/** A request the page is about to make. */
public class Request internal constructor(
    /** The requested URL. */
    public val url: String,
    /** HTTP method, e.g. `"GET"`. */
    public val method: String,
    /** Request headers as the page sent them. */
    public val headers: Map<String, String>,
    /** What the request is for, as far as the platform reports it. */
    public val resourceType: ResourceType,
)

/** A response the page received. Bodies are not captured in v1. */
public class Response internal constructor(
    /** The URL that produced this response. */
    public val url: String,
    /** HTTP status code. */
    public val status: Int,
    /** Response headers as the platform reported them. */
    public val headers: Map<String, String>,
)

/** What a request is for, so rules can be written against categories rather than extensions. */
public enum class ResourceType {
    /** The main document or an iframe's document. */
    Document,

    /** A CSS file. */
    Stylesheet,

    /** A JavaScript file. */
    Script,

    /** Reserved for a future single-image classification; blocking rules match [Images]. */
    Image,

    /** What [dev.webdroid.Page.blockResourceTypes] actually matches for image requests. */
    Images,

    /** Reserved for a future single-font classification; blocking rules match [Fonts]. */
    Font,

    /** What [dev.webdroid.Page.blockResourceTypes] actually matches for font requests. */
    Fonts,

    /** Audio or video. */
    Media,

    /** An XHR/`fetch` call rather than a resource the document declared. */
    Fetch,

    /** Anything not covered by the other categories. */
    Other,
}

/**
 * A request paused by a matching route rule. Exactly one of the two calls must
 * be made, or the page waits until its navigation ceiling.
 */
public class Route internal constructor(
    /** The URL the page is requesting. */
    public val url: String,
    /** HTTP method of the paused request. */
    public val method: String,
    /** Request headers as the page sent them. */
    public val headers: Map<String, String>,
) {
    /** The same request, as the [dev.webdroid.Page.onRequest] observation shape. */
    public val request: Request = Request(url, method, headers, ResourceType.Other)

    internal var action: Action = Action.Continue
    internal var syntheticResponse: android.webkit.WebResourceResponse? = null

    /** Blocks the request entirely; the page sees it fail. */
    public fun abort() {
        action = Action.Abort
    }

    /** Answers the request with a synthetic response instead of letting it reach the network. */
    public fun fulfill(
        mimeType: String = "text/plain",
        encoding: String = "UTF-8",
        statusCode: Int = 200,
        reasonPhrase: String = "OK",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0),
    ) {
        action = Action.Fulfill
        val response = android.webkit.WebResourceResponse(
            mimeType,
            encoding,
            statusCode,
            reasonPhrase,
            headers,
            java.io.ByteArrayInputStream(body),
        )
        syntheticResponse = response
    }

    /** Identical to [`continue`]; kept only for source compatibility. */
    @Deprecated("Identical to continue(); kept only for source compatibility.", ReplaceWith("`continue`()"))
    public fun resume() {
        action = Action.Continue
    }

    /** Lets the request proceed unmodified. */
    public fun `continue`() {
        action = Action.Continue
    }

    internal enum class Action {
        Abort,
        Fulfill,
        Continue,
    }
}

/** A JavaScript dialog the page opened. Unanswered dialogs block the page. */
public interface Dialog {
    /** Which native dialog this is. */
    public val type: DialogType

    /** The text the page passed to the dialog call. */
    public val message: String

    /** Accepts the dialog, supplying [promptText] for a `prompt()`; ignored otherwise. */
    public suspend fun accept(promptText: String? = null)

    /** Dismisses the dialog, as if the user cancelled it. */
    public suspend fun dismiss()
}

/** Which native dialog a page opened. */
public enum class DialogType {
    /** A JavaScript `alert()`. */
    Alert,

    /** A JavaScript `confirm()`. */
    Confirm,

    /** A JavaScript `prompt()`. */
    Prompt,

    /** The browser's before-unload confirmation. */
    BeforeUnload,
}

/** A console message emitted by page script. */
public class ConsoleMessage internal constructor(
    /** Which console method the page called. */
    public val level: ConsoleLevel,
    /** The message text. */
    public val text: String,
    /** The script URL the message came from, when the platform reports one. */
    public val source: String?,
    /** The line number within [source], when the platform reports one. */
    public val line: Int?,
)

/** The console method the page called: `console.debug`/`log`/`warn`/`error`. */
public enum class ConsoleLevel {
    /** `console.debug`. */
    Debug,

    /** `console.log`/`console.info`. */
    Info,

    /** `console.warn`. */
    Warning,

    /** `console.error`. */
    Error,
}

/** A frame within the page. The main frame is always present. */
public interface Frame {
    /** This frame's current URL. */
    public val url: String

    /** True for the top-level document; false for an iframe. */
    public val isMain: Boolean

    /** The rendered text of this frame's document. */
    public suspend fun text(): String

    /** This frame's serialised HTML. */
    public suspend fun content(): String

    /** Evaluates an expression in this frame's context and returns its value. */
    public suspend fun evaluate(expression: String): String?
}
