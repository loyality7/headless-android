package dev.headless.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spike section 0: is the control endpoint reachable from inside the app, and
 * what does this build's protocol surface actually answer?
 *
 * The whole protocol backend is gated on these passing. If they do not, the
 * platform backend is the library.
 */
@RunWith(AndroidJUnit4::class)
class SocketSpikeTest {

    @Before
    fun setUp() = enableDebugging()

    @Test
    fun socketIsReachableInProcess() = withHost { host ->
        val webView = onMain { host.addWebView(1, 1) }
        host.load(webView, "about:blank")

        val (name, socket) = SocketDiscovery.connectAny()
        socket.use {
            record("socket.name", name)
            record("socket.candidates", SocketDiscovery.candidates())
            record("socket.procNetUnix", SocketDiscovery.fromProcNetUnix())
        }
        onMain { host.destroyWebView(webView) }
    }

    @Test
    fun discoveryEndpointsParse() = withHost { host ->
        val webView = onMain { host.addWebView(1, 1) }
        host.load(webView, "about:blank")
        val (name, socket) = SocketDiscovery.connectAny()
        socket.close()

        val version = DevToolsHttp.version(name)
        record("json.version", version)

        val targets = DevToolsHttp.targets(name)
        record("json.targetCount", targets.size)
        record("json.targets", targets.map { it.optString("url") })
        assertTrue("no page target exposed", targets.any { it.has("webSocketDebuggerUrl") })

        onMain { host.destroyWebView(webView) }
    }

    @Test
    fun protocolRoundTrips() = withHost { host ->
        val webView = onMain { host.addWebView(1, 1) }
        host.load(webView, "about:blank")

        session(host) { cdp ->
            assertEquals("\"spike\"", cdp.evaluate("'spike'"))
            cdp.send("Page.enable")
            cdp.send(
                "Page.navigate",
                JSONObject().put("url", "data:text/html,<h1 id=t>hello</h1>")
            )
            // Poll rather than sleep: the document is swapped asynchronously.
            val title = waitFor(10_000) {
                cdp.evaluate("document.getElementById('t')?.textContent ?? null")
                    .takeIf { it == "\"hello\"" }
            }
            assertEquals("\"hello\"", title)
        }

        onMain { host.destroyWebView(webView) }
    }

    /** The capability matrix. Output is the table the go/no-go decision is written from. */
    @Test
    fun capabilityMatrix() = withHost { host ->
        val webView = onMain { host.addWebView(360, 640) }
        host.load(webView, "about:blank")

        val probes = listOf(
            "Page.enable" to JSONObject(),
            "Runtime.enable" to JSONObject(),
            "DOM.enable" to JSONObject(),
            "DOM.getDocument" to JSONObject(),
            "Network.enable" to JSONObject(),
            "Log.enable" to JSONObject(),
            "Input.dispatchKeyEvent" to JSONObject().put("type", "rawKeyDown"),
            "Emulation.setDeviceMetricsOverride" to JSONObject()
                .put("width", 360).put("height", 640).put("deviceScaleFactor", 1).put("mobile", true),
            "Fetch.enable" to JSONObject(),
            "Page.captureScreenshot" to JSONObject(),
            "Page.printToPDF" to JSONObject(),
            "Target.getTargets" to JSONObject(),
            "Accessibility.enable" to JSONObject(),
            "Tracing.getCategories" to JSONObject(),
        )

        session(host) { cdp ->
            for ((method, params) in probes) {
                val supported = runCatching { cdp.supports(method, params) }.getOrElse { false }
                record("capability.$method", supported)
            }
        }

        onMain { host.destroyWebView(webView) }
    }
}

/** Opens a CDP session against the newest page target and closes it on every path. */
internal fun <T> session(host: HostActivity, block: (CdpSession) -> T): T {
    val (name, socket) = SocketDiscovery.connectAny()
    socket.close()
    val target = DevToolsHttp.targets(name).first { it.has("webSocketDebuggerUrl") }
    val path = target.getString("webSocketDebuggerUrl").substringAfter("://").substringAfter('/')
    return CdpSession(WebSocket.connect(name, "/$path")).use(block)
}

/** Polls [probe] until it returns non-null. No fixed sleeps anywhere in the spike. */
internal fun <T : Any> waitFor(timeoutMs: Long, probe: () -> T?): T {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        probe()?.let { return it }
        Thread.sleep(50)
    }
    error("condition not met within ${timeoutMs}ms")
}
