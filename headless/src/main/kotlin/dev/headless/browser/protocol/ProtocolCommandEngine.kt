package dev.headless.browser.protocol

import dev.headless.browser.BrowserException
import dev.headless.browser.ErrorCode
import dev.headless.browser.browserError
import org.json.JSONObject

public data class EvaluateResult(
    public val value: Any?,
    public val type: String,
)

public data class DomNode(
    public val nodeId: Int,
    public val nodeName: String,
)

/**
 * Provides typed CDP command bindings for reliable DevTools domains (Page, Runtime, DOM, Network, Input, Console).
 * Enforces strict validation: unbound domain calls throw [ErrorCode.UNSUPPORTED], and payload deserialization errors throw [ErrorCode.PROTOCOL_ERROR].
 */
internal class ProtocolCommandEngine(
    private val channel: CdpChannel,
) {
    public companion object {
        val SUPPORTED_DOMAINS: Set<String> = setOf(
            "Page",
            "Runtime",
            "DOM",
            "Network",
            "Input",
            "Console",
        )
    }

    /**
     * Navigates to the specified URL via `Page.navigate`.
     */
    suspend fun pageNavigate(url: String): String {
        validateDomain("Page")
        val params = JSONObject().apply { put("url", url) }
        val res = channel.sendCommand("Page.navigate", params)
        return try {
            res.optString("frameId", "")
        } catch (e: Exception) {
            throw browserError(ErrorCode.PROTOCOL_ERROR, "Failed to deserialize Page.navigate response", e)
        }
    }

    /**
     * Enables Page domain notifications via `Page.enable`.
     */
    suspend fun pageEnable() {
        validateDomain("Page")
        channel.sendCommand("Page.enable")
    }

    /**
     * Reloads the page via `Page.reload`.
     */
    suspend fun pageReload(ignoreCache: Boolean = false) {
        validateDomain("Page")
        val params = JSONObject().apply { put("ignoreCache", ignoreCache) }
        channel.sendCommand("Page.reload", params)
    }

    /**
     * Evaluates a JavaScript expression via `Runtime.evaluate`.
     */
    suspend fun runtimeEvaluate(expression: String): EvaluateResult {
        validateDomain("Runtime")
        val params = JSONObject().apply {
            put("expression", expression)
            put("returnByValue", true)
        }
        val res = channel.sendCommand("Runtime.evaluate", params)
        return try {
            val resultObj = res.optJSONObject("result")
                ?: throw browserError(ErrorCode.PROTOCOL_ERROR, "Runtime.evaluate response missing result object")
            val type = resultObj.optString("type", "undefined")
            val value = if (resultObj.has("value")) resultObj.get("value") else null
            EvaluateResult(value = value, type = type)
        } catch (e: BrowserException) {
            throw e
        } catch (e: Exception) {
            throw browserError(ErrorCode.PROTOCOL_ERROR, "Failed to deserialize Runtime.evaluate payload", e)
        }
    }

    /**
     * Enables Runtime domain events via `Runtime.enable`.
     */
    suspend fun runtimeEnable() {
        validateDomain("Runtime")
        channel.sendCommand("Runtime.enable")
    }

    /**
     * Fetches root DOM document node via `DOM.getDocument`.
     */
    suspend fun domGetDocument(): DomNode {
        validateDomain("DOM")
        val res = channel.sendCommand("DOM.getDocument")
        return try {
            val rootObj = res.optJSONObject("root")
                ?: throw browserError(ErrorCode.PROTOCOL_ERROR, "DOM.getDocument response missing root object")
            val nodeId = rootObj.optInt("nodeId", -1)
            val nodeName = rootObj.optString("nodeName", "")
            DomNode(nodeId = nodeId, nodeName = nodeName)
        } catch (e: BrowserException) {
            throw e
        } catch (e: Exception) {
            throw browserError(ErrorCode.PROTOCOL_ERROR, "Failed to deserialize DOM.getDocument payload", e)
        }
    }

    /**
     * Executes `DOM.querySelector` for a given selector.
     */
    suspend fun domQuerySelector(nodeId: Int, selector: String): Int {
        validateDomain("DOM")
        val params = JSONObject().apply {
            put("nodeId", nodeId)
            put("selector", selector)
        }
        val res = channel.sendCommand("DOM.querySelector", params)
        return try {
            res.optInt("nodeId", 0)
        } catch (e: Exception) {
            throw browserError(ErrorCode.PROTOCOL_ERROR, "Failed to deserialize DOM.querySelector response", e)
        }
    }

    /**
     * Enables Network domain tracking via `Network.enable`.
     */
    suspend fun networkEnable() {
        validateDomain("Network")
        channel.sendCommand("Network.enable")
    }

    /**
     * Dispatches mouse event via `Input.dispatchMouseEvent`.
     */
    suspend fun inputDispatchMouseEvent(type: String, x: Double, y: Double, button: String = "left") {
        validateDomain("Input")
        val params = JSONObject().apply {
            put("type", type)
            put("x", x)
            put("y", y)
            put("button", button)
        }
        channel.sendCommand("Input.dispatchMouseEvent", params)
    }

    /**
     * Enables Console domain logs via `Console.enable`.
     */
    suspend fun consoleEnable() {
        validateDomain("Console")
        channel.sendCommand("Console.enable")
    }

    /**
     * Executes an arbitrary CDP command, ensuring unbound domains are rejected with `UNSUPPORTED`.
     */
    suspend fun executeCommand(method: String, params: JSONObject = JSONObject()): JSONObject {
        val domain = method.substringBefore(".")
        validateDomain(domain)
        return channel.sendCommand(method, params)
    }

    private fun validateDomain(domain: String) {
        if (!SUPPORTED_DOMAINS.contains(domain)) {
            throw browserError(
                ErrorCode.UNSUPPORTED,
                "CDP domain '$domain' is unbound/unsupported by protocol engine on this device",
            )
        }
    }
}
