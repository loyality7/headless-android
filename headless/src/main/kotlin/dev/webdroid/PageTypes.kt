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
    public val url: String,
    public val status: Int?,
    public val settled: Boolean,
)

/** A matched element, read at the moment of the query. */
public class Element internal constructor(
    public val tag: String,
    public val text: String,
    public val html: String,
    public val attributes: Map<String, String>,
)

/** A cookie, as the platform stores it. Cookie storage is process-global. */
public class Cookie(
    public val name: String,
    public val value: String,
    public val domain: String,
    public val path: String = "/",
    public val expiresAtMillis: Long? = null,
    public val secure: Boolean = false,
    public val httpOnly: Boolean = false,
)

/** A request the page is about to make. */
public class Request internal constructor(
    public val url: String,
    public val method: String,
    public val headers: Map<String, String>,
    public val resourceType: ResourceType,
)

/** A response the page received. Bodies are not captured in v1. */
public class Response internal constructor(
    public val url: String,
    public val status: Int,
    public val headers: Map<String, String>,
)

/** What a request is for, so rules can be written against categories rather than extensions. */
public enum class ResourceType {
    Document, Stylesheet, Script, Image, Images, Font, Fonts, Media, Fetch, Other,
}

/**
 * A request paused by a matching route rule. Exactly one of the two calls must
 * be made, or the page waits until its navigation ceiling.
 */
public class Route internal constructor(
    public val url: String,
    public val method: String,
    public val headers: Map<String, String>,
) {
    public val request: Request = Request(url, method, headers, ResourceType.Other)

    internal var action: Action = Action.Continue
    internal var syntheticResponse: android.webkit.WebResourceResponse? = null

    public fun abort() {
        action = Action.Abort
    }

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

    @Deprecated("Identical to continue(); kept only for source compatibility.", ReplaceWith("`continue`()"))
    public fun resume() {
        action = Action.Continue
    }

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
    public val type: DialogType
    public val message: String

    public suspend fun accept(promptText: String? = null)
    public suspend fun dismiss()
}

public enum class DialogType { Alert, Confirm, Prompt, BeforeUnload }

/** A console message emitted by page script. */
public class ConsoleMessage internal constructor(
    public val level: ConsoleLevel,
    public val text: String,
    public val source: String?,
    public val line: Int?,
)

public enum class ConsoleLevel { Debug, Info, Warning, Error }

/** A frame within the page. The main frame is always present. */
public interface Frame {
    public val url: String
    public val isMain: Boolean

    public suspend fun text(): String
    public suspend fun content(): String
    public suspend fun evaluate(expression: String): String?
}
