package dev.headless.browser

/**
 * Diagnostic metrics captured for a [dev.headless.browser.core.PageSession].
 * All values are collected strictly locally and never transmitted externally.
 */
public data class SessionMetrics(
    /** Total active session runtime in milliseconds since initialization. */
    val sessionDurationMs: Long,
    /** Total number of successful page navigations executed. */
    val totalNavigations: Int,
    /** Total number of JavaScript evaluation calls executed. */
    val totalJsEvaluations: Int,
    /** Accumulated total JavaScript execution duration in milliseconds. */
    val totalJsExecutionTimeMs: Long,
    /** Total network bytes blocked by resource rules, SSRF guards, or ad filters. */
    val blockedBytes: Long,
    /** Total memory pressure warnings encountered during session runtime. */
    val memoryPressureEvents: Int,
    /** Total renderer crashes or OOM terminations survived. */
    val rendererCrashes: Int,
)
