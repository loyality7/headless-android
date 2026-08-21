package dev.webdroid.platform

import dev.webdroid.BrowserConfig
import dev.webdroid.WaitUntil
import dev.webdroid.core.PageSession
import dev.webdroid.core.SessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Handles page settling based on lifecycle, DOM stability, network idleness, or custom predicates.
 */
internal class PlatformSettleEngine(
    private val session: PageSession,
    private val scriptEngine: PlatformScriptEngine,
    private val config: BrowserConfig,
) {

    /**
     * Injects the DOM mutation tracking script onto the current page.
     */
    suspend fun injectMutationObserver() {
        val script = """
            (function() {
                if (window.__headless_observer_installed) return;
                window.__headless_observer_installed = true;
                window.__headless_last_mutation = Date.now();
                var observer = new MutationObserver(function(mutations) {
                    window.__headless_last_mutation = Date.now();
                });
                var target = document.body || document.documentElement || document;
                if (target) {
                    observer.observe(target, { childList: true, subtree: true, attributes: true, characterData: true });
                }
                document.addEventListener('DOMContentLoaded', function() {
                    if (document.body) {
                        observer.observe(document.body, { childList: true, subtree: true, attributes: true, characterData: true });
                    }
                });
            })();
        """.trimIndent()

        try {
            scriptEngine.evaluate(script)
        } catch (_: Exception) {
            // Ignore pre-load injection failure
        }
    }

    /**
     * Waits until [waitUntil] condition is met or until [timeoutMillis] expires.
     *
     * @return `true` if settled within ceiling, `false` if ceiling reached (timed out).
     */
    suspend fun settle(
        waitUntil: WaitUntil,
        timeoutMillis: Long = 0,
    ): Boolean = session.runInState(SessionState.Settling) {
        val effectiveTimeout = if (timeoutMillis > 0) timeoutMillis else config.timeouts.settleMillis

        val result = withTimeoutOrNull(effectiveTimeout) {
            when (waitUntil) {
                is WaitUntil.Load, is WaitUntil.DomReady -> true
                is WaitUntil.DomStable -> awaitDomStable(waitUntil.quietMillis)
                is WaitUntil.NetworkIdle -> awaitNetworkIdle(waitUntil.quietMillis)
                is WaitUntil.Custom -> awaitCustomPredicate(waitUntil.expression)
            }
        }

        result ?: false
    }

    private suspend fun awaitDomStable(quietMillis: Long): Boolean {
        injectMutationObserver()
        val startTime = System.currentTimeMillis()
        val pollInterval = 100L

        // Wait initial sampling window so background timers/scripts have a chance to mutate
        delay(pollInterval * 2)

        while (true) {
            val lastMutationStr = try {
                scriptEngine.evaluate("window.__headless_last_mutation")
            } catch (_: Exception) {
                null
            }

            val lastMutation = lastMutationStr?.toLongOrNull() ?: startTime
            val now = System.currentTimeMillis()
            val timeSinceLastMutation = now - lastMutation

            // If script is ready or DOM has been quiet for quietMillis
            val isReady = try {
                scriptEngine.evaluate("window.__ready === true || (document.readyState === 'complete' && (${now - lastMutation} >= $quietMillis))")
            } catch (_: Exception) {
                "false"
            }

            if (isReady == "true" || timeSinceLastMutation >= quietMillis) {
                return true
            }

            delay(pollInterval)
        }
    }

    /**
     * Waits until the page stops asking for resources.
     *
     * This used to be `delay(quietMillis); return true` — a fixed sleep that
     * always claimed success, which is the one implementation the specification
     * rules out. It now reads the interception callback's record of when the
     * page last requested anything.
     *
     * The claim made here is "nothing has been requested for N milliseconds",
     * not "zero requests are outstanding". The platform gives no per-resource
     * completion callback, so the stronger claim is not available, and stating
     * the weaker one honestly beats implying the stronger one.
     *
     * Returns only when the window is genuinely quiet; the caller's ceiling,
     * applied in [settle], decides how long that is worth waiting for.
     */
    private suspend fun awaitNetworkIdle(quietMillis: Long): Boolean {
        val tracker = session.requestActivity
        while (true) {
            val quietForMillis: Long = tracker.quietForMillis()
            if (quietForMillis >= quietMillis) return true

            // Sleep only for the remainder of the window, so a request arriving
            // late restarts it rather than being missed.
            val remainingMillis: Long = quietMillis - quietForMillis
            delay(maxOf(remainingMillis, POLL_FLOOR_MILLIS))
        }
    }

    private suspend fun awaitCustomPredicate(expression: String): Boolean {
        val pollInterval = 100L
        while (true) {
            val evalResult = try {
                scriptEngine.evaluate(expression)
            } catch (_: Exception) {
                null
            }

            if (isTruthy(evalResult)) {
                return true
            }
            delay(pollInterval)
        }
    }

    private companion object {
        /** Never spin. A request arriving mid-window restarts it on the next tick. */
        const val POLL_FLOOR_MILLIS = 50L
    }

    private fun isTruthy(result: String?): Boolean {
        if (result == null || result == "null" || result == "false" || result == "0" || result == "\"\"" || result == "undefined") {
            return false
        }
        return true
    }
}

