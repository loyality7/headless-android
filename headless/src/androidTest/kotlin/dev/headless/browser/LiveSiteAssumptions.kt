package dev.headless.browser

import org.junit.Assume

/**
 * Skips the test rather than failing it when a live site's own status
 * indicates it, not this library, is the problem.
 *
 * A missing status (transport failure — DNS, TLS, connection refused) or a 5xx
 * response is the site's outage. Anything else — 200, 404, a redirect — is
 * this library's problem to explain, and the test still fails normally.
 */
public fun assumeLiveSiteHealthy(status: Int?) {
    Assume.assumeTrue(
        "skipping: likely a third-party outage, not a regression here (status=$status)",
        status != null && status < 500,
    )
}
