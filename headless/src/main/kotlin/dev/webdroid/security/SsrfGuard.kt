package dev.webdroid.security

import dev.webdroid.BrowserException
import dev.webdroid.ErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Refuses navigation to addresses a caller-supplied URL should never reach.
 *
 * A library that fetches an arbitrary URL inside someone else's application is an
 * SSRF primitive unless it declines the places an attacker wants: loopback,
 * private ranges, link-local, and the cloud metadata endpoint.
 *
 * Two properties are deliberate and load-bearing:
 *
 * - **It fails closed.** A URL that cannot be parsed, or a host that cannot be
 *   resolved, is refused rather than allowed. An address we cannot classify is
 *   not an address we can vouch for.
 * - **It holds no state.** The decision comes only from the URL and the caller's
 *   [dev.webdroid.BrowserConfig.allowPrivateAddresses]. There is no
 *   global switch that can weaken it for the whole process.
 */
public object SsrfGuard {

    /**
     * Schemes that address no host at all.
     *
     * `about:` covers the blank page used during teardown; `data:` carries its
     * own payload. Neither can reach the network, so neither can be an SSRF
     * vector. Everything else — including `file:`, which addresses the local
     * filesystem — has to justify itself through the address checks below.
     */
    private val HOSTLESS_SCHEMES = setOf("about", "data", "blob", "javascript")

    /**
     * Throws unless [uri] is safe to navigate to.
     *
     * @param allowPrivateAddresses when true the host application has explicitly
     *   accepted responsibility for private destinations, so every check is
     *   skipped. This is how tests reach the local fixture site.
     * @throws BrowserException [ErrorCode.SSRF_BLOCKED] for a forbidden address,
     *   an unparseable URL, or a host that does not resolve.
     */
    /**
     * Resolves [uri] and validates every address it points at.
     *
     * Suspending, and resolution runs on [Dispatchers.IO], because name lookup
     * on the main thread throws `NetworkOnMainThreadException`. The previous
     * implementation resolved inline from a main-thread dispatcher, caught that
     * exception, and returned — so on a real device **no hostname was ever
     * validated**. Only IP literals, which need no lookup, were being checked.
     *
     * The result is cached so [validateResolved] can answer synchronously from
     * the redirect callback, which cannot suspend.
     */
    public suspend fun validateUriResolving(uri: String, allowPrivateAddresses: Boolean = false) {
        if (allowPrivateAddresses) return
        val host = hostToValidate(uri) ?: return

        val addresses = withContext(Dispatchers.IO) {
            runCatching { InetAddress.getAllByName(host) }.getOrNull()
        } ?: throw blocked(uri, "the host could not be resolved")

        if (addresses.isEmpty()) {
            throw blocked(uri, "the host resolved to no addresses")
        }

        // Every address the name resolves to must be acceptable. A name that
        // returns one public and one private address is a rebinding attempt.
        val forbidden = addresses.firstOrNull { isForbiddenAddress(it) }
        verdictCache[host] = forbidden == null

        if (forbidden != null) {
            throw blocked(uri, "resolves to ${forbidden.hostAddress}")
        }
    }

    /**
     * Validates [uri] without performing a name lookup.
     *
     * For use from `shouldOverrideUrlLoading`, which is a synchronous main-thread
     * callback where resolution is not permitted. Literal addresses are checked
     * outright; a hostname is checked against what [validateUriResolving]
     * previously resolved, and a host never seen before is allowed through here
     * so that the navigation itself can validate it properly.
     */
    public fun validateUri(uri: String, allowPrivateAddresses: Boolean = false) {
        if (allowPrivateAddresses) return
        val host = hostToValidate(uri) ?: return

        // A literal address needs no lookup, so it can always be judged here.
        val literal = runCatching { parseLiteralAddress(host) }.getOrNull()
        if (literal != null) {
            if (isForbiddenAddress(literal)) throw blocked(uri, "resolves to ${literal.hostAddress}")
            return
        }

        if (verdictCache[host] == false) {
            throw blocked(uri, "the host previously resolved to a forbidden address")
        }
    }

    /**
     * The host that must be validated, or null when the URL addresses none.
     *
     * @throws BrowserException when the URL is unusable. Failing closed: an
     *   address we cannot classify is not one we can vouch for.
     */
    private fun hostToValidate(uri: String): String? {
        // Read the scheme off the raw string before parsing. A data URL legally
        // carries characters that URI rejects — `data:text/html,<h1>x</h1>` is
        // the obvious one — and it addresses no host, so it must not be failed
        // by a parser that never needed to see it.
        if (schemeOf(uri) in HOSTLESS_SCHEMES) return null

        val parsed = runCatching { URI(uri) }.getOrNull()
            ?: throw blocked(uri, "the URL could not be parsed")

        return parsed.host ?: throw blocked(uri, "the URL has no host")
    }

