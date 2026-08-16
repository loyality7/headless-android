package dev.headless.browser.security

import dev.headless.browser.BrowserException
import dev.headless.browser.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * The guard is tested in the configuration production actually ships with.
 *
 * There is deliberately no setup that weakens it first: the previous version of
 * this test disabled loopback checking in `@Before`, which meant it proved the
 * guard worked in a configuration no caller ever used.
 */
class SsrfGuardTest {

    private fun assertBlocked(url: String) {
        val thrown = assertThrows("expected $url to be blocked", BrowserException::class.java) {
            SsrfGuard.validateUri(url)
        }
        assertEquals(ErrorCode.SSRF_BLOCKED, thrown.code)
    }

    @Test
    fun `loopback is blocked with the default configuration`() {
        // The defect this test exists for: loopback used to be permitted in
        // production because a mutable global defaulted to allowing it.
        assertBlocked("http://127.0.0.1/admin")
        assertBlocked("http://127.0.0.1:8080/")
        assertBlocked("http://localhost/config")
    }

    @Test
    fun `private ranges are blocked`() {
        assertBlocked("http://10.0.0.1/secret")
        assertBlocked("http://192.168.1.1/router")
        assertBlocked("http://172.16.0.1/internal")
        assertBlocked("http://172.31.255.254/internal")
    }

    @Test
    fun `cloud metadata endpoints are blocked`() {
        assertBlocked("http://169.254.169.254/latest/meta-data/")
        assertBlocked("http://169.254.170.2/v2/credentials")
    }

    @Test
    fun `other reserved ranges are blocked`() {
        assertBlocked("http://0.0.0.0/")
        assertBlocked("http://100.64.0.1/")      // carrier-grade NAT
        assertBlocked("http://240.0.0.1/")       // reserved
    }

    @Test
    fun `ipv6 loopback and unique local are blocked`() {
        assertBlocked("http://[::1]/admin")
        assertBlocked("http://[fc00::1]/internal")
        assertBlocked("http://[fd12:3456:789a::1]/internal")
    }

    @Test
    fun `ipv4 mapped into ipv6 does not smuggle a private address through`() {
        // ::ffff:127.0.0.1 reaches loopback while looking like a v6 address.
        assertBlocked("http://[::ffff:127.0.0.1]/admin")
        assertBlocked("http://[::ffff:10.0.0.1]/secret")
    }

    @Test
    fun `an unparseable url is refused rather than allowed`() {
        // Fails closed. The previous implementation returned early here, so a
        // malformed URL skipped every check.
        assertBlocked("http://[not a url")
        assertBlocked("ht!tp://example.com")
    }

    @Test
    fun `a host that cannot be resolved is refused`() {
        assertBlocked("http://this-host-does-not-exist.invalid/")
    }

    @Test
    fun `a url with no host is refused`() {
        assertBlocked("http:///nowhere")
    }

    @Test
    fun `hostless schemes are permitted`() {
        // about:blank is used during teardown, and a data URL carries its own
        // payload. Neither can reach the network.
        SsrfGuard.validateUri("about:blank")
        SsrfGuard.validateUri("data:text/html,<h1>hello</h1>")
    }

    @Test
    fun `explicit opt-in permits private addresses`() {
        // This is how a test reaches the local fixture site, and the only way
        // the guard can be relaxed.
        SsrfGuard.validateUri("http://127.0.0.1:8080/static", allowPrivateAddresses = true)
        SsrfGuard.validateUri("http://10.0.0.1/secret", allowPrivateAddresses = true)
    }

    @Test
    fun `public addresses are permitted`() {
        SsrfGuard.validateUri("http://93.184.216.34/")      // example.com, a literal
        SsrfGuard.validateUri("http://1.1.1.1/")
    }

    @Test
    fun `address classification covers both families`() {
        assertTrue(SsrfGuard.isForbiddenAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(SsrfGuard.isForbiddenAddress(InetAddress.getByName("::1")))
        assertTrue(SsrfGuard.isForbiddenAddress(InetAddress.getByName("192.168.0.1")))
        assertTrue(!SsrfGuard.isForbiddenAddress(InetAddress.getByName("8.8.8.8")))
    }

    @Test
    fun `the guard exposes no mutable state`() {
        // A public mutable switch on a security decision is reachable by any
        // code in the host application. There must not be one.
        val mutableProperties = SsrfGuard::class.java.declaredFields
            .filterNot { java.lang.reflect.Modifier.isFinal(it.modifiers) }
            .filterNot { it.isSynthetic }
            .map { it.name }

        assertEquals("SsrfGuard must hold no mutable state: $mutableProperties", emptyList<String>(), mutableProperties)
    }
}
