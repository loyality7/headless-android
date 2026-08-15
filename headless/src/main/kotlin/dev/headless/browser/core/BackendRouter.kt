package dev.headless.browser.core

import android.util.Log
import dev.headless.browser.BrowserConfig
import dev.headless.browser.Element
import dev.headless.browser.NavigationResult
import dev.headless.browser.WaitUntil
import dev.headless.browser.platform.PlatformInputEngine
import dev.headless.browser.platform.PlatformNavigator
import dev.headless.browser.platform.PlatformReader
import dev.headless.browser.platform.PlatformRouter
import dev.headless.browser.platform.PlatformScreenshotEngine
import dev.headless.browser.platform.PlatformScriptEngine
import dev.headless.browser.platform.ScreenshotFormat
import dev.headless.browser.platform.ScreenshotOptions
import dev.headless.browser.protocol.ProtocolCommandEngine

/**
 * Unified backend router that dynamically selects the optimal backend per capability.
 *
 * Routing Rules:
 * - Request blocking is ALWAYS served by the platform backend ([PlatformRouter]).
 * - Text, DOM, script evaluation, and input prefer the protocol backend ([ProtocolCommandEngine])
 *   when [Capabilities.protocolBackend] is true, seamlessly falling back to platform engines when false.
 * - Screenshots use the platform screenshot engine ([PlatformScreenshotEngine]).
 * - Selection decisions are logged for diagnostic visibility.
 */
internal class BackendRouter(
    private val session: PageSession,
    private val config: BrowserConfig,
    private val platformNavigator: PlatformNavigator,
    private val platformReader: PlatformReader,
    private val platformScriptEngine: PlatformScriptEngine,
    private val platformInputEngine: PlatformInputEngine,
    private val platformScreenshotEngine: PlatformScreenshotEngine,
    private val platformRouter: PlatformRouter,
    private var protocolEngine: ProtocolCommandEngine? = null,
) {

    fun setProtocolEngine(engine: ProtocolCommandEngine?) {
        this.protocolEngine = engine
    }

    suspend fun goto(url: String, waitUntil: WaitUntil, timeoutMillis: Long): NavigationResult {
        logRouting("goto", "PlatformNavigator")
        return platformNavigator.goto(url, waitUntil, timeoutMillis)
    }

    suspend fun evaluateScript(expression: String): String? {
        val caps = session.capabilities()
        val engine = protocolEngine
        return if (caps.protocolBackend && engine != null) {
            logRouting("evaluateScript", "ProtocolCommandEngine")
            val evalRes = engine.runtimeEvaluate(expression)
            evalRes.value?.toString()
        } else {
            logRouting("evaluateScript", "PlatformScriptEngine")
            platformScriptEngine.evaluate(expression)
        }
    }

    suspend fun text(): String {
        val caps = session.capabilities()
        val engine = protocolEngine
        return if (caps.protocolBackend && engine != null) {
            logRouting("text", "ProtocolCommandEngine")
            val evalRes = engine.runtimeEvaluate("document.body ? document.body.innerText : ''")
            evalRes.value?.toString() ?: ""
        } else {
            logRouting("text", "PlatformReader")
            platformReader.text()
        }
    }

    suspend fun content(): String {
        val caps = session.capabilities()
        val engine = protocolEngine
        return if (caps.protocolBackend && engine != null) {
            logRouting("content", "ProtocolCommandEngine")
            val evalRes = engine.runtimeEvaluate("document.documentElement.outerHTML")
            evalRes.value?.toString() ?: ""
        } else {
            logRouting("content", "PlatformReader")
            platformReader.content()
        }
    }

    suspend fun title(): String {
        val caps = session.capabilities()
        val engine = protocolEngine
        return if (caps.protocolBackend && engine != null) {
            logRouting("title", "ProtocolCommandEngine")
            val evalRes = engine.runtimeEvaluate("document.title")
            evalRes.value?.toString() ?: ""
        } else {
            logRouting("title", "PlatformReader")
            platformReader.title()
        }
    }

    suspend fun querySelector(selector: String): Element? {
        val caps = session.capabilities()
        val engine = protocolEngine
        return if (caps.protocolBackend && engine != null) {
            logRouting("querySelector", "ProtocolCommandEngine")
            try {
                val rootNode = engine.domGetDocument()
                val targetNodeId = engine.domQuerySelector(rootNode.nodeId, selector)
                if (targetNodeId == 0) {
                    null
                } else {
                    val evalRes = engine.runtimeEvaluate("""
                        (function() {
                            var el = document.querySelector("$selector");
                            if (!el) return null;
                            var attrs = {};
                            for (var i = 0; i < el.attributes.length; i++) {
                                var a = el.attributes[i];
                                attrs[a.name] = a.value;
                            }
                            return JSON.stringify({
                                tag: el.tagName.toLowerCase(),
                                text: el.innerText || '',
                                html: el.outerHTML || '',
                                attributes: attrs
                            });
                        })()
                    """.trimIndent())
                    val jsonStr = evalRes.value?.toString()
                    if (jsonStr != null && jsonStr != "null") {
                        val json = org.json.JSONObject(jsonStr)
                        val attrsMap = mutableMapOf<String, String>()
                        val attrsObj = json.optJSONObject("attributes")
                        attrsObj?.keys()?.forEach { k ->
                            attrsMap[k] = attrsObj.getString(k)
                        }
                        Element(
                            tag = json.getString("tag"),
                            text = json.getString("text"),
                            html = json.getString("html"),
                            attributes = attrsMap,
                        )
                    } else {
                        null
                    }
                }
            } catch (_: Throwable) {
                platformReader.querySelector(selector)
            }
        } else {
            logRouting("querySelector", "PlatformReader")
            platformReader.querySelector(selector)
        }
    }

    suspend fun click(selector: String) {
        val caps = session.capabilities()
        val engine = protocolEngine
        if (caps.protocolBackend && engine != null) {
            logRouting("click", "ProtocolCommandEngine")
            val evalRes = engine.runtimeEvaluate("""
                (function() {
                    var el = document.querySelector("$selector");
                    if (!el) return false;
                    var rect = el.getBoundingClientRect();
                    return JSON.stringify({x: rect.left + rect.width/2, y: rect.top + rect.height/2});
                })()
            """.trimIndent())
            val posStr = evalRes.value?.toString()
            if (posStr != null && posStr != "false") {
                val posJson = org.json.JSONObject(posStr)
                val x = posJson.getDouble("x")
                val y = posJson.getDouble("y")
                engine.inputDispatchMouseEvent("mousePressed", x, y, button = "left")
                engine.inputDispatchMouseEvent("mouseReleased", x, y, button = "left")
                return
            }
        }
        logRouting("click", "PlatformInputEngine")
        platformInputEngine.click(selector)
    }

    suspend fun screenshot(format: ScreenshotFormat = ScreenshotFormat.PNG, quality: Int = 100): ByteArray {
        logRouting("screenshot", "PlatformScreenshotEngine")
        return platformScreenshotEngine.screenshot(ScreenshotOptions(format = format, quality = quality))
    }

    fun routeRequestBlocking(): PlatformRouter {
        logRouting("requestBlocking", "PlatformRouter")
        return platformRouter
    }

    private fun logRouting(operation: String, backend: String) {
        runCatching {
            Log.d(TAG, "Routing operation '$operation' to backend '$backend'")
        }
    }

    companion object {
        private const val TAG = "BackendRouter"
    }
}
