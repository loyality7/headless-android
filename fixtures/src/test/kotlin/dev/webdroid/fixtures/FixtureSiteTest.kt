package dev.webdroid.fixtures

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The fixture site is test infrastructure, so it gets tested too: a page that
 * silently stops serving what it claims turns every failure downstream into a
 * hunt in the wrong module.
 */
class FixtureSiteTest {

    private lateinit var site: FixtureSite
    private val client = OkHttpClient.Builder().followRedirects(false).build()

    @Before
    fun setUp() {
        site = FixtureSite()
    }

    @After
    fun tearDown() {
        site.close()
    }

    private fun get(url: String): Pair<Int, String> {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            return response.code to response.body!!.string()
        }
    }

    private fun bodyOf(fixture: Fixture): String {
        val (code, body) = get(site.url(fixture))
        assertEquals("$fixture did not serve", 200, code)
        return body
    }

    @Test
    fun `every fixture serves`() {
        // RedirectChain and RedirectToPrivate answer with 302 by design.
        val direct = Fixture.entries - Fixture.RedirectToPrivate
        for (fixture in direct) {
            val (code, body) = get(site.url(fixture))
            assertTrue("$fixture answered $code", code == 200 || code == 302)
            if (code == 200) assertTrue("$fixture served nothing", body.isNotEmpty())
        }
    }

    @Test
    fun `the static fixture is unchanging`() {
        assertEquals(bodyOf(Fixture.Static), bodyOf(Fixture.Static))
    }

    @Test
    fun `the client-rendered fixture ships an empty container`() {
        // The point of it: the content is absent from the served markup and
        // appears only once script runs, so a load-event wait reads an empty page.
        // The text does occur in the script source, which is exactly where it
        // must stay, so the check is against the markup before the script tag.
        val body = bodyOf(Fixture.ClientRendered)
        val markup = body.substringBefore("<script>")

        assertTrue(markup.contains("""<div id="app"></div>"""))
        assertTrue("content must not be in the served markup", !markup.contains("rendered by script"))
    }

    @Test
    fun `the slow-settling fixture announces when it goes quiet`() {
        val body = bodyOf(Fixture.SlowSettling)
        assertTrue(body.contains("data-settled"))
        assertTrue(body.contains("setInterval"))
    }

    @Test
    fun `the asset-heavy fixture references images, fonts and media`() {
        val body = bodyOf(Fixture.AssetHeavy)
        assertTrue(body.contains(".png"))
        assertTrue(body.contains(".jpg"))
        assertTrue(body.contains(".woff2"))
        assertTrue(body.contains(".mp4"))
    }

    @Test
    fun `assets are large enough for blocking to be measurable`() {
        val (code, body) = get(site.url("/1.png"))
        assertEquals(200, code)
        assertTrue("asset was ${body.length} bytes", body.length >= 32 * 1024)
    }

    @Test
    fun `the huge-dom fixture is actually huge`() {
        val body = bodyOf(Fixture.HugeDom)
        assertTrue("only ${body.length} bytes", body.length > 400_000)
        assertTrue(body.contains("""id="r19999""""))
    }

    @Test
    fun `the redirect chain terminates at the depth asked for`() {
        var url = site.url("${Fixture.RedirectChain.path}?n=3")
        var hops = 0
        while (hops < 10) {
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            val location = response.header("Location")
            val code = response.code
            response.close()
            if (code != 302) break
            url = site.url(location!!)
            hops++
        }
        assertEquals(3, hops)
    }

    @Test
    fun `the private-redirect fixture points off-host at a loopback address`() {
        val response = client.newCall(Request.Builder().url(site.url(Fixture.RedirectToPrivate)).build()).execute()
        val location = response.header("Location")
        response.close()

        assertEquals(302, response.code)
        assertTrue("redirected to $location", location!!.contains("127.0.0.1"))
    }

    @Test
    fun `requests are counted, so blocking can be measured`() {
        val before = site.requestCount
        get(site.url(Fixture.Static))
        get(site.url("/1.png"))
        assertEquals(before + 2, site.requestCount)
    }

    @Test
    fun `an unknown path is a 404, not a silent empty page`() {
        val (code, _) = get(site.url("/nothing-here"))
        assertEquals(404, code)
    }

    @Test
    fun `the site binds to loopback only`() {
        // Nothing here should ever be reachable off the machine running the tests.
        assertTrue("not listening on loopback", site.isLoopback)

        // And the URL is built numerically, so a machine with a Docker hosts
        // entry does not hand tests a kubernetes.docker.internal address.
        assertTrue(site.url(Fixture.Static).startsWith("http://127.0.0.1:${site.port}/"))
    }
}
