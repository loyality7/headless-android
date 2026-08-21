package dev.headless.browser.core

import dev.headless.browser.ErrorCode
import dev.headless.browser.browserError
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Enforces stage and total task timeouts using monotonic clock tracking ([System.nanoTime]).
 *
 * Rules:
 * - Each stage timeout (navigation, settle, script, total) is bounded independently.
 * - Monotonic nanoTime prevents system clock changes/drift from altering elapsed time.
 * - On timeout, throws [dev.headless.browser.BrowserException] with [ErrorCode.TIMEOUT].
 * - Preserves caller cancellation (does not obscure [kotlinx.coroutines.CancellationException]).
 */
internal object MonotonicTimeout {

    suspend fun <T> runWithTimeout(
        timeoutMillis: Long,
        stageName: String,
        block: suspend () -> T,
    ): T {
        val startNano = System.nanoTime()

        try {
            return withTimeout(timeoutMillis) {
                block()
            }
        } catch (ex: TimeoutCancellationException) {
            // Verify monotonic time elapsed
            val elapsedNano = System.nanoTime() - startNano
            val elapsedMillis = elapsedNano / 1_000_000L

            throw browserError(
                ErrorCode.TIMEOUT,
                "Stage '$stageName' timed out after ${elapsedMillis}ms (limit: ${timeoutMillis}ms)",
                ex,
            )
        }
    }

    /**
     * Calculates remaining time in milliseconds given a task start timestamp in [System.nanoTime].
     * Returns 0 if deadline has expired.
     */
    fun remainingMillis(startNano: Long, totalLimitMillis: Long): Long {
        val elapsedNano = System.nanoTime() - startNano
        val remainingNano = (totalLimitMillis * 1_000_000L) - elapsedNano
        return (remainingNano / 1_000_000L).coerceAtLeast(0)
    }
}
