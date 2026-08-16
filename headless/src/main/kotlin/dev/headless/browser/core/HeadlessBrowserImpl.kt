package dev.headless.browser.core

import android.content.Context
import dev.headless.browser.BrowserConfig
import dev.headless.browser.Capabilities
import dev.headless.browser.HeadlessBrowser
import dev.headless.browser.Page
import dev.headless.browser.Viewport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Implementation of HeadlessBrowser that manages multiple page sessions.
 *
 * Enforces maxSessions limit, tracks active sessions, and ensures clean teardown.
 */
internal class HeadlessBrowserImpl(
    context: Context,
    private val config: BrowserConfig,
) : HeadlessBrowser {

    private val appContext = context.applicationContext
    private val sessionRegistry = SessionRegistry()
    private val sessions = ConcurrentHashMap<Int, PageImpl>()
    private val sessionIdCounter = AtomicInteger(0)
    private val sessionLock = Mutex()
    private val isClosed = java.util.concurrent.atomic.AtomicBoolean(false)

    override suspend fun newPage(viewport: Viewport?): Page = sessionLock.withLock {
        checkNotClosed()
        
        // Enforce maxSessions limit
        if (sessions.size >= config.maxSessions) {
            throw dev.headless.browser.browserError(
                dev.headless.browser.ErrorCode.MEMORY_LIMIT,
                "session budget exhausted (${sessions.size}/${config.maxSessions})"
            )
        }

        val sessionId = sessionIdCounter.incrementAndGet()
        val pageSession = PageSession(appContext, viewport, config, registry = sessionRegistry)
        
        try {
            pageSession.initialize()
            val page = PageImpl(pageSession, config, onClose = {
                sessions.remove(sessionId)
            })
            sessions[sessionId] = page
            return page
        } catch (e: Exception) {
            pageSession.close()
            throw e
        }
    }

    override suspend fun capabilities(): Capabilities {
        checkNotClosed()
        // Create a temporary session to probe capabilities
        val tempSession = PageSession(appContext, null, config, registry = sessionRegistry)
        return try {
            tempSession.initialize()
            tempSession.capabilities()
        } finally {
            tempSession.close()
        }
    }

    override suspend fun close() {
        if (isClosed.compareAndSet(false, true)) {
            sessionLock.withLock {
                // Close all active sessions
                val activeSessions = sessions.values.toList()
                sessions.clear()
                activeSessions.forEach { page ->
                    try {
                        page.close()
                    } catch (_: Exception) {
                        // Best effort cleanup
                    }
                }
            }
        }
    }

    private fun checkNotClosed() {
        if (isClosed.get()) {
            throw dev.headless.browser.browserError(
                dev.headless.browser.ErrorCode.DETACHED,
                "browser is closed"
            )
        }
    }
}
