package dev.headless.browser.platform

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

public enum class ResourceType {
    Images,
    Fonts,
    Media,
}

public class Route internal constructor(
    public val url: String,
    public val method: String,
    public val headers: Map<String, String>,
) {
    internal var action: Action = Action.Continue
    internal var syntheticResponse: WebResourceResponse? = null

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
        val response = WebResourceResponse(
            mimeType,
            encoding,
            statusCode,
            reasonPhrase,
            headers,
            ByteArrayInputStream(body),
        )
        syntheticResponse = response
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

/**
 * Handles network request routing, resource blocking (images, fonts, media), and synthetic responses.
 */
internal class PlatformRouter {

    private val blockedTypes = mutableSetOf<ResourceType>()
    private val routeRules = mutableListOf<Pair<String, (Route) -> Unit>>()
    private val requestHooks = mutableListOf<(String) -> Unit>()

    fun blockTypes(vararg types: ResourceType) {
        blockedTypes.addAll(types)
    }

    fun route(patternGlob: String, handler: (Route) -> Unit) {
        routeRules.add(Pair(patternGlob, handler))
    }

    fun onRequest(hook: (String) -> Unit) {
        requestHooks.add(hook)
    }

    fun interceptRequest(request: WebResourceRequest?): WebResourceResponse? {
        if (request == null) return null
        val urlStr = request.url.toString()

        // Notify request hooks
        requestHooks.forEach { hook -> hook(urlStr) }

        // Check resource type blocking
        if (shouldBlockByResourceType(urlStr)) {
            return createEmptyResponse()
        }

        // Check custom route rules
        for ((pattern, handler) in routeRules) {
            if (matchesGlob(urlStr, pattern)) {
                val route = Route(
                    url = urlStr,
                    method = request.method ?: "GET",
                    headers = request.requestHeaders ?: emptyMap(),
                )
                handler(route)

                when (route.action) {
                    Route.Action.Abort -> return createEmptyResponse()
                    Route.Action.Fulfill -> return route.syntheticResponse ?: createEmptyResponse()
                    Route.Action.Continue -> break
                }
            }
        }

        return null
    }

    private fun shouldBlockByResourceType(url: String): Boolean {
        val lower = url.lowercase()
        if (blockedTypes.contains(ResourceType.Images)) {
            if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".svg") ||
                lower.contains("/image/") || lower.contains("/img/")
            ) {
                return true
            }
        }
        if (blockedTypes.contains(ResourceType.Fonts)) {
            if (lower.endsWith(".woff") || lower.endsWith(".woff2") || lower.endsWith(".ttf") || lower.endsWith(".otf")) {
                return true
            }
        }
        if (blockedTypes.contains(ResourceType.Media)) {
            if (lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mp3") || lower.endsWith(".wav")) {
                return true
            }
        }
        return false
    }

    private fun matchesGlob(url: String, pattern: String): Boolean {
        if (pattern == "*" || pattern == "**") return true
        val placeholder = "___DOUBLE_STAR___"
        val escaped = pattern
            .replace("**", placeholder)
            .replace(".", "\\.")
            .replace("?", "\\?")
            .replace("+", "\\+")
            .replace("*", "[^/]*")
            .replace(placeholder, ".*")
        return Regex("^$escaped$", RegexOption.IGNORE_CASE).containsMatchIn(url)
    }

    private fun createEmptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            200,
            "OK",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )
    }
}