    /** An address written literally in the URL, or null when [host] is a name. */
    private fun parseLiteralAddress(host: String): InetAddress? {
        val trimmed = host.removeSurrounding("[", "]")
        val looksNumeric = trimmed.all { it.isDigit() || it == '.' } ||
            (trimmed.contains(':') && trimmed.all { it.isLetterOrDigit() || it == ':' || it == '.' })
        if (!looksNumeric) return null
        // Safe: a literal never triggers a lookup, so this cannot touch the network.
        return runCatching { InetAddress.getByName(trimmed) }.getOrNull()
    }

    /**
     * What each hostname last resolved to, as a verdict.
     *
     * Bounded implicitly by how many distinct hosts a session visits. A wrong
     * entry can only be stale, and a stale allow is re-checked on the next
     * navigation through [validateUriResolving].
     */
    private val verdictCache = ConcurrentHashMap<String, Boolean>()

    /**
     * Whether [addr] falls in a range this library refuses to reach.
     *
     * Covers both address families. The platform predicates catch most of it;
     * the explicit ranges below are the cases they miss or where being explicit
     * is worth more than being terse.
     */
    public fun isForbiddenAddress(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress) return true
        if (addr.isLinkLocalAddress) return true
        if (addr.isSiteLocalAddress) return true
        if (addr.isAnyLocalAddress) return true
        if (addr.isMulticastAddress) return true

        return when (addr) {
            is Inet4Address -> isForbiddenIpv4(addr.address)
            is Inet6Address -> isForbiddenIpv6(addr)
            else -> true
        }
    }

    private fun isForbiddenIpv4(raw: ByteArray): Boolean {
        if (raw.size != 4) return true
        val b0 = raw[0].toInt() and 0xFF
        val b1 = raw[1].toInt() and 0xFF

        return when {
            b0 == 0 -> true                      // 0.0.0.0/8, "this network"
            b0 == 10 -> true                     // 10.0.0.0/8
            b0 == 127 -> true                    // 127.0.0.0/8
            b0 == 100 && b1 in 64..127 -> true   // 100.64.0.0/10, carrier-grade NAT
            b0 == 169 && b1 == 254 -> true       // 169.254.0.0/16, link-local and cloud metadata
            b0 == 172 && b1 in 16..31 -> true    // 172.16.0.0/12
            b0 == 192 && b1 == 168 -> true       // 192.168.0.0/16
            b0 == 192 && b1 == 0 -> true         // 192.0.0.0/24, protocol assignments
            b0 >= 240 -> true                    // 240.0.0.0/4, reserved
            else -> false
        }
    }

    private fun isForbiddenIpv6(addr: Inet6Address): Boolean {
        val raw = addr.address
        if (raw.size != 16) return true

        val b0 = raw[0].toInt() and 0xFF

        // fc00::/7 — unique local. The IPv6 equivalent of the private ranges,
        // and not covered by isSiteLocalAddress on every platform.
        if (b0 and 0xFE == 0xFC) return true

        // An IPv4-mapped or IPv4-compatible address hides a v4 destination
        // inside a v6 one, so classify the address it actually reaches.
        val mapped = runCatching { InetAddress.getByAddress(raw.copyOfRange(12, 16)) }.getOrNull()
        val isV4Embedded = raw.take(10).all { it.toInt() == 0 } &&
            (raw[10].toInt() and 0xFF).let { it == 0 || it == 0xFF }
        if (isV4Embedded && mapped != null) {
            return isForbiddenIpv4(mapped.address)
        }

        return false
    }

    /**
     * The scheme, read from the raw string rather than from a parsed URI.
     *
     * Returns null when the text before the first colon is not a legal scheme,
     * which keeps a bare path or a malformed URL out of the hostless allowance.
     */
    private fun schemeOf(uri: String): String? {
        val colon = uri.indexOf(':')
        if (colon <= 0) return null
        val candidate = uri.substring(0, colon)
        if (!candidate.first().isLetter()) return null
        if (!candidate.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return null
        return candidate.lowercase()
    }

    private fun blocked(uri: String, reason: String) = BrowserException(
        ErrorCode.SSRF_BLOCKED,
        "Navigation to '$uri' was blocked: $reason",
    )
}
