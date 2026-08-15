package dev.headless.browser.platform

import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebStorage
import dev.headless.browser.BrowserConfig
import dev.headless.browser.core.PageSession
import dev.headless.browser.core.SessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

public data class Cookie(
    public val name: String,
    public val value: String,
    public val domain: String? = null,
    public val path: String? = null,
)

/**
 * Handles cookie management and web storage clearing for the platform backend.
 *
 * **Important Platform Limitation**:
 * Android WebView's [CookieManager] and [WebStorage] are **process-global**.
 * Per-session isolated cookie jars are not supported by the Android WebView API.
 * Isolation between sessions is achieved by explicit clearing ([clearCookies], [clearStorage]),
 * not per-session storage separation.
 */
public class PlatformStorageEngine(
    private val session: PageSession,
    private val scriptEngine: PlatformScriptEngine,
    private val config: BrowserConfig,
) {
    private val cookieManager: CookieManager = CookieManager.getInstance()

    init {
        cookieManager.setAcceptCookie(true)
    }

    /**
     * Retrieves cookies set for the specified [url].
     *
     * Note: Cookies are process-global in Android WebView.
     */
    public suspend fun getCookies(url: String): List<Cookie> = session.runInState(SessionState.Operating) {
        val cookieHeader = cookieManager.getCookie(url) ?: return@runInState emptyList()
        cookieHeader.split(";").mapNotNull { entry ->
            val parts = entry.trim().split("=", limit = 2)
            if (parts.size == 2) {
                Cookie(name = parts[0].trim(), value = parts[1].trim())
            } else null
        }
    }

    /**
     * Sets a cookie string for the specified [url].
     *
     * Note: Cookies are process-global in Android WebView.
     */
    public suspend fun setCookie(url: String, cookieHeader: String): Boolean = session.runInState(SessionState.Operating) {
        val deferred = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            cookieManager.setCookie(url, cookieHeader, ValueCallback { success ->
                cookieManager.flush()
                deferred.complete(success ?: true)
            })
        }
        deferred.await()
    }

    /**
     * Clears all process-global cookies.
     *
     * Note: This affects all WebViews running in the host app process.
     */
    public suspend fun clearCookies(): Boolean = session.runInState(SessionState.Operating) {
        val deferred = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            cookieManager.removeAllCookies(ValueCallback { removed ->
                cookieManager.flush()
                deferred.complete(removed ?: true)
            })
        }
        deferred.await()
    }

    /**
     * Clears local storage, session storage, and IndexedDB for the current page origin,
     * as well as process-global [WebStorage].
     */
    public suspend fun clearStorage(): Unit = session.runInState(SessionState.Operating) {
        withContext(Dispatchers.Main) {
            WebStorage.getInstance().deleteAllData()
        }

        val clearScript = """
            (function() {
                try { localStorage.clear(); } catch(e) {}
                try { sessionStorage.clear(); } catch(e) {}
                try {
                    if (window.indexedDB && window.indexedDB.databases) {
                        window.indexedDB.databases().then(function(dbs) {
                            dbs.forEach(function(db) { window.indexedDB.deleteDatabase(db.name); });
                        });
                    }
                } catch(e) {}
                return 'cleared';
            })();
        """.trimIndent()

        scriptEngine.evaluate(clearScript)
    }
}
