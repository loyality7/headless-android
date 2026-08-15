package dev.headless.browser.platform

import dev.headless.browser.BrowserConfig
import dev.headless.browser.BrowserException
import dev.headless.browser.Element
import dev.headless.browser.ErrorCode
import dev.headless.browser.browserError
import dev.headless.browser.core.PageSession
import dev.headless.browser.core.SessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Handles DOM reading, element queries, and selector waiting on the platform backend.
 */
internal class PlatformReader(
    private val session: PageSession,
    private val scriptEngine: PlatformScriptEngine,
    private val config: BrowserConfig,
) {

    /**
     * Reads the current document title.
     */
    suspend fun title(): String = session.runInState(SessionState.Operating) {
        val raw = scriptEngine.evaluate("document.title") ?: ""
        parseJsonString(raw)
    }

    /**
     * Reads the inner text of the document body, capped to prevent OOM.
     */
    suspend fun text(): String = session.runInState(SessionState.Operating) {
        val raw = scriptEngine.evaluate("(document.body ? document.body.innerText : '')") ?: ""
        parseJsonString(raw)
    }

    /**
     * Reads the outer HTML of the document element, capped to prevent OOM.
     */
    suspend fun content(): String = session.runInState(SessionState.Operating) {
        val raw = scriptEngine.evaluate("(document.documentElement ? document.documentElement.outerHTML : '')") ?: ""
        parseJsonString(raw)
    }

    /**
     * Queries the first element matching [selector].
     *
     * @return [Element] if found, `null` if no element matches.
     */
    suspend fun querySelector(selector: String): Element? = session.runInState(SessionState.Operating) {
        val escaped = JSONObject.quote(selector)
        val script = """
            (function() {
                var el = document.querySelector($escaped);
                if (!el) return null;
                var attrs = {};
                if (el.attributes) {
                    for (var i = 0; i < el.attributes.length; i++) {
                        var a = el.attributes[i];
                        attrs[a.name] = a.value;
                    }
                }
                return JSON.stringify({
                    tag: el.tagName ? el.tagName.toLowerCase() : '',
                    text: (el.innerText || el.textContent || '').substring(0, 50000),
                    html: (el.outerHTML || '').substring(0, 50000),
                    attributes: attrs
                });
            })();
        """.trimIndent()

        val jsonStr = scriptEngine.evaluate(script) ?: return@runInState null
        parseElementJson(jsonStr)
    }

    /**
     * Queries all elements matching [selector].
     */
    suspend fun querySelectorAll(selector: String): List<Element> = session.runInState(SessionState.Operating) {
        val escaped = JSONObject.quote(selector)
        val script = """
            (function() {
                var nodes = document.querySelectorAll($escaped);
                var res = [];
                var count = Math.min(nodes.length, 200);
                for (var n = 0; n < count; n++) {
                    var el = nodes[n];
                    var attrs = {};
                    if (el.attributes) {
                        for (var i = 0; i < el.attributes.length; i++) {
                            var a = el.attributes[i];
                            attrs[a.name] = a.value;
                        }
                    }
                    res.push({
                        tag: el.tagName ? el.tagName.toLowerCase() : '',
                        text: (el.innerText || el.textContent || '').substring(0, 20000),
                        html: (el.outerHTML || '').substring(0, 20000),
                        attributes: attrs
                    });
                }
                return JSON.stringify(res);
            })();
        """.trimIndent()

        val jsonStr = scriptEngine.evaluate(script) ?: return@runInState emptyList()
        parseElementListJson(jsonStr)
    }

    /**
     * Waits until an element matching [selector] appears in the DOM.
     *
     * @return [Element] as soon as it appears.
     * @throws BrowserException [ErrorCode.SELECTOR_NOT_FOUND] if element does not appear before timeout.
     */
    suspend fun waitForSelector(
        selector: String,
        timeoutMillis: Long = 0,
    ): Element = session.runInState(SessionState.Operating) {
        val effectiveTimeout = if (timeoutMillis > 0) timeoutMillis else config.timeouts.scriptMillis
        val pollInterval = 50L

        val result = withTimeoutOrNull(effectiveTimeout) {
            while (true) {
                val element = querySelector(selector)
                if (element != null) {
                    return@withTimeoutOrNull element
                }
                delay(pollInterval)
            }
            null
        }

        result ?: throw browserError(
            ErrorCode.SELECTOR_NOT_FOUND,
            "selector '$selector' matched nothing before deadline of ${effectiveTimeout}ms",
        )
    }

    private fun parseJsonString(raw: String): String {
        if (raw.startsWith("\"") && raw.endsWith("\"")) {
            try {
                return JSONObject("{\"v\":$raw}").getString("v")
            } catch (_: Exception) {
                return raw.removeSurrounding("\"")
            }
        }
        return raw
    }

    private fun parseElementJson(rawJson: String): Element? {
        val jsonStr = parseJsonString(rawJson)
        if (jsonStr.isEmpty() || jsonStr == "null") return null

        return try {
            val obj = JSONObject(jsonStr)
            val tag = obj.optString("tag", "")
            val text = obj.optString("text", "")
            val html = obj.optString("html", "")
            val attrsObj = obj.optJSONObject("attributes")
            val attrs = mutableMapOf<String, String>()
            attrsObj?.keys()?.forEach { key ->
                attrs[key] = attrsObj.getString(key)
            }
            Element(tag, text, html, attrs)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseElementListJson(rawJson: String): List<Element> {
        val jsonStr = parseJsonString(rawJson)
        if (jsonStr.isEmpty() || jsonStr == "null") return emptyList()

        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<Element>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val tag = obj.optString("tag", "")
                val text = obj.optString("text", "")
                val html = obj.optString("html", "")
                val attrsObj = obj.optJSONObject("attributes")
                val attrs = mutableMapOf<String, String>()
                attrsObj?.keys()?.forEach { key ->
                    attrs[key] = attrsObj.getString(key)
                }
                list.add(Element(tag, text, html, attrs))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }
}
