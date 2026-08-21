package dev.webdroid.core

import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts what happened to a set of sessions.
 *
 * Owned by whoever created those sessions, not by the process. The counters
 * used to live on [PageSession]'s companion, which meant every session in the
 * application shared them: a caller could not ask "how many of *my* sessions are
 * open", and the instrumented suite could not either. Four device tests passed
 * alone and failed together because each was reading the others' totals.
 *
 * A session that is not given one gets its own, so nothing is shared by
 * accident. Pass the same registry to several sessions to aggregate them.
 */
public class SessionRegistry {

    private val active = AtomicInteger(0)
    private val rendererCrashes = AtomicInteger(0)
    private val rendererOoms = AtomicInteger(0)
    private val memoryLimitRefusals = AtomicInteger(0)
    private val ssrfBlocked = AtomicInteger(0)

    /** Sessions currently initialised and not yet torn down. */
    public val activeSessions: Int get() = active.get()

    /** Renderer processes that died reporting a crash. */
    public val totalRendererCrashes: Int get() = rendererCrashes.get()

    /** Renderer processes the system killed to reclaim memory. */
    public val totalRendererOoms: Int get() = rendererOoms.get()

    /** Operations refused rather than risking an out-of-memory kill. */
    public val totalMemoryLimitRefusals: Int get() = memoryLimitRefusals.get()

    /** Navigations and redirects refused by the SSRF rules. */
    public val totalSsrfBlocked: Int get() = ssrfBlocked.get()

    internal fun sessionOpened() {
        active.incrementAndGet()
    }

    internal fun sessionClosed() {
        active.decrementAndGet()
    }

    internal fun recordRendererDeath(didCrash: Boolean) {
        if (didCrash) rendererCrashes.incrementAndGet() else rendererOoms.incrementAndGet()
    }

    internal fun recordMemoryLimitRefusal() {
        memoryLimitRefusals.incrementAndGet()
    }

    internal fun recordSsrfBlocked() {
        ssrfBlocked.incrementAndGet()
    }

    /** A readable summary of the counters, for logs. */
    override fun toString(): String =
        "SessionRegistry(active=$activeSessions, crashes=$totalRendererCrashes, " +
            "ooms=$totalRendererOoms, memoryRefusals=$totalMemoryLimitRefusals, " +
            "ssrfBlocked=$totalSsrfBlocked)"
}
