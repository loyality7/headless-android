package dev.webdroid.fixtures

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

/**
 * The pages tests drive.
 *
 * Tests never resolve a public host: live sites make failures unreproducible and
 * turn a test suite into a monitoring system for somebody else's deployment.
 *
 * ```kotlin
 * FixtureSite().use { site ->
 *     page.goto(site.url(Fixture.ClientRendered))
 * }
 * ```
 */
public class FixtureSite(
    /**
     * Host the returned URLs are built from.
     *
     * Numeric on purpose: resolving the loopback name picks up whatever the
     * machine's hosts file says, and a developer running Docker gets URLs
     * pointing at `kubernetes.docker.internal`. An instrumented test on a device
     * passes the host's address on the local network instead, since loopback on
     * a phone is the phone.
     */
    private val host: String = "127.0.0.1",
) : Closeable {

    private val server = MockWebServer()
    private val requests = AtomicInteger()

    /** Requests served since start. Blocking is measured against this, not against a stopwatch. */
    public val requestCount: Int get() = requests.get()

    /** The port the server bound to. Assigned by the system, never fixed. */
    public val port: Int get() = server.port

    /** True when the server is listening on a loopback address, as it must be. */
    public val isLoopback: Boolean
        get() = java.net.InetAddress.getByName(server.hostName).isLoopbackAddress

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.incrementAndGet()
                return respond(request)
            }
        }
        server.start()
    }

    /** Absolute URL of a fixture. */
    public fun url(fixture: Fixture): String = url(fixture.path)

    /** Absolute URL of an arbitrary path, for redirect targets and asset probes. */
    public fun url(path: String): String =
        "http://$host:${server.port}${if (path.startsWith("/")) path else "/$path"}"

    override fun close(): Unit = server.shutdown()

    private fun respond(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty().substringBefore('?')
        val query = request.path.orEmpty().substringAfter('?', "")

        return when {
            path == Fixture.Static.path -> html(STATIC)
            path == Fixture.ClientRendered.path -> html(CLIENT_RENDERED)
            path == Fixture.SlowSettling.path -> html(SLOW_SETTLING)
            path == Fixture.AssetHeavy.path -> html(ASSET_HEAVY)
            path == Fixture.HugeDom.path -> html(hugeDom())
            path == Fixture.Crash.path -> html(CRASH)
            path == Fixture.Dialog.path -> html(DIALOG)

            // A login form and the page behind it, for form-automation coverage
            // that used to depend on a real third-party test site.
            path == Fixture.Login.path && request.method == "GET" -> html(LOGIN)
            path == Fixture.Login.path && request.method == "POST" -> handleLoginSubmit(request)
            path == Fixture.SecureArea.path -> html(SECURE_AREA)

            // A chain that counts down, so a test can pick its own depth and
            // assert that the cap terminates it rather than the server does.
            path == Fixture.RedirectChain.path -> redirect(query)

            // Redirects off-host to a private address: the SSRF guard must
            // re-check after the hop, since the first one proves nothing.
            path == Fixture.RedirectToPrivate.path ->
                MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:1/private")

            path.endsWith(".png") || path.endsWith(".jpg") -> binary("image/png")
            path.endsWith(".woff2") -> binary("font/woff2")
            path.endsWith(".mp4") -> binary("video/mp4")
            path.endsWith(".css") -> MockResponse().setHeader("Content-Type", "text/css").setBody("body{margin:0}")
            path.endsWith(".js") -> MockResponse().setHeader("Content-Type", "text/javascript").setBody("void 0;")

            else -> MockResponse().setResponseCode(404).setBody("no fixture at $path")
        }
    }

    /** Parses the url-encoded form body and redirects only for the one valid credential pair. */
    private fun handleLoginSubmit(request: RecordedRequest): MockResponse {
        val body = request.body.readUtf8()
        val fields = body.split("&").mapNotNull { pair ->
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) java.net.URLDecoder.decode(parts[0], "UTF-8") to java.net.URLDecoder.decode(parts[1], "UTF-8") else null
        }.toMap()

        return if (fields["username"] == "tomsmith" && fields["password"] == "SuperSecretPassword!") {
            MockResponse().setResponseCode(302).setHeader("Location", Fixture.SecureArea.path)
        } else {
            html(LOGIN_INVALID)
        }
    }

    private fun redirect(query: String): MockResponse {
        val remaining = query.substringAfter("n=", "0").toIntOrNull() ?: 0
        return if (remaining <= 0) {
            html("<html><body><h1 id='end'>chain complete</h1></body></html>")
        } else {
            MockResponse().setResponseCode(302)
                .setHeader("Location", "${Fixture.RedirectChain.path}?n=${remaining - 1}")
        }
    }

    private fun html(body: String) = MockResponse()
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody(body)

    /** Weight without the bytes: enough to be worth blocking, cheap to serve. */
    private fun binary(contentType: String) = MockResponse()
        .setHeader("Content-Type", contentType)
        .setBody("0".repeat(ASSET_BYTES))

    private companion object {
        const val ASSET_BYTES = 32 * 1024
        const val HUGE_DOM_NODES = 20_000

        val STATIC = """
            <html><head><title>static</title></head>
            <body><h1 id="heading">static fixture</h1><p class="body">unchanging</p>
            <a href="/one">one</a><a href="/two">two</a></body></html>
        """.trimIndent()

        /** Content appears only after script runs, so a load-event wait reads an empty page. */
        val CLIENT_RENDERED = """
            <html><head><title>client rendered</title></head>
            <body><div id="app"></div>
            <script>
              setTimeout(function () {
                document.getElementById('app').innerHTML =
                  '<h1 id="heading">rendered by script</h1><span class="price">42</span>';
                window.__ready = true;
              }, 300);
            </script></body></html>
        """.trimIndent()

        /** Mutates for a known interval, then goes quiet. Ends with a marker a test can wait on. */
        val SLOW_SETTLING = """
            <html><head><title>slow settling</title></head>
            <body><ul id="list"></ul>
            <script>
              var n = 0;
              var timer = setInterval(function () {
                var li = document.createElement('li');
                li.textContent = 'row ' + (++n);
                document.getElementById('list').appendChild(li);
                if (n === 20) {
                  clearInterval(timer);
                  document.body.setAttribute('data-settled', 'true');
                  window.__ready = true;
                }
              }, 100);
            </script></body></html>
        """.trimIndent()

        /** Images, fonts and media, for measuring what blocking actually saves. */
        val ASSET_HEAVY = """
            <html><head><title>asset heavy</title>
            <link rel="stylesheet" href="/a.css">
            <style>@font-face{font-family:f;src:url('/f.woff2') format('woff2')}</style>
            </head>
            <body><h1 id="heading">asset heavy</h1>
            <img src="/1.png"><img src="/2.png"><img src="/3.jpg"><img src="/4.jpg">
            <video src="/v.mp4"></video>
            <script src="/a.js"></script></body></html>
        """.trimIndent()

        /**
         * Kills the renderer. The library must survive this and report it, with
         * the host app intact.
         */
        val CRASH = """
            <html><head><title>crash</title></head>
            <body><h1 id="heading">about to crash</h1>
            <script>location.href = 'chrome://crash';</script></body></html>
        """.trimIndent()

        val DIALOG = """
            <html><head><title>dialog</title></head>
            <body><h1 id="heading">dialog</h1>
            <script>window.__answer = window.confirm('proceed?');</script></body></html>
        """.trimIndent()

        /** Credentials tests submit: `tomsmith` / `SuperSecretPassword!`. */
        val LOGIN = """
            <html><head><title>login</title></head>
            <body><h1 id="heading">login</h1>
            <form method="post" action="${Fixture.Login.path}">
              <input type="text" id="username" name="username">
              <input type="password" id="password" name="password">
              <button type="submit">Login</button>
            </form></body></html>
        """.trimIndent()

        val LOGIN_INVALID = """
            <html><head><title>login</title></head>
            <body><h1 id="heading">login</h1>
            <div id="flash">Your username is invalid!</div>
            <form method="post" action="${Fixture.Login.path}">
              <input type="text" id="username" name="username">
              <input type="password" id="password" name="password">
              <button type="submit">Login</button>
            </form></body></html>
        """.trimIndent()

        val SECURE_AREA = """
            <html><head><title>secure area</title></head>
            <body><h1 id="heading">secure area</h1>
            <div id="flash">You logged into a secure area!</div></body></html>
        """.trimIndent()

        fun hugeDom(): String = buildString(HUGE_DOM_NODES * 32) {
            append("<html><head><title>huge dom</title></head><body><h1 id=\"heading\">huge</h1>")
            repeat(HUGE_DOM_NODES) { append("<div class=\"row\" id=\"r").append(it).append("\">row ").append(it).append("</div>") }
            append("</body></html>")
        }
    }
}

/** Every page the fixture site serves. */
public enum class Fixture(public val path: String) {
    /** Unchanging HTML. The baseline every other fixture is compared against. */
    Static("/static"),

    /** Content appears after script runs. A load-event wait reads it empty. */
    ClientRendered("/client-rendered"),

    /** Mutates for two seconds, then goes quiet and sets `data-settled`. */
    SlowSettling("/slow-settling"),

    /** Images, fonts, media and a stylesheet, for measuring blocking. */
    AssetHeavy("/asset-heavy"),

    /** Twenty thousand nodes, for output caps and read performance. */
    HugeDom("/huge-dom"),

    /** Kills its own renderer. */
    Crash("/crash"),

    /** Opens a confirm dialog, which blocks the page until answered. */
    Dialog("/dialog"),

    /** A login form. Submits to itself; the only valid pair is `tomsmith` / `SuperSecretPassword!`. */
    Login("/login"),

    /** Where [Login] redirects on success. Carries the `#flash` success message. */
    SecureArea("/secure"),

    /** Redirects `n` times. Append `?n=` to choose the depth. */
    RedirectChain("/redirect-chain"),

    /** Redirects to a loopback address, which the SSRF guard must refuse. */
    RedirectToPrivate("/redirect-to-private"),
}
