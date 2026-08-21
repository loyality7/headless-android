package dev.headless.browser

/**
 * Marks a test that resolves a third-party host: real TLS, a real redirect
 * across hosts, or somebody else's script — the things a local fixture cannot
 * stand in for.
 *
 * These are excluded from the default instrumented run (see
 * `.github/workflows/instrumented.yml` and `live-sites.yml`) because a failure
 * here is as likely to be somebody else's outage as a regression here. They
 * still run, deliberately, on a schedule and on demand.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
public annotation class LiveSite
