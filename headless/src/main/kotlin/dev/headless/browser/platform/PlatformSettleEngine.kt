package dev.headless.browser.platform

import dev.headless.browser.BrowserConfig
import dev.headless.browser.WaitUntil
import dev.headless.browser.core.PageSession
import dev.headless.browser.core.SessionState
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

    private suspend fun awaitNetworkIdle(quietMillis: Long): Boolean {
        delay(quietMillis)
        return true
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

    private fun isTruthy(result: String?): Boolean {
        if (result == null || result == "null" || result == "false" || result == "0" || result == "\"\"" || result == "undefined") {
            return false
        }
        return true
    }
}

