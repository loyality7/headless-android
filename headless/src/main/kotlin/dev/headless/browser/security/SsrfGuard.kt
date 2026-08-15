package dev.headless.browser.security

import dev.headless.browser.BrowserException
import dev.headless.browser.ErrorCode
import java.net.InetAddress
import java.net.URI

/**
 * SSRF (Server-Side Request Forgery) protection guard.
 *
 * Validates URLs and resolved IP addresses against forbidden loopback, private,
 * link-local, and cloud metadata (IMDS) address ranges.
 */
public object SsrfGuard {

    /**
     * Checks whether the given URI target is allowed under SSRF rules.
     *
     * @param uri The URI to validate.
     * @param allowPrivateAddresses If true, permits private and local IP addresses.
     * @throws BrowserException with [ErrorCode.SSRF_BLOCKED] if target resolves to a forbidden range.
     */
    public fun validateUri(uri: String, allowPrivateAddresses: Boolean = false) {
        if (allowPrivateAddresses) return

        val parsed = runCatching { URI(uri) }.getOrNull() ?: return
        val host = parsed.host ?: return

        // Validate raw IP strings or resolve hostname
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return

        for (addr in addresses) {
            if (isForbiddenAddress(addr)) {
                throw BrowserException(
                    ErrorCode.SSRF_BLOCKED,
                    "Navigation to '$uri' (${addr.hostAddress}) blocked by SSRF protection rule"
                )
            }
        }
    }

    /**
     * Evaluates whether an [InetAddress] falls into a forbidden IP range.
     */
    public fun isForbiddenAddress(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress) return true
        if (addr.isLinkLocalAddress) return true
        if (addr.isSiteLocalAddress) return true
        if (addr.isAnyLocalAddress) return true

        val raw = addr.address
        if (raw.size == 4) {
            // IPv4 Checks
            val b0 = raw[0].toInt() and 0xFF
            val b1 = raw[1].toInt() and 0xFF
            val b2 = raw[2].toInt() and 0xFF
            val b3 = raw[3].toInt() and 0xFF

            // 127.0.0.0/8 (Loopback)
            if (b0 == 127) return true

            // 10.0.0.0/8 (Private)
            if (b0 == 10) return true

            // 172.16.0.0/12 (Private)
            if (b0 == 172 && b1 in 16..31) return true

            // 192.168.0.0/16 (Private)
            if (b0 == 192 && b1 == 168) return true

            // 169.254.0.0/16 (Link-local & AWS IMDS 169.254.169.254)
            if (b0 == 169 && b1 == 254) return true

            // 0.0.0.0/8 (Current network)
            if (b0 == 0) return true
        }

        return false
    }
}
