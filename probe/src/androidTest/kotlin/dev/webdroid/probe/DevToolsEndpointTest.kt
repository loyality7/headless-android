package dev.webdroid.probe

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Probe section 0: is the control endpoint reachable from inside the app, and
 * what does this build's protocol surface actually answer?
 *
 * The whole protocol backend is gated on these passing. If they do not, the
 * platform backend is the library.
 */
@RunWith(AndroidJUnit4::class)
class DevToolsEndpointTest {

    @Before
    fun setUp() = enableDebugging()

    @Test
    fun socketIsReachableInProcess() = withDetachedWebView { webView ->
        loadDetached(webView, "about:blank")

        val (name, socket) = SocketDiscovery.connectAny()
        socket.use {
            record("socket.name", name)
            record("socket.candidates", SocketDiscovery.candidates())
            record("socket.procNetUnix", SocketDiscovery.fromProcNetUnix())
        }
    }

    @Test
    fun discoveryEndpointsParse() = withDetachedWebView { webView ->
        loadDetached(webView, "about:blank")
        val (name, socket) = SocketDiscovery.connectAny()
        socket.close()

        val version = DevToolsHttp.version(name)
        record("json.version", version)

        val targets = DevToolsHttp.targets(name)
        record("json.targetCount", targets.size)
        record("json.targets", targets.map { it.optString("url") })
        assertTrue("no page target exposed", targets.any { it.has("webSocketDebuggerUrl") })
    }

    @Test
    fun protocolRoundTrips() = withDetachedWebView { webView ->
        record("roundtrip.step", "webview created")
        loadDetached(webView, "about:blank")
        record("roundtrip.step", "about:blank loaded")

        session { cdp ->
            // evaluate returns the unwrapped value, so a JavaScript string comes
            // back as its characters rather than as a quoted JSON literal.
            assertEquals("probe", cdp.evaluate("'probe'"))
            assertEquals("4", cdp.evaluate("2 + 2"))
            record("roundtrip.step", "evaluate works")

            cdp.send("Page.enable")
            record("roundtrip.step", "Page.enable returned")

            cdp.send(
                "Page.navigate",
                JSONObject().put("url", "data:text/html,<h1 id=t>hello</h1>")
            )
            record("roundtrip.step", "Page.navigate returned")

            // Poll rather than sleep: the document is swapped asynchronously.
            val heading = waitFor(10_000) {
                runCatching {
                    cdp.evaluate("document.getElementById('t') ? document.getElementById('t').textContent : null")
                }.getOrNull().takeIf { it == "hello" }
            }
            record("protocol.navigateAndRead", heading)
            assertEquals("hello", heading)
        }
    }

    /** The capability matrix. Output is the table the go/no-go decision is written from. */
    @Test
    fun capabilityMatrix() = withDetachedWebView { webView ->
        loadDetached(webView, "about:blank")

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

        session { cdp ->
            for ((method, params) in probes) {
                val supported = runCatching { cdp.supports(method, params) }.getOrElse { false }
                record("capability.$method", supported)
            }
        }
    }
}

/** Opens a CDP session against the newest page target and closes it on every path. */
internal fun <T> session(block: (CdpSession) -> T): T {
    val (name, socket) = SocketDiscovery.connectAny()
    socket.close()

    val target = DevToolsHttp.targets(name).first { it.has("webSocketDebuggerUrl") }
    val debuggerUrl = target.getString("webSocketDebuggerUrl")
    record("session.targetUrl", target.optString("url"))
    record("session.debuggerUrl", debuggerUrl)

    val path = "/" + debuggerUrl.substringAfter("://").substringAfter('/')
    return CdpSession(WebSocket.connect(name, path)).use(block)
}

/**
 * Polls [probe] until it returns non-null. No fixed sleeps anywhere in the probe.
 *
 * The interval is deliberately not tight: each poll is a protocol round trip,
 * and a 50 ms loop issued enough commands to overwhelm the instrumentation
 * channel and take the test process down with it.
 */
internal fun <T : Any> waitFor(timeoutMs: Long, intervalMs: Long = 250, probe: () -> T?): T {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        probe()?.let { return it }
        Thread.sleep(intervalMs)
    }
    error("condition not met within ${timeoutMs}ms")
}
